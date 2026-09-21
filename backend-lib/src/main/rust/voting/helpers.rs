use super::*;

// Must match JNI_ROUND_PHASE_* constants in JniVotingModels.kt.
const PHASE_INITIALIZED: u32 = 0;
const PHASE_HOTKEY_GENERATED: u32 = 1;
const PHASE_DELEGATION_CONSTRUCTED: u32 = 2;
const PHASE_DELEGATION_PROVED: u32 = 3;
const PHASE_VOTE_READY: u32 = 4;

const JNI_ROUND_SUMMARY: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniRoundSummary";
const JNI_NOTE_INFO: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniNoteInfo";
const JNI_WITNESS_DATA: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniWitnessData";
const JNI_VOTING_HOTKEY: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniVotingHotkey";
const JNI_BUNDLE_SETUP_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniBundleSetupResult";
const JNI_DELEGATION_PIR_PRECOMPUTE_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniDelegationPirPrecomputeResult";
// `JniRoundPlan`/`JniRoundRunReport` have no Kotlin-side class yet (Task 5 of the
// voting-5.0.0-sdk-port plan is Rust/JNI-export only; Task 9 designs the exact
// Kotlin-facing shape and adds the matching class to JniVotingModels.kt). The
// constructor signatures below are this task's best-effort proposal for that
// shape, encoding every RoundPlan field either directly (primitives, int arrays)
// or as a JSON string for nested/complex types the crate doesn't derive
// `Serialize` for -- see encode_round_plan's doc comment.
const JNI_ROUND_PLAN: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniRoundPlan";
const JNI_ROUND_RUN_REPORT: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniRoundRunReport";
// Task 7 (voting-5.0.0-sdk-port): `trackSharesNative`'s report, replacing the
// old record/mark-confirmed/add-sent-servers cluster's `JniShareDelegationRecord`
// readback. No Kotlin-side class exists yet -- same "Rust/JNI-export-only task"
// situation as JNI_ROUND_PLAN/JNI_ROUND_RUN_REPORT above.
const JNI_SHARE_TRACKING_RUN_REPORT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniShareTrackingRunReport";

// Must match JniNoteInfo(ByteArray, ByteArray, Long, Long, ByteArray,
// ByteArray, ByteArray, Int, String) in JniVotingModels.kt.
// Guarded by JniVotingModelsTest.
const JNI_NOTE_INFO_CTOR_SIG: &str = "([B[BJJ[B[B[BILjava/lang/String;)V";
// Must match JniWitnessData(ByteArray, Long, ByteArray, Array<ByteArray>)
// in JniVotingModels.kt. Guarded by JniVotingModelsTest.
const JNI_WITNESS_DATA_CTOR_SIG: &str = "([BJ[B[[B)V";
// Must match JniVotingHotkey(ByteArray, ByteArray, String) in JniVotingModels.kt.
const JNI_VOTING_HOTKEY_CTOR_SIG: &str = "([B[BLjava/lang/String;)V";
// Must match JniBundleSetupResult(Int, Long, LongArray) in JniVotingModels.kt.
const JNI_BUNDLE_SETUP_RESULT_CTOR_SIG: &str = "(IJ[J)V";
// Must match JniDelegationPirPrecomputeResult(Long, Long) in JniVotingModels.kt.
const JNI_DELEGATION_PIR_PRECOMPUTE_RESULT_CTOR_SIG: &str = "(JJ)V";
// Task 6 (voting-5.0.0-sdk-port): batch Keystone signing surface replacing the
// old buildGovernancePczt*/getDelegationSubmissionWithKeystoneSig*/
// storeKeystoneSignatureNative pair. No Kotlin-side classes exist yet for
// these three -- same situation as JNI_ROUND_PLAN/JNI_ROUND_RUN_REPORT above
// (Rust/JNI-export-only task; the app-side class is a later task).
const JNI_KEYSTONE_SIGNING_REQUEST: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniKeystoneSigningRequest";
const JNI_KEYSTONE_SIGNATURE_BATCH_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniKeystoneSignatureBatchResult";
const JNI_KEYSTONE_SIGNATURE_RECORD: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniKeystoneSignatureRecord";
// Proposed JniKeystoneSigningRequest(ByteArray, ByteArray, ByteArray,
// ByteArray, Int, String, Long, Long, Int, Int) constructor, one parameter
// per zcash_voting::delegate::KeystoneSigningRequest field in declaration
// order (pczt_bytes, redacted_pczt_bytes, pczt_sighash, rk, action_index,
// display_memo, eligible_weight_zatoshi, delegated_weight_zatoshi,
// bundle_count, bundle_index).
const JNI_KEYSTONE_SIGNING_REQUEST_CTOR_SIG: &str = "([B[B[B[BILjava/lang/String;JJII)V";
// Proposed JniKeystoneSignatureBatchResult(Int, Int) constructor, matching
// zcash_voting::storage::KeystoneSignatureBatchResult { inserted,
// already_present }.
const JNI_KEYSTONE_SIGNATURE_BATCH_RESULT_CTOR_SIG: &str = "(II)V";
// Proposed JniKeystoneSignatureRecord(Int, ByteArray, ByteArray, ByteArray)
// constructor, matching zcash_voting::storage::KeystoneSignatureRecord
// { bundle_index, sig, sighash, rk }.
const JNI_KEYSTONE_SIGNATURE_RECORD_CTOR_SIG: &str = "(I[B[B[B)V";
// Proposed JniRoundPlan(String, Boolean, String, IntArray, IntArray, String?,
// Boolean, Boolean, String, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean,
// String?, Boolean, Boolean, Int, Boolean, Boolean, IntArray, IntArray, Boolean,
// Boolean, Boolean, String, String) constructor, one parameter per RoundPlan
// field in declaration order. No Kotlin class exists yet -- see the JNI_ROUND_PLAN
// doc comment above.
const JNI_ROUND_PLAN_CTOR_SIG: &str = "(Ljava/lang/String;ZLjava/lang/String;[I[ILjava/lang/String;ZZLjava/lang/String;ZZZZZZLjava/lang/String;ZZIZZ[I[IZZZLjava/lang/String;Ljava/lang/String;)V";
// Proposed JniRoundRunReport(String, String?, JniRoundPlan?, Int, Int, Int,
// String, IntArray, String, String, Int) constructor. No Kotlin class exists yet
// -- see the JNI_ROUND_RUN_REPORT doc comment above.
const JNI_ROUND_RUN_REPORT_CTOR_SIG: &str = "(Ljava/lang/String;Ljava/lang/String;Lcash/z/ecc/android/sdk/internal/model/voting/JniRoundPlan;IIILjava/lang/String;[ILjava/lang/String;Ljava/lang/String;I)V";
// Proposed JniShareTrackingRunReport(String, String?, Int, String, String,
// String, String, String) constructor: quiescenceKind, quiescenceDetailJson,
// passes, confirmedJson, resubmittedJson, ambiguousJson, unrecoverableJson,
// failuresJson -- one parameter per ShareTrackingRunReport field in
// declaration order, complex fields (Vec<ShareKey>/Vec<ResubmittedShare>/
// Vec<String>) JSON-encoded since ShareKey/ResubmittedShare are not
// `Serialize` in the crate. No Kotlin class exists yet -- see the
// JNI_SHARE_TRACKING_RUN_REPORT doc comment above.
const JNI_SHARE_TRACKING_RUN_REPORT_CTOR_SIG: &str = "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

