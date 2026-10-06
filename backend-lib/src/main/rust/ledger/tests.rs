//! Tests for the Ledger JNI surface's Rust half: the handle registry, the error mapping, and the
//! signing session built from a real wallet database and driven against a software device.
//!
//! The JNI exports themselves are thin wrappers over what is tested here; their Kotlin signatures
//! are exercised by the sdk-lib instrumentation tests.

use std::convert::Infallible;

use incrementalmerkletree::frontier::Frontier;
use orchard::keys::SpendAuthorizingKey;
use pczt::{
    Pczt,
    roles::{
        combiner::Combiner, signer::Signer, spend_finalizer::SpendFinalizer, verifier::Verifier,
    },
};
use pczt_ledger::{
    Network as LedgerNetwork,
    apdu::{Ins, StatusWord, encode::SIGHASH_ALL},
    framing::{self, BleFrameSize, Deframer},
    limits::{LimitViolation, Section},
    pairing::{DeviceIdentity, PairingError},
    session::{LedgerViolation, PumpStep, SessionError, SessionStatus, Stage},
};
use transparent::{
    bundle::{OutPoint, TxOut},
    keys::{NonHardenedChildIndex, TransparentKeyScope},
};
use zcash_client_backend::{
    data_api::{
        AccountBirthday, AccountPurpose, CoinbaseFilter, WalletWrite,
        chain::ChainState,
        testing::{
            AddressType, InitialChainState, TestBuilder, TestState, single_output_change_strategy,
        },
        wallet::{
            ConfirmationsPolicy, create_pczt_from_proposal, input_selection::GreedyInputSelector,
        },
    },
    fees::StandardFeeRule,
    keys::UnifiedSpendingKey,
    wallet::{OvkPolicy, WalletTransparentOutput},
    zip321::{Payment, TransactionRequest},
};
use zcash_client_sqlite::{
    AccountUuid,
    testing::{BlockCache, db::TestDbFactory},
};
use zcash_primitives::{block::BlockHash, transaction::builder::BundlePadding};
use zcash_protocol::{
    ShieldedPool,
    consensus::{NetworkUpgrade, Parameters, ZIP212_GRACE_PERIOD},
    local_consensus::LocalNetwork,
    value::Zatoshis,
};

use super::{
    error::{Kind, LedgerError},
    handles::Registry,
    session::{PreparedSession, SessionRequest, new_sign_session},
};

// ---------------------------------------------------------------------------
// Handles and framing
// ---------------------------------------------------------------------------

/// A reply split into BLE frames the way the device sends it comes back whole through a
/// registry-held deframer, and a freed handle is refused rather than reused.
#[test]
fn a_framed_reply_reassembles_through_a_registry_handle() {
    static DEFRAMERS: Registry<Deframer> = Registry::new();

    // A reply longer than one frame: 300 data bytes and `9000`.
    let mut reply = (0..300u16).map(|i| i as u8).collect::<Vec<_>>();
    reply.extend_from_slice(&StatusWord::Ok.to_u16().to_be_bytes());
    let size = BleFrameSize::new(23).expect("a usable frame size");
    let frames = framing::ble_frames(&reply, size).expect("the reply frames");
    assert!(frames.len() > 1, "the reply spans several frames");

    let handle = DEFRAMERS.insert(Deframer::ble()).expect("handle");
    let mut reassembled = None;
    for (i, frame) in frames.iter().enumerate() {
        let out = DEFRAMERS
            .with(handle, |deframer| {
                deframer
                    .push(frame)
                    .map_err(|e| LedgerError::from_framing(&e))
            })
            .expect("every frame is accepted");
        if i + 1 < frames.len() {
            assert!(out.is_none(), "no reply before the last frame");
        } else {
            reassembled = out;
        }
    }
    assert_eq!(reassembled.as_deref(), Some(reply.as_slice()));

    DEFRAMERS.remove(handle);
    assert!(!DEFRAMERS.contains(handle));
    let err = DEFRAMERS
        .with(handle, |_| Ok(()))
        .expect_err("a freed handle is refused");
    assert_eq!(err.kind, Kind::Internal);
}

/// A frame that does not belong to the reply poisons the reassembly, and the refusal maps to a
/// malformed reply.
#[test]
fn a_frame_out_of_sequence_is_a_malformed_reply() {
    let mut reply = vec![0xAB; 64];
    reply.extend_from_slice(&StatusWord::Ok.to_u16().to_be_bytes());
    let frames = framing::ble_frames(&reply, BleFrameSize::new(20).expect("size")).expect("frames");
    let mut deframer = Deframer::ble();
    deframer.push(&frames[0]).expect("frame 0");
    let err = deframer
        .push(&frames[2])
        .expect_err("frame 2 before frame 1");
    let mapped = LedgerError::from_framing(&err);
    assert_eq!(mapped.kind, Kind::MalformedReply);
    assert!(
        deframer.push(&frames[1]).is_err(),
        "the reassembly stays dead"
    );
}

/// The MTU handshake reply's byte 5 is the frame size.
#[test]
fn the_mtu_handshake_reply_names_the_frame_size() {
    let size = framing::parse_ble_mtu_response(&[0x08, 0x00, 0x00, 0x99, 0x01, 153])
        .expect("a real device's reply");
    assert_eq!(size.get(), 153);
    let err = framing::parse_ble_mtu_response(&[0x05, 0, 0, 0, 0, 153]).expect_err("wrong tag");
    assert_eq!(LedgerError::from_framing(&err).kind, Kind::MalformedReply);
}

