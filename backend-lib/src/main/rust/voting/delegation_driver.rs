//! Software delegation signing and the [`voting::DelegationStepInputs`]
//! builder `round_session.rs`'s `runRoundNative` plugs into
//! `RoundHostContext::delegation`.
//!
//! Supersedes most of `delegation.rs`'s prior responsibility. The old flow
//! had the app hand-select notes, build a governance PCZT itself
//! (`buildGovernancePcztNative`/`build_governance_pczt_for_bundle`), and drive
//! PIR/proving/signing through a sequence of separate JNI calls
//! (`precomputeDelegationPirNative`, `buildAndProveDelegationNative`,
//! `getDelegationSubmissionNative`, ...). `zcash_voting::DelegationPipeline`
//! now owns all of that internally: it reads the wallet DB itself through
//! [`voting::SqliteWalletDbOpener`] (one open per stage, since wallet DB
//! handles are not `Send`) rather than the app opening it once and handing
//! over hand-picked notes.
//!
//! This module builds two things from JNI-decoded inputs:
//! 1. [`SeedSpendAuthSigner`] -- the software `SpendAuthSigner` the pipeline
//!    calls back into for a software wallet's signature. Ports
//!    `delegation.rs`'s former `sign_delegation_sighash_with_seed` body
//!    1:1 -- only the calling convention changed, from a direct JNI
//!    parameter list to a trait method the pipeline invokes.
//! 2. [`build_delegation_step_inputs`] -- constructs a
//!    [`voting::DelegationPipeline`] scoped to one round/account/hotkey, and
//!    wraps it (plus a [`voting::PirFleet`] and the caller's
//!    [`voting::DelegationSigner`]) into a [`voting::DelegationStepInputs`]
//!    for `RoundHostContext::delegation`.

use std::sync::Arc;

use ff::PrimeField;
use pasta_curves::pallas;
use prost::Message as _;
use zcash_client_backend::proto::service::TreeState;

use super::db::{VotingDbHandle, db_from_handle};
use super::helpers::*;
use super::*;

use voting::config::PirLayout;
use voting::delegate::{DelegationLwdInputs, DelegationSigningRequest};
use voting::types::{VotingError, VotingHotkey, VotingRoundParams};
use voting::{
    DelegationDriver, DelegationPipeline, DelegationSigner, DelegationStepInputs,
    KeystoneSignatureSource, PirFleet, SpendAuthSigner, SqliteWalletDbOpener,
};

/// Software wallet [`SpendAuthSigner`]: derives the account SpendAuth key
/// from `seed` and signs the pipeline's [`DelegationSigningRequest`].
///
/// Ports `delegation.rs`'s former `sign_delegation_sighash_with_seed` body
/// 1:1 -- the cryptographic recipe (ZIP-32 fingerprint check, USK
/// derivation, alpha randomization, signing) is unchanged; only the entry
/// point moved from a direct JNI parameter to a trait method the pipeline
/// calls internally. The seed never crosses into `zcash_voting` itself --
/// only the resulting 64-byte signature does.
pub(super) struct SeedSpendAuthSigner {
    seed: SecretVec<u8>,
}

impl SeedSpendAuthSigner {
    pub(super) fn new(seed: SecretVec<u8>) -> Self {
        Self { seed }
    }
}