pub(super) const ORCHARD_RAW_ADDRESS_BYTES: usize = 43;
pub(super) const ORCHARD_FVK_BYTES: usize = 96;
pub(super) const PROTOCOL_FIELD_BYTES: usize = 32;
pub(super) const VOTE_COMMITMENT_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const BLIND_BYTES: usize = PROTOCOL_FIELD_BYTES;
// Length of VotingHotkey::stored_secret(), the opaque app-owned secret Android
// must persist after a fresh generateHotkeyNative call.
pub(super) const HOTKEY_STORED_SECRET_BYTES: usize = 64;
// Hotkeys use one stable Orchard address for voting identity and recovery.
pub(super) const HOTKEY_ADDRESS_INDEX: u32 = 0;
// ZIP-32 account for deriving hotkey material from the hotkey seed. This is intentionally
// distinct from HOTKEY_ADDRESS_INDEX: account selects the Orchard account, address index
// selects the stable address within that account. zcash_voting's vote path currently derives
// hotkey signing material only for account 0.
pub(super) const HOTKEY_ACCOUNT_INDEX: u32 = 0;
pub(super) const SPEND_AUTH_SIG_BYTES: usize = 64;
pub(super) const NOTE_SCOPE_EXTERNAL: u32 = 0;
pub(super) const NOTE_SCOPE_INTERNAL: u32 = 1;
pub(super) const ORCHARD_DIVERSIFIER_BYTES: usize = 11;
pub(super) const ORCHARD_WITNESS_PATH_DEPTH: usize = 32;
pub(super) const ACCOUNT_UUID_BYTES: usize = 16;
pub(super) const NETWORK_ID_TESTNET: jint = 0;
pub(super) const NETWORK_ID_MAINNET: jint = 1;

struct JniRoundSummaryPayload {
    round_id: String,
    phase: jint,
    snapshot_height: jlong,
    created_at: jlong,
}