// ---------------------------------------------------------------------------
// Device identity and error mapping
// ---------------------------------------------------------------------------

/// The key the software device answers the identity probe with.
fn device_secret_key() -> secp256k1::SecretKey {
    secp256k1::SecretKey::from_slice(&[0x5D; 32]).expect("a valid secret key")
}

fn device_public_key() -> secp256k1::PublicKey {
    secp256k1::PublicKey::from_secret_key(
        &secp256k1::Secp256k1::signing_only(),
        &device_secret_key(),
    )
}

fn device_identity() -> DeviceIdentity {
    DeviceIdentity::from_compressed_pubkey(&device_public_key().serialize())
}

/// `pk_len ‖ pk ‖ addr_len ‖ addr ‖ chain_code ‖ 9000`, the shape `handler_get_public_key`
/// replies with.
fn wallet_public_key_reply(key: &secp256k1::PublicKey) -> Vec<u8> {
    let pk = key.serialize_uncompressed();
    let address = b"tmSoftwareDeviceFirstAddress";
    let mut reply = vec![pk.len() as u8];
    reply.extend_from_slice(&pk);
    reply.push(address.len() as u8);
    reply.extend_from_slice(address);
    reply.extend_from_slice(&[0x11; 32]);
    reply.extend_from_slice(&StatusWord::Ok.to_u16().to_be_bytes());
    reply
}

fn firmware_version_reply(version: (u8, u8, u8)) -> Vec<u8> {
    let mut reply = vec![0x38, 0x30, version.0, version.1, version.2, 2, 3, 22];
    reply.extend_from_slice(&StatusWord::Ok.to_u16().to_be_bytes());
    reply
}

fn status_reply(status: StatusWord) -> Vec<u8> {
    status.to_u16().to_be_bytes().to_vec()
}

/// `LedgerDevice.exportUfvk` loops on `UfvkExportStep.RetrySameApdu` with no cap of its own, so
/// the bound it relies on is the engine's: a `0x6901` refusal is absorbed and the same command
/// handed out again at most `MAX_CMD_NOT_ACCEPTED_RETRIES` times, and the next one fails the export
/// as a device refusal. This pins that behavior of `pczt_ledger`, which the Kotlin loop would spin
/// on forever if it ever went away.
#[test]
fn a_viewing_key_export_gives_up_after_the_engines_refusal_budget() {
    use pczt_ledger::apdu::MAX_CMD_NOT_ACCEPTED_RETRIES;
    use pczt_ledger::pairing::{VkExchange, VkStep};

    let mut export = VkExchange::ufvk(LedgerNetwork::Test, zip32::AccountId::ZERO).expect("export");
    let first = export
        .next_apdu()
        .expect("the export starts with a command");
    let refusal = status_reply(StatusWord::CmdNotAccepted);

    for attempt in 0..MAX_CMD_NOT_ACCEPTED_RETRIES {
        assert!(
            matches!(export.process_response(&refusal), Ok(VkStep::RetrySameApdu)),
            "refusal {attempt} is absorbed and the command is to be resent"
        );
        assert_eq!(
            export.next_apdu().as_deref(),
            Some(first.as_slice()),
            "the resend is the identical command"
        );
    }

    let err = export
        .process_response(&refusal)
        .expect_err("one refusal past the budget ends the export");
    assert_eq!(LedgerError::from_pairing(&err).kind, Kind::DeviceRefused);
    assert!(
        export.next_apdu().is_none(),
        "a failed export hands out nothing more, so the loop cannot continue"
    );
}

#[test]
fn a_device_identity_round_trips_through_its_string_form() {
    let identity = pczt_ledger::pairing::parse_device_identity_response(&wallet_public_key_reply(
        &device_public_key(),
    ))
    .expect("the reply parses");
    assert_eq!(identity, device_identity());
    let encoded = identity.to_string();
    assert!(encoded.starts_with("tpk0-"));
    assert_eq!(DeviceIdentity::parse(&encoded), Ok(identity));

    let upper = encoded.to_uppercase();
    let err = DeviceIdentity::parse(&upper).expect_err("the prefix and hex are case-sensitive");
    let mapped = LedgerError::from_identity(&err);
    assert_eq!(mapped.kind, Kind::InvalidInput);
    assert!(
        !mapped
            .reason
            .as_deref()
            .unwrap_or_default()
            .contains(&upper[5..]),
        "the reason never echoes the input"
    );
}

