//! Tests for [`super::propose`], and the synthetic test wallets other modules' send-max tests
//! build on.
//!
//! The re-proposal tests prove the property the wallet's Send Max relies on: proposing an
//! ordinary transfer of exactly the send-max payment (as the review screen does) selects the
//! same inputs and pays the same fee, leaving no value as change.

use std::collections::BTreeSet;
use std::convert::Infallible;

use transparent::address::TransparentAddress;
use zcash_address::ZcashAddress;
use zcash_client_backend::{
    data_api::{
        Account as _, MaxSpendMode,
        locking::{LockOwner, LockRequest},
        testing::{AddressType, FakeCompactOutput, IronwoodFvk, TestBuilder, TestState},
        wallet::{input_selection::SpendPolicy, propose_transfer},
    },
    fees::{ChangeValue, StandardFeeRule},
    proposal::Proposal,
    zip321::{Payment, TransactionRequest},
};
use zcash_client_sqlite::{
    ReceivedNoteId,
    testing::{
        BlockCache,
        db::{TestDb, TestDbFactory},
    },
};
use zcash_keys::address::{Address, UnifiedAddress};
use zcash_primitives::{
    block::BlockHash,
    transaction::fees::zip317::{GRACE_ACTIONS, MARGINAL_FEE, MINIMUM_FEE},
};
use zcash_protocol::{
    consensus::BlockHeight, local_consensus::LocalNetwork, memo::MemoBytes, value::Zatoshis,
};

use super::*;

/// A synthetic wallet with one account, as `zcash_client_backend`'s test harness builds it.
pub(crate) type TestWallet = TestState<BlockCache, TestDb, LocalNetwork>;

/// A send-max proposal over a [`TestWallet`].
pub(crate) type TestProposal = Proposal<StandardFeeRule, ReceivedNoteId>;

/// The value of each note [`funded_wallet`] receives.
pub(crate) const NOTE_VALUE: u64 = 100_000;

/// The ZIP 317 marginal fee per logical action ([`MARGINAL_FEE`]).
///
/// This is also the dust threshold `zcash_client_backend`'s ZIP 317 change strategy uses: a note
/// whose value does not exceed the marginal fee costs at least as much to spend as it carries.
pub(crate) fn marginal_fee() -> u64 {
    MARGINAL_FEE.into_u64()
}

/// The ZIP 317 fee of a single-transaction send-max whose inputs and output all fit in the
/// [`GRACE_ACTIONS`] minimum of one Orchard-family bundle ([`MINIMUM_FEE`], the marginal fee
/// times the grace actions).
fn minimum_fee() -> u64 {
    let fee = MINIMUM_FEE.into_u64();
    debug_assert_eq!(fee, marginal_fee() * GRACE_ACTIONS as u64);
    fee
}

/// Received funds need ten confirmations under the default confirmations policy.
pub(crate) const CONFIRMATIONS: usize = 10;

/// A note the test wallet receives in a pool.
#[derive(Clone, Copy, Debug)]
pub(crate) enum Received {
    Sapling(u64),
    Orchard(u64),
    Ironwood(u64),
}

/// A network on which every upgrade through NU6.3 (Ironwood) is active from the Sapling
/// activation height, as `zcash_client_backend`'s own Ironwood tests configure it.
pub(crate) fn ironwood_active_network() -> LocalNetwork {
    let activation = TestBuilder::<(), ()>::DEFAULT_NETWORK
        .sapling
        .expect("the default test network activates Sapling");
    LocalNetwork {
        nu6: Some(activation),
        nu6_1: Some(activation),
        nu6_2: Some(activation),
        nu6_3: Some(activation),
        ..TestBuilder::<(), ()>::DEFAULT_NETWORK
    }
}

/// A wallet on `network` that has scanned nothing.
pub(crate) fn unscanned_wallet(network: LocalNetwork) -> TestWallet {
    TestBuilder::new()
        .with_network(network)
        .with_data_store_factory(TestDbFactory::default())
        .with_block_cache(BlockCache::new())
        .with_account_from_sapling_activation(BlockHash([0; 32]))
        .build()
}

