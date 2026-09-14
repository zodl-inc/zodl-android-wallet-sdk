use super::helpers::*;
use super::*;
use std::{
    ops::Deref,
    sync::{MutexGuard, Weak},
};

static NEXT_DB_HANDLE: AtomicI64 = AtomicI64::new(1);
static DB_REGISTRY: OnceLock<Mutex<HashMap<jlong, Arc<VotingDbHandle>>>> = OnceLock::new();
static DB_BY_KEY: OnceLock<Mutex<HashMap<DbKey, Weak<VotingDbHandle>>>> = OnceLock::new();

/// Admits one proof at a time for one bundle of one round; see
/// [`VotingDbHandle::proof_lock`].
type ProofLock = Arc<Mutex<()>>;

#[derive(Clone, Eq, Hash, PartialEq)]
struct DbKey {
    path: String,
    wallet_id: String,
}

pub(super) struct VotingDbHandle {
    db: VotingDb,
    // The database location this handle was opened from, kept so a proof can
    // reopen it on a private connection (see open_private_connection).
    path: String,
    wallet_id: String,
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
    // Proving entrypoints deliberately do not hold access_mutex: a Halo2 proof
    // runs for minutes, and holding it would stop every other bundle of the
    // round. They hold a per-(round, bundle) lock from proof_locks instead and
    // run the crate call on a private connection, so two bundles can prove at
    // the same time while the same bundle still cannot prove twice at once.
    proof_locks: Mutex<HashMap<(String, u32), ProofLock>>,
}

/// A connected PIR client together with the endpoint and layout it was
/// negotiated for, so a request for a different server or geometry reconnects
/// instead of silently reusing the wrong dataset.
struct CachedPirClient {
    url: String,
    layout: voting::config::PirLayout,
    client: Arc<voting::PirClientBlocking>,
}

impl VotingDbHandle {
    fn open(path: &str, wallet_id: &str, network: voting::types::Network) -> anyhow::Result<Self> {
        let db = VotingDb::open(path).map_err(|e| anyhow!("VotingDb::open failed: {}", e))?;
        db.set_wallet_id(wallet_id);

        Ok(Self {
            db,
            path: path.to_string(),
            wallet_id: wallet_id.to_string(),
            tree_sync: VoteTreeSync::new(),
            access_mutex: Mutex::new(()),
            network,
            pir_client: Mutex::new(None),
            proof_locks: Mutex::new(HashMap::new()),
        })
    }

    pub(super) fn access_lock(&self) -> anyhow::Result<MutexGuard<'_, ()>> {
        self.access_mutex
            .lock()
            .map_err(|_| anyhow!("voting DB access mutex poisoned"))
    }

    /// Returns the lock that admits one proof at a time for a single bundle of
    /// a single round.
    ///
    /// A second proof of the same bundle would redo the same work and race the
    /// first one's writes, so it waits here. Different bundles get different
    /// locks and may prove concurrently, which is the point: the shared
    /// `access_mutex` is not held while a proof runs.
    pub(super) fn proof_lock(
        &self,
        round_id: &str,
        bundle_index: u32,
    ) -> anyhow::Result<ProofLock> {
        let mut locks = self
            .proof_locks
            .lock()
            .map_err(|_| anyhow!("voting DB proof lock registry mutex poisoned"))?;

        Ok(locks
            .entry((round_id.to_string(), bundle_index))
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone())
    }

    /// Opens a second connection to this handle's voting database, for one
    /// proof to use on its own.
    ///
    /// `zcash_voting` holds a `VotingDb`'s internal connection mutex for the
    /// whole vote-commitment proof, so proving through the shared handle would
    /// block every other caller of that `VotingDb` instance for the duration of
    /// the proof. Proving on a private connection keeps that guard private to
    /// the proof. It is safe to write through: the database is in WAL mode, the
    /// crate's writes are single statements or short immediate transactions,
    /// the bundles of a round write disjoint rows, and the per-bundle proof
    /// lock keeps two proofs of one bundle apart.
    ///
    /// Returns `None` for an in-memory database, where a second connection
    /// would be a different, empty database instead of the same one; in-memory
    /// handles (tests and fixtures) keep proving through the shared connection.
    pub(super) fn open_private_connection(&self) -> anyhow::Result<Option<VotingDb>> {
        if self.path == ":memory:" {
            return Ok(None);
        }

        let db = VotingDb::open(&self.path).map_err(|e| {
            anyhow!(
                "VotingDb::open for a private proving connection failed: {}",
                e
            )
        })?;
        db.set_wallet_id(&self.wallet_id);
        Ok(Some(db))
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
        let mut cached = self
            .pir_client
            .lock()
            .map_err(|_| anyhow!("voting DB PIR client mutex poisoned"))?;

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
        &self.db
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
            registry()
                .lock()
                .map_err(|_| anyhow!("voting DB registry mutex poisoned"))?
                .remove(&db_handle);
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
    fn proof_lock_is_per_round_and_bundle() {
        let db = VotingDbHandle::open(":memory:", "wallet-1", voting::types::Network::Testnet)
            .expect("in-memory DB open");

        let first = db.proof_lock("round-1", 0).expect("first proof lock");
        let same = db.proof_lock("round-1", 0).expect("same proof lock");
        let other_bundle = db
            .proof_lock("round-1", 1)
            .expect("other bundle proof lock");
        let other_round = db.proof_lock("round-2", 0).expect("other round proof lock");

        assert!(Arc::ptr_eq(&first, &same));
        assert!(!Arc::ptr_eq(&first, &other_bundle));
        assert!(!Arc::ptr_eq(&first, &other_round));

        let guard = first.lock().expect("hold the proof lock");
        assert!(same.try_lock().is_err());
        assert!(other_bundle.try_lock().is_ok());
        assert!(other_round.try_lock().is_ok());
        drop(guard);
    }

    #[test]
    fn open_private_connection_returns_none_for_memory_path() {
        let db = VotingDbHandle::open(":memory:", "wallet-1", voting::types::Network::Testnet)
            .expect("in-memory DB open");

        assert!(
            db.open_private_connection()
                .expect("private connection")
                .is_none()
        );
    }

    #[test]
    fn private_connection_sees_rows_written_through_the_shared_one() {
        let db_path = unique_db_path();
        let db_path_str = db_path.to_str().expect("test db path is valid UTF-8");
        let db = VotingDbHandle::open(db_path_str, "wallet-1", voting::types::Network::Testnet)
            .expect("file-backed DB open");

        let params = voting::types::VotingRoundParams {
            vote_round_id: "round-1".to_string(),
            snapshot_height: 1000,
            ea_pk: vec![0xEA; 32],
            nc_root: vec![0xAA; 32],
            nullifier_imt_root: vec![0xBB; 32],
        };
        db.init_round(voting::types::Network::Testnet, &params, None)
            .expect("init round through the shared connection");

        let private_db = db
            .open_private_connection()
            .expect("private connection")
            .expect("file-backed DB gets a private connection");
        assert_eq!(private_db.wallet_id(), "wallet-1");
        assert!(
            private_db
                .has_round("round-1")
                .expect("has_round on the private connection")
        );

        drop(private_db);
        drop(db);
        remove_db_files(&db_path);
    }

    fn remove_db_files(db_path: &std::path::Path) {
        let _ = fs::remove_file(db_path);
        for suffix in ["-wal", "-shm"] {
            let mut sidecar = db_path.as_os_str().to_os_string();
            sidecar.push(suffix);
            let _ = fs::remove_file(std::path::PathBuf::from(sidecar));
        }
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