/// The mapping keeps device identities out of the reason and carries the verdicts the Kotlin
/// layer switches on.
#[test]
fn session_errors_map_to_structured_kinds() {
    let other = DeviceIdentity::from_compressed_pubkey(
        &secp256k1::PublicKey::from_secret_key(
            &secp256k1::Secp256k1::signing_only(),
            &secp256k1::SecretKey::from_slice(&[0x42; 32]).expect("key"),
        )
        .serialize(),
    );
    let mismatch = LedgerError::from_session(
        &SessionError::DeviceMismatch {
            expected: device_identity(),
            found: other,
        },
        false,
    );
    assert_eq!(mismatch.kind, Kind::DeviceMismatch);
    let reason = mismatch.reason.expect("a reason");
    assert!(!reason.contains("tpk0-"), "no identity in the reason");
    assert!(!reason.contains(&device_identity().to_string()[5..]));

    let rejected = LedgerError::from_session(
        &SessionError::UserRejected {
            stage: Stage::Streaming,
        },
        true,
    );
    assert_eq!(rejected.kind, Kind::UserRejected);
    assert!(rejected.restartable);

    let bad_state = LedgerError::from_session(
        &SessionError::Device {
            stage: Stage::SigningOrchard,
            status: StatusWord::BadState,
        },
        true,
    );
    assert_eq!(bad_state.kind, Kind::DeviceRefused);
    assert_eq!(bad_state.status_word, Some(0xB007));
    assert!(bad_state.transient);

    let incorrect = LedgerError::from_session(
        &SessionError::Device {
            stage: Stage::Streaming,
            status: StatusWord::IncorrectData,
        },
        false,
    );
    assert!(!incorrect.transient);
    assert!(!incorrect.restartable);

    let wrong_app = LedgerError::from_session(
        &SessionError::WrongApp {
            status: StatusWord::ClaNotSupported,
        },
        false,
    );
    assert_eq!(wrong_app.kind, Kind::WrongApp);
    assert_eq!(wrong_app.status_word, Some(0x6E00));

    assert_eq!(
        LedgerError::from_session(&SessionError::Parse("garbage".into()), false).kind,
        Kind::InvalidInput
    );

    for too_old in [
        LimitViolation::PcztUnsupported {
            version: (3, 3, 0),
            required: (3, 4, 0),
        },
        LimitViolation::IronwoodUnsupported {
            version: (3, 9, 3),
            required: (3, 10, 0),
        },
        LimitViolation::HashedMemoUnsupported {
            section: Section::Orchard,
            index: 0,
        },
    ] {
        let mapped = LedgerError::from_session(
            &SessionError::Validation(vec![LedgerViolation::Limit(too_old.clone())]),
            false,
        );
        assert_eq!(mapped.kind, Kind::AppTooOld, "{too_old:?}");
    }
}

/// Only a refusal made entirely of app-age violations is `AppTooOld`: if the plan also breaks a
/// rule an app update does not lift, updating the app would not make it signable.
#[test]
fn only_app_age_violations_map_to_app_too_old() {
    let hashed_memo = LedgerViolation::Limit(LimitViolation::HashedMemoUnsupported {
        section: Section::Orchard,
        index: 0,
    });
    let pczt_unsupported = LedgerViolation::Limit(LimitViolation::PcztUnsupported {
        version: (3, 3, 0),
        required: (3, 4, 0),
    });
    let map = |violations: Vec<LedgerViolation>| {
        LedgerError::from_session(&SessionError::Validation(violations), false).kind
    };

    assert_eq!(
        map(vec![hashed_memo.clone(), pczt_unsupported]),
        Kind::AppTooOld,
        "only app-age violations"
    );
    assert_eq!(
        map(vec![LedgerViolation::SaplingPresent, hashed_memo]),
        Kind::TransactionNotSignable,
        "an app-age violation alongside a shape violation"
    );
    assert_eq!(
        map(vec![LedgerViolation::SaplingPresent]),
        Kind::TransactionNotSignable,
        "only a shape violation"
    );
    assert_eq!(
        map(vec![]),
        Kind::TransactionNotSignable,
        "no violations at all"
    );
}

#[test]
fn pairing_errors_map_to_structured_kinds() {
    let not_accepted = LedgerError::from_pairing(&PairingError::CmdNotAccepted);
    assert_eq!(not_accepted.kind, Kind::CmdNotAccepted);
    assert_eq!(not_accepted.status_word, Some(0x6901));

    let budget = LedgerError::from_pairing(&PairingError::DerivationBudgetExhausted);
    assert_eq!(budget.kind, Kind::DerivationBudgetExhausted);

    let rejected = LedgerError::from_pairing(&PairingError::UserRejected);
    assert_eq!(rejected.kind, Kind::UserRejected);

    // The decode message is `zcash_address`'s and is not passed through.
    let secret_looking = "uviewtest1qqqqsecret";
    let key = LedgerError::from_pairing(&PairingError::UnifiedViewingKey(secret_looking.into()));
    assert_eq!(key.kind, Kind::MalformedReply);
    assert!(!key.reason.expect("a reason").contains(secret_looking));

    let locked = LedgerError::from_pairing(&PairingError::Device {
        status: StatusWord::DeviceLocked,
    });
    assert_eq!(locked.kind, Kind::DeviceRefused);
    assert!(locked.transient && locked.restartable);
}

/// A `0x6985` from the device is a user rejection, as the one-shot parsers and the session both
/// see it.
#[test]
fn a_deny_status_word_is_a_user_rejection() {
    let deny = status_reply(StatusWord::Deny);
    let err = pczt_ledger::pairing::parse_device_identity_response(&deny).expect_err("denied");
    assert_eq!(LedgerError::from_pairing(&err).kind, Kind::UserRejected);
    let err = pczt_ledger::pairing::parse_unified_address(&deny, LedgerNetwork::Test)
        .expect_err("denied");
    assert_eq!(LedgerError::from_pairing(&err).kind, Kind::UserRejected);
}

// ---------------------------------------------------------------------------
// Signing sessions over a real wallet database
// ---------------------------------------------------------------------------

