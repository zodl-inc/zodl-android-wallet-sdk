//! Characterization tests for the two Orchard to Ironwood migration lanes that send the maximum:
//! the immediate migration ([`super::propose_immediate_send_max`]) and the engine's one-shot
//! crossing ([`crate::migration_send_max::propose_orchard_to_ironwood`]).
//!
//! They pin the behaviour both lanes have on `main`, so that no change to the user's Send Max
//! (`crate::send_max`) or to proposal error classification (`crate::proposal_error`) reaches
//! a migration: each lane keeps its own spend mode and gate, is not refused for size when
//! proposed, and reports failures as its own untyped `anyhow` text, never as a
//! [`ProposalRejection`].

use std::collections::BTreeSet;

use zcash_address::ZcashAddress;
use zcash_client_backend::{
    data_api::{Account as _, testing::TestBuilder},
    proposal::ProposalError,
};
use zcash_protocol::{ShieldedPool, consensus::Parameters, local_consensus::LocalNetwork};

use crate::proposal_error::ProposalRejection;
use crate::send_max::tests::{
    CONFIRMATIONS, NOTE_VALUE, Received, TestProposal, TestWallet, ironwood_active_network,
    mine_empty_blocks, receive, scan_from, spent_notes, spent_pools, unscanned_wallet, wallet_with,
    wallet_with_orchard_notes_on, wallet_with_too_many_notes_on,
};

/// The default test network, on which NU6.3 (Ironwood) is not active.
const PRE_NU6_3: LocalNetwork = TestBuilder::<(), ()>::DEFAULT_NETWORK;

/// The immediate migration's proposal for the test wallet's account.
fn propose_immediate(st: &mut TestWallet) -> anyhow::Result<TestProposal> {
    let account = st.test_account().unwrap().id();
    let network = *st.network();
    super::propose_immediate_send_max(&network, st.wallet_mut(), account)
}

/// The engine lane's one-shot crossing proposal for the test wallet's account.
fn propose_engine(st: &mut TestWallet) -> anyhow::Result<TestProposal> {
    let account = st.test_account().unwrap().id();
    let network = *st.network();
    crate::migration_send_max::propose_orchard_to_ironwood(st.wallet_mut(), &network, account)
}

/// The account's own internal Orchard receiver, which both lanes pay.
fn own_internal_receiver(st: &TestWallet) -> ZcashAddress {
    let fvk = orchard::keys::FullViewingKey::from(st.test_account().unwrap().usk().orchard());
    zcash_client_backend::address::Receiver::Orchard(
        fvk.address_at(0u32, orchard::keys::Scope::Internal),
    )
    .to_zcash_address(st.network().network_type())
}

/// Asserts `proposal` is a single transaction paying only `recipient`, with no memo.
fn assert_pays_only_without_memo(proposal: &TestProposal, recipient: &ZcashAddress) {
    assert_eq!(proposal.steps().len(), 1);
    let payments = proposal.steps().head.transaction_request().payments();
    assert_eq!(payments.len(), 1);
    let payment = payments.values().next().unwrap();
    assert_eq!(payment.recipient_address(), recipient);
    assert_eq!(payment.memo(), None);
}

/// Asserts that `error` is plain `anyhow` text starting with `prefix`, carrying no
/// [`ProposalRejection`] for the JNI layer to throw as a typed exception.
fn assert_untyped(error: &anyhow::Error, prefix: &str) {
    assert!(
        error.to_string().starts_with(prefix),
        "expected {prefix:?}, got {error}"
    );
    assert!(
        error.downcast_ref::<ProposalRejection>().is_none(),
        "a migration error must not be a classified ProposalRejection: {error:?}"
    );
}

/// A wallet on `network` with one spendable Orchard note, then one Orchard and one Sapling note
/// that are not yet spendable.
fn wallet_with_an_unconfirmed_orchard_note(network: LocalNetwork) -> TestWallet {
    let mut st = unscanned_wallet(network);
    let first = receive(&mut st, Received::Orchard(NOTE_VALUE));
    mine_empty_blocks(&mut st, CONFIRMATIONS);
    receive(&mut st, Received::Orchard(NOTE_VALUE));
    receive(&mut st, Received::Sapling(NOTE_VALUE));
    scan_from(&mut st, first);
    st
}

// Immediate lane: `MaxSpendable`, no NU6.3 gate.

/// `MaxSpendable` over Orchard only: the spendable Orchard note is swept to the account's own
/// internal receiver without a memo, and the unconfirmed Orchard note and the Sapling note are
/// left behind rather than failing the proposal.
#[test]
fn immediate_lane_sweeps_spendable_orchard_only_and_leaves_the_rest() {
    let mut st = wallet_with_an_unconfirmed_orchard_note(ironwood_active_network());

    let proposal = propose_immediate(&mut st).expect("MaxSpendable leaves the unconfirmed note");

    assert_eq!(spent_notes(&proposal).len(), 1);
    assert_eq!(
        spent_pools(&proposal),
        BTreeSet::from([ShieldedPool::Orchard])
    );
    assert_pays_only_without_memo(&proposal, &own_internal_receiver(&st));
}

/// The immediate lane has no NU6.3 activation gate.
#[test]
fn immediate_lane_proposes_before_nu6_3() {
    let mut st = wallet_with(PRE_NU6_3, &[Received::Orchard(NOTE_VALUE)]);

    let proposal = propose_immediate(&mut st).expect("no NU6.3 gate on the immediate lane");

    assert_eq!(
        spent_pools(&proposal),
        BTreeSet::from([ShieldedPool::Orchard])
    );
}