/// Mines a block paying `note` to the wallet's own account, returning the block's height.
pub(crate) fn receive(st: &mut TestWallet, note: Received) -> BlockHeight {
    let usk = st.test_account().unwrap().usk().clone();
    let orchard_fvk = || orchard::keys::FullViewingKey::from(usk.orchard());
    let value = |v| Zatoshis::const_from_u64(v);
    let external = AddressType::DefaultExternal;
    match note {
        Received::Sapling(v) => {
            let fvk = usk.sapling().to_diversifiable_full_viewing_key();
            st.generate_next_block(&fvk, external, value(v)).0
        }
        Received::Orchard(v) => st.generate_next_block(&orchard_fvk(), external, value(v)).0,
        Received::Ironwood(v) => {
            let fvk = IronwoodFvk(orchard_fvk());
            st.generate_next_block(&fvk, external, value(v)).0
        }
    }
}

/// Mines `count` empty blocks.
pub(crate) fn mine_empty_blocks(st: &mut TestWallet, count: usize) {
    for _ in 0..count {
        st.generate_empty_block();
    }
}

/// Scans every cached block from `from` to the tip.
pub(crate) fn scan_from(st: &mut TestWallet, from: BlockHeight) {
    let tip = st.latest_cached_block().unwrap().height();
    st.scan_cached_blocks(
        from,
        usize::try_from(u32::from(tip) - u32::from(from) + 1).unwrap(),
    );
}

/// A wallet on `network` holding `notes`, every one of them spendable under the default
/// confirmations policy. With no notes, the wallet has scanned one empty block, so it knows the
/// chain tip.
pub(crate) fn wallet_with(network: LocalNetwork, notes: &[Received]) -> TestWallet {
    let mut st = unscanned_wallet(network);
    let first = match notes.split_first() {
        Some((first, rest)) => {
            let height = receive(&mut st, *first);
            rest.iter().for_each(|note| {
                receive(&mut st, *note);
            });
            height
        }
        None => st.generate_empty_block().0,
    };
    mine_empty_blocks(&mut st, CONFIRMATIONS);
    scan_from(&mut st, first);
    st
}

/// A wallet on the default test network (Ironwood not active) whose only funds are one
/// spendable note of [`NOTE_VALUE`].
pub(crate) fn funded_wallet(note: fn(u64) -> Received) -> TestWallet {
    wallet_with(TestBuilder::<(), ()>::DEFAULT_NETWORK, &[note(NOTE_VALUE)])
}

/// A send-max shape: its name, the note the wallet holds, and the recipient it pays.
struct Shape(
    &'static str,
    fn(u64) -> Received,
    fn(&TestWallet) -> ZcashAddress,
);

/// The Orchard spending key of an account outside the test wallet.
fn outside_orchard_key() -> orchard::keys::SpendingKey {
    orchard::keys::SpendingKey::from_bytes([9; 32]).unwrap()
}

/// The transparent receiver of an account outside the test wallet.
const OUTSIDE_TRANSPARENT: [u8; 20] = [7; 20];

/// An Orchard-only unified address that belongs to nobody in the test wallet.
pub(crate) fn external_recipient(st: &TestWallet) -> ZcashAddress {
    let address = orchard::keys::FullViewingKey::from(&outside_orchard_key())
        .address_at(0u32, orchard::keys::Scope::External);
    unified(
        st,
        UnifiedAddress::from_receivers(Some(address), None, None, None, None),
    )
}

/// A Sapling address that belongs to nobody in the test wallet.
pub(crate) fn sapling_recipient(st: &TestWallet) -> ZcashAddress {
    let (_, address) = sapling::zip32::ExtendedSpendingKey::master(&[9; 32])
        .expect("a valid seed")
        .default_address();
    Address::Sapling(Box::new(address)).to_zcash_address(st.network())
}