type TestWallet = TestState<BlockCache, zcash_client_sqlite::testing::db::TestDb, LocalNetwork>;

/// The seed whose keys the software device holds.
const DEVICE_SEED: [u8; 32] = [7; 32];

/// A wallet holding one account imported from the device's UFVK, the way a Ledger pairing
/// imports it: no ZIP 32 derivation, key source `"ledger"` unless [`tagged_wallet`] says otherwise.
struct LedgerWallet {
    st: TestWallet,
    account: AccountUuid,
    usk: UnifiedSpendingKey,
}

fn ledger_wallet() -> LedgerWallet {
    tagged_wallet(Some("ledger"))
}

/// A [`LedgerWallet`] whose account is imported under `key_source` instead of `"ledger"`.
fn tagged_wallet(key_source: Option<&str>) -> LedgerWallet {
    // Past Canopy's ZIP 212 grace period, so the Sapling fixture can be built as a PCZT at all,
    // and past NU5, so Orchard notes can be received; the upstream `pczt_single_step` test sets
    // its chain up the same way.
    let mut chain_state = None;
    let mut st = TestBuilder::new()
        .with_data_store_factory(TestDbFactory::default())
        .with_block_cache(BlockCache::new())
        .with_initial_chain_state(|_, network| {
            let birthday_height = std::cmp::max(
                network
                    .activation_height(NetworkUpgrade::Nu5)
                    .expect("NU5 is active"),
                network
                    .activation_height(NetworkUpgrade::Canopy)
                    .expect("Canopy is active")
                    + ZIP212_GRACE_PERIOD,
            );
            let state = ChainState::new(
                birthday_height - 1,
                BlockHash([5; 32]),
                Frontier::empty(),
                Frontier::empty(),
                Frontier::empty(),
            );
            chain_state = Some(state.clone());
            InitialChainState {
                chain_state: state,
                prior_sapling_roots: vec![],
                prior_orchard_roots: vec![],
            }
        })
        .build();
    let usk = UnifiedSpendingKey::from_seed(st.network(), &DEVICE_SEED, zip32::AccountId::ZERO)
        .expect("a spending key");
    let ufvk = usk.to_unified_full_viewing_key();
    let birthday =
        AccountBirthday::from_parts(chain_state.expect("the chain state was built"), None);
    let account = st
        .wallet_mut()
        .import_account_ufvk(
            "Ledger",
            &ufvk,
            &birthday,
            AccountPurpose::Spending { derivation: None },
            key_source,
        )
        .expect("the UFVK imports");
    let account = zcash_client_backend::data_api::Account::id(&account);
    LedgerWallet { st, account, usk }
}

/// A PCZT paying `value` from the wallet's Orchard funds to an address of another seed.
fn orchard_transfer_pczt(wallet: &mut LedgerWallet, value: u64) -> Pczt {
    let fvk = wallet
        .usk
        .to_unified_full_viewing_key()
        .orchard()
        .cloned()
        .expect("an Orchard key");
    let (height, _, _) = wallet.st.generate_next_block(
        &fvk,
        AddressType::DefaultExternal,
        Zatoshis::const_from_u64(500_000),
    );
    wallet.st.scan_cached_blocks(height, 1);

    let recipient =
        UnifiedSpendingKey::from_seed(wallet.st.network(), &[9; 32], zip32::AccountId::ZERO)
            .expect("another key")
            .to_unified_full_viewing_key()
            .default_address(zcash_client_backend::keys::UnifiedAddressRequest::AllAvailableKeys)
            .expect("an address")
            .0;
    let request = TransactionRequest::new(vec![Payment::without_memo(
        recipient.to_zcash_address(wallet.st.network().network_type()),
        Zatoshis::const_from_u64(value),
    )])
    .expect("a request");
    let proposal = wallet
        .st
        .propose_transfer(
            wallet.account,
            &GreedyInputSelector::new(),
            &single_output_change_strategy(StandardFeeRule::Zip317, None, ShieldedPool::Orchard),
            request,
            ConfirmationsPolicy::MIN,
        )
        .expect("a proposal");
    wallet
        .st
        .create_pczt_from_proposal::<Infallible, _, Infallible>(
            wallet.account,
            OvkPolicy::Sender,
            &proposal,
            None,
        )
        .expect("a PCZT")
}