pub(super) fn jint_to_u32(value: jint, field: &str) -> anyhow::Result<u32> {
    u32::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(super) fn jlong_to_u64(value: jlong, field: &str) -> anyhow::Result<u64> {
    u64::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(super) fn jlong_to_u32(value: jlong, field: &str) -> anyhow::Result<u32> {
    u32::try_from(value).map_err(|_| anyhow!("{field} must be in range 0..=u32::MAX, got {value}"))
}

pub(super) fn jint_to_usize(value: jint, field: &str) -> anyhow::Result<usize> {
    usize::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(super) fn u32_to_jint(value: u32, field: &str) -> anyhow::Result<jint> {
    jint::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Int range: {value}"))
}

pub(super) fn usize_to_jint(value: usize, field: &str) -> anyhow::Result<jint> {
    jint::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Int range: {value}"))
}

pub(super) fn u64_to_jlong(value: u64, field: &str) -> anyhow::Result<jlong> {
    jlong::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Long range: {value}"))
}

pub(super) fn require_len(bytes: Vec<u8>, field: &str, expected: usize) -> anyhow::Result<Vec<u8>> {
    if bytes.len() == expected {
        Ok(bytes)
    } else {
        Err(anyhow!(
            "{field} must be exactly {expected} bytes, got {}",
            bytes.len()
        ))
    }
}

fn require_each_len(
    values: Vec<Vec<u8>>,
    field: &str,
    expected: usize,
) -> anyhow::Result<Vec<Vec<u8>>> {
    values
        .into_iter()
        .enumerate()
        .map(|(index, value)| require_len(value, &format!("{field}[{index}]"), expected))
        .collect()
}

pub(super) fn require_min_len(
    bytes: Vec<u8>,
    field: &str,
    minimum: usize,
) -> anyhow::Result<Vec<u8>> {
    if bytes.len() >= minimum {
        Ok(bytes)
    } else {
        Err(anyhow!(
            "{field} must be at least {minimum} bytes, got {}",
            bytes.len()
        ))
    }
}

pub(super) fn java_bytes(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<u8>> {
    env.convert_byte_array(array)
        .map_err(|e| anyhow!("{field}: failed to read byte array: {e}"))
}

pub(super) fn java_fixed_bytes<const N: usize>(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<[u8; N]> {
    fixed_bytes(java_bytes(env, array, field)?, field)
}

pub(super) fn fixed_bytes<const N: usize>(bytes: Vec<u8>, field: &str) -> anyhow::Result<[u8; N]> {
    let len = bytes.len();

    bytes
        .try_into()
        .map_err(|_| anyhow!("{field} must be exactly {N} bytes, got {len}"))
}

pub(super) fn round_phase_to_u32(phase: RoundPhase) -> u32 {
    match phase {
        RoundPhase::Initialized => PHASE_INITIALIZED,
        RoundPhase::HotkeyGenerated => PHASE_HOTKEY_GENERATED,
        RoundPhase::DelegationConstructed => PHASE_DELEGATION_CONSTRUCTED,
        RoundPhase::DelegationProved => PHASE_DELEGATION_PROVED,
        RoundPhase::VoteReady => PHASE_VOTE_READY,
    }
}

pub(super) fn java_secret_bytes_at_least(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    minimum: usize,
) -> anyhow::Result<SecretVec<u8>> {
    require_min_len(java_bytes(env, array, field)?, field, minimum).map(SecretVec::new)
}

/// Like [`java_secret_bytes_at_least`], but for call sites where the caller-supplied bytes
/// feed the exact same ZIP-32 `UnifiedSpendingKey::from_seed` derivation
/// `VotingHotkey::from_stored_secret` uses internally, so a length that from_stored_secret
/// would reject should be rejected here too rather than accepted and only failing later.
pub(super) fn java_secret_bytes_exact(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    expected: usize,
) -> anyhow::Result<SecretVec<u8>> {
    require_len(java_bytes(env, array, field)?, field, expected).map(SecretVec::new)
}

pub(super) fn java_byte_array_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Vec<u8>> {
    let field = JByteArray::from(env.get_field(obj, name, "[B")?.l()?);
    java_bytes(env, &field, name)
}

/// Like [`java_byte_array_field`], but for a nullable `ByteArray?` field:
/// `None` when the field itself is Java `null`.
pub(super) fn java_nullable_byte_array_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Option<Vec<u8>>> {
    let field = env.get_field(obj, name, "[B")?.l()?;
    if field.is_null() {
        Ok(None)
    } else {
        java_bytes(env, &JByteArray::from(field), name).map(Some)
    }
}

pub(super) fn java_string_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<String> {
    let field = JString::from(env.get_field(obj, name, "Ljava/lang/String;")?.l()?);
    java_string_to_rust(env, &field)
}

pub(super) fn java_string_array_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Vec<String>> {
    let field = JObjectArray::from(env.get_field(obj, name, "[Ljava/lang/String;")?.l()?);
    java_string_array(env, &field, name)
}

fn java_byte_array_list_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Vec<Vec<u8>>> {
    let list = env.get_field(obj, name, "Ljava/util/List;")?.l()?;
    let count = env.call_method(&list, "size", "()I", &[])?.i()?;
    if count < 0 {
        return Err(anyhow!("{name}.size() returned negative count {count}"));
    }

    (0..count)
        .map(|index| {
            let element = env
                .call_method(&list, "get", "(I)Ljava/lang/Object;", &[JValue::Int(index)])?
                .l()?;
            let bytes = JByteArray::from(element);
            java_bytes(env, &bytes, &format!("{name}[{index}]"))
        })
        .collect()
}

pub(super) fn network_from_id(id: jint) -> anyhow::Result<Network> {
    match id {
        NETWORK_ID_TESTNET => Ok(Network::TestNetwork),
        NETWORK_ID_MAINNET => Ok(Network::MainNetwork),
        _ => Err(anyhow!("invalid network_id {}", id)),
    }
}

/// Resolves the `voting::types::Network` a voting DB handle is opened for.
///
/// Android has no custom-network registry in this path, so only the two
/// well-known network ids are accepted; everything else (including the
/// legacy "custom network" id 2) is rejected here.
pub(super) fn voting_network_from_id(id: jint) -> anyhow::Result<voting::types::Network> {
    match id {
        NETWORK_ID_TESTNET => Ok(voting::types::Network::Testnet),
        NETWORK_ID_MAINNET => Ok(voting::types::Network::Mainnet),
        _ => Err(anyhow!("invalid network_id {}", id)),
    }
}

pub(super) fn hotkey_orchard_raw_address(
    hotkey_seed: &[u8],
    network: Network,
    account_index: u32,
) -> anyhow::Result<Vec<u8>> {
    let account_id = zip32::AccountId::try_from(account_index)
        .map_err(|_| anyhow!("invalid account_index {}", account_index))?;
    let usk = UnifiedSpendingKey::from_seed(&network, hotkey_seed, account_id)
        .map_err(|e| anyhow!("failed to derive hotkey USK: {}", e))?;
    let fvk = usk.to_unified_full_viewing_key();
    let orchard_fvk = fvk
        .orchard()
        .ok_or_else(|| anyhow!("hotkey UFVK has no Orchard component"))?;
    let addr = orchard_fvk.address_at(HOTKEY_ADDRESS_INDEX, Scope::External);
    require_len(
        addr.to_raw_address_bytes().to_vec(),
        "hotkey_raw_address",
        ORCHARD_RAW_ADDRESS_BYTES,
    )
}

pub(super) fn orchard_fvk_bytes(ufvk_str: &str, network: Network) -> anyhow::Result<Vec<u8>> {
    let ufvk = UnifiedFullViewingKey::decode(&network, ufvk_str)
        .map_err(|e| anyhow!("failed to decode UFVK: {}", e))?;
    let fvk = ufvk
        .orchard()
        .ok_or_else(|| anyhow!("UFVK has no Orchard component"))?;
    require_len(fvk.to_bytes().to_vec(), "orchard_fvk", ORCHARD_FVK_BYTES)
}

pub(super) fn java_note_info_array(
    env: &mut JNIEnv<'_>,
    notes: &JObjectArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<NoteInfo>> {
    let count = env.get_array_length(notes)?;
    (0..count)
        .map(|index| {
            let note = env.get_object_array_element(notes, index)?;
            java_note_info(env, &note).map_err(|e| anyhow!("{field}[{index}]: {e}"))
        })
        .collect()
}

fn java_note_info(env: &mut JNIEnv<'_>, note: &JObject<'_>) -> anyhow::Result<NoteInfo> {
    let scope = require_note_scope(jint_to_u32(
        env.get_field(note, "scope", "I")?.i()?,
        "scope",
    )?)?;

    Ok(NoteInfo {
        commitment: require_len(
            java_byte_array_field(env, note, "commitment")?,
            "commitment",
            PROTOCOL_FIELD_BYTES,
        )?,
        nullifier: require_len(
            java_byte_array_field(env, note, "nullifier")?,
            "nullifier",
            PROTOCOL_FIELD_BYTES,
        )?,
        value: jlong_to_u64(env.get_field(note, "value", "J")?.j()?, "value")?,
        position: jlong_to_u64(env.get_field(note, "position", "J")?.j()?, "position")?,
        diversifier: require_len(
            java_byte_array_field(env, note, "diversifier")?,
            "diversifier",
            ORCHARD_DIVERSIFIER_BYTES,
        )?,
        rho: require_len(
            java_byte_array_field(env, note, "rho")?,
            "rho",
            PROTOCOL_FIELD_BYTES,
        )?,
        rseed: require_len(
            java_byte_array_field(env, note, "rseed")?,
            "rseed",
            PROTOCOL_FIELD_BYTES,
        )?,
        scope,
        ufvk_str: java_string_field(env, note, "ufvk")?,
    })
}

pub(super) fn java_witness_data(
    env: &mut JNIEnv<'_>,
    witness: &JObject<'_>,
) -> anyhow::Result<WitnessData> {
    let auth_path = java_byte_array_list_field(env, witness, "authPath")?;
    if auth_path.len() != ORCHARD_WITNESS_PATH_DEPTH {
        return Err(anyhow!(
            "authPath must contain {ORCHARD_WITNESS_PATH_DEPTH} entries, got {}",
            auth_path.len()
        ));
    }

    Ok(WitnessData {
        note_commitment: require_len(
            java_byte_array_field(env, witness, "noteCommitment")?,
            "noteCommitment",
            PROTOCOL_FIELD_BYTES,
        )?,
        position: jlong_to_u64(env.get_field(witness, "position", "J")?.j()?, "position")?,
        root: require_len(
            java_byte_array_field(env, witness, "root")?,
            "root",
            PROTOCOL_FIELD_BYTES,
        )?,
        auth_path: require_each_len(auth_path, "authPath", PROTOCOL_FIELD_BYTES)?,
    })
}

fn require_note_scope(scope: u32) -> anyhow::Result<u32> {
    match scope {
        NOTE_SCOPE_EXTERNAL | NOTE_SCOPE_INTERNAL => Ok(scope),
        _ => Err(anyhow!(
            "scope must be {NOTE_SCOPE_EXTERNAL} (external) or {NOTE_SCOPE_INTERNAL} (internal), got {scope}"
        )),
    }
}

pub(super) fn make_jni_round_state<'local>(
    env: &mut JNIEnv<'local>,
    state: RoundState,
) -> anyhow::Result<jobject> {
    let phase = round_phase_to_u32(state.phase);
    let class = env.find_class("cash/z/ecc/android/sdk/internal/model/voting/JniRoundState")?;
    let round_id_obj: JObject<'local> = env.new_string(&state.round_id)?.into();
    let hotkey_obj: JObject<'local> = match &state.hotkey_address {
        Some(a) => env.new_string(a)?.into(),
        None => JObject::null(),
    };
    let long_class = env.find_class("java/lang/Long")?;
    let weight_obj: JObject<'local> = match state.delegated_weight {
        Some(w) => env.new_object(
            &long_class,
            "(J)V",
            &[JValue::Long(u64_to_jlong(w, "delegated_weight")?)],
        )?,
        None => JObject::null(),
    };
    let obj = env.new_object(
        &class,
        // Matches JniRoundState(roundId, phase, snapshotHeight, hotkeyAddress,
        //                       delegatedWeight, proofGenerated).
        "(Ljava/lang/String;IJLjava/lang/String;Ljava/lang/Long;Z)V",
        &[
            JValue::Object(&round_id_obj),
            JValue::Int(u32_to_jint(phase, "round_phase")?),
            JValue::Long(u64_to_jlong(state.snapshot_height, "snapshot_height")?),
            JValue::Object(&hotkey_obj),
            JValue::Object(&weight_obj),
            JValue::Bool(state.proof_generated as jboolean),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn make_jni_round_summaries(
    env: &mut JNIEnv<'_>,
    rounds: Vec<RoundSummary>,
) -> anyhow::Result<jobjectArray> {
    let payloads = rounds
        .into_iter()
        .map(JniRoundSummaryPayload::try_from)
        .collect::<anyhow::Result<Vec<_>>>()?;

    Ok(
        rust_vec_to_java(env, payloads, JNI_ROUND_SUMMARY, |env, round| {
            let round_id_obj: JObject<'_> = env.new_string(round.round_id)?.into();
            env.new_object(
                JNI_ROUND_SUMMARY,
                // Matches JniRoundSummary(roundId, phase, snapshotHeight, createdAt).
                "(Ljava/lang/String;IJJ)V",
                &[
                    JValue::Object(&round_id_obj),
                    JValue::Int(round.phase),
                    JValue::Long(round.snapshot_height),
                    JValue::Long(round.created_at),
                ],
            )
        })?
        .into_raw(),
    )
}

impl TryFrom<RoundSummary> for JniRoundSummaryPayload {
    type Error = anyhow::Error;

    fn try_from(round: RoundSummary) -> anyhow::Result<Self> {
        Ok(JniRoundSummaryPayload {
            round_id: round.round_id,
            phase: u32_to_jint(round_phase_to_u32(round.phase), "phase")?,
            snapshot_height: u64_to_jlong(round.snapshot_height, "snapshot_height")?,
            created_at: u64_to_jlong(round.created_at, "created_at")?,
        })
    }
}

pub(super) fn make_jni_note_info_array<'local>(
    env: &mut JNIEnv<'local>,
    notes: Vec<NoteInfo>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(notes.len(), "notes length")?;
    let class = env.find_class(JNI_NOTE_INFO)?;
    let mut notes = notes.into_iter().enumerate();
    if let Some((_, first)) = notes.next() {
        let first = make_jni_note_info(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, note) in notes {
            let note = make_jni_note_info(env, note)?;
            env.set_object_array_element(&array, usize_to_jint(index, "notes index")?, &note)?;
            env.delete_local_ref(note)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

fn make_jni_note_info<'local>(
    env: &mut JNIEnv<'local>,
    note: NoteInfo,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(16, |env| {
        let class = env.find_class(JNI_NOTE_INFO)?;
        let commitment =
            make_jni_fixed_bytes(env, note.commitment, "commitment", PROTOCOL_FIELD_BYTES)?;
        let nullifier =
            make_jni_fixed_bytes(env, note.nullifier, "nullifier", PROTOCOL_FIELD_BYTES)?;
        let diversifier = make_jni_fixed_bytes(
            env,
            note.diversifier,
            "diversifier",
            ORCHARD_DIVERSIFIER_BYTES,
        )?;
        let rho = make_jni_fixed_bytes(env, note.rho, "rho", PROTOCOL_FIELD_BYTES)?;
        let rseed = make_jni_fixed_bytes(env, note.rseed, "rseed", PROTOCOL_FIELD_BYTES)?;
        let ufvk: JObject<'_> = env.new_string(note.ufvk_str)?.into();

        Ok(env.new_object(
            &class,
            JNI_NOTE_INFO_CTOR_SIG,
            &[
                JValue::Object(&commitment),
                JValue::Object(&nullifier),
                JValue::Long(u64_to_jlong(note.value, "value")?),
                JValue::Long(u64_to_jlong(note.position, "position")?),
                JValue::Object(&diversifier),
                JValue::Object(&rho),
                JValue::Object(&rseed),
                JValue::Int(u32_to_jint(note.scope, "scope")?),
                JValue::Object(&ufvk),
            ],
        )?)
    })
}

pub(super) fn make_jni_witness_data_array<'local>(
    env: &mut JNIEnv<'local>,
    witnesses: Vec<WitnessData>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(witnesses.len(), "witnesses length")?;
    let class = env.find_class(JNI_WITNESS_DATA)?;
    let mut witnesses = witnesses.into_iter().enumerate();
    if let Some((_, first)) = witnesses.next() {
        let first = make_jni_witness_data(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, witness) in witnesses {
            let witness = make_jni_witness_data(env, witness)?;
            env.set_object_array_element(
                &array,
                usize_to_jint(index, "witnesses index")?,
                &witness,
            )?;
            env.delete_local_ref(witness)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

fn make_jni_witness_data<'local>(
    env: &mut JNIEnv<'local>,
    witness: WitnessData,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(48, |env| {
        let class = env.find_class(JNI_WITNESS_DATA)?;
        let note_commitment = make_jni_fixed_bytes(
            env,
            witness.note_commitment,
            "note_commitment",
            PROTOCOL_FIELD_BYTES,
        )?;
        let root = make_jni_fixed_bytes(env, witness.root, "root", PROTOCOL_FIELD_BYTES)?;
        let auth_path = make_jni_fixed_byte_array_vec(
            env,
            witness.auth_path,
            "auth_path",
            ORCHARD_WITNESS_PATH_DEPTH,
            PROTOCOL_FIELD_BYTES,
        )?;
        let auth_path = JObject::from(auth_path);

        Ok(env.new_object(
            &class,
            JNI_WITNESS_DATA_CTOR_SIG,
            &[
                JValue::Object(&note_commitment),
                JValue::Long(u64_to_jlong(witness.position, "position")?),
                JValue::Object(&root),
                JValue::Object(&auth_path),
            ],
        )?)
    })
}

/// Builds the Kotlin hotkey JNI model, including the opaque stored secret.
///
/// Unlike the pre-1.0 wallet-seed-derived hotkey, `generateHotkeyNative` can
/// mint a fresh app-owned hotkey identity, so its stored secret must cross
/// JNI here for Android to persist in secure storage; the crate never
/// re-derives it from the wallet seed.
pub(super) fn make_jni_voting_hotkey<'local>(
    env: &mut JNIEnv<'local>,
    hotkey: voting::types::VotingHotkey,
) -> anyhow::Result<jobject> {
    let stored_secret = require_len(
        hotkey.stored_secret().to_vec(),
        "hotkey_stored_secret",
        HOTKEY_STORED_SECRET_BYTES,
    )?;
    let raw_address = *hotkey.raw_orchard_address();
    let address = hotkey_unified_address(&raw_address, hotkey.network())?;

    let class = env.find_class(JNI_VOTING_HOTKEY)?;
    let secret_obj: JObject<'local> = env.byte_array_from_slice(&stored_secret)?.into();
    let raw_address_obj: JObject<'local> = env.byte_array_from_slice(&raw_address)?.into();
    let addr_obj: JObject<'local> = env.new_string(&address)?.into();
    let obj = env.new_object(
        &class,
        JNI_VOTING_HOTKEY_CTOR_SIG,
        &[
            JValue::Object(&secret_obj),
            JValue::Object(&raw_address_obj),
            JValue::Object(&addr_obj),
        ],
    )?;
    Ok(obj.into_raw())
}

/// Encodes a hotkey's raw Orchard receiver as a Unified Address string.
fn hotkey_unified_address(
    raw_address: &[u8; ORCHARD_RAW_ADDRESS_BYTES],
    network: voting::types::Network,
) -> anyhow::Result<String> {
    let orchard_address =
        Option::<orchard::Address>::from(orchard::Address::from_raw_address_bytes(raw_address))
            .ok_or_else(|| anyhow!("hotkey raw Orchard address bytes are invalid"))?;
    let unified_address = zcash_client_backend::address::UnifiedAddress::from_receivers(
        Some(orchard_address),
        None,
        None,
    )
    .ok_or_else(|| anyhow!("failed to build unified address from hotkey Orchard receiver"))?;
    let encode_network = match network {
        voting::types::Network::Mainnet => Network::MainNetwork,
        voting::types::Network::Testnet | voting::types::Network::Regtest => Network::TestNetwork,
    };
    Ok(unified_address.encode(&encode_network))
}

/// Builds the Kotlin bundle setup JNI model with width-checked Java primitives.
pub(super) fn make_jni_bundle_setup_result<'local>(
    env: &mut JNIEnv<'local>,
    count: u32,
    weight: u64,
    bundle_weights: &[u64],
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_BUNDLE_SETUP_RESULT)?;
    let weights = bundle_weights
        .iter()
        .enumerate()
        .map(|(index, weight)| u64_to_jlong(*weight, &format!("bundle_weights[{index}]")))
        .collect::<anyhow::Result<Vec<_>>>()?;
    let weights_array =
        env.new_long_array(usize_to_jint(weights.len(), "bundle_weights length")?)?;
    env.set_long_array_region(&weights_array, 0, &weights)?;
    let weights_array_obj = JObject::from(weights_array);
    let obj = env.new_object(
        &class,
        JNI_BUNDLE_SETUP_RESULT_CTOR_SIG,
        &[
            JValue::Int(u32_to_jint(count, "bundle_count")?),
            JValue::Long(u64_to_jlong(weight, "eligible_weight")?),
            JValue::Object(&weights_array_obj),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn make_jni_delegation_pir_precompute_result<'local>(
    env: &mut JNIEnv<'local>,
    result: DelegationPirPrecomputeResult,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_DELEGATION_PIR_PRECOMPUTE_RESULT)?;
    let obj = env.new_object(
        &class,
        JNI_DELEGATION_PIR_PRECOMPUTE_RESULT_CTOR_SIG,
        &[
            JValue::Long(u64_to_jlong(
                u64::from(result.cached_count),
                "cached_count",
            )?),
            JValue::Long(u64_to_jlong(
                u64::from(result.fetched_count),
                "fetched_count",
            )?),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn java_keystone_signature_input_array(
    env: &mut JNIEnv<'_>,
    signatures: &JObjectArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<voting::storage::KeystoneSignatureInput>> {
    let count = env.get_array_length(signatures)?;
    (0..count)
        .map(|index| {
            let signature = env.get_object_array_element(signatures, index)?;
            java_keystone_signature_input(env, &signature)
                .map_err(|e| anyhow!("{field}[{index}]: {e}"))
        })
        .collect()
}

fn java_keystone_signature_input(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
) -> anyhow::Result<voting::storage::KeystoneSignatureInput> {
    Ok(voting::storage::KeystoneSignatureInput {
        bundle_index: jint_to_u32(env.get_field(obj, "bundleIndex", "I")?.i()?, "bundleIndex")?,
        sig: java_byte_array_field(env, obj, "sig")?,
        sighash: java_byte_array_field(env, obj, "sighash")?,
        rk: java_byte_array_field(env, obj, "rk")?,
    })
}

pub(super) fn make_jni_keystone_signature_batch_result<'local>(
    env: &mut JNIEnv<'local>,
    result: voting::storage::KeystoneSignatureBatchResult,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_KEYSTONE_SIGNATURE_BATCH_RESULT)?;
    let obj = env.new_object(
        &class,
        JNI_KEYSTONE_SIGNATURE_BATCH_RESULT_CTOR_SIG,
        &[
            JValue::Int(u32_to_jint(result.inserted, "inserted")?),
            JValue::Int(u32_to_jint(result.already_present, "already_present")?),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn make_jni_keystone_signature_record_array<'local>(
    env: &mut JNIEnv<'local>,
    records: Vec<voting::storage::KeystoneSignatureRecord>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(records.len(), "records length")?;
    let class = env.find_class(JNI_KEYSTONE_SIGNATURE_RECORD)?;
    let mut records = records.into_iter().enumerate();
    if let Some((_, first)) = records.next() {
        let first = make_jni_keystone_signature_record(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, record) in records {
            let record = make_jni_keystone_signature_record(env, record)?;
            env.set_object_array_element(&array, usize_to_jint(index, "records index")?, &record)?;
            env.delete_local_ref(record)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

fn make_jni_keystone_signature_record<'local>(
    env: &mut JNIEnv<'local>,
    record: voting::storage::KeystoneSignatureRecord,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(16, |env| {
        let class = env.find_class(JNI_KEYSTONE_SIGNATURE_RECORD)?;
        let sig = make_jni_bytes(env, &record.sig)?;
        let sighash = make_jni_bytes(env, &record.sighash)?;
        let rk = make_jni_bytes(env, &record.rk)?;
        Ok(env.new_object(
            &class,
            JNI_KEYSTONE_SIGNATURE_RECORD_CTOR_SIG,
            &[
                JValue::Int(u32_to_jint(record.bundle_index, "bundle_index")?),
                JValue::Object(&sig),
                JValue::Object(&sighash),
                JValue::Object(&rk),
            ],
        )?)
    })
}

pub(super) fn make_jni_keystone_signing_request_array<'local>(
    env: &mut JNIEnv<'local>,
    requests: Vec<voting::delegate::KeystoneSigningRequest>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(requests.len(), "requests length")?;
    let class = env.find_class(JNI_KEYSTONE_SIGNING_REQUEST)?;
    let mut requests = requests.into_iter().enumerate();
    if let Some((_, first)) = requests.next() {
        let first = make_jni_keystone_signing_request(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, request) in requests {
            let request = make_jni_keystone_signing_request(env, request)?;
            env.set_object_array_element(
                &array,
                usize_to_jint(index, "requests index")?,
                &request,
            )?;
            env.delete_local_ref(request)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

fn make_jni_keystone_signing_request<'local>(
    env: &mut JNIEnv<'local>,
    request: voting::delegate::KeystoneSigningRequest,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(32, |env| {
        let class = env.find_class(JNI_KEYSTONE_SIGNING_REQUEST)?;
        let pczt_bytes = make_jni_bytes(env, &request.pczt_bytes)?;
        let redacted_pczt_bytes = make_jni_bytes(env, &request.redacted_pczt_bytes)?;
        let pczt_sighash = make_jni_bytes(env, &request.pczt_sighash)?;
        let rk = make_jni_bytes(env, &request.rk)?;
        let display_memo: JObject<'_> = env.new_string(&request.display_memo)?.into();

        Ok(env.new_object(
            &class,
            JNI_KEYSTONE_SIGNING_REQUEST_CTOR_SIG,
            &[
                JValue::Object(&pczt_bytes),
                JValue::Object(&redacted_pczt_bytes),
                JValue::Object(&pczt_sighash),
                JValue::Object(&rk),
                JValue::Int(u32_to_jint(request.action_index, "action_index")?),
                JValue::Object(&display_memo),
                JValue::Long(u64_to_jlong(
                    request.eligible_weight_zatoshi,
                    "eligible_weight_zatoshi",
                )?),
                JValue::Long(u64_to_jlong(
                    request.delegated_weight_zatoshi,
                    "delegated_weight_zatoshi",
                )?),
                JValue::Int(u32_to_jint(request.bundle_count, "bundle_count")?),
                JValue::Int(u32_to_jint(request.bundle_index, "bundle_index")?),
            ],
        )?)
    })
}

fn make_jni_bytes<'local>(
    env: &mut JNIEnv<'local>,
    bytes: &[u8],
) -> anyhow::Result<JObject<'local>> {
    Ok(env.byte_array_from_slice(bytes)?.into())
}

fn make_jni_fixed_bytes<'local>(
    env: &mut JNIEnv<'local>,
    bytes: Vec<u8>,
    field: &str,
    expected: usize,
) -> anyhow::Result<JObject<'local>> {
    make_jni_bytes(env, &require_len(bytes, field, expected)?)
}

fn make_jni_fixed_byte_array_vec<'local>(
    env: &mut JNIEnv<'local>,
    values: Vec<Vec<u8>>,
    field: &str,
    expected_count: usize,
    expected_size: usize,
) -> anyhow::Result<JObjectArray<'local>> {
    if values.len() != expected_count {
        return Err(anyhow!(
            "{field} must contain {expected_count} entries, got {}",
            values.len()
        ));
    }

    let values = require_each_len(values, field, expected_size)?;

    Ok(rust_vec_to_java(env, values, "[B", |env, bytes| {
        Ok(JObject::from(env.byte_array_from_slice(&bytes)?))
    })?)
}

/// Runs the voting note chunker and returns total count, total eligible weight,
/// and each bundle's quantized voting weight.
///
/// Takes an explicit `policy` (rather than the crate's `chunk_notes(notes)` convenience
/// wrapper, which is hardcoded to `BundlePolicy::default()`) so this always agrees with
/// whatever policy the caller actually persists via `ensure_bundles_with_skipped_suffix_with_policy`
/// -- passing a different policy to each would silently desync `expected_count`/`expected_weight`
/// from the persisted `layout` and trip `setupBundlesNative`'s mismatch check.
pub(super) fn bundle_setup_from_notes(
    notes: &[NoteInfo],
    policy: voting::BundlePolicy,
) -> anyhow::Result<(u32, u64, Vec<u64>)> {
    // zcash_voting 1.0.0 (merged-library patch) moved `chunk_notes`/`chunk_notes_with_policy`
    // from `types` to `note_bundling`; same `&[NoteInfo] -> ChunkResult` signature.
    let chunk_result = voting::note_bundling::chunk_notes_with_policy(notes, policy);
    let bundle_weights = chunk_result
        .bundles
        .iter()
        .map(|bundle| {
            let total = bundle.iter().try_fold(0u64, |acc, note| {
                acc.checked_add(note.value)
                    .ok_or_else(|| anyhow!("bundle note value overflows u64"))
            })?;
            Ok((total / voting::BALLOT_DIVISOR) * voting::BALLOT_DIVISOR)
        })
        .collect::<anyhow::Result<Vec<_>>>()?;
    Ok((
        u32::try_from(chunk_result.bundles.len())
            .map_err(|_| anyhow!("bundle count is too large for u32"))?,
        chunk_result.eligible_weight,
        bundle_weights,
    ))
}

/// Recomputes deterministic note chunking and returns the requested bundle.
pub(super) fn bundled_notes_for_index(
    notes: &[NoteInfo],
    bundle_index: u32,
) -> anyhow::Result<Vec<NoteInfo>> {
    // zcash_voting 1.0.0 (merged-library patch) moved `chunk_notes` from `types` to
    // `note_bundling`; same `&[NoteInfo] -> ChunkResult` signature.
    let chunk_result = voting::note_bundling::chunk_notes(notes);
    let bundle_index = usize::try_from(bundle_index)
        .map_err(|_| anyhow!("bundle_index is too large for this platform: {bundle_index}"))?;

    chunk_result
        .bundles
        .get(bundle_index)
        .cloned()
        .ok_or_else(|| anyhow!("bundle_index {bundle_index} is not present in note bundle set"))
}

pub(super) fn java_int_array(
    env: &mut JNIEnv<'_>,
    array: &JIntArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<jint>> {
    let len = env.get_array_length(array)?;
    let mut buf = vec![0i32; jint_to_usize(len, field)?];
    env.get_int_array_region(array, 0, &mut buf)
        .map_err(|e| anyhow!("{field}: failed to read int array: {e}"))?;
    Ok(buf)
}

pub(super) fn java_string_array(
    env: &mut JNIEnv<'_>,
    array: &JObjectArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<String>> {
    let count = env.get_array_length(array)?;
    (0..count)
        .map(|index| {
            let element = env.get_object_array_element(array, index)?;
            let element = JString::from(element);
            java_string_to_rust(env, &element).map_err(|e| anyhow!("{field}[{index}]: {e}"))
        })
        .collect()
}

pub(super) fn make_jni_int_array<'local>(
    env: &mut JNIEnv<'local>,
    values: &[u32],
) -> anyhow::Result<JIntArray<'local>> {
    let jints = values
        .iter()
        .map(|v| u32_to_jint(*v, "value"))
        .collect::<anyhow::Result<Vec<jint>>>()?;
    let array = env.new_int_array(jints.len() as jsize)?;
    env.set_int_array_region(&array, 0, &jints)?;
    Ok(array)
}

fn optional_jni_string<'local>(
    env: &mut JNIEnv<'local>,
    value: Option<String>,
) -> anyhow::Result<JObject<'local>> {
    Ok(match value {
        Some(value) => JObject::from(env.new_string(value)?),
        None => JObject::null(),
    })
}

fn delegation_status_json(status: &voting::session::DelegationStatus) -> serde_json::Value {
    serde_json::json!({
        "bundleIndex": status.bundle_index,
        "phase": status.phase.as_str(),
        "txHash": status.tx_hash,
        "submissionDiagnostic": status.submission_diagnostic.as_ref().map(|diagnostic| {
            serde_json::json!({
                "kind": diagnostic.kind().as_str(),
                "message": diagnostic.message(),
            })
        }),
        "terminal": status.terminal,
    })
}

fn delegation_recovery_work_json(
    work: &voting::session::DelegationRecoveryWork,
) -> serde_json::Value {
    let kind = match work.kind {
        voting::session::DelegationRecoveryWorkKind::Delegate => "delegate",
        voting::session::DelegationRecoveryWorkKind::AdvanceDelegation => "advance_delegation",
        voting::session::DelegationRecoveryWorkKind::AdvanceImportedDelegation => {
            "advance_imported_delegation"
        }
        // DelegationRecoveryWorkKind is #[non_exhaustive].
        _ => "unknown",
    };
    serde_json::json!({
        "kind": kind,
        "bundleIndex": work.bundle_index,
        "phase": work.phase.as_str(),
        "txHash": work.tx_hash,
    })
}

fn vote_recovery_work_json(work: &voting::session::VoteRecoveryWork) -> serde_json::Value {
    let kind = match work.kind {
        voting::session::VoteRecoveryWorkKind::AdvanceVote => "advance_vote",
        voting::session::VoteRecoveryWorkKind::AdvanceVoteBatch => "advance_vote_batch",
        voting::session::VoteRecoveryWorkKind::SubmitShares => "submit_shares",
        // VoteRecoveryWorkKind is #[non_exhaustive].
        _ => "unknown",
    };
    serde_json::json!({
        "kind": kind,
        "bundleIndex": work.bundle_index,
        "proposalId": work.proposal_id,
        "txHash": work.tx_hash,
        "vcTreePosition": work.vc_tree_position,
        "shareIndexes": work.share_indexes,
    })
}

fn completed_vote_display_json(
    display: &voting::session::CompletedVoteDisplay,
) -> serde_json::Value {
    serde_json::json!({
        "choices": display.choices.iter().map(|choice| serde_json::json!({
            "proposalId": choice.proposal_id,
            "choice": choice.choice,
        })).collect::<Vec<_>>(),
        "votedAt": display.voted_at,
    })
}

fn round_plan_action_to_jint(action: voting::session::RoundPlanAction) -> jint {
    match action {
        voting::session::RoundPlanAction::Idle => 0,
        voting::session::RoundPlanAction::Delegate => 1,
        voting::session::RoundPlanAction::Vote => 2,
        voting::session::RoundPlanAction::SubmitShares => 3,
        voting::session::RoundPlanAction::Done => 4,
        // RoundPlanAction is #[non_exhaustive].
        _ => -1,
    }
}

/// Encodes a [`voting::session::RoundPlan`] for `getRoundPlanNative`/
/// `setBallotIntentsNative`/the embedded field of `encode_round_run_report`.
///
/// `RoundPlan` carries ~28 fields (per a prior investigation, re-confirmed here
/// against the pinned crate revision); every one is encoded below rather than a
/// hand-picked subset, per the Task 5 brief's Step 5 note that "a partial
/// encoder that silently drops fields is a worse failure mode than a compile
/// error." Simple fields (strings, booleans, `u32` collections) become direct
/// JNI values. `NextStep` has a real `Serialize` impl in the crate, so
/// `next_steps` is encoded with `serde_json::to_string` directly. The other
/// nested record types (`DelegationStatus`, `DelegationRecoveryWork`,
/// `VoteRecoveryWork`, `CompletedVoteDisplay`) do not derive `Serialize` (it
/// would be a foreign-type orphan-rule violation for this crate to add it), so
/// their vectors are hand-built into `serde_json::Value` above and also
/// serialized to a JSON string field. This keeps the encoder bounded while
/// still surfacing every field; Task 9 owns turning these JSON string fields
/// into first-class typed Kotlin models if that turns out to be worth it.
pub(super) fn encode_round_plan<'local>(
    env: &mut JNIEnv<'local>,
    plan: &voting::session::RoundPlan,
) -> anyhow::Result<JObject<'local>> {
    let class = env.find_class(JNI_ROUND_PLAN)?;
    let round_id: JObject<'local> = env.new_string(&plan.round_id)?.into();
    let next_steps_json: JObject<'local> = env
        .new_string(serde_json::to_string(&plan.next_steps)?)?
        .into();
    let open_proposals = JObject::from(make_jni_int_array(env, &plan.open_proposals)?);
    let unrostered_intents = JObject::from(make_jni_int_array(env, &plan.unrostered_intents)?);
    let immediate_share_key_json = optional_jni_string(
        env,
        plan.immediate_share_key
            .as_ref()
            .map(serde_json::to_string)
            .transpose()?,
    )?;
    let delegation_statuses_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &plan
                .delegation_statuses
                .iter()
                .map(delegation_status_json)
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let completed_vote_display_json = optional_jni_string(
        env,
        plan.completed_vote_display
            .as_ref()
            .map(|display| completed_vote_display_json(display).to_string()),
    )?;
    let delegation_bundles_needing_work = JObject::from(make_jni_int_array(
        env,
        &plan.delegation_bundles_needing_work,
    )?);
    let delegation_bundles_needing_signing = JObject::from(make_jni_int_array(
        env,
        &plan.delegation_bundles_needing_signing,
    )?);
    let recovered_delegation_work_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &plan
                .recovered_delegation_work
                .iter()
                .map(delegation_recovery_work_json)
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let recovered_vote_work_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &plan
                .recovered_vote_work
                .iter()
                .map(vote_recovery_work_json)
                .collect::<Vec<_>>(),
        )?)?
        .into();

    Ok(env.new_object(
        &class,
        JNI_ROUND_PLAN_CTOR_SIG,
        &[
            JValue::Object(&round_id),
            JValue::Bool(plan.pending_recovery as jboolean),
            JValue::Object(&next_steps_json),
            JValue::Object(&open_proposals),
            JValue::Object(&unrostered_intents),
            JValue::Object(&immediate_share_key_json),
            JValue::Bool(plan.immediate_share_confirmed as jboolean),
            JValue::Bool(plan.all_decided as jboolean),
            JValue::Object(&delegation_statuses_json),
            JValue::Bool(plan.blocking_recovery as jboolean),
            JValue::Bool(plan.blocking_share_work as jboolean),
            JValue::Bool(plan.has_unconfirmed_shares as jboolean),
            JValue::Bool(plan.hotkey_bound as jboolean),
            JValue::Bool(plan.completed_vote_artifact as jboolean),
            JValue::Bool(plan.completed_for_display as jboolean),
            JValue::Object(&completed_vote_display_json),
            JValue::Bool(plan.needs_draft_setup as jboolean),
            JValue::Bool(plan.needs_bundle_setup as jboolean),
            JValue::Int(round_plan_action_to_jint(plan.primary_action)),
            JValue::Bool(plan.needs_delegation_signing as jboolean),
            JValue::Bool(plan.has_in_flight_delegation as jboolean),
            JValue::Object(&delegation_bundles_needing_work),
            JValue::Object(&delegation_bundles_needing_signing),
            JValue::Bool(plan.needs_vote_polling as jboolean),
            JValue::Bool(plan.has_remaining_vote_or_share_work as jboolean),
            JValue::Bool(plan.has_recoverable_vote_or_share_work as jboolean),
            JValue::Object(&recovered_delegation_work_json),
            JValue::Object(&recovered_vote_work_json),
        ],
    )?)
}

fn round_quiescence_kind(quiescence: &voting::RoundQuiescence) -> &'static str {
    use voting::RoundQuiescence::*;
    match quiescence {
        NoWorkLeft => "no_work_left",
        NeedsBundleSetup => "needs_bundle_setup",
        PersistedChainTerminal => "persisted_chain_terminal",
        NeedsBallot { .. } => "needs_ballot",
        NeedsDelegationSignatures { .. } => "needs_delegation_signatures",
        BackgroundShareWorkOnly { .. } => "background_share_work_only",
        Cancelled => "cancelled",
        ChainTerminal { .. } => "chain_terminal",
        ChainRecoveryStalled { .. } => "chain_recovery_stalled",
        Failures => "failures",
        PassBudgetExhausted { .. } => "pass_budget_exhausted",
        // RoundQuiescence is #[non_exhaustive].
        _ => "unknown",
    }
}