/// A transparent P2PKH address that belongs to nobody in the test wallet.
pub(crate) fn transparent_recipient(st: &TestWallet) -> ZcashAddress {
    Address::Transparent(TransparentAddress::PublicKeyHash(OUTSIDE_TRANSPARENT))
        .to_zcash_address(st.network())
}

/// A ZIP 320 (TEX) address that belongs to nobody in the test wallet.
pub(crate) fn tex_recipient(st: &TestWallet) -> ZcashAddress {
    Address::Tex(OUTSIDE_TRANSPARENT).to_zcash_address(st.network())
}

/// A unified address whose only receiver is transparent, belonging to nobody in the test wallet.
fn transparent_only_unified_recipient(st: &TestWallet) -> ZcashAddress {
    let receiver = TransparentAddress::PublicKeyHash(OUTSIDE_TRANSPARENT);
    unified(
        st,
        UnifiedAddress::from_receivers(None, None, Some(receiver), None, None),
    )
}

fn unified(st: &TestWallet, address: Option<UnifiedAddress>) -> ZcashAddress {
    Address::from(address.expect("at least one receiver")).to_zcash_address(st.network())
}

/// [`propose`] against the test wallet's account.
pub(crate) fn propose_max(
    st: &mut TestWallet,
    request: SendMaxRequest,
) -> Result<TestProposal, ProposalRejection> {
    let account = st.test_account().unwrap().id();
    let network = *st.network();
    propose(st.wallet_mut(), &network, account, request)
}

/// The user's Send Max of the test wallet's whole spendable balance to `recipient`, with no memo.
pub(crate) fn propose_max_to(st: &mut TestWallet, recipient: ZcashAddress) -> TestProposal {
    propose_max(st, SendMaxRequest::spendable_to(recipient, None)).expect("proposes the send-max")
}

/// An ordinary transfer of `amount` to `recipient`, proposed exactly as the JNI
/// `proposeTransfer` proposes it, optionally locking the inputs it selects.
fn propose_payment(
    st: &mut TestWallet,
    recipient: ZcashAddress,
    amount: Zatoshis,
    lock_inputs: Option<LockRequest>,
) -> TestProposal {
    try_propose_payment(st, recipient, amount, lock_inputs)
        .unwrap_or_else(|e| panic!("re-proposes the payment: {e}"))
}

/// [`propose_payment`], with its failure classified as the JNI `proposeTransfer` classifies it.
fn try_propose_payment(
    st: &mut TestWallet,
    recipient: ZcashAddress,
    amount: Zatoshis,
    lock_inputs: Option<LockRequest>,
) -> Result<TestProposal, ProposalRejection> {
    let account = st.test_account().unwrap().id();
    let network = *st.network();
    // The test account is not Ledger-tagged, so this is the split `proposeTransfer` uses for it.
    let (change_strategy, input_selector) =
        crate::zip317_helper(None, crate::change_split_policy(None));
    let request = TransactionRequest::new(vec![
        Payment::new(recipient, Some(amount), None, None, None, vec![]).unwrap(),
    ])
    .unwrap();
    propose_transfer::<_, _, _, _, Infallible>(
        st.wallet_mut(),
        &network,
        account,
        &input_selector,
        &change_strategy,
        request,
        send_max_confirmations_policy(),
        &SpendPolicy::default(),
        lock_inputs,
        None,
    )
    .map_err(classify)
}

/// The notes a proposal spends, across all of its steps.
pub(crate) fn spent_notes(proposal: &TestProposal) -> BTreeSet<ReceivedNoteId> {
    proposal
        .steps()
        .iter()
        .filter_map(|step| step.shielded_inputs())
        .flat_map(|inputs| inputs.notes().iter().map(|note| *note.internal_note_id()))
        .collect()
}

/// The pools of the notes a proposal spends.
pub(crate) fn spent_pools(proposal: &TestProposal) -> BTreeSet<ShieldedPool> {
    proposal
        .steps()
        .iter()
        .filter_map(|step| step.shielded_inputs())
        .flat_map(|inputs| inputs.notes().iter().map(|note| note.note().pool()))
        .collect()
}