/// A PCZT shielding a transparent coin received at the account's first external address.
fn shielding_pczt(wallet: &mut LedgerWallet) -> Pczt {
    let fvk = wallet
        .usk
        .to_unified_full_viewing_key()
        .orchard()
        .cloned()
        .expect("an Orchard key");
    // A block, so the wallet has a chain tip to target.
    let (height, _, _) = wallet.st.generate_next_block(
        &fvk,
        AddressType::Internal,
        Zatoshis::const_from_u64(50_000),
    );
    wallet.st.scan_cached_blocks(height, 1);

    let taddr = *wallet
        .usk
        .to_unified_full_viewing_key()
        .default_address(zcash_client_backend::keys::UnifiedAddressRequest::AllAvailableKeys)
        .expect("an address")
        .0
        .transparent()
        .expect("a transparent receiver");
    let utxo = WalletTransparentOutput::from_parts(
        OutPoint::fake(),
        TxOut::new(Zatoshis::const_from_u64(100_000), taddr.script().into()),
        Some(height),
        Some(wallet.account),
        Some(TransparentKeyScope::EXTERNAL),
        None,
    )
    .expect("a UTXO");
    wallet
        .st
        .wallet_mut()
        .put_received_transparent_utxo(&utxo)
        .expect("the UTXO is stored");

    let proposal = wallet
        .st
        .propose_shielding(
            &GreedyInputSelector::new(),
            &single_output_change_strategy(StandardFeeRule::Zip317, None, ShieldedPool::Orchard),
            Zatoshis::const_from_u64(10_000),
            &[taddr],
            wallet.account,
            ConfirmationsPolicy::MIN,
            CoinbaseFilter::AllTransparentOutputs,
        )
        .expect("a shielding proposal");
    let network = *wallet.st.network();
    create_pczt_from_proposal::<_, _, Infallible, _, Infallible, _>(
        wallet.st.wallet_mut(),
        &network,
        &zcash_client_backend::util::SystemClock,
        &mut crate::system_rng(),
        wallet.account,
        OvkPolicy::Sender,
        &proposal,
        None,
        BundlePadding::DEFAULT,
    )
    .expect("a PCZT")
}

fn request<'a>(
    wallet: &LedgerWallet,
    pczt: &'a [u8],
    identity: &'a str,
    firmware: &'a [u8],
) -> SessionRequest<'a> {
    SessionRequest {
        network: LedgerNetwork::Test,
        account: wallet.account,
        pczt,
        device_identity: identity,
        zip32_account: zip32::AccountId::ZERO,
        firmware_version_reply: firmware,
    }
}

/// A software Ledger holding `usk`: it answers the two probes, acknowledges every stream packet,
/// and signs with the account's keys over the session's own shaped PCZT, the way the device
/// signs what it was streamed.
struct SoftwareDevice {
    identity_key: secp256k1::PublicKey,
    version: (u8, u8, u8),
    usk: UnifiedSpendingKey,
    shaped: Pczt,
    /// Exchange index answered with this status word and no data.
    fail_at: Option<(usize, StatusWord)>,
    exchanges: usize,
}

impl SoftwareDevice {
    fn exchange(&mut self, apdu: &[u8]) -> Vec<u8> {
        let index = self.exchanges;
        self.exchanges += 1;
        if let Some((at, status)) = self.fail_at
            && at == index
        {
            return status_reply(status);
        }
        let (ins, p2) = (apdu[1], apdu[3]);
        let mut reply = match ins {
            x if x == Ins::GetFirmwareVersion.code() => {
                firmware_version_reply(self.version)[..8].to_vec()
            }
            x if x == Ins::GetWalletPublicKey.code() => {
                let full = wallet_public_key_reply(&self.identity_key);
                full[..full.len() - 2].to_vec()
            }
            x if x == Ins::PcztSignOrchard.code() => {
                let index = usize::from(p2);
                let mut signer = Signer::new(self.shaped.clone()).expect("a signer");
                signer
                    .sign_orchard(
                        crate::system_rng(),
                        index,
                        &SpendAuthorizingKey::from(self.usk.orchard()),
                    )
                    .expect("the account signs its own spend");
                signer.finish().orchard().actions()[index]
                    .spend()
                    .spend_auth_sig()
                    .expect("signed")
                    .to_vec()
            }
            x if x == Ins::PcztSignTransparent.code() => {
                let index = usize::from(p2);
                // The device signs with the key at the input's derivation path and nothing else.
                let (scope, address_index) = input_scope_and_index(&self.shaped, index);
                let signer = Signer::new(self.shaped.clone()).expect("a signer");
                let sighash = signer.transparent_sighash(index).expect("a sighash");
                // `derive_secret_key` returns a key of `zcash_transparent`'s own secp256k1, a
                // newer major version than this test's `secp256k1` dev-dependency, so its 32
                // secret bytes are carried across to this crate's `SecretKey`: the same key and
                // the same signature either way.
                let sk_bytes = self
                    .usk
                    .transparent()
                    .derive_secret_key(scope, address_index)
                    .expect("the key at the input's path")
                    .to_secret_bytes();
                let sk = secp256k1::SecretKey::from_slice(&sk_bytes).expect("secret key bytes");
                let signature = secp256k1::Secp256k1::signing_only()
                    .sign_ecdsa(&secp256k1::Message::from_digest(sighash), &sk);
                let mut der = signature.serialize_der().to_vec();
                der.push(SIGHASH_ALL);
                der
            }
            _ => Vec::new(),
        };
        reply.extend_from_slice(&StatusWord::Ok.to_u16().to_be_bytes());
        reply
    }
}

/// The scope and address index of a transparent input's sole BIP 44 derivation.
fn input_scope_and_index(
    pczt: &Pczt,
    index: usize,
) -> (TransparentKeyScope, NonHardenedChildIndex) {
    let mut found = None;
    Verifier::new(pczt.clone())
        .with_transparent::<Infallible, _>(|bundle| {
            let derivation = bundle.inputs()[index]
                .bip32_derivation()
                .values()
                .next()
                .expect("the shaped input carries a derivation");
            if let [_, _, _, scope, address_index] = derivation.derivation_path().as_slice() {
                found = Some((
                    TransparentKeyScope::custom(scope.index()).expect("an unhardened scope"),
                    NonHardenedChildIndex::from_index(address_index.index())
                        .expect("an unhardened index"),
                ));
            }
            Ok(())
        })
        .expect("the transparent bundle parses");
    found.expect("a BIP 44 path")
}

