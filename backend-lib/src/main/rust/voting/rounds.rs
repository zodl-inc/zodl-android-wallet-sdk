use super::db::*;
use super::helpers::*;
use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getRoundStateNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        if !db
            .has_round(&round_id)
            .map_err(|e| anyhow!("has_round: {}", e))?
        {
            Ok(JObject::null().into_raw())
        } else {
            let state = db
                .get_round_state(&round_id)
                .map_err(|e| anyhow!("get_round_state: {}", e))?;
            make_jni_round_state(env, state)
        }
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Clears unsigned/unproved delegation setup fields for one round (preserving submitted bundles
/// and bundles with persisted Keystone signatures) so an interrupted or corrupted per-bundle setup
/// can be safely rebuilt from scratch. Wraps `zcash_voting::precompute::reset_voting_session_state`
/// — the crate's sanctioned recovery path (also drops the process-local vote-tree cache for this
/// round). Callers should treat this as the response to a `refusing to overwrite pczt_sighash`
/// (or similar) error from `buildGovernancePcztNative`/`buildGovernancePcztFromSeedNative`, not a
/// routine call.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_resetVotingSessionStateNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        voting::precompute::reset_voting_session_state(&db, &round_id)
            .map_err(|e| anyhow!("reset_voting_session_state: {}", e))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_listRoundsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let rounds = db
            .list_rounds()
            .map_err(|e| anyhow!("list_rounds: {}", e))?;
        make_jni_round_summaries(env, rounds)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getBundleCountNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let count = db
            .get_bundle_count(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("get_bundle_count: {}", e))?;
        u32_to_jint(count, "bundle_count")
    });
    unwrap_exc_or(&mut env, res, -1)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_clearRoundNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.clear_round(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("clear_round: {}", e))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_deleteSkippedBundlesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    keep_count: jint,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let deleted_rows = db
            .delete_skipped_bundles(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(keep_count, "keep_count")?,
            )
            .map_err(|e| anyhow!("delete_skipped_bundles: {}", e))?;
        u64_to_jlong(deleted_rows, "deleted_rows")
    });
    unwrap_exc_or(&mut env, res, -1)
}