/// The fee of all of a proposal's transactions together.
fn total_fee(proposal: &TestProposal) -> Zatoshis {
    proposal
        .steps()
        .iter()
        .map(|step| step.balance().fee_required())
        .sum::<Option<Zatoshis>>()
        .expect("fees are in range")
}

/// The total a proposal pays to its recipients, across all of its steps.
fn payment_total(proposal: &TestProposal) -> Zatoshis {
    proposal
        .steps()
        .iter()
        .map(|step| {
            step.transaction_request()
                .total()
                .expect("payments are in range")
                .unwrap_or(Zatoshis::ZERO)
        })
        .sum::<Option<Zatoshis>>()
        .expect("payments are in range")
}

/// The change a proposal leaves, other than ZIP 320 ephemeral outputs (which carry the payment
/// to a TEX recipient through its second transaction).
fn change_outputs(proposal: &TestProposal) -> Vec<ChangeValue> {
    proposal
        .steps()
        .iter()
        .flat_map(|step| step.balance().proposed_change())
        .filter(|change| !change.is_ephemeral())
        .cloned()
        .collect()
}

/// The total value of [`change_outputs`].
fn change_value(proposal: &TestProposal) -> Zatoshis {
    change_outputs(proposal)
        .iter()
        .map(|change| change.value())
        .sum::<Option<Zatoshis>>()
        .expect("change is in range")
}

/// Proposes the Send Max to `recipient`, then an ordinary transfer of exactly its payment, as
/// the review screen does, and checks that the two spend the same notes and pay the same fee,
/// so neither leaves any value behind as change.
///
/// This does not require the re-proposal to have no change output at all: see
/// [`reproposal_change_is_zero_valued`].
fn assert_reproposal_matches_send_max(st: &mut TestWallet, recipient: ZcashAddress) {
    let max = propose_max_to(st, recipient.clone());
    assert!(change_outputs(&max).is_empty(), "a send-max has no change");

    let payment = propose_payment(st, recipient, payment_total(&max), None);

    assert_eq!(spent_notes(&payment), spent_notes(&max), "same inputs");
    assert_eq!(total_fee(&payment), total_fee(&max), "same fee");
    assert_eq!(
        payment.steps().len(),
        max.steps().len(),
        "same transactions"
    );
    assert_eq!(
        change_value(&payment),
        Zatoshis::ZERO,
        "no value left as change"
    );
}

/// The [`ProposalRejection`] a failed proposal reports.
fn propose_max_error(st: &mut TestWallet, request: SendMaxRequest) -> ProposalRejection {
    propose_max(st, request).expect_err("the send-max is refused")
}

fn memo() -> Option<MemoBytes> {
    Some(MemoBytes::from_bytes(b"gift").unwrap())
}

// Confirmations policy

#[test]
fn card_notes_wait_for_the_untrusted_confirmation_count() {
    let policy = send_max_confirmations_policy();
    assert_eq!(policy.untrusted().get(), 10);
    assert_eq!(policy.trusted().get(), 3);
}

// Requests

#[test]
fn a_user_send_max_spends_every_shielded_pool_and_only_what_is_spendable() {
    let st = funded_wallet(Received::Orchard);
    let recipient = external_recipient(&st);

    let request = SendMaxRequest::spendable_to(recipient.clone(), memo());

    assert_eq!(request.pools, &SEND_MAX_POOLS);
    assert!(matches!(request.mode, MaxSpendMode::MaxSpendable));
    assert_eq!(request.recipient, recipient);
    assert_eq!(request.memo, memo());
}

// Re-proposal equivalence

