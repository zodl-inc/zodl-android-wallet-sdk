use super::helpers::*;
use super::*;
use std::{
    ops::Deref,
    sync::{MutexGuard, Weak},
};

static NEXT_DB_HANDLE: AtomicI64 = AtomicI64::new(1);
static DB_REGISTRY: OnceLock<Mutex<HashMap<jlong, Arc<VotingDbHandle>>>> = OnceLock::new();
static DB_BY_KEY: OnceLock<Mutex<HashMap<DbKey, Weak<VotingDbHandle>>>> = OnceLock::new();

#[derive(Clone, Eq, Hash, PartialEq)]
struct DbKey {
    path: String,
    wallet_id: String,
}

pub(super) struct VotingDbHandle {
    // `VotingDb::open_wallet_sidecar` itself returns `Arc<VotingDb>` -- the
    // crate dedupes sidecar connections by path internally -- so this field
    // has to stay Arc-wrapped to hold that value at all, not because
    // anything in this module clones it independently.
    db: Arc<VotingDb>,
    // VoteTreeSync owns only its synchronous tree-client cache and protects
    // that cache internally. JNI vote-tree entrypoints still hold access_mutex
    // before calling it so DB writes and tree-client state changes are
    // serialized for shared managed handles.
    pub(super) tree_sync: VoteTreeSync,
    access_mutex: Mutex<()>,
    // The voting network rides the handle so downstream JNI entrypoints do not
    // need a redundant network_id parameter once a handle is open.
    pub(super) network: voting::types::Network,
    pir_client: Mutex<Option<CachedPirClient>>,
}

/// A connected PIR client together with the endpoint and layout it was
/// negotiated for, so a request for a different server or geometry reconnects
/// instead of silently reusing the wrong dataset.
struct CachedPirClient {
    url: String,
    layout: voting::config::PirLayout,
    client: Arc<voting::PirClientBlocking>,
}

/// Takes `mutex`, recovering a poisoned one instead of failing.
///
/// Every JNI entrypoint runs under `catch_unwind`, so a panic inside a PIR
/// handshake is caught and turned into a Java exception - but it still
/// poisons whatever mutex was held, for the life of the deduped handle. The
/// only mutex taken through here guards a cache that is rebuilt on demand
/// (the PIR client), so a panic leaves no broken invariant behind it and the
/// poison flag is noise.
pub(super) fn recover_lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

impl VotingDbHandle {
    fn open(path: &str, wallet_id: &str, network: voting::types::Network) -> anyhow::Result<Self> {
        let db = VotingDb::open_wallet_sidecar(std::path::Path::new(path), wallet_id)
            .map_err(|e| anyhow!("VotingDb::open_wallet_sidecar failed: {}", e))?;

        Ok(Self {
            db,
            tree_sync: VoteTreeSync::new(),
            access_mutex: Mutex::new(()),
            network,
            pir_client: Mutex::new(None),
        })
    }

    pub(super) fn access_lock(&self) -> anyhow::Result<MutexGuard<'_, ()>> {
        self.access_mutex
            .lock()
            .map_err(|_| anyhow!("voting DB access mutex poisoned"))
    }

    /// Returns a PIR client connected to `url` for `layout`, connecting only
    /// the first time.
    ///
    /// The handshake is expensive: it stands up a tokio runtime and a TLS
    /// client, fetches both tiers' parameters, and downloads the whole Tier-0
    /// dataset to recompute its root. Delegation precompute and proof
    /// generation each need a client for every bundle of a round, so the
    /// connection is made once per handle and shared between them. It is keyed
    /// by endpoint and layout so a server or geometry change reconnects, and
    /// it is dropped with the handle.
    pub(super) fn pir_client_for(
        &self,
        url: &str,
        layout: voting::config::PirLayout,
    ) -> anyhow::Result<Arc<voting::PirClientBlocking>> {
        let mut cached = recover_lock(&self.pir_client);

        if let Some(cached) = cached.as_ref()
            && cached.url == url
            && cached.layout == layout
        {
            return Ok(cached.client.clone());
        }

        let client = Arc::new(connect_pir_client(url, layout)?);
        *cached = Some(CachedPirClient {
            url: url.to_string(),
            layout,
            client: Arc::clone(&client),
        });
        Ok(client)
    }
}

fn connect_pir_client(
    pir_url: &str,
    pir_layout: voting::config::PirLayout,
) -> anyhow::Result<voting::PirClientBlocking> {
    voting::connect_pir_blocking(pir_layout, pir_url, Arc::new(voting::HyperTransport::new()))
        .map_err(|e| anyhow!("connect to PIR server failed: {}", e))
}

impl Deref for VotingDbHandle {
    type Target = VotingDb;

    fn deref(&self) -> &Self::Target {
        self.db.deref()
    }
}

