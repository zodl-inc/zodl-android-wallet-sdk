use super::db::*;
use super::helpers::*;
use super::*;

/// Connects a PIR client routed over `tor_runtime`'s resolved
/// [`super::round_session::SessionRoute`] -- real Tor when a live runtime is
/// handed in, plain HTTP as the explicit fallback otherwise (`0`, or any
/// resolution failure). Same Tor-preference policy `openRoundSessionNative`
/// applies to a round session's chain/helper traffic, via the exact same
/// `resolve_session_route` helper -- see that function's doc comment in
/// `round_session.rs`. Without this, every PIR fetch through this helper
/// used the crate's default DIRECT transport (`HyperTransport::new()`)
/// unconditionally, correlating the caller's IP with holding
/// voting-eligible notes on every PIR round-trip, not just when a user
/// explicitly submits a vote.
///
/// `precomputeDelegationPirNative` below has no `tor_runtime` JNI parameter
/// of its own -- it stays unwired from the app per the Task 2 ledger ruling
/// -- and passes `0` here, preserving its pre-existing Direct-only behavior
/// unchanged. `precomputePirProofsNative`/`precomputeSnapshotBundlesNative`
/// further down are the two exports this routes for real, each with their
/// own `tor_runtime: jlong` JNI parameter.
fn connect_pir_client(
    pir_url: &str,
    pir_layout: voting::config::PirLayout,
    tor_runtime: jlong,
) -> anyhow::Result<voting::PirClientBlocking> {
    let route = super::round_session::resolve_session_route(tor_runtime);
    voting::connect_pir_blocking(
        pir_layout,
        pir_url,
        Arc::new(voting::HyperTransport::with_route(route)),
    )
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

/// Bootstraps (or validates) `round_id`'s `rounds` row via
/// `DelegationPipeline::ensure_round`, so a delegation-enabled `runRound`/
/// `setupBundlesNative` call has a round to work against. See
/// `delegation_driver::ensure_round_from_jni`'s doc comment for why this
/// dedicated entry point exists (found while verifying the round-bootstrap
/// fix on-device: nothing reachable through `runRoundNative` alone can
/// create this row for a virgin round_id).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_ensureRoundNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    anchor_tree_state_bytes: JByteArray<'local>,
    snapshot_height: jlong,
    ea_pk: JByteArray<'local>,
    nc_root: JByteArray<'local>,
    nullifier_imt_root: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let round_id = java_string_to_rust(env, &round_id)?;
        let anchor_tree_state = java_bytes(env, &anchor_tree_state_bytes, "anchorTreeStateBytes")?;
        let snapshot_height = jlong_to_u64(snapshot_height, "snapshotHeight")?;
        let ea_pk = java_bytes(env, &ea_pk, "eaPk")?;
        let nc_root = java_bytes(env, &nc_root, "ncRoot")?;
        let nullifier_imt_root = java_bytes(env, &nullifier_imt_root, "nullifierImtRoot")?;
        super::delegation_driver::ensure_round_from_jni(
            db_handle,
            &round_id,
            &anchor_tree_state,
            snapshot_height,
            &ea_pk,
            &nc_root,
            &nullifier_imt_root,
        )
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_precomputeDelegationPirNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    tor_runtime: jlong,
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
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let round_id = java_string_to_rust(env, &round_id)?;

        // The pre-flight reads run under the shared access lock, which is
        // released again before the PIR client is asked for.
        let bundle_notes = {
            let _access_lock = db.access_lock()?;
            let notes = java_note_info_array(env, &notes, "notes")?;
            let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
            require_bundle_notes_match(&db, &round_id, bundle_index, &bundle_notes)?;
            bundle_notes
        };

        // Connecting a PIR client downloads a whole Tier-0 dataset, so it must
        // not happen under the access lock. Uses this file's own Tor-aware
        // connect_pir_client (not db.pir_client_for's cache) -- without a live
        // Tor runtime this would otherwise route every PIR request over plain
        // HTTP unconditionally, correlating the caller's IP with holding
        // voting-eligible notes, exactly the privacy requirement
        // precomputePirProofsNative below already carries. The tradeoff is
        // paying the Tier-0 handshake again on every call rather than reusing
        // a cached connection; see openRoundSessionNative's own doc comment
        // for the same Tor-preference policy this mirrors.
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_layout =
            pir_layout_from_jni(pir_depth, pir_tier0_layers, pir_tier1_layers, pir_poly_len)?;
        let pir_client = connect_pir_client(&pir_url, pir_layout, tor_runtime)?;

        // The precompute itself writes through the shared connection and is
        // short, so it takes the access lock again.
        let result = {
            let _access_lock = db.access_lock()?;
            db.precompute_delegation_pir(
                &round_id,
                bundle_index,
                &bundle_notes,
                &pir_client,
                db.network,
            )
            .map_err(|e| anyhow!("precompute_delegation_pir: {}", e))?
        };

        make_jni_delegation_pir_precompute_result(env, result)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Warms the bundle- and round-independent PIR proof cache via
/// `zcash_voting::precompute::precompute_pir_proofs_with_report`, so a later
/// `precomputeDelegationPirNative` call (or vote construction) can find its nullifier
/// non-membership proofs already cached instead of paying PIR round-trip latency
/// synchronously. Unlike `precomputeDelegationPirNative` above, this takes no
/// `round_id`/`bundle_index` -- `PirCachePrecomputeResult`'s own doc comment in the crate
/// describes the function it wraps as "the bundle- and round-independent PIR proof
/// precompute". This is the intended entry point for background pre-warming while the
/// app is otherwise idle, added because no JNI export reached either
/// `precompute_pir_proofs`/`precompute_pir_proofs_with_report` before this.
///
/// Passes `None` for the crate's `options: Option<ObservabilityOptions>` parameter --
/// `ObservationScope::new(None)` behaves identically to `ObservationScope::disabled()`
/// (confirmed by reading `zcash_voting::observability::scope`), so this stays exactly as
/// thin as calling the plain `precompute_pir_proofs` would have been, just via the
/// `_with_report` entry point the task specified. Uses `voting::BundlePolicy::default()`
/// for `bundle_policy`, matching this module's other default-policy call
/// (`bundle_setup_from_notes` in `helpers.rs`, backing `computeBundleSetupNative`) --
/// no Kotlin-facing knob exists yet for either parameter, and adding one is a distinct,
/// larger task than this thin export.
///
/// `tor_runtime: jlong` -- resolved via `super::round_session::resolve_session_route`,
/// same as `openRoundSessionNative`'s own `tor_runtime` parameter: real Tor routing when
/// it points at a live runtime, plain HTTP as the explicit fallback otherwise (`0` when
/// the caller has no live Tor runtime, e.g. Tor disabled). Added because this export is
/// wired (app repo) to fire automatically on mere screen entry (Poll List / Proposal
/// Detail / Review), not just on explicit vote submission -- routing its PIR network
/// requests through Tor by default (when available) avoids correlating the caller's IP
/// with holding voting-eligible notes just from browsing.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_precomputePirProofsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    tor_runtime: jlong,
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
        let notes = java_note_info_array(env, &notes, "notes")?;
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_layout =
            pir_layout_from_jni(pir_depth, pir_tier0_layers, pir_tier1_layers, pir_poly_len)?;
        let pir_client = connect_pir_client(&pir_url, pir_layout, tor_runtime)?;
        let report = voting::precompute::precompute_pir_proofs_with_report(
            &db,
            &notes,
            voting::BundlePolicy::default(),
            db.network,
            &pir_client,
            None,
        );
        let result = report
            .result
            .map_err(|e| anyhow!("precompute_pir_proofs_with_report: {}", e))?;

        make_jni_pir_precompute_result(env, result)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Persists the canonical bundle plan for `round_id`'s snapshot note set and warms PIR for
/// every bundle in that plan, via `zcash_voting::precompute::precompute_snapshot_bundles_with_report`.
///
/// Unlike `precomputeDelegationPirNative` above (one already-persisted bundle at a time, and
/// only after its bundle row exists), this is the whole-round entry point: it first calls the
/// crate's `ensure_bundles_with_policy` internally to persist (or validate, if already
/// persisted -- bundle rows are first-write-wins) the bundle layout for every bundle `notes`
/// chunks into, then loops the exact same per-bundle PIR-warmup step
/// `precomputeDelegationPirNative` wraps (`VotingDb::precompute_delegation_pir`, via the crate's
/// internal `observe_delegation_pir`) across every bundle in that layout. So a caller that
/// wants a round's bundles precomputed end to end (layout + every bundle's PIR proofs) should
/// call this once with the round's full snapshot note set, rather than persisting bundles via
/// `setupBundlesNative` and then calling `precomputeDelegationPirNative` once per bundle index.
///
/// Passes `None` for `options` and `voting::BundlePolicy::default()` for `bundle_policy`, same
/// reasoning as `precomputePirProofsNative` above -- no Kotlin-facing knob exists yet for
/// either, and this stays exactly as thin as calling the plain `precompute_snapshot_bundles`
/// would have been.
///
/// `tor_runtime: jlong` -- same parameter, same `resolve_session_route` resolution, and same
/// rationale as `precomputePirProofsNative`'s own `tor_runtime` doc above: this export is also
/// wired (app repo) to fire on mere screen entry, so its PIR fetches must not default to a
/// non-Tor transport.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_precomputeSnapshotBundlesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    tor_runtime: jlong,
    round_id: JString<'local>,
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
        let round_id = java_string_to_rust(env, &round_id)?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_layout =
            pir_layout_from_jni(pir_depth, pir_tier0_layers, pir_tier1_layers, pir_poly_len)?;
        let pir_client = connect_pir_client(&pir_url, pir_layout, tor_runtime)?;
        let report = voting::precompute::precompute_snapshot_bundles_with_report(
            &db,
            &round_id,
            &notes,
            voting::BundlePolicy::default(),
            &pir_client,
            db.network,
            None,
        );
        let result = report
            .result
            .map_err(|e| anyhow!("precompute_snapshot_bundles_with_report: {}", e))?;

        make_jni_snapshot_bundle_precompute_report(env, result)
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
///
/// `store_keystone_signatures_batch` itself only checks byte lengths and
/// that `sig`/`sighash`/`rk` match the persisted bundle columns -- it does
/// NOT check that `sig` is a cryptographically valid RedPallas spend-auth
/// signature over `sighash` under `rk`. The old hand-rolled PCZT path had
/// its own `verify_delegation_submission_sig` for exactly this; restore the
/// same check here so a malformed/forged (but correctly-sized) signature
/// from a compromised or buggy Keystone device can't be persisted.
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
        for signature in &signatures {
            verify_keystone_signature(signature)
                .map_err(|e| anyhow!("signatures[bundle {}]: {}", signature.bundle_index, e))?;
        }
        let result = db
            .store_keystone_signatures_batch(&round_id, &signatures)
            .map_err(|e| anyhow!("store_keystone_signatures_batch: {}", e))?;

        make_jni_keystone_signature_batch_result(env, result)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Verifies a Keystone-produced spend-auth signature against its claimed
/// `rk`/`sighash`, mirroring the old `verify_delegation_submission_sig`
/// (see git history of this file at the pre-`DelegationPipeline` revision).
fn verify_keystone_signature(
    signature: &voting::storage::KeystoneSignatureInput,
) -> anyhow::Result<()> {
    use orchard::primitives::redpallas::{Signature, SpendAuth, VerificationKey};

    let rk = fixed_bytes::<PROTOCOL_FIELD_BYTES>(signature.rk.clone(), "rk")?;
    let sighash = fixed_bytes::<PROTOCOL_FIELD_BYTES>(signature.sighash.clone(), "sighash")?;
    let sig = fixed_bytes::<SPEND_AUTH_SIG_BYTES>(signature.sig.clone(), "sig")?;
    let vk = VerificationKey::<SpendAuth>::try_from(rk)
        .map_err(|_| anyhow!("rk is not a valid spend authorization verification key"))?;
    if vk.is_identity() {
        return Err(anyhow!(
            "rk is not a valid spend authorization verification key"
        ));
    }

    vk.verify(&sighash, &Signature::<SpendAuth>::from(sig))
        .map_err(|_| anyhow!("sig does not verify against rk and sighash"))
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

/// Extracts the ZIP-244 shielded sighash from finalized PCZT bytes via
/// `zcash_voting::action::extract_pczt_sighash`.
///
/// Stateless: no `db_handle`/`session_handle`, just bytes in and the 32-byte
/// sighash out. The Keystone signing flow needs it to pair a device-returned
/// signature with the sighash that device signed, before handing both to
/// `storeKeystoneSignaturesNative`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractPcztSighashNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    pczt_bytes: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let bytes = java_bytes(env, &pczt_bytes, "pcztBytes")?;
        let sighash = voting::action::extract_pczt_sighash(&bytes)
            .map_err(|e| anyhow!("extract_pczt_sighash: {}", e))?;
        Ok(env.byte_array_from_slice(&sighash)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Extracts the 64-byte RedPallas spend-auth signature from a Keystone-signed
/// PCZT via `zcash_voting::action::extract_spend_auth_sig`.
///
/// Stateless, like `extractPcztSighashNative` above. `action_index` is the
/// caller's expected action; the crate function tries that index first and
/// then falls back to scanning every action, which is unambiguous because a
/// governance PCZT has exactly one signable action. That fallback lives in the
/// crate on purpose -- this wrapper deliberately calls the current crate
/// function directly rather than re-implementing the narrower index-only
/// lookup the pre-`DelegationPipeline` revision of this file carried.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractSpendAuthSigNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    signed_pczt_bytes: JByteArray<'local>,
    action_index: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let bytes = java_bytes(env, &signed_pczt_bytes, "signedPcztBytes")?;
        let action_index = jint_to_usize(action_index, "action_index")?;
        let sig = voting::action::extract_spend_auth_sig(&bytes, action_index)
            .map_err(|e| anyhow!("extract_spend_auth_sig: {}", e))?;
        Ok(env.byte_array_from_slice(&sig)?.into_raw())
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
        // ensure_bundles_with_skipped_suffix_with_policy only writes
        // note_positions_blob/note_identity_hashes_blob; bundles.pczt_sighash
        // and bundles.rk stay NULL until a real setup/proving pass runs.
        // store_keystone_signature's matches_bundle guard compares against
        // those columns, so without seeding them first this call fails with
        // KeystoneSignatureConflict before ever reaching the assertions
        // below. Mirrors the crate's own round_drive/tests/signatures.rs
        // store_signature helper.
        seed_bundle_signing_context(&db, &round_id, 0, &[0xAA; 32], &[0x22; 32]);

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
        // See store_keystone_signature_persists_and_is_retrievable's comment:
        // bundle 0's pczt_sighash/rk are NULL until seeded, and
        // matches_bundle would reject the seed call below without this.
        seed_bundle_signing_context(&db, &round_id, 0, &[0xAA; 32], &[0x22; 32]);

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

    /// Locks in the exact shape of the crate function
    /// `extractPcztSighashNative` marshals for: `&[u8]` in, a fixed
    /// `[u8; PROTOCOL_FIELD_BYTES]` out, `VotingError` (not `anyhow::Error`)
    /// as the error type. If the crate ever widens the return to a `Vec<u8>`
    /// or changes the array length, this stops compiling rather than silently
    /// handing Kotlin a differently-sized sighash.
    #[test]
    fn extract_pczt_sighash_signature_is_stable() {
        fn _assert_signature(
            pczt_bytes: &[u8],
        ) -> Result<[u8; PROTOCOL_FIELD_BYTES], voting::VotingError> {
            voting::action::extract_pczt_sighash(pczt_bytes)
        }
    }

    /// Same guard for `extractSpendAuthSigNative`: `&[u8]` plus a `usize`
    /// action index in, a fixed `[u8; SPEND_AUTH_SIG_BYTES]` RedPallas
    /// signature out, `VotingError` as the error type.
    #[test]
    fn extract_spend_auth_sig_signature_is_stable() {
        fn _assert_signature(
            signed_pczt_bytes: &[u8],
            action_index: usize,
        ) -> Result<[u8; SPEND_AUTH_SIG_BYTES], voting::VotingError> {
            voting::action::extract_spend_auth_sig(signed_pczt_bytes, action_index)
        }
    }

    /// Directly sets a bundle row's `pczt_sighash`/`rk` columns, the way a
    /// real setup/proving pass would, so `store_keystone_signature`'s
    /// `matches_bundle` guard has something to compare against. Mirrors
    /// `zcash_voting::round_drive::tests::signatures::store_signature`
    /// (confirmed by reading that file at the pinned commit) rather than
    /// guessing at the schema.
    fn seed_bundle_signing_context(
        db: &VotingDb,
        round_id: &str,
        bundle_index: u32,
        sighash: &[u8],
        rk: &[u8],
    ) {
        let conn = db.conn();
        let wallet_id = db.wallet_id();
        conn.execute(
            "UPDATE bundles SET pczt_sighash = ?1, rk = ?2 \
             WHERE round_id = ?3 AND wallet_id = ?4 AND bundle_index = ?5",
            rusqlite::params![sighash, rk, round_id, wallet_id, bundle_index],
        )
        .expect("seed bundle signing context");
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
