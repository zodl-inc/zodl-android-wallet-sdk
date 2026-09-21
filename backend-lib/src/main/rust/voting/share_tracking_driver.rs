//! Background share delivery/confirmation via `zcash_voting`'s
//! [`voting::ShareTrackingDriver`].
//!
//! **Production-completion correction (2026-09-21):** the original task
//! doc comment below flagged `trackSharesNative`'s lack of a session handle
//! as "not a correctness gap, only a cancellability gap" -- true, but the
//! cancellability gap itself turned out to be a real production hang:
//! `ShareTrackingDriver::run`'s `control: &ChainSubmissionControl` parameter
//! is the *exact same type* `RoundDriver::run` takes (see
//! `round_session.rs`'s `cancelRoundSessionNative`, which just calls
//! `session.control.cancel()`), so this module always had everything it
//! needed to support real cancellation -- it just never registered its
//! `ChainSubmissionControl` anywhere a separate JNI call could reach. This
//! task gives it the same open/run/cancel/close session shape
//! `round_session.rs` already uses, reusing its own registry pattern
//! (`db.rs`'s `DB_REGISTRY`/`next_handle`/`db_from_handle` pattern, same as
//! `round_session.rs`'s `SESSION_REGISTRY`). No crate-side change was
//! needed: `ChainSubmissionControl::cancel()` already works exactly as
//! `runRoundNative`'s does.
//!
//! Supersedes the hand-rolled record/mark-confirmed/add-sent-servers
//! cluster this task deletes from `recovery.rs` (unchanged from the
//! original note) -- the crate's own driver owns delivery scheduling,
//! quorum confirmation, and retry internally, repeating passes on the
//! cadence each pass itself computes until the round's shares are
//! quiescent.

use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::OnceLock;

use super::db::db_from_handle;
use super::helpers::*;
use super::round_session::{fallback_runtime, optional_seconds, resolve_tor_runtime, unix_now_seconds};
use super::route::ZodlVotingRoute;
use super::*;

use tor_rtcompat::ToplevelBlockOn;

use voting::{
    ChainSubmissionControl, DirectRoute, HelperClient, HelperHealth, HelperTransport,
    HyperTransport, NoopShareTrackingReporter, ShareTrackingDriver, ShareTrackingHostContext,
    ShareTrackingHostSourceBridge,
};

/// One open share-tracking session: a round-scoped [`ChainSubmissionControl`]
/// a separate `cancelShareTrackingSessionNative` call can reach, plus the
/// `round_id` `runShareTrackingSessionNative` drives passes against. Mirrors
/// `round_session.rs`'s `RoundSessionHandle` shape exactly, minus the fields
/// that are round-session-only (executor, delegation pipeline cache).
pub(super) struct ShareTrackingSessionHandle {
    db_handle: jlong,
    round_id: String,
    control: ChainSubmissionControl,
}

static NEXT_SHARE_TRACKING_SESSION_HANDLE: AtomicI64 = AtomicI64::new(1);
static SHARE_TRACKING_SESSION_REGISTRY: OnceLock<Mutex<HashMap<jlong, Arc<ShareTrackingSessionHandle>>>> =
    OnceLock::new();

fn share_tracking_registry() -> &'static Mutex<HashMap<jlong, Arc<ShareTrackingSessionHandle>>> {
    SHARE_TRACKING_SESSION_REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_share_tracking_session_handle() -> anyhow::Result<jlong> {
    NEXT_SHARE_TRACKING_SESSION_HANDLE
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
        .map_err(|_| anyhow!("share tracking session handle space exhausted"))
}

fn share_tracking_session_from_handle(handle: jlong) -> anyhow::Result<Arc<ShareTrackingSessionHandle>> {
    if handle <= 0 {
        return Err(anyhow!(
            "Share tracking session handle must be positive, got {handle}"
        ));
    }
    share_tracking_registry()
        .lock()
        .map_err(|_| anyhow!("share tracking session registry mutex poisoned"))?
        .get(&handle)
        .cloned()
        .ok_or_else(|| anyhow!("Share tracking session handle is closed or unknown: {handle}"))
}

/// Opens a share-tracking session for `round_id` against the voting DB at
/// `db_handle`. Registers a fresh, unstarted [`ChainSubmissionControl`] at
/// operation epoch 0 that `cancelShareTrackingSessionNative` can later
/// target -- see this module's doc comment for why this alone closes the
/// production hang.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_openShareTrackingSessionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        // Validate the db handle resolves before registering the session,
        // matching openRoundSessionNative's own up-front db_from_handle
        // check.
        let _ = db_from_handle(db_handle)?;
        let round_id = java_string_to_rust(env, &round_id)?;

        let session = Arc::new(ShareTrackingSessionHandle {
            db_handle,
            round_id,
            control: ChainSubmissionControl::new(0),
        });

        let handle = next_share_tracking_session_handle()?;
        share_tracking_registry()
            .lock()
            .map_err(|_| anyhow!("share tracking session registry mutex poisoned"))?
            .insert(handle, session);

        Ok(handle)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// Drives `session_handle`'s round to share-tracking quiescence with a