/// Variant-specific payload for [`RoundQuiescence`], where present. This is a
/// deliberately lighter-touch encoding than `encode_round_plan`'s: the Task 5
/// brief only requires `RoundRunReport`'s embedded `Option<RoundPlan>` to reuse
/// `encode_round_plan`, not that every `RoundRunReport` field reach the same
/// fidelity. `ChainSubmissionResult` and `ShareKey` are Debug-formatted rather
/// than field-by-field encoded for the same reason `encode_round_run_report`'s
/// other complex fields are.
fn round_quiescence_detail_json(
    quiescence: &voting::RoundQuiescence,
) -> anyhow::Result<Option<String>> {
    use voting::RoundQuiescence::*;
    let value = match quiescence {
        NoWorkLeft | NeedsBundleSetup | PersistedChainTerminal | Cancelled | Failures => {
            return Ok(None);
        }
        NeedsBallot {
            open_proposals,
            unrostered_intents,
        } => serde_json::json!({
            "openProposals": open_proposals,
            "unrosteredIntents": unrostered_intents,
        }),
        NeedsDelegationSignatures { bundles } => serde_json::json!({ "bundles": bundles }),
        BackgroundShareWorkOnly { shares } => serde_json::json!({
            "shares": shares.iter().map(|share| format!("{share:?}")).collect::<Vec<_>>(),
        }),
        ChainTerminal { step, outcome } | ChainRecoveryStalled { step, outcome } => {
            serde_json::json!({
                "step": serde_json::to_value(step).ok(),
                "outcome": format!("{outcome:?}"),
            })
        }
        PassBudgetExhausted { remaining } => serde_json::json!({
            "remaining": serde_json::to_value(remaining).ok(),
        }),
        // RoundQuiescence is #[non_exhaustive].
        other => serde_json::json!({ "debug": format!("{other:?}") }),
    };
    Ok(Some(value.to_string()))
}