fn registry() -> &'static Mutex<HashMap<jlong, Arc<VotingDbHandle>>> {
    DB_REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn db_by_key() -> &'static Mutex<HashMap<DbKey, Weak<VotingDbHandle>>> {
    DB_BY_KEY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> anyhow::Result<jlong> {
    NEXT_DB_HANDLE
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
        .map_err(|_| anyhow!("voting DB handle space exhausted"))
}

pub(super) fn db_from_handle(handle: jlong) -> anyhow::Result<Arc<VotingDbHandle>> {
    if handle <= 0 {
        return Err(anyhow!("Voting DB handle must be positive, got {handle}"));
    }

    registry()
        .lock()
        .map_err(|_| anyhow!("voting DB registry mutex poisoned"))?
        .get(&handle)
        .cloned()
        .ok_or_else(|| anyhow!("Voting DB handle is closed or unknown: {handle}"))
}

fn open_managed_db(
    path: &str,
    wallet_id: &str,
    network: voting::types::Network,
) -> anyhow::Result<Arc<VotingDbHandle>> {
    if path == ":memory:" {
        return Ok(Arc::new(VotingDbHandle::open(path, wallet_id, network)?));
    }

    let key = DbKey {
        path: path.to_string(),
        wallet_id: wallet_id.to_string(),
    };
    let mut dbs = db_by_key()
        .lock()
        .map_err(|_| anyhow!("voting DB key registry mutex poisoned"))?;
    dbs.retain(|_, db| db.strong_count() > 0);

    if let Some(db) = dbs.get(&key).and_then(Weak::upgrade) {
        if db.network != network {
            return Err(anyhow!(
                "voting DB at {path} for wallet {wallet_id} is already open for a different network"
            ));
        }
        return Ok(db);
    }

    let db = Arc::new(VotingDbHandle::open(path, wallet_id, network)?);
    dbs.insert(key, Arc::downgrade(&db));
    Ok(db)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_openVotingDbNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    wallet_id: JString<'local>,
    network_id: jint,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let path = java_string_to_rust(env, &db_path)?;
        let wallet_id = java_string_to_rust(env, &wallet_id)?;
        if wallet_id.is_empty() {
            return Err(anyhow!("walletId must not be empty"));
        }
        let network = voting_network_from_id(network_id)?;

        let db = open_managed_db(&path, &wallet_id, network)?;
        let handle = next_handle()?;
        registry()
            .lock()
            .map_err(|_| anyhow!("voting DB registry mutex poisoned"))?
            .insert(handle, db);

        Ok(handle)
    });
    unwrap_exc_or(&mut env, res, 0)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_closeVotingDbNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        if db_handle > 0 {
            // The last drop of the removed handle tears down the PIR client's
            // tokio runtime and closes SQLite, neither of which may run while
            // DB_REGISTRY is held: the removed value therefore outlives the
            // guard and is dropped once the registry is free again.
            let removed = {
                let mut handles = registry()
                    .lock()
                    .map_err(|_| anyhow!("voting DB registry mutex poisoned"))?;
                handles.remove(&db_handle)
            };
            drop(removed);
        }
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        time::{SystemTime, UNIX_EPOCH},
    };

    #[test]
    fn managed_db_reuses_access_lock_for_same_path_and_wallet() {
        let db_path = unique_db_path();
        let db_path_str = db_path.to_str().expect("test db path is valid UTF-8");
        let first = open_managed_db(db_path_str, "wallet-1", voting::types::Network::Testnet)
            .expect("first DB open");
        let second = open_managed_db(db_path_str, "wallet-1", voting::types::Network::Testnet)
            .expect("second DB open");

        assert!(Arc::ptr_eq(&first, &second));
        let guard = first.access_lock().expect("first access lock");
        assert!(second.access_mutex.try_lock().is_err());
        drop(guard);

        drop(first);
        drop(second);
        let _ = fs::remove_file(db_path);
    }

    #[test]
    fn open_wallet_sidecar_creates_schema_without_manual_migration() {
        let db_path = unique_db_path();
        let db_path_str = db_path.to_str().expect("test db path is valid UTF-8");

        // Before this task's rewrite, VotingDbHandle::open called the lower-
        // level VotingDb::open(path) + a separate set_wallet_id(wallet_id)
        // call. After the rewrite it calls open_wallet_sidecar, which owns
        // schema creation and migrations internally and takes wallet_id as a
        // constructor argument. Opening a brand-new path with zero manual
        // setup on our side must succeed.
        let handle = open_managed_db(db_path_str, "wallet-1", voting::types::Network::Testnet)
            .expect("opening a fresh sidecar path must succeed with no manual schema step");

        drop(handle);
        let _ = fs::remove_file(&db_path);
        let _ = fs::remove_file(format!("{db_path_str}.voting"));
    }

    fn unique_db_path() -> std::path::PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("current time is after UNIX_EPOCH")
            .as_nanos();
        std::env::temp_dir().join(format!(
            "zcash-android-voting-db-test-{}-{nanos}.sqlite",
            std::process::id()
        ))
    }
}