fn software_device(usk: &UnifiedSpendingKey, shaped: Pczt) -> SoftwareDevice {
    SoftwareDevice {
        identity_key: device_public_key(),
        version: (3, 9, 3),
        usk: usk.clone(),
        shaped,
        fail_at: None,
        exchanges: 0,
    }
}

/// Drives a prepared session against `device` the way the Kotlin runner does, and returns the
/// statuses and whether the review was announced exactly once, before its packet.
fn drive(
    prepared: &mut PreparedSession,
    device: &mut SoftwareDevice,
) -> Result<Vec<SessionStatus>, LedgerError> {
    let session = &mut prepared.session;
    let mut statuses = Vec::new();
    let mut announcements = 0;
    loop {
        if session.pending_user_action().is_some() && !session.is_past_review() {
            announcements += 1;
        }
        let apdu = match session.next_step() {
            PumpStep::Send(apdu) => apdu,
            PumpStep::AwaitingReply => return Err(LedgerError::internal("not resumable")),
            PumpStep::Done => break,
        };
        let reply = device.exchange(&apdu);
        let status = session
            .process_response(&reply)
            .map_err(|e| LedgerError::from_session(&e, session.is_restartable()))?;
        statuses.push(status);
        if status == SessionStatus::Complete {
            break;
        }
    }
    assert!(announcements <= 1, "the review is announced at most once");
    Ok(statuses)
}

/// The whole flow the app runs for a Ledger account: a PCZT from the SDK's own proposal code,
/// a session built from the wallet, a ceremony against the device, and the device-signed PCZT
/// combined with the one the prover receives. The combine is what
/// `createTransactionFromPczt` does first, so its success here is the proof that the
/// Ledger-signed PCZT — which carries stamped derivations besides the signatures — is
/// accepted next to the proven one.
#[test]
fn a_ledger_account_signs_an_orchard_transfer_that_combines_with_the_proven_pczt() {
    let mut wallet = ledger_wallet();
    let pczt = orchard_transfer_pczt(&mut wallet, 200_000);
    let bytes = pczt.clone().serialize().expect("serializes");
    let identity = device_identity().to_string();
    let firmware = firmware_version_reply((3, 9, 3));

    let mut prepared = new_sign_session(
        wallet.st.wallet(),
        request(&wallet, &bytes, &identity, &firmware),
    )
    .expect("the session builds from the wallet");
    assert!(prepared.total_commands > 2);
    let mut device = software_device(&wallet.usk, prepared.session.shaped_pczt().clone());

    let statuses = drive(&mut prepared, &mut device).expect("the ceremony completes");
    assert_eq!(statuses.last(), Some(&SessionStatus::Complete));
    assert_eq!(
        device.exchanges, prepared.total_commands,
        "the progress total counts every command"
    );
    let outcome = prepared.session.finish().expect("the signatures apply");
    assert_eq!(outcome.orchard_sigs().len(), 1);

    let signed = Pczt::parse(outcome.pczt_with_sigs()).expect("the signed PCZT parses");
    let combined = Combiner::new(vec![pczt, signed])
        .combine()
        .expect("the Ledger-signed PCZT combines with the wallet's PCZT");
    let (index, sig) = outcome.orchard_sigs()[0];
    assert_eq!(
        combined.orchard().actions()[index].spend().spend_auth_sig(),
        &Some(sig),
        "the device's signature survives the combine"
    );
}

/// A shielding transaction from a UFVK-imported account: the wallet stamps the transparent
/// input's derivation (which `create_pczt_from_proposal` leaves out for an account without a
/// ZIP 32 derivation), the engine checks it against the account's key and the input's script,
/// and the device's transparent signature applies and finalizes after the combine.
#[test]
fn a_ledger_account_signs_a_shielding_transaction() {
    let mut wallet = ledger_wallet();
    let pczt = shielding_pczt(&mut wallet);
    let bytes = pczt.clone().serialize().expect("serializes");
    let identity = device_identity().to_string();
    let firmware = firmware_version_reply((3, 9, 3));

    let mut prepared = new_sign_session(
        wallet.st.wallet(),
        request(&wallet, &bytes, &identity, &firmware),
    )
    .expect("the session builds with the stamped input derivation");
    assert_eq!(
        prepared.session.plan().stream().transparent_inputs().len(),
        1
    );
    let mut device = software_device(&wallet.usk, prepared.session.shaped_pczt().clone());
    drive(&mut prepared, &mut device).expect("the ceremony completes");
    let outcome = prepared.session.finish().expect("the signature applies");
    assert_eq!(outcome.transparent_sigs().len(), 1);

    let signed = Pczt::parse(outcome.pczt_with_sigs()).expect("parses");
    let combined = Combiner::new(vec![pczt, signed])
        .combine()
        .expect("the Ledger-signed PCZT combines with the wallet's PCZT");
    SpendFinalizer::new(combined)
        .finalize_spends()
        .expect("the combined PCZT carries what the Spend Finalizer needs");
}