#[test]
fn reproposal_matches_send_max_from_sapling_only() {
    let mut st = funded_wallet(Received::Sapling);
    let recipient = external_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_from_orchard_only() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = external_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_from_sapling_and_orchard() {
    let mut st = wallet_with(
        TestBuilder::<(), ()>::DEFAULT_NETWORK,
        &[Received::Sapling(NOTE_VALUE), Received::Orchard(NOTE_VALUE)],
    );
    let recipient = external_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_from_sapling_orchard_and_ironwood() {
    let mut st = wallet_with(
        ironwood_active_network(),
        &[
            Received::Sapling(NOTE_VALUE),
            Received::Orchard(NOTE_VALUE),
            Received::Ironwood(NOTE_VALUE),
        ],
    );
    let recipient = external_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_to_a_sapling_recipient() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = sapling_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_to_a_transparent_recipient() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = transparent_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_from_sapling_to_a_sapling_recipient() {
    let mut st = funded_wallet(Received::Sapling);
    let recipient = sapling_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_from_sapling_to_a_transparent_recipient() {
    let mut st = funded_wallet(Received::Sapling);
    let recipient = transparent_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_to_a_tex_recipient() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = tex_recipient(&st);
    let max = propose_max_to(&mut st, recipient.clone());
    assert_eq!(
        max.steps().len(),
        2,
        "a TEX payment is a two-step ZIP 320 proposal"
    );

    assert_reproposal_matches_send_max(&mut st, recipient);
}

#[test]
fn reproposal_matches_send_max_to_a_transparent_only_unified_address() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = transparent_only_unified_recipient(&st);
    assert_reproposal_matches_send_max(&mut st, recipient);
}

/// The review path's ZIP 317 change strategy may add a shielded change output to the transaction
/// it re-proposes, but only a zero-valued one: `zcash_client_backend` deliberately allows
/// zero-valued change when the payment leaves nothing over (`fees::common`, "Always allow
/// zero-valued change"), as it improves indistinguishability, and it fits within the bundle
/// padding the fee already pays for. Every non-ephemeral change output the re-proposal carries
/// is therefore worth exactly zero, for every recipient shape.
#[test]
fn reproposal_change_is_zero_valued() {
    let shapes = [
        Shape("Orchard to Orchard", Received::Orchard, external_recipient),
        Shape("Sapling to Orchard", Received::Sapling, external_recipient),
        Shape("Orchard to Sapling", Received::Orchard, sapling_recipient),
        Shape(
            "Orchard to transparent",
            Received::Orchard,
            transparent_recipient,
        ),
        Shape("Orchard to TEX", Received::Orchard, tex_recipient),
        Shape(
            "Orchard to transparent-only unified",
            Received::Orchard,
            transparent_only_unified_recipient,
        ),
    ];
    for Shape(shape, note, recipient) in shapes {
        let mut st = funded_wallet(note);
        let recipient = recipient(&st);
        let max = propose_max_to(&mut st, recipient.clone());
        let payment = propose_payment(&mut st, recipient, payment_total(&max), None);
        for change in change_outputs(&payment) {
            assert_eq!(change.value(), Zatoshis::ZERO, "{shape}: {change:?}");
        }
    }
}

// Input selection

#[test]
fn send_max_pays_the_whole_balance_less_the_fee() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = external_recipient(&st);

    let max = propose_max_to(&mut st, recipient);

    assert_eq!(u64::from(total_fee(&max)), minimum_fee());
    assert_eq!(u64::from(payment_total(&max)), NOTE_VALUE - minimum_fee());
}

/// A note worth no more than the ZIP 317 marginal fee costs at least as much to spend as it
/// carries, so it is neither selected nor counted in the maximum. The dust note sits exactly on
/// that boundary: worth the marginal fee, which is the largest value that is still dust.
#[test]
fn dust_notes_are_left_out_of_the_maximum() {
    let dust = marginal_fee();
    let mut st = wallet_with(
        TestBuilder::<(), ()>::DEFAULT_NETWORK,
        &[Received::Orchard(NOTE_VALUE), Received::Orchard(dust)],
    );
    let recipient = external_recipient(&st);

    let max = propose_max_to(&mut st, recipient.clone());

    assert_eq!(spent_notes(&max).len(), 1, "the dust note is not selected");
    assert_eq!(
        u64::from(payment_total(&max)) + u64::from(total_fee(&max)),
        NOTE_VALUE,
        "the maximum counts only the economic note"
    );
    assert_reproposal_matches_send_max(&mut st, recipient);
}

/// A note locked by another in-flight proposal is excluded both by the send-max and by the
/// ordinary transfer that re-proposes its payment.
#[test]
fn locked_notes_are_left_out_by_both_paths() {
    let mut st = wallet_with(
        TestBuilder::<(), ()>::DEFAULT_NETWORK,
        &[
            Received::Orchard(NOTE_VALUE),
            Received::Orchard(2 * NOTE_VALUE),
        ],
    );
    let recipient = external_recipient(&st);
    let in_flight = propose_payment(
        &mut st,
        recipient.clone(),
        Zatoshis::const_from_u64(NOTE_VALUE / 2),
        Some(LockRequest::new(LockOwner::new([1; 32]), 100)),
    );
    let locked = spent_notes(&in_flight);
    assert_eq!(locked.len(), 1);

    let max = propose_max_to(&mut st, recipient.clone());

    assert_eq!(spent_notes(&max).len(), 1);
    assert!(
        spent_notes(&max).is_disjoint(&locked),
        "the locked note is not selected"
    );
    assert_reproposal_matches_send_max(&mut st, recipient);
}

// Error classification, against a wallet

#[test]
fn a_wallet_with_nothing_spendable_is_insufficient_funds() {
    let mut st = wallet_with(TestBuilder::<(), ()>::DEFAULT_NETWORK, &[]);
    let recipient = external_recipient(&st);

    let error = propose_max_error(&mut st, SendMaxRequest::spendable_to(recipient, None));

    assert!(
        matches!(error, ProposalRejection::InsufficientFunds { available, .. } if available == Zatoshis::ZERO),
        "{error:?}"
    );
}

#[test]
fn a_balance_that_does_not_exceed_the_fee_is_insufficient_funds() {
    // Above the dust threshold, so the note counts as available, but one zatoshi short of the
    // fee its own spend costs.
    let below_fee = minimum_fee() - 1;
    assert!(below_fee > marginal_fee());
    let mut st = wallet_with(
        TestBuilder::<(), ()>::DEFAULT_NETWORK,
        &[Received::Orchard(below_fee)],
    );
    let recipient = external_recipient(&st);

    let error = propose_max_error(&mut st, SendMaxRequest::spendable_to(recipient, None));

    assert_eq!(
        error,
        ProposalRejection::InsufficientFunds {
            available: Zatoshis::const_from_u64(below_fee),
            required: Zatoshis::const_from_u64(minimum_fee() + 1),
        }
    );
    // Kotlin's `indicatesInsufficientFunds` still matches this text when the typed exception
    // cannot be thrown.
    assert!(error.to_string().starts_with("Insufficient balance"));
}

#[test]
fn a_memo_to_a_transparent_recipient_is_rejected() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = transparent_recipient(&st);

    let error = propose_max_error(&mut st, SendMaxRequest::spendable_to(recipient, memo()));

    assert_eq!(error, ProposalRejection::TransparentMemo);
}

#[test]
fn a_memo_to_a_tex_recipient_is_rejected() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = tex_recipient(&st);

    let error = propose_max_error(&mut st, SendMaxRequest::spendable_to(recipient, memo()));

    assert_eq!(error, ProposalRejection::TransparentMemo);
}

