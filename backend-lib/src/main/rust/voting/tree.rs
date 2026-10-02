use super::db::*;
use super::helpers::*;
use super::round_session::resolve_session_route;
use super::round_session::SessionRoute;
use super::*;

use voting::HyperTransport;

/// Syncs the vote tree for `round_id` from `node_url`, using the same route
/// selection as round/share-tracking sessions: Tor ON → Tor; Tor OFF + predefined
/// node → OHTTP (fail closed); Tor OFF + custom → Direct. Matches Swift SDK's
/// `.with_tree_transport(transport.clone())` pattern. The tree sync is recreated
/// when the route key changes (Tor on→off, custom toggle, etc.), so config
/// switches start from a fresh sync state.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_syncVoteTreeNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    tor_runtime: jlong,
    is_custom_server: jboolean,
    round_id: JString<'local>,
    node_url: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let node_url_string = java_string_to_rust(env, &node_url)?;

        let is_custom = is_custom_server != 0;
        let network = db.network;
        let route = resolve_session_route(tor_runtime, is_custom, &node_url_string, network)?;

        // Derive the route key for this sync call
        let route_key = match &route {
            SessionRoute::Tor(_) => TreeRouteKey::Tor(tor_runtime),
            SessionRoute::Ohttp(_) => TreeRouteKey::Ohttp,
            SessionRoute::Direct(_) => TreeRouteKey::Direct,
        };

        // Lock the tree_sync_slot and check if the route key has changed
        let mut slot = db
            .tree_sync_slot
            .lock()
            .map_err(|_| anyhow!("tree_sync_slot mutex poisoned"))?;

        if slot.route_key != route_key {
            // Route changed: replace the sync with one configured for the new route
            // Unsized coercion to the crate's tree `Transport` trait object.
            slot.sync = Arc::new(VoteTreeSync::with_transport(Arc::new(
                HyperTransport::with_route(route),
            )));
            slot.route_key = route_key;
        }

        // Call sync() directly (blocking, not async). The HyperTransport's
        // BlockingRuntime handles the async internally (tree_sync.rs:456,
        // http_transport.rs:1269).
        let height = slot
            .sync
            .sync(&db, &round_id, &node_url_string)
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
        let slot = db
            .tree_sync_slot
            .lock()
            .map_err(|_| anyhow!("tree_sync_slot mutex poisoned"))?;
        slot.sync
            .reset(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("reset_tree_client: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}