#[test]
fn a_session_is_refused_for_an_app_that_predates_pczt_signing() {
    let mut wallet = ledger_wallet();
    let bytes = orchard_transfer_pczt(&mut wallet, 100_000)
        .serialize()
        .expect("serializes");
    let identity = device_identity().to_string();
    let firmware = firmware_version_reply((3, 3, 0));
    let err = new_sign_session(
        wallet.st.wallet(),
        request(&wallet, &bytes, &identity, &firmware),
    )
    .err()
    .expect("refused");
    assert_eq!(err.kind, Kind::AppTooOld);
}

/// A session is built only for an account tagged as Ledger-imported, under the comparison that
/// also gives its proposals a single change output: an account imported under any other tag,
/// or none, is refused before any device I/O, so a mis-tagged account fails at its first
/// signature rather than on a transaction the device cannot sign.
#[test]
fn a_session_is_built_only_for_a_ledger_tagged_account() {
    let identity = device_identity().to_string();
    let firmware = firmware_version_reply((3, 9, 3));

    for tag in ["ledger", "Ledger"] {
        let mut wallet = tagged_wallet(Some(tag));
        let bytes = orchard_transfer_pczt(&mut wallet, 100_000)
            .serialize()
            .expect("serializes");
        let prepared = new_sign_session(
            wallet.st.wallet(),
            request(&wallet, &bytes, &identity, &firmware),
        );
        if let Err(err) = prepared {
            panic!("a {tag:?}-tagged account must start a session: {err:?}");
        }
    }

    for tag in [None, Some("Ledger Nano"), Some("keystone")] {
        let mut wallet = tagged_wallet(tag);
        let bytes = orchard_transfer_pczt(&mut wallet, 100_000)
            .serialize()
            .expect("serializes");
        let err = new_sign_session(
            wallet.st.wallet(),
            request(&wallet, &bytes, &identity, &firmware),
        )
        .err()
        .unwrap_or_else(|| panic!("a {tag:?}-tagged account must be refused"));
        assert_eq!(err.kind, Kind::InvalidInput, "tag {tag:?}");
        assert_eq!(
            err.reason.as_deref(),
            Some("the account is not a Ledger account"),
            "tag {tag:?}"
        );
    }
}

/// A PCZT spending Sapling funds cannot be reviewed or signed by the device, and is refused
/// before a single command is handed out.
#[test]
fn a_session_is_refused_for_a_pczt_the_device_cannot_sign() {
    let mut wallet = ledger_wallet();
    let sapling = wallet
        .usk
        .to_unified_full_viewing_key()
        .sapling()
        .cloned()
        .expect("a Sapling key");
    let (height, _, _) = wallet.st.generate_next_block(
        &sapling,
        AddressType::DefaultExternal,
        Zatoshis::const_from_u64(500_000),
    );
    wallet.st.scan_cached_blocks(height, 1);
    let recipient =
        UnifiedSpendingKey::from_seed(wallet.st.network(), &[9; 32], zip32::AccountId::ZERO)
            .expect("another key")
            .to_unified_full_viewing_key()
            .sapling()
            .expect("a Sapling key")
            .default_address()
            .1;
    let request_tx = TransactionRequest::new(vec![Payment::without_memo(
        zcash_client_backend::address::Address::Sapling(Box::new(recipient))
            .to_zcash_address(wallet.st.network()),
        Zatoshis::const_from_u64(100_000),
    )])
    .expect("a request");
    let proposal = wallet
        .st
        .propose_transfer(
            wallet.account,
            &GreedyInputSelector::new(),
            &single_output_change_strategy(StandardFeeRule::Zip317, None, ShieldedPool::Sapling),
            request_tx,
            ConfirmationsPolicy::MIN,
        )
        .expect("a proposal");
    let bytes = wallet
        .st
        .create_pczt_from_proposal::<Infallible, _, Infallible>(
            wallet.account,
            OvkPolicy::Sender,
            &proposal,
            None,
        )
        .expect("a PCZT")
        .serialize()
        .expect("serializes");

    let identity = device_identity().to_string();
    let firmware = firmware_version_reply((3, 9, 3));
    let err = new_sign_session(
        wallet.st.wallet(),
        request(&wallet, &bytes, &identity, &firmware),
    )
    .err()
    .expect("refused");
    assert_eq!(err.kind, Kind::TransactionNotSignable);
    assert!(
        err.reason.expect("a reason").contains("Sapling"),
        "the reason names what the device cannot sign"
    );
}

/// A different device answering the identity probe ends the session before any transaction byte.
#[test]
fn a_different_device_is_refused_before_the_transaction_is_streamed() {
    let mut wallet = ledger_wallet();
    let bytes = orchard_transfer_pczt(&mut wallet, 100_000)
        .serialize()
        .expect("serializes");
    let identity = device_identity().to_string();
    let firmware = firmware_version_reply((3, 9, 3));
    let mut prepared = new_sign_session(
        wallet.st.wallet(),
        request(&wallet, &bytes, &identity, &firmware),
    )
    .expect("session");
    let mut device = software_device(&wallet.usk, prepared.session.shaped_pczt().clone());
    device.identity_key = secp256k1::PublicKey::from_secret_key(
        &secp256k1::Secp256k1::signing_only(),
        &secp256k1::SecretKey::from_slice(&[0x42; 32]).expect("key"),
    );
    let err = drive(&mut prepared, &mut device).expect_err("refused");
    assert_eq!(err.kind, Kind::DeviceMismatch);
    assert_eq!(device.exchanges, 2, "only the two probes went out");
}