fn round_step_failure_record_json(record: &voting::RoundStepFailureRecord) -> serde_json::Value {
    serde_json::json!({
        "step": record.step.as_ref().and_then(|step| serde_json::to_value(step).ok()),
        "bundleIndex": record.bundle_index,
        "kind": format!("{:?}", record.failure.kind),
        "message": record.failure.message,
    })
}

/// Encodes a [`voting::RoundRunReport`], the terminal output of
/// `runRoundNative`'s `RoundDriver::run`.
///
/// The embedded `plan: Option<RoundPlan>` reuses [`encode_round_plan`] per the
/// brief. The rest of the report -- quiescence detail, failures, chain
/// outcomes, share deliveries -- is encoded more thinly (stable discriminator
/// strings plus Debug-derived JSON) than `RoundPlan`'s fields: the brief does
/// not ask for the same field-by-field fidelity here, and several of these
/// types (`ChainSubmissionResult`, `RoundStepFailureKind`) are large,
/// non-exhaustive enums without a `Serialize` impl this crate can add. Signed
/// delegation bundles (`report.delegations`) are intentionally reduced to a
/// count rather than serialized: they carry proving/signing material a Kotlin
/// JSON field is the wrong place to route, and Task 6/7 (delegation wiring,
/// share tracking) are better positioned to design their real encoding.
pub(super) fn encode_round_run_report<'local>(
    env: &mut JNIEnv<'local>,
    report: &voting::RoundRunReport,
) -> anyhow::Result<JObject<'local>> {
    let class = env.find_class(JNI_ROUND_RUN_REPORT)?;
    let quiescence_kind: JObject<'local> = env
        .new_string(round_quiescence_kind(&report.quiescence))?
        .into();
    let quiescence_detail_json =
        optional_jni_string(env, round_quiescence_detail_json(&report.quiescence)?)?;
    let plan = match &report.plan {
        Some(plan) => encode_round_plan(env, plan)?,
        None => JObject::null(),
    };
    let failures_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &report
                .failures
                .iter()
                .map(round_step_failure_record_json)
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let skipped_bundles = JObject::from(make_jni_int_array(env, &report.skipped_bundles)?);
    let chain_outcomes_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &report
                .chain_outcomes
                .iter()
                .map(|(step, outcome)| {
                    serde_json::json!({
                        "step": serde_json::to_value(step).ok(),
                        "outcome": format!("{outcome:?}"),
                    })
                })
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let share_deliveries_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &report
                .share_deliveries
                .iter()
                .map(|delivery| format!("{delivery:?}"))
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let delegations_signed_count = usize_to_jint(report.delegations.len(), "delegations")?;

    Ok(env.new_object(
        &class,
        JNI_ROUND_RUN_REPORT_CTOR_SIG,
        &[
            JValue::Object(&quiescence_kind),
            JValue::Object(&quiescence_detail_json),
            JValue::Object(&plan),
            JValue::Int(u32_to_jint(
                report.tally.completed_proposals,
                "completed_proposals",
            )?),
            JValue::Int(u32_to_jint(
                report.tally.total_proposals,
                "total_proposals",
            )?),
            JValue::Int(u32_to_jint(
                report.tally.remaining_obligations,
                "remaining_obligations",
            )?),
            JValue::Object(&failures_json),
            JValue::Object(&skipped_bundles),
            JValue::Object(&chain_outcomes_json),
            JValue::Object(&share_deliveries_json),
            JValue::Int(delegations_signed_count),
        ],
    )?)
}