impl SpendAuthSigner for SeedSpendAuthSigner {
    fn sign(&self, request: DelegationSigningRequest) -> Result<[u8; 64], VotingError> {
        let seed = self.seed.expose_secret();

        let seed_fingerprint =
            zip32::fingerprint::SeedFingerprint::from_seed(seed).ok_or_else(|| {
                VotingError::InvalidInput {
                    message: "seed must be 32 to 252 bytes".to_string(),
                }
            })?;
        if seed_fingerprint.to_bytes() != request.seed_fingerprint {
            return Err(VotingError::InvalidInput {
                message: "seed does not match the delegation signing request seed fingerprint"
                    .to_string(),
            });
        }

        let account = zip32::AccountId::try_from(request.account_index).map_err(|_| {
            VotingError::InvalidInput {
                message: format!("invalid account_index {}", request.account_index),
            }
        })?;
        let usk = UnifiedSpendingKey::from_seed(&request.network, seed, account).map_err(|e| {
            VotingError::InvalidInput {
                message: format!("failed to derive USK from seed: {e}"),
            }
        })?;
        let ask = orchard::keys::SpendAuthorizingKey::from(usk.orchard());
        let alpha = Option::<pallas::Scalar>::from(pallas::Scalar::from_repr(request.alpha))
            .ok_or_else(|| VotingError::InvalidInput {
                message: "delegation signing request alpha is not a canonical scalar".to_string(),
            })?;
        let rsk = ask.randomize(&alpha);
        let sig: [u8; SPEND_AUTH_SIG_BYTES] =
            (&rsk.sign(rand::rngs::OsRng, &request.sighash)).into();
        Ok(sig)
    }
}

/// Builds a [`DelegationPipeline`] scoped to one round/account/hotkey.
///
/// Shared by [`build_delegation_step_inputs`] (the brief-exact builder) and
/// [`delegation_step_inputs_from_jni`] (the `runRoundNative` glue, which also
/// needs the concrete pipeline to cache on the session for
/// `getKeystoneSigningRequestsNative` -- see that function's doc comment for
/// why).
fn build_pipeline(
    db: &VotingDbHandle,
    wallet_db_path: &str,
    account_uuid: &str,
    anchor_tree_state: &[u8],
    round_params: VotingRoundParams,
    hotkey: Option<VotingHotkey>,
) -> anyhow::Result<Arc<DelegationPipeline<SqliteWalletDbOpener>>> {
    let tree_state = TreeState::decode(anchor_tree_state)
        .map_err(|e| anyhow!("decode anchor TreeState: {}", e))?;
    // Empty round_name resolves to round_params.vote_round_id inside
    // from_anchor_tree_state (crate::round::delegation_round_name); this
    // builder's signature has no separate round_name input (per the brief's
    // exact "Produces" signature), and the fallback matches what the old
    // buildAndProveDelegationNative flow used when the app had no nicer
    // human-readable round name on hand.
    let lwd =
        DelegationLwdInputs::from_anchor_tree_state(db.network, round_params, "", &tree_state)
            .map_err(|e| anyhow!("DelegationLwdInputs::from_anchor_tree_state: {}", e))?;
    let wallet = SqliteWalletDbOpener::new(wallet_db_path, db.network);
    let voting_db = Arc::new(
        db.scoped(&db.wallet_id())
            .map_err(|e| anyhow!("VotingDb::scoped: {}", e))?,
    );

    let pipeline = DelegationPipeline::new(
        voting_db,
        wallet,
        lwd,
        account_uuid,
        hotkey,
        // BundlePolicy::default() is what the codebase's only production
        // bundle-setup call site actually uses today (setupBundlesNative in
        // notes.rs), not recoverable_bundle_policy_v1 -- see this task's
        // report for the NOTE-2 resolution.
        voting::BundlePolicy::default(),
        None,
    )
    .map_err(|e| anyhow!("DelegationPipeline::new: {}", e))?;
    Ok(Arc::new(pipeline))
}

/// Builds a [`DelegationStepInputs`] for `RoundHostContext::delegation`,
/// scoped to one round/account/hotkey/signer/PIR fleet.
///
/// # Errors
///
/// Returns an error when the anchor tree state bytes do not decode, the
/// round params are invalid, the hotkey's network disagrees with the anchor
/// tree state's network, or the PIR fleet's endpoints/layout are invalid.
#[allow(clippy::too_many_arguments)]
pub(super) fn build_delegation_step_inputs(
    db: &VotingDbHandle,
    wallet_db_path: &str,
    account_uuid: &str,
    anchor_tree_state: &[u8],
    round_params: VotingRoundParams,
    hotkey: Option<VotingHotkey>,
    pir_endpoints: &[String],
    pir_layout: PirLayout,
    transport: Arc<dyn voting::Transport>,
    signer: DelegationSigner,
) -> anyhow::Result<DelegationStepInputs> {
    let (step_inputs, _pipeline) = build_delegation_step_inputs_and_pipeline(
        db,
        wallet_db_path,
        account_uuid,
        anchor_tree_state,
        round_params,
        hotkey,
        pir_endpoints,
        pir_layout,
        transport,
        signer,
    )?;
    Ok(step_inputs)
}