/// [`ShareTrackingDriver`]. See `trackSharesNative`'s old doc comment (now
/// superseded) for the Tor-optional fallback this preserves unchanged.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_runShareTrackingSessionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    session_handle: jlong,
    tor_runtime: jlong,
    helper_urls: JObjectArray<'local>,
    vote_end_time_seconds: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let session = share_tracking_session_from_handle(session_handle)?;
        let db = db_from_handle(session.db_handle)?;
        let configured_helper_urls = java_string_array(env, &helper_urls, "helper_urls")?;
        let vote_end_time_seconds = optional_seconds(vote_end_time_seconds)?;

        // SAFETY: see resolve_tor_runtime's doc comment in round_session.rs.
        let resolved_tor_runtime = unsafe { resolve_tor_runtime(tor_runtime) };

        let database: &voting::storage::VotingDb = &db;
        let template = ShareTrackingHostContext {
            configured_helper_urls,
            now_seconds: unix_now_seconds(),
            vote_end_time_seconds,
        };
        let host = ShareTrackingHostSourceBridge::new(move || {
            let mut ctx = template.clone();
            ctx.now_seconds = unix_now_seconds();
            ctx
        });

        let report = match resolved_tor_runtime {
            Ok(tor_runtime) => {
                let transport: Arc<dyn HelperTransport> = Arc::new(HyperTransport::with_route(
                    ZodlVotingRoute::new(tor_runtime),
                ));
                let client = HelperClient::new(transport, HelperHealth::default());
                let driver = ShareTrackingDriver::new(database, &client, &session.round_id);
                tor_runtime.runtime().block_on(async {
                    driver
                        .run(&host, &session.control, &NoopShareTrackingReporter {})
                        .await
                })
            }
            Err(_) => {
                let transport: Arc<dyn HelperTransport> =
                    Arc::new(HyperTransport::with_route(DirectRoute::new()));
                let client = HelperClient::new(transport, HelperHealth::default());
                let driver = ShareTrackingDriver::new(database, &client, &session.round_id);
                fallback_runtime()?.block_on(async {
                    driver
                        .run(&host, &session.control, &NoopShareTrackingReporter {})
                        .await
                })
            }
        };

        Ok(encode_share_tracking_report(env, &report)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Cancels an in-flight `runShareTrackingSessionNative` call on this session
/// -- identical mechanism to `cancelRoundSessionNative`: sets a shared
/// [`ChainSubmissionControl`] flag the run future polls internally, from
/// whatever thread this JNI call happens on. This is what unblocks the
/// `tor_runtime.block_on(...)` call in `runShareTrackingSessionNative` from
/// outside it.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_cancelShareTrackingSessionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    session_handle: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        let session = share_tracking_session_from_handle(session_handle)?;
        session.control.cancel();
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

/// Removes a share-tracking session from the registry. No implicit
/// cancellation: a caller with a `runShareTrackingSessionNative` call in
/// flight must `cancelShareTrackingSessionNative` first if it wants that run
/// to stop early -- identical contract to `closeRoundSessionNative`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_closeShareTrackingSessionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    session_handle: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        if session_handle > 0 {
            share_tracking_registry()
                .lock()
                .map_err(|_| anyhow!("share tracking session registry mutex poisoned"))?
                .remove(&session_handle);
        }
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn share_tracking_session_from_handle_rejects_nonpositive_handles() {
        assert!(share_tracking_session_from_handle(0).is_err());
        assert!(share_tracking_session_from_handle(-1).is_err());
    }

    #[test]
    fn share_tracking_session_from_handle_rejects_unknown_handle() {
        assert!(share_tracking_session_from_handle(jlong::MAX).is_err());
    }

    #[test]
    fn share_tracking_session_handles_are_send_and_sync() {
        fn _assert_send_sync<T: Send + Sync>() {}
        _assert_send_sync::<ShareTrackingSessionHandle>();
    }

    #[test]
    fn share_tracking_driver_constructor_signature_is_stable() {
        fn _assert_signature<'a>(
            database: &'a voting::storage::VotingDb,
            client: &'a HelperClient,
            round_id: &'a str,
        ) -> ShareTrackingDriver<'a> {
            ShareTrackingDriver::new(database, client, round_id)
        }
    }

    #[test]
    fn share_tracking_driver_run_signature_is_stable() {
        fn _assert_signature(
            driver: &ShareTrackingDriver<'_>,
            host: &dyn voting::ShareTrackingHostSource,
            control: &ChainSubmissionControl,
            events: &dyn voting::ShareTrackingReporter,
        ) {
            let _future = driver.run(host, control, events);
        }
    }

    #[test]
    fn share_tracking_host_context_round_trips_the_optional_vote_end() {
        let ctx = ShareTrackingHostContext {
            configured_helper_urls: vec!["https://helper.example".to_string()],
            now_seconds: 1_700_000_000,
            vote_end_time_seconds: None,
        };
        assert_eq!(ctx.vote_end_time_seconds, None);

        let ctx_with_end = ShareTrackingHostContext {
            vote_end_time_seconds: Some(1_700_000_100),
            ..ctx
        };
        assert_eq!(ctx_with_end.vote_end_time_seconds, Some(1_700_000_100));
    }
}