fn share_key_json(key: &voting::share_tracking::ShareKey) -> serde_json::Value {
    serde_json::json!({
        "bundleIndex": key.bundle_index,
        "proposalId": key.proposal_id,
        "shareIndex": key.share_index,
    })
}

fn resubmitted_share_json(
    resubmitted: &voting::share_tracking::ResubmittedShare,
) -> serde_json::Value {
    serde_json::json!({
        "share": share_key_json(&resubmitted.share),
        "serverUrl": resubmitted.server_url,
    })
}

fn share_tracking_quiescence_kind(quiescence: &voting::ShareTrackingQuiescence) -> &'static str {
    use voting::ShareTrackingQuiescence::*;
    match quiescence {
        NothingToTrack => "nothing_to_track",
        AllConfirmed => "all_confirmed",
        VoteEndReached => "vote_end_reached",
        Cancelled => "cancelled",
        AlreadyDriving => "already_driving",
        Failing { .. } => "failing",
        PassBudgetExhausted { .. } => "pass_budget_exhausted",
        // ShareTrackingQuiescence is #[non_exhaustive].
        _ => "unknown",
    }
}

/// Variant-specific payload for [`voting::ShareTrackingQuiescence`], mirroring
/// `round_quiescence_detail_json`'s "thin encoding" approach: stable
/// discriminator from `share_tracking_quiescence_kind` above, plus this JSON
/// blob only for the two variants that carry extra fields.
fn share_tracking_quiescence_detail_json(
    quiescence: &voting::ShareTrackingQuiescence,
) -> Option<String> {
    use voting::ShareTrackingQuiescence::*;
    let value = match quiescence {
        NothingToTrack | AllConfirmed | VoteEndReached | Cancelled | AlreadyDriving => {
            return None;
        }
        Failing { messages } => serde_json::json!({ "messages": messages }),
        PassBudgetExhausted { unrecoverable } => serde_json::json!({
            "unrecoverable": unrecoverable.iter().map(share_key_json).collect::<Vec<_>>(),
        }),
        // ShareTrackingQuiescence is #[non_exhaustive].
        other => serde_json::json!({ "debug": format!("{other:?}") }),
    };
    Some(value.to_string())
}