/// The immediate lane is not refused for size when proposed: a sweep of more notes than fit
/// in a block still proposes (it fails only when its transaction is built).
#[test]
fn immediate_lane_has_no_proposal_time_size_gate() {
    let mut st = wallet_with_too_many_notes_on(PRE_NU6_3);

    let proposal = propose_immediate(&mut st).expect("no proposal-time size gate");

    // The error the build step reports, which reaches Kotlin as main's
    // `TransactionNotCreatedException` (see `TransactionEncoderImplAnchorNotFoundTest`).
    assert!(matches!(
        proposal.check_transaction_size(),
        Err(ProposalError::TransactionTooLarge { .. })
    ));
}

/// Nothing spendable: the immediate lane's own `Debug`-formatted text, not a typed rejection.
#[test]
fn immediate_lane_reports_insufficient_funds_as_its_own_text() {
    let mut st = wallet_with(PRE_NU6_3, &[]);

    let error = propose_immediate(&mut st).expect_err("nothing to sweep");

    assert_untyped(
        &error,
        "Error proposing immediate send-max: InsufficientFunds",
    );
}

// Engine lane: `Everything`, NU6.3 gate.

/// `Everything` over Orchard only: a spendable note is swept to the account's own internal
/// receiver without a memo, but an unconfirmed Orchard note fails the proposal rather than being
/// left behind; an unconfirmed Sapling note does not.
#[test]
fn engine_lane_refuses_to_leave_an_orchard_note_behind() {
    let mut st = wallet_with(ironwood_active_network(), &[Received::Orchard(NOTE_VALUE)]);
    let proposal = propose_engine(&mut st).expect("everything in Orchard is spendable");
    assert_eq!(
        spent_pools(&proposal),
        BTreeSet::from([ShieldedPool::Orchard])
    );
    assert_pays_only_without_memo(&proposal, &own_internal_receiver(&st));

    let mut st = wallet_with_an_unconfirmed_orchard_note(ironwood_active_network());
    let error = propose_engine(&mut st).expect_err("Everything refuses the unconfirmed note");
    assert_untyped(&error, "Error creating the migration proposal: ");
}

/// The engine lane refuses to propose before NU6.3 is active.
#[test]
fn engine_lane_is_gated_on_nu6_3() {
    let mut st = wallet_with(PRE_NU6_3, &[Received::Orchard(NOTE_VALUE)]);

    let error = propose_engine(&mut st).expect_err("NU6.3 is not active");

    assert_eq!(
        error.to_string(),
        "Ironwood (NU6.3) is not active yet; there is nothing to migrate to."
    );
    assert!(error.downcast_ref::<ProposalRejection>().is_none());
}

/// The engine lane is not refused for size when proposed either.
#[test]
fn engine_lane_has_no_proposal_time_size_gate() {
    let mut st = wallet_with_too_many_notes_on(ironwood_active_network());

    let proposal = propose_engine(&mut st).expect("no proposal-time size gate");

    // The error the build step reports, which reaches Kotlin as main's
    // `TransactionNotCreatedException` (see `TransactionEncoderImplAnchorNotFoundTest`).
    assert!(matches!(
        proposal.check_transaction_size(),
        Err(ProposalError::TransactionTooLarge { .. })
    ));
}

/// Nothing spendable: the engine lane's own `Display`-formatted text, not a typed rejection.
#[test]
fn engine_lane_reports_insufficient_funds_as_its_own_text() {
    let mut st = wallet_with(ironwood_active_network(), &[]);

    let error = propose_engine(&mut st).expect_err("nothing to migrate");

    assert_untyped(
        &error,
        "Error creating the migration proposal: Insufficient balance",
    );
}

// Size bounds: never applied to a migration lane.

/// More Orchard notes than the largest shielded-action bound a phone applies to the user's Send
/// Max (75).
const MORE_NOTES_THAN_A_SEND_BOUND: usize = 80;

/// A wallet holding more Orchard notes than a phone's bound lets the user's Send Max spend still
/// migrates them all, in either lane: a transaction size bound is applied to the user's sends
/// only, never to a migration. The user's Send Max of the same wallet under that bound is
/// refused, so the wallet is one the bound would act on.
#[test]
fn both_lanes_still_propose_more_notes_than_a_send_bound_allows() {
    let bound = crate::size_bound::TransactionSizeBound::CONSENSUS.with_max_shielded_actions(75);
    let mut st =
        wallet_with_orchard_notes_on(ironwood_active_network(), MORE_NOTES_THAN_A_SEND_BOUND);

    let recipient = crate::send_max::tests::external_recipient(&st);
    let error = crate::send_max::tests::propose_max(
        &mut st,
        crate::send_max::SendMaxRequest::spendable_to(recipient, None).within(bound),
    )
    .expect_err("the bounded Send Max is refused");
    assert!(matches!(error, ProposalRejection::ExceedsSizeBound { .. }));

    let immediate = propose_immediate(&mut st).expect("the immediate lane proposes");
    assert_eq!(spent_notes(&immediate).len(), MORE_NOTES_THAN_A_SEND_BOUND);
    let engine = propose_engine(&mut st).expect("the engine lane proposes");
    assert_eq!(spent_notes(&engine).len(), MORE_NOTES_THAN_A_SEND_BOUND);
}