/// A user rejection at the review is restartable; a `0x6901` on a stream packet is a resend of
/// the same bytes, not an error.
#[test]
fn a_rejected_review_is_restartable_and_a_refused_frame_is_resent() {
    let mut wallet = ledger_wallet();
    let bytes = orchard_transfer_pczt(&mut wallet, 100_000)
        .serialize()
        .expect("serializes");
    let identity = device_identity().to_string();
    let firmware = firmware_version_reply((3, 9, 3));

    // 0x6901 on the header packet (exchange 2), then success.
    let mut prepared = new_sign_session(
        wallet.st.wallet(),
        request(&wallet, &bytes, &identity, &firmware),
    )
    .expect("session");
    let mut device = software_device(&wallet.usk, prepared.session.shaped_pczt().clone());
    device.fail_at = Some((2, StatusWord::CmdNotAccepted));
    let statuses = drive(&mut prepared, &mut device).expect("the ceremony completes");
    assert!(statuses.contains(&SessionStatus::RetrySameApdu));
    assert_eq!(statuses.last(), Some(&SessionStatus::Complete));

    // Deny on the review packet.
    let mut prepared = new_sign_session(
        wallet.st.wallet(),
        request(&wallet, &bytes, &identity, &firmware),
    )
    .expect("session");
    let review = prepared.total_commands
        - prepared.session.plan().orchard_real_spends().len()
        - prepared.session.plan().ironwood_real_spends().len()
        - prepared.session.plan().stream().transparent_inputs().len()
        - 1;
    let mut device = software_device(&wallet.usk, prepared.session.shaped_pczt().clone());
    device.fail_at = Some((review, StatusWord::Deny));
    let err = drive(&mut prepared, &mut device).expect_err("rejected");
    assert_eq!(err.kind, Kind::UserRejected);
    assert!(err.restartable);
    assert_eq!(prepared.session.stage(), Stage::Failed);
}

/// The fixture wallet's account is what a Ledger pairing imports: the software device's UFVK, no
/// ZIP 32 derivation, key source `"ledger"`.
#[test]
fn the_software_device_holds_the_imported_accounts_keys() {
    let wallet = ledger_wallet();
    let account = wallet
        .st
        .wallet()
        .get_account(wallet.account)
        .expect("readable")
        .expect("present");
    use zcash_client_backend::data_api::{Account, WalletRead};
    assert_eq!(
        account.ufvk().expect("a UFVK").encode(wallet.st.network()),
        wallet
            .usk
            .to_unified_full_viewing_key()
            .encode(wallet.st.network())
    );
    assert!(account.source().key_derivation().is_none());
    assert_eq!(account.source().key_source(), Some("ledger"));
}

/// [`a_ledger_account_signs_an_orchard_transfer_that_combines_with_the_proven_pczt`] carried
/// through proving and extraction: the wallet's PCZT is proven the way `addProofsToPczt` proves
/// it, combined with the Ledger-signed PCZT, and extracted and stored by the very function
/// `createTransactionFromPczt` calls — which verifies the proof and every signature.
///
/// Ignored by default: building the Orchard proving key and a proof in the unoptimized test
/// profile takes minutes. Run it with
/// `cargo test --release --all-features ledger::tests::proven -- --ignored`.
#[test]
#[ignore = "proves an Orchard bundle; slow outside --release"]
fn proven_and_ledger_signed_pczts_extract_to_a_stored_transaction() {
    use pczt::roles::prover::Prover;
    use zcash_client_backend::data_api::wallet::extract_and_store_transaction_from_pczt;
    use zcash_primitives::transaction::{
        builder::cached_orchard_proving_key, components::orchard::bundle_version_for_branch,
    };
    use zcash_protocol::consensus::BranchId;

    let mut wallet = ledger_wallet();
    let pczt = orchard_transfer_pczt(&mut wallet, 200_000);
    let bytes = pczt.clone().serialize().expect("serializes");
    let identity = device_identity().to_string();
    let firmware = firmware_version_reply((3, 9, 3));

    let mut prepared = new_sign_session(
        wallet.st.wallet(),
        request(&wallet, &bytes, &identity, &firmware),
    )
    .expect("session");
    let mut device = software_device(&wallet.usk, prepared.session.shaped_pczt().clone());
    drive(&mut prepared, &mut device).expect("the ceremony completes");
    let signed =
        Pczt::parse(prepared.session.finish().expect("finish").pczt_with_sigs()).expect("parses");

    let branch = BranchId::try_from(*pczt.global().consensus_branch_id()).expect("a branch");
    let circuit_version = bundle_version_for_branch(branch, orchard::ValuePool::Orchard)
        .expect("Orchard is active")
        .circuit_version();
    let proven = Prover::new(pczt)
        .create_orchard_proof(
            crate::system_rng(),
            cached_orchard_proving_key(circuit_version),
        )
        .expect("the Orchard proof")
        .finish();

    let combined = Combiner::new(vec![proven, signed])
        .combine()
        .expect("the proven and Ledger-signed PCZTs combine");
    let vk = orchard::circuit::VerifyingKey::build(circuit_version);
    extract_and_store_transaction_from_pczt::<_, ()>(
        wallet.st.wallet_mut(),
        &zcash_client_backend::util::SystemClock,
        &mut crate::system_rng(),
        combined,
        None,
        Some(&vk),
    )
    .expect("the transaction extracts, verifies and is stored");
}
