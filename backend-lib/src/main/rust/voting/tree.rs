use super::db::*;
use super::helpers::*;
use super::round_session::{fallback_runtime, resolve_session_route, resolve_tor_runtime};
use super::*;

use tor_rtcompat::ToplevelBlockOn;
use voting::tree_sync::VoteTreeTransport;
use voting::HyperTransport;

/// Syncs the vote tree for `round_id` from `node_url`, using the same route
/// selection as round/share-tracking sessions: Tor ON → Tor; Tor OFF + predefined
/// node → OHTTP (fail closed); Tor OFF + custom → Direct. Matches Swift SDK's
/// `.with_tree_transport(transport.clone())` pattern.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_syncVoteTreeNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    tor_runtime: jlong,
    is_custom_server: jlong,
    round_id: JString<'local>,
    node_url: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let node_url_string = java_string_to_rust(env, &node_url)?;

        // Build route for the vote tree node URL
        let is_custom = is_custom_server != 0;
        let route = resolve_session_route(tor_runtime, is_custom, &node_url_string)?;
        let transport: Arc<VoteTreeTransport> = Arc::new(HyperTransport::with_route(route));

        // Resolve runtime for block_on (tree sync calls are sync, but the underlying
        // transport is async, so we need to block_on somewhere in the call chain)
        let resolved_tor_runtime = unsafe { resolve_tor_runtime(tor_runtime) };

        // Sync with the tree transport. The voting crate's VoteTreeSync::sync_with_transport
        // (or equivalent) takes the transport and uses it instead of the default clearnet one.
        let height = match resolved_tor_runtime {
            Ok(tor_rt) => {
                tor_rt.runtime().block_on(async {
                    db.tree_sync
                        .sync_with_transport(&db, &round_id, &node_url_string, transport)
                        .await
                })
            }
            Err(_) => {
                fallback_runtime()?.block_on(async {
                    db.tree_sync
                        .sync_with_transport(&db, &round_id, &node_url_string, transport)
                        .await
                })
            }
        }
        .map_err(|e| anyhow!("sync_vote_tree: {}", e))?;

        u64_to_jlong(u64::from(height), "synced_height")
    });
    unwrap_exc_or(&mut env, res, -1)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_resetTreeClientNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.tree_sync
            .reset(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("reset_tree_client: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}
