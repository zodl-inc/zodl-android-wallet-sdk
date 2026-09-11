use super::db::*;
use super::helpers::*;
use super::*;

fn connect_pir_client(
    pir_url: &str,
    pir_layout: voting::config::PirLayout,
) -> anyhow::Result<voting::PirClientBlocking> {
    voting::connect_pir_blocking(pir_layout, pir_url, Arc::new(voting::HyperTransport::new()))
        .map_err(|e| anyhow!("connect to PIR server failed: {}", e))
}

fn pir_layout_from_jni(
    pir_depth: jint,
    tier0_layers: jint,
    tier1_layers: jint,
    poly_len: jint,
) -> anyhow::Result<voting::config::PirLayout> {
    Ok(voting::config::PirLayout {
        pir_depth: jint_to_u32(pir_depth, "pir_depth")?,
        tier0_layers: jint_to_u32(tier0_layers, "tier0_layers")?,
        tier1_layers: jint_to_u32(tier1_layers, "tier1_layers")?,
        poly_len: jint_to_u32(poly_len, "poly_len")?,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_precomputeDelegationPirNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    pir_server_url: JString<'local>,
    pir_depth: jint,
    pir_tier0_layers: jint,
    pir_tier1_layers: jint,
    pir_poly_len: jint,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        require_bundle_notes_match(&db, &round_id, bundle_index, &bundle_notes)?;
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_layout =
            pir_layout_from_jni(pir_depth, pir_tier0_layers, pir_tier1_layers, pir_poly_len)?;
        let pir_client = connect_pir_client(&pir_url, pir_layout)?;
        let result = db
            .precompute_delegation_pir(
                &round_id,
                bundle_index,
                &bundle_notes,
                &pir_client,
                db.network,
            )
            .map_err(|e| anyhow!("precompute_delegation_pir: {}", e))?;

        make_jni_delegation_pir_precompute_result(env, result)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Loops `DelegationPipeline::keystone_request` over `bundle_indices` against
/// the pipeline a delegation-enabled `runRoundNative` call already built and
/// cached on `session_handle` -- see `RoundSessionHandle::delegation_pipeline`
/// and `delegation_driver::delegation_step_inputs_from_jni` for why the same
/// instance is reused rather than a fresh one built from scratch here.
///
/// Fails with a descriptive error if no delegation pipeline is cached yet
/// (i.e. `runRoundNative` on this session was never called with non-null
/// `delegation_inputs`).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getKeystoneSigningRequestsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    session_handle: jlong,
    bundle_indices: JIntArray<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let session = super::round_session::session_from_handle(session_handle)?;
        let pipeline = session.cached_delegation_pipeline()?.ok_or_else(|| {
            anyhow!(
                "no delegation pipeline cached on this session; call runRoundNative with \
                 non-null delegation_inputs before requesting Keystone signing requests"
            )
        })?;
        let indices = java_int_array(env, &bundle_indices, "bundle_indices")?;
        let requests = indices
            .into_iter()
            .map(|index| {
                let bundle_index = jint_to_u32(index, "bundle_indices[]")?;
                pipeline
                    .keystone_request(bundle_index)
                    .map_err(|e| anyhow!("DelegationPipeline::keystone_request: {}", e))
            })
            .collect::<anyhow::Result<Vec<_>>>()?;

        make_jni_keystone_signing_request_array(env, requests)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Atomically stores a batch of Keystone delegation signatures via
/// `VotingDb::store_keystone_signatures_batch`, replacing the old
/// per-bundle `storeKeystoneSignatureNative`/`getDelegationSubmissionWithKeystoneSigNative`
/// pair. See that crate method's own doc comment for the idempotent-replay
/// and signing-context-conflict semantics; this wrapper only marshals the
/// JNI array in and the result out.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeKeystoneSignaturesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    signatures: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let signatures = java_keystone_signature_input_array(env, &signatures, "signatures")?;
        let result = db
            .store_keystone_signatures_batch(&round_id, &signatures)
            .map_err(|e| anyhow!("store_keystone_signatures_batch: {}", e))?;

        make_jni_keystone_signature_batch_result(env, result)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Thin wrapper over `VotingDb::get_keystone_signatures`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getKeystoneSignaturesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let signatures = db
            .get_keystone_signatures(&round_id)
            .map_err(|e| anyhow!("get_keystone_signatures: {}", e))?;

        make_jni_keystone_signature_record_array(env, signatures)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

fn require_bundle_notes_match(
    db: &VotingDb,
    round_id: &str,
    bundle_index: u32,
    notes: &[NoteInfo],
) -> anyhow::Result<()> {
    let conn = db.conn();
    let wallet_id = db.wallet_id();
    voting::storage::queries::require_bundle_notes(&conn, round_id, &wallet_id, bundle_index, notes)
        .map_err(|e| anyhow!("bundle notes do not match persisted setup: {}", e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn store_keystone_signature_persists_and_is_retrievable() {
        let (db, round_id) = test_db_with_round();
        let notes = [note_info()];
        db.ensure_bundles_with_skipped_suffix_with_policy(
            &round_id,
            &notes,
            voting::BundlePolicy::default(),
        )
        .expect("bundle setup");

        db.store_keystone_signature(&round_id, 0, &[0x11; 64], &[0xAA; 32], &[0x22; 32])
            .expect("store keystone signature");

        let signatures = db
            .get_keystone_signatures(&round_id)
            .expect("get keystone signatures");
        assert_eq!(signatures.len(), 1);
        assert_eq!(signatures[0].bundle_index, 0);
        assert_eq!(signatures[0].sig, vec![0x11; 64]);
        assert_eq!(signatures[0].sighash, vec![0xAA; 32]);
        assert_eq!(signatures[0].rk, vec![0x22; 32]);
    }

    #[test]
    fn store_keystone_signatures_batch_reports_inserted_and_already_present() {
        let (db, round_id) = test_db_with_round();
        let notes = [note_info()];
        db.ensure_bundles_with_skipped_suffix_with_policy(
            &round_id,
            &notes,
            voting::BundlePolicy::default(),
        )
        .expect("bundle setup");

        db.store_keystone_signature(&round_id, 0, &[0x11; 64], &[0xAA; 32], &[0x22; 32])
            .expect("seed one signature directly");

        // A batch replaying the already-stored bundle 0 tuple alongside a
        // second, genuinely new bundle-scoped row (bundle 0 is the only
        // bundle this single-note round actually has, so the "new" row here
        // reuses bundle 0's own signing context bytes -- matches_bundle only
        // checks pczt_sighash/rk against bundles, not bundle uniqueness of
        // the input array) should report one already_present and zero
        // inserted for the replay call itself.
        let result = db
            .store_keystone_signatures_batch(
                &round_id,
                &[voting::storage::KeystoneSignatureInput {
                    bundle_index: 0,
                    sig: vec![0x11; 64],
                    sighash: vec![0xAA; 32],
                    rk: vec![0x22; 32],
                }],
            )
            .expect("batch store replays idempotently");
        assert_eq!(result.inserted, 0);
        assert_eq!(result.already_present, 1);
    }

    fn test_db_with_round() -> (VotingDb, String) {
        let db = VotingDb::open(":memory:").expect("test DB");
        db.set_wallet_id("delegation-test-wallet");
        let params = round_params();
        db.init_round(voting::types::Network::Regtest, &params, None)
            .expect("round initialized");
        (db, params.vote_round_id)
    }

    fn note_info() -> NoteInfo {
        NoteInfo {
            commitment: vec![1; PROTOCOL_FIELD_BYTES],
            nullifier: vec![2; PROTOCOL_FIELD_BYTES],
            value: 15_000_000,
            position: 0,
            diversifier: vec![0; 11],
            rho: vec![0; PROTOCOL_FIELD_BYTES],
            rseed: vec![0; PROTOCOL_FIELD_BYTES],
            scope: 0,
            ufvk_str: String::new(),
        }
    }

    fn round_params() -> voting::types::VotingRoundParams {
        voting::types::VotingRoundParams {
            vote_round_id: "0101010101010101010101010101010101010101010101010101010101010101"
                .to_string(),
            // zcash_voting only builds governance PCZTs for Ironwood/NU6.3 snapshot
            // heights; Regtest activates NU6.3 at height 10 (matches the crate's
            // own test fixtures, since that constant isn't public).
            snapshot_height: 10,
            ea_pk: vec![0xEA; PROTOCOL_FIELD_BYTES],
            nc_root: vec![0x01; PROTOCOL_FIELD_BYTES],
            nullifier_imt_root: vec![0x02; PROTOCOL_FIELD_BYTES],
        }
    }
}
