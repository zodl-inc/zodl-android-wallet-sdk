use super::db::*;
use super::helpers::*;
use super::*;

// =============================================================================
// C. Note setup
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_computeBundleSetupNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let notes = java_note_info_array(env, &notes, "notes")?;
        let (count, weight, bundle_weights) = bundle_setup_from_notes(&notes)?;
        make_jni_bundle_setup_result(env, count, weight, &bundle_weights)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_setupBundlesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let (expected_count, expected_weight, bundle_weights) = bundle_setup_from_notes(&notes)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let layout = db
            .ensure_bundles_with_skipped_suffix_with_policy(
                &round_id,
                &notes,
                voting::BundlePolicy::default(),
            )
            .map_err(|e| anyhow!("ensure_bundles_with_skipped_suffix_with_policy: {}", e))?;
        if layout.bundle_count != expected_count || layout.eligible_weight != expected_weight {
            // ensure_bundles_with_skipped_suffix_with_policy has already persisted the
            // round's bundles. Treat a mismatch as an internal bug; callers must clear
            // the round before retrying.
            return Err(anyhow!(
                "setup_bundles result mismatch after persisting bundles; call clearRound before retrying: db=({}, {}) chunk=({}, {})",
                layout.bundle_count,
                layout.eligible_weight,
                expected_count,
                expected_weight
            ));
        }
        make_jni_bundle_setup_result(
            env,
            layout.bundle_count,
            layout.eligible_weight,
            &bundle_weights,
        )
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_generateHotkeyNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    stored_secret: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let network = db.network;
        // Wrap in SecretVec like every other hotkey-secret call site in this module
        // (buildGovernancePcztNative, buildAndProveDelegationNative, buildVoteCommitmentNative,
        // deriveHotkeyRawAddressNative): this was the one JNI entry point that read the
        // caller-supplied secret as a bare Vec<u8>, which neither zeroizes on drop nor guards
        // against an incidental `{:?}` from printing the material.
        let stored_secret = SecretVec::new(java_bytes(env, &stored_secret, "storedSecret")?);
        let hotkey = if stored_secret.expose_secret().is_empty() {
            voting::hotkey::generate_random_voting_hotkey(network)
                .map_err(|e| anyhow!("generate_random_voting_hotkey: {}", e))?
        } else {
            voting::types::VotingHotkey::from_stored_secret(stored_secret.expose_secret(), network)
                .map_err(|e| anyhow!("VotingHotkey::from_stored_secret: {}", e))?
        };
        make_jni_voting_hotkey(env, hotkey)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}