/// Like [`build_delegation_step_inputs`], but also returns the concrete
/// [`DelegationPipeline`] it built.
///
/// `runRoundNative` needs this variant (not the brief-exact one above)
/// because `getKeystoneSigningRequestsNative` needs the *same* pipeline
/// instance to call `keystone_request` on later in the same session, rather
/// than constructing a second, redundant one from scratch (which would
/// re-run wallet-DB reads and round validation for no reason and could, in
/// principle, observe different wallet state than the one delegation actually
/// ran against). `round_session.rs` caches the returned pipeline on the
/// session; `delegation.rs`'s `getKeystoneSigningRequestsNative` reads it
/// back out.
#[allow(clippy::too_many_arguments)]
pub(super) fn build_delegation_step_inputs_and_pipeline(
    db: &VotingDbHandle,
    wallet_db_path: &str,
    account_uuid: &str,
    anchor_tree_state: &[u8],
    round_params: VotingRoundParams,
    hotkey: Option<VotingHotkey>,
    pir_endpoints: &[String],
    pir_layout: PirLayout,
    transport: Arc<dyn voting::Transport>,
    signer: DelegationSigner,
) -> anyhow::Result<(
    DelegationStepInputs,
    Arc<DelegationPipeline<SqliteWalletDbOpener>>,
)> {
    let pipeline = build_pipeline(
        db,
        wallet_db_path,
        account_uuid,
        anchor_tree_state,
        round_params,
        hotkey,
    )?;
    let pir = PirFleet::new(pir_endpoints, pir_layout, transport)
        .map_err(|e| anyhow!("PirFleet::new: {}", e))?;
    let driver = Arc::clone(&pipeline) as Arc<dyn DelegationDriver>;
    let step_inputs = DelegationStepInputs {
        driver,
        signer,
        pir: Arc::new(pir),
    };
    Ok((step_inputs, pipeline))
}

/// One decoded `delegation_inputs` Java object, per the proposed shape
/// documented on [`decode_delegation_inputs`].
struct DecodedDelegationInputs {
    db: Arc<VotingDbHandle>,
    wallet_db_path: String,
    account_uuid: String,
    anchor_tree_state_bytes: Vec<u8>,
    hotkey_secret: Option<Vec<u8>>,
    pir_endpoints: Vec<String>,
    pir_layout: PirLayout,
    signer_is_keystone: bool,
    software_seed: Option<Vec<u8>>,
    keystone_sig: Option<Vec<u8>>,
    keystone_sighash: Option<Vec<u8>>,
}