#[test]
fn a_wallet_that_has_not_scanned_requires_sync() {
    let mut st = unscanned_wallet(TestBuilder::<(), ()>::DEFAULT_NETWORK);
    let recipient = external_recipient(&st);

    let error = propose_max_error(&mut st, SendMaxRequest::spendable_to(recipient, None));

    assert_eq!(error, ProposalRejection::SyncRequired);
}

/// More Orchard notes than one transaction can spend within the block size limit (an Orchard
/// action with its signature and its share of the proof is a little over 3 kB, so a 2 MB block
/// holds about 630 of them).
const TOO_MANY_NOTES: usize = 700;

/// Notes per block when receiving [`TOO_MANY_NOTES`], to keep the chain short.
const NOTES_PER_BLOCK: usize = 50;

/// A wallet holding [`TOO_MANY_NOTES`] spendable Orchard notes, each worth [`small_note_value`]:
/// more than the marginal fee it costs to spend.
fn wallet_with_too_many_notes() -> TestWallet {
    wallet_with_too_many_notes_on(TestBuilder::<(), ()>::DEFAULT_NETWORK)
}

/// [`wallet_with_too_many_notes`] on `network`.
pub(crate) fn wallet_with_too_many_notes_on(network: LocalNetwork) -> TestWallet {
    wallet_with_orchard_notes_on(network, TOO_MANY_NOTES)
}