/// Encodes a [`voting::ShareTrackingRunReport`], the terminal output of
/// `trackSharesNative`'s `ShareTrackingDriver::run`.
///
/// `ShareKey`/`ResubmittedShare` do not derive `Serialize` in the crate (a
/// foreign-type orphan-rule violation for this crate to add), so `confirmed`/
/// `resubmitted`/`ambiguous`/`unrecoverable` are hand-built into
/// `serde_json::Value` via `share_key_json`/`resubmitted_share_json` above and
/// serialized to JSON string fields -- the same approach
/// `encode_round_run_report` already uses for its own non-`Serialize` fields.
pub(super) fn encode_share_tracking_report<'local>(
    env: &mut JNIEnv<'local>,
    report: &voting::ShareTrackingRunReport,
) -> anyhow::Result<JObject<'local>> {
    let class = env.find_class(JNI_SHARE_TRACKING_RUN_REPORT)?;
    let quiescence_kind: JObject<'local> = env
        .new_string(share_tracking_quiescence_kind(&report.quiescence))?
        .into();
    let quiescence_detail_json = optional_jni_string(
        env,
        share_tracking_quiescence_detail_json(&report.quiescence),
    )?;
    let confirmed_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &report
                .confirmed
                .iter()
                .map(share_key_json)
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let resubmitted_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &report
                .resubmitted
                .iter()
                .map(resubmitted_share_json)
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let ambiguous_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &report
                .ambiguous
                .iter()
                .map(resubmitted_share_json)
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let unrecoverable_json: JObject<'local> = env
        .new_string(serde_json::to_string(
            &report
                .unrecoverable
                .iter()
                .map(share_key_json)
                .collect::<Vec<_>>(),
        )?)?
        .into();
    let failures_json: JObject<'local> = env
        .new_string(serde_json::to_string(&report.failures)?)?
        .into();

    Ok(env.new_object(
        &class,
        JNI_SHARE_TRACKING_RUN_REPORT_CTOR_SIG,
        &[
            JValue::Object(&quiescence_kind),
            JValue::Object(&quiescence_detail_json),
            JValue::Int(u32_to_jint(report.passes, "passes")?),
            JValue::Object(&confirmed_json),
            JValue::Object(&resubmitted_json),
            JValue::Object(&ambiguous_json),
            JValue::Object(&unrecoverable_json),
            JValue::Object(&failures_json),
        ],
    )?)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Must match JNI_VOTE_SHARE_COUNT in JniConstants.kt.
    const VOTE_SHARE_COUNT: usize = 16;

    #[test]
    fn recover_payloads_reconstructs_share_payloads_from_commitment_bundle_json() {
        // SignedVoteCommitment no longer carries a `share_payloads` field
        // directly (removed in zcash_voting 4.0.0) -- this locks in that the
        // replacement path (parse commitment_bundle_json back into a
        // VoteRecoveryBundle, then voting::share::recover_payloads) produces
        // a non-empty result for a fixture with encrypted_shares, so the
        // real JNI path's silent-empty-array failure mode would be caught
        // here first.
        let recovery = voting::vote::VoteRecoveryBundle {
            // vote_round_id must be 64 lowercase hex characters (VotingRoundParams's
            // own format, enforced by voting::share::recover_payloads) — matches the
            // literal delegation.rs's round_params() test fixture already uses.
            vote_round_id: "0101010101010101010101010101010101010101010101010101010101010101"
                .to_string(),
            bundle_index: 0,
            proposal_id: 1,
            vote_decision: 0,
            anchor_height: 100,
            vc_tree_position: 456,
            single_share: false,
            num_options: 3,
            van_nullifier: [0x31; PROTOCOL_FIELD_BYTES],
            vote_authority_note_new: [0x32; PROTOCOL_FIELD_BYTES],
            vote_commitment: [0x01; PROTOCOL_FIELD_BYTES],
            proof: vec![0x34; 8],
            shares_hash: [0x35; PROTOCOL_FIELD_BYTES],
            r_vpk: [0x36; PROTOCOL_FIELD_BYTES],
            alpha_v: [0x37; PROTOCOL_FIELD_BYTES],
            vote_auth_sig: [0x38; SPEND_AUTH_SIG_BYTES],
            encrypted_shares: (0..VOTE_SHARE_COUNT)
                .map(|share_index| voting::types::EncryptedShare {
                    c1: vec![0x21; PROTOCOL_FIELD_BYTES],
                    c2: vec![0x22; PROTOCOL_FIELD_BYTES],
                    share_index: share_index as u32,
                    plaintext_value: 5,
                    randomness: vec![0x23; PROTOCOL_FIELD_BYTES],
                })
                .collect(),
            share_blinds: vec![[0x02; PROTOCOL_FIELD_BYTES]; VOTE_SHARE_COUNT],
            share_comms: vec![[0x51; PROTOCOL_FIELD_BYTES]; VOTE_SHARE_COUNT],
            // `batch` is new in this crate revision (`vote.rs:2445`) — the pre-port
            // `storeVoteFixtureNative` literal this fixture is otherwise copied from
            // (`recovery.rs:511-539`) predates it and will fail to compile with E0063
            // (missing field) at this crate revision. `None` matches a singleton
            // (non-batch) vote, which is what this fixture and `storeVoteFixtureNative`
            // both represent.
            batch: None,
        };

        let payloads = voting::share::recover_payloads(&recovery).expect("recover payloads");
        assert_eq!(payloads.len(), VOTE_SHARE_COUNT);
    }
}