/// Decodes `runRoundNative`'s `delegation_inputs: JObject` parameter.
///
/// No Kotlin-side class exists yet for this shape (same situation Task 5
/// documented for `JniRoundPlan`/`JniRoundRunReport`: this task is Rust/JNI-
/// export only). Proposed fields, one Kotlin property per JNI field below:
///
/// - `dbHandle: Long` -- the `openVotingDbNative` handle whose wallet scope
///   and network this delegation work runs under (may differ from the
///   session's own handle only in theory; in practice callers pass the same
///   handle `openRoundSessionNative` used).
/// - `walletDbPath: String`, `accountUuid: String` -- fed straight to
///   `SqliteWalletDbOpener`/`DelegationPipeline::new`.
/// - `anchorTreeStateBytes: ByteArray` -- the serialized
///   `TreeState` the app already fetched over gRPC for the old
///   `storeTreeStateNative` flow; reused here, not re-fetched.
/// - `hotkeySecret: ByteArray?` -- nullable; `null` when no hotkey is bound
///   yet (bundle setup/eligibility stages tolerate that per
///   `DelegationPipeline::new`'s own doc comment).
/// - `pirEndpoints: Array<String>`, `pirDepth/pirTier0Layers/pirTier1Layers/
///   pirPolyLen: Int` -- fed to `PirFleet::new`/`PirLayout`, mirroring
///   `pir_layout_from_jni` in `delegation.rs`.
/// - `keystone: Boolean` -- selects `DelegationSigner::Keystone` (`true`) vs
///   `DelegationSigner::Software` (`false`).
/// - `softwareSeed: ByteArray?` -- required when `keystone == false`, feeds
///   [`SeedSpendAuthSigner`].
/// - `keystoneSig: ByteArray?`, `keystoneSighash: ByteArray?` -- both present
///   selects `KeystoneSignatureSource::Provided`; both absent selects
///   `KeystoneSignatureSource::Stored` (resume from a previously persisted
///   Keystone signature); exactly one present is rejected.
fn decode_delegation_inputs(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
) -> anyhow::Result<DecodedDelegationInputs> {
    let db_handle = env.get_field(obj, "dbHandle", "J")?.j()?;
    let db = db_from_handle(db_handle)?;
    let wallet_db_path = java_string_field(env, obj, "walletDbPath")?;
    let account_uuid = java_string_field(env, obj, "accountUuid")?;
    let anchor_tree_state_bytes = java_byte_array_field(env, obj, "anchorTreeStateBytes")?;
    let hotkey_secret = java_nullable_byte_array_field(env, obj, "hotkeySecret")?;
    let pir_endpoints = java_string_array_field(env, obj, "pirEndpoints")?;
    let pir_layout = PirLayout {
        pir_depth: jint_to_u32(env.get_field(obj, "pirDepth", "I")?.i()?, "pirDepth")?,
        tier0_layers: jint_to_u32(
            env.get_field(obj, "pirTier0Layers", "I")?.i()?,
            "pirTier0Layers",
        )?,
        tier1_layers: jint_to_u32(
            env.get_field(obj, "pirTier1Layers", "I")?.i()?,
            "pirTier1Layers",
        )?,
        poly_len: jint_to_u32(env.get_field(obj, "pirPolyLen", "I")?.i()?, "pirPolyLen")?,
    };
    let signer_is_keystone = env.get_field(obj, "keystone", "Z")?.z()?;
    let software_seed = java_nullable_byte_array_field(env, obj, "softwareSeed")?;
    let keystone_sig = java_nullable_byte_array_field(env, obj, "keystoneSig")?;
    let keystone_sighash = java_nullable_byte_array_field(env, obj, "keystoneSighash")?;

    Ok(DecodedDelegationInputs {
        db,
        wallet_db_path,
        account_uuid,
        anchor_tree_state_bytes,
        hotkey_secret,
        pir_endpoints,
        pir_layout,
        signer_is_keystone,
        software_seed,
        keystone_sig,
        keystone_sighash,
    })
}

fn signer_from_decoded(decoded: &DecodedDelegationInputs) -> anyhow::Result<DelegationSigner> {
    if decoded.signer_is_keystone {
        match (&decoded.keystone_sig, &decoded.keystone_sighash) {
            (Some(sig), Some(sighash)) => Ok(DelegationSigner::Keystone(
                KeystoneSignatureSource::Provided {
                    sig: sig.clone(),
                    sighash: sighash.clone(),
                },
            )),
            (None, None) => Ok(DelegationSigner::Keystone(KeystoneSignatureSource::Stored)),
            _ => Err(anyhow!(
                "keystoneSig and keystoneSighash must both be present or both be absent"
            )),
        }
    } else {
        let seed = decoded.software_seed.clone().ok_or_else(|| {
            anyhow!("softwareSeed is required when delegation_inputs.keystone is false")
        })?;
        Ok(DelegationSigner::Software(Arc::new(
            SeedSpendAuthSigner::new(SecretVec::new(seed)),
        )))
    }
}