/// A wallet on `network` holding `count` spendable Orchard notes, each worth
/// [`small_note_value`]: more than the marginal fee it costs to spend.
pub(crate) fn wallet_with_orchard_notes_on(network: LocalNetwork, count: usize) -> TestWallet {
    let mut st = unscanned_wallet(network);
    let fvk = orchard::keys::FullViewingKey::from(st.test_account().unwrap().usk().orchard());
    let output = FakeCompactOutput::new(
        fvk,
        AddressType::DefaultExternal,
        Zatoshis::const_from_u64(small_note_value()),
    );
    let first = (0..count)
        .step_by(NOTES_PER_BLOCK)
        .map(|received| {
            let block = vec![output.clone(); NOTES_PER_BLOCK.min(count - received)];
            st.generate_next_block_multi(&block).0
        })
        .min()
        .expect("at least one note");
    mine_empty_blocks(&mut st, CONFIRMATIONS);
    scan_from(&mut st, first);
    st
}

/// The value of each note [`wallet_with_orchard_notes_on`] receives: twice the marginal fee, so
/// each note is not dust and nets one marginal fee once its own spend is paid for.
pub(crate) fn small_note_value() -> u64 {
    2 * marginal_fee()
}

/// How many of the [`TOO_MANY_NOTES`] [`too_large_payment`] needs: more than fit in a block,
/// fewer than the wallet holds.
const NOTES_FOR_TOO_LARGE_PAYMENT: u64 = 680;

/// A payment that needs more notes than fit in a block: [`NOTES_FOR_TOO_LARGE_PAYMENT`] times
/// what each note nets after the marginal fee its spend costs, i.e.
/// `NOTES_FOR_TOO_LARGE_PAYMENT * (small_note_value() - marginal_fee())`.
fn too_large_payment() -> u64 {
    NOTES_FOR_TOO_LARGE_PAYMENT * (small_note_value() - marginal_fee())
}

/// A send-max is refused when proposed, not only once its transaction is built, when it would
/// spend more notes than fit in a block; and the ordinary transfer the review screen proposes
/// for such a payment is refused as the same category, so both reach Kotlin as the same typed
/// exception.
#[test]
fn too_many_notes_are_too_large_for_send_max_and_for_a_transfer() {
    let mut st = wallet_with_too_many_notes();
    let recipient = external_recipient(&st);

    let error = propose_max_error(
        &mut st,
        SendMaxRequest::spendable_to(recipient.clone(), None),
    );
    assert_eq!(error, ProposalRejection::TransactionTooLarge);

    let error = try_propose_payment(
        &mut st,
        recipient,
        Zatoshis::const_from_u64(too_large_payment()),
        None,
    )
    .expect_err("the transfer is refused");
    assert_eq!(error, ProposalRejection::TransactionTooLarge);
}

/// The ordinary transfer reports the categories a send-max reports for the same wallet.
#[test]
fn a_transfer_the_wallet_cannot_cover_is_insufficient_funds() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = external_recipient(&st);

    let error = try_propose_payment(
        &mut st,
        recipient,
        Zatoshis::const_from_u64(NOTE_VALUE),
        None,
    )
    .expect_err("the transfer is refused");

    assert!(
        matches!(error, ProposalRejection::InsufficientFunds { available, .. } if available == Zatoshis::const_from_u64(NOTE_VALUE)),
        "{error:?}"
    );
}