/// Full JNI-facing build: decodes `delegation_inputs`, loads the round's
/// persisted params for `round_id`, and builds both the
/// [`DelegationStepInputs`] `runRoundNative` needs and the
/// [`DelegationPipeline`] the session caches for
/// `getKeystoneSigningRequestsNative`.
pub(super) fn delegation_step_inputs_from_jni(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    round_id: &str,
    transport: Arc<dyn voting::Transport>,
) -> anyhow::Result<(
    DelegationStepInputs,
    Arc<DelegationPipeline<SqliteWalletDbOpener>>,
)> {
    let decoded = decode_delegation_inputs(env, obj)?;

    let round_params = {
        let conn = decoded.db.conn();
        let wallet_id = decoded.db.wallet_id();
        voting::storage::queries::load_round_params(&conn, round_id, &wallet_id)
            .map_err(|e| anyhow!("load_round_params: {}", e))?
    };

    let hotkey = match &decoded.hotkey_secret {
        Some(secret) => Some(
            VotingHotkey::from_stored_secret(secret, decoded.db.network)
                .map_err(|e| anyhow!("VotingHotkey::from_stored_secret: {}", e))?,
        ),
        None => None,
    };
    let signer = signer_from_decoded(&decoded)?;

    build_delegation_step_inputs_and_pipeline(
        &decoded.db,
        &decoded.wallet_db_path,
        &decoded.account_uuid,
        &decoded.anchor_tree_state_bytes,
        round_params,
        hotkey,
        &decoded.pir_endpoints,
        decoded.pir_layout,
        transport,
        signer,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keystone_signature_source_matches_provided_shape() {
        // Locks in that our current Keystone flow's "64-byte spend-auth
        // signature plus the 32-byte sighash it was computed over" shape
        // still constructs zcash_voting::KeystoneSignatureSource::Provided
        // directly, before this task wires it into DelegationSigner.
        let _source = zcash_voting::KeystoneSignatureSource::Provided {
            sig: vec![0u8; SPEND_AUTH_SIG_BYTES],
            sighash: vec![0u8; PROTOCOL_FIELD_BYTES],
        };
    }

    #[test]
    fn seed_spend_auth_signer_produces_a_verifiable_signature() {
        use orchard::primitives::redpallas::{SpendAuth, VerificationKey};

        let seed = [0x5Au8; 32];
        let account = zip32::AccountId::ZERO;
        let network = voting::types::Network::Regtest;
        let usk = UnifiedSpendingKey::from_seed(&network, &seed, account).expect("USK from seed");
        let seed_fingerprint =
            zip32::fingerprint::SeedFingerprint::from_seed(&seed).expect("seed fingerprint");

        // The zero scalar is canonical and sufficient to exercise the
        // signing/verification path; the randomizer's specific value does
        // not matter for this test.
        let alpha = Option::<pallas::Scalar>::from(pallas::Scalar::from_repr([0u8; 32]))
            .expect("zero is a canonical scalar");

        let request = DelegationSigningRequest {
            account_index: 0,
            network,
            seed_fingerprint: seed_fingerprint.to_bytes(),
            sighash: [0x42u8; 32],
            alpha: alpha.to_repr(),
        };

        let signer = SeedSpendAuthSigner::new(SecretVec::new(seed.to_vec()));
        let sig = signer.sign(request.clone()).expect("signing succeeds");
        assert_ne!(sig, [0u8; 64]);

        let ask = orchard::keys::SpendAuthorizingKey::from(usk.orchard());
        let rsk = ask.randomize(&alpha);
        let rk = VerificationKey::<SpendAuth>::from(&rsk);
        rk.verify(
            &request.sighash,
            &orchard::primitives::redpallas::Signature::<SpendAuth>::from(sig),
        )
        .expect("signature verifies against the randomized key it was produced for");
    }

    #[test]
    fn seed_spend_auth_signer_rejects_mismatched_seed_fingerprint() {
        let request = DelegationSigningRequest {
            account_index: 0,
            network: voting::types::Network::Regtest,
            seed_fingerprint: [0xAAu8; 32],
            sighash: [0x42u8; 32],
            alpha: [0u8; 32],
        };

        let signer = SeedSpendAuthSigner::new(SecretVec::new(vec![0x5Au8; 32]));
        let err = signer.sign(request).unwrap_err();
        assert!(matches!(err, VotingError::InvalidInput { .. }));
    }
}