#[test]
fn a_transfer_from_a_wallet_that_has_not_scanned_requires_sync() {
    let mut st = unscanned_wallet(TestBuilder::<(), ()>::DEFAULT_NETWORK);
    let recipient = external_recipient(&st);

    let error = try_propose_payment(&mut st, recipient, Zatoshis::const_from_u64(1), None)
        .expect_err("the transfer is refused");

    assert_eq!(error, ProposalRejection::SyncRequired);
}

// The Kotlin `Proposal.totalPaymentAmount()` formula

/// The payment total the Kotlin SDK derives from a proposal's protobuf
/// (`ProposalUnsafe.totalPaymentAmount`): what the steps spend from the wallet's own notes, less
/// the fees and less the non-ephemeral change. Inputs that refer to an earlier step are not
/// counted as spent. Signed, as the Kotlin arithmetic is, so that a corrupt proposal shows as a
/// negative value rather than wrapping.
fn kotlin_total_payment_amount(proposal: &TestProposal) -> i128 {
    use zcash_client_backend::proto::proposal::{Proposal as ProtoProposal, proposed_input::Value};

    let proto = ProtoProposal::from_standard_proposal(proposal);
    let balances = proto.steps.iter().map(|step| step.balance.clone().unwrap());
    let spent: i128 = proto
        .steps
        .iter()
        .flat_map(|step| &step.inputs)
        .filter_map(|input| match &input.value {
            Some(Value::ReceivedOutput(output)) => Some(i128::from(output.value)),
            _ => None,
        })
        .sum();
    let fee: i128 = balances.clone().map(|b| i128::from(b.fee_required)).sum();
    let change: i128 = balances
        .flat_map(|b| b.proposed_change)
        .filter(|change| !change.is_ephemeral)
        .map(|change| i128::from(change.value))
        .sum();
    spent - fee - change
}

fn assert_kotlin_formula_matches_request_total(shape: &str, proposal: &TestProposal) {
    assert_eq!(
        kotlin_total_payment_amount(proposal),
        i128::from(payment_total(proposal).into_u64()),
        "{shape}: the Kotlin formula equals TransactionRequest::total()"
    );
}

#[test]
fn the_kotlin_payment_total_equals_the_request_total_of_a_send_max() {
    let shapes = [
        Shape("Orchard to Orchard", Received::Orchard, external_recipient),
        Shape("Sapling to Orchard", Received::Sapling, external_recipient),
        Shape("Orchard to Sapling", Received::Orchard, sapling_recipient),
        Shape(
            "Orchard to transparent",
            Received::Orchard,
            transparent_recipient,
        ),
        Shape("Orchard to TEX", Received::Orchard, tex_recipient),
    ];
    for Shape(shape, note, recipient) in shapes {
        let mut st = funded_wallet(note);
        let recipient = recipient(&st);
        let max = propose_max_to(&mut st, recipient);
        assert!(change_outputs(&max).is_empty(), "{shape}: no change");
        assert_kotlin_formula_matches_request_total(shape, &max);
    }
}

#[test]
fn the_kotlin_payment_total_equals_the_request_total_with_change() {
    let amount = Zatoshis::const_from_u64(30_000);
    let shapes = [
        Shape("Orchard to Orchard", Received::Orchard, external_recipient),
        Shape(
            "Orchard to transparent",
            Received::Orchard,
            transparent_recipient,
        ),
        Shape("Orchard to TEX", Received::Orchard, tex_recipient),
    ];
    for Shape(shape, note, recipient) in shapes {
        let mut st = funded_wallet(note);
        let recipient = recipient(&st);
        let payment = propose_payment(&mut st, recipient, amount, None);
        assert!(change_value(&payment) > Zatoshis::ZERO, "{shape}: change");
        assert_eq!(payment_total(&payment), amount, "{shape}: pays the amount");
        assert_kotlin_formula_matches_request_total(shape, &payment);
    }
}
