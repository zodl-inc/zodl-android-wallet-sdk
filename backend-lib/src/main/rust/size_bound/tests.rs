//! Tests for [`super::TransactionSizeBound`] and [`super::check_within`].

use zcash_client_backend::data_api::{Account as _, testing::TestBuilder};
use zcash_protocol::{
    ShieldedPool, consensus::BranchId, constants::MAX_BLOCK_BYTES, local_consensus::LocalNetwork,
    value::Zatoshis,
};

use super::*;
use crate::send_max::{
    SendMaxRequest,
    tests::{
        Received, TestProposal, TestWallet, external_recipient, funded_wallet,
        ironwood_active_network, marginal_fee, propose_max, propose_max_to, sapling_recipient,
        small_note_value, tex_recipient, transparent_recipient, wallet_with,
        wallet_with_orchard_notes_on,
    },
};

/// The default test network, on which NU6.3 (Ironwood) is not active.
const PRE_NU6_3: LocalNetwork = TestBuilder::<(), ()>::DEFAULT_NETWORK;

/// More notes than the largest action cap a phone applies (75).
const MORE_THAN_A_PHONE_PROVES: usize = 80;

/// The cap on shielded actions a phone with ample memory applies.
const PHONE_ACTIONS: usize = 75;

/// No cap, as the Kotlin side encodes it.
const NONE: i64 = -1;

fn check(
    st: &TestWallet,
    proposal: &TestProposal,
    bound: &TransactionSizeBound,
) -> Result<(), ProposalRejection> {
    check_within(proposal, st.network(), bound)
}

/// The counts [`check_within`] holds the only step of `proposal` to.
fn counts(st: &TestWallet, proposal: &TestProposal) -> StepCounts {
    assert_eq!(proposal.steps().len(), 1);
    let branch = BranchId::for_height(
        st.network(),
        BlockHeight::from(proposal.min_target_height()),
    );
    StepCounts::of(&proposal.steps().head, branch, &|_| {
        unreachable!("a single-step proposal spends no earlier output")
    })
}

#[test]
fn consensus_is_the_default_and_caps_nothing() {
    let bound = TransactionSizeBound::default();

    assert_eq!(bound, TransactionSizeBound::CONSENSUS);
    assert_eq!(bound.max_bytes(), MAX_BLOCK_BYTES);
    assert_eq!(bound.max_shielded_actions(), None);
    for pool in BOUNDED_POOLS {
        assert_eq!(bound.max_pool_actions(pool), None);
    }
    assert_eq!(bound.max_transparent_inputs(), None);
    assert_eq!(bound.max_transparent_outputs(), None);
    assert_eq!(bound.max_shielded_payment_outputs(), None);
}

#[test]
fn every_cap_is_set_by_its_own_builder() {
    let bound = TransactionSizeBound::CONSENSUS
        .with_max_bytes(250_000)
        .with_max_shielded_actions(75)
        .with_max_pool_actions(ShieldedPool::Sapling, 0)
        .with_max_pool_actions(ShieldedPool::Orchard, 32)
        .with_max_pool_actions(ShieldedPool::Ironwood, 31)
        .with_max_transparent_inputs(10)
        .with_max_transparent_outputs(9)
        .with_max_shielded_payment_outputs(4);

    assert_eq!(bound.max_bytes(), 250_000);
    assert_eq!(bound.max_shielded_actions(), Some(75));
    assert_eq!(bound.max_pool_actions(ShieldedPool::Sapling), Some(0));
    assert_eq!(bound.max_pool_actions(ShieldedPool::Orchard), Some(32));
    assert_eq!(bound.max_pool_actions(ShieldedPool::Ironwood), Some(31));
    assert_eq!(bound.max_transparent_inputs(), Some(10));
    assert_eq!(bound.max_transparent_outputs(), Some(9));
    assert_eq!(bound.max_shielded_payment_outputs(), Some(4));
}

#[test]
fn the_byte_limit_is_never_raised_above_a_block() {
    let bound = TransactionSizeBound::CONSENSUS.with_max_bytes(MAX_BLOCK_BYTES * 2);

    assert_eq!(bound, TransactionSizeBound::CONSENSUS);
}

#[test]
fn intersect_takes_the_stricter_of_each_limit() {
    let relay = TransactionSizeBound::CONSENSUS
        .with_max_bytes(250_000)
        .with_max_pool_actions(ShieldedPool::Orchard, 40);
    let signer = TransactionSizeBound::CONSENSUS
        .with_max_shielded_actions(48)
        .with_max_pool_actions(ShieldedPool::Orchard, 10)
        .with_max_transparent_inputs(10);

    let both = relay.intersect(&signer);

    assert_eq!(both, signer.intersect(&relay));
    assert_eq!(both.max_bytes(), 250_000);
    assert_eq!(both.max_shielded_actions(), Some(48));
    assert_eq!(both.max_pool_actions(ShieldedPool::Orchard), Some(10));
    assert_eq!(both.max_pool_actions(ShieldedPool::Sapling), None);
    assert_eq!(both.max_transparent_inputs(), Some(10));
    assert_eq!(both.max_transparent_outputs(), None);
    assert_eq!(relay.intersect(&TransactionSizeBound::CONSENSUS), relay);
}

#[test]
fn a_null_bound_decodes_as_consensus() {
    assert_eq!(
        TransactionSizeBound::decode(None).unwrap(),
        TransactionSizeBound::CONSENSUS
    );
    assert_eq!(
        TransactionSizeBound::decode(Some(&[NONE; ENCODED_LEN])).unwrap(),
        TransactionSizeBound::CONSENSUS
    );
}

#[test]
fn the_jni_encoding_is_read_in_its_fixed_order() {
    let bound = TransactionSizeBound::decode(Some(&[1_000, 75, 0, 32, 31, 10, 9, 4])).unwrap();

    assert_eq!(
        bound,
        TransactionSizeBound::CONSENSUS
            .with_max_bytes(1_000)
            .with_max_shielded_actions(75)
            .with_max_pool_actions(ShieldedPool::Sapling, 0)
            .with_max_pool_actions(ShieldedPool::Orchard, 32)
            .with_max_pool_actions(ShieldedPool::Ironwood, 31)
            .with_max_transparent_inputs(10)
            .with_max_transparent_outputs(9)
            .with_max_shielded_payment_outputs(4)
    );
}

#[test]
fn a_malformed_encoding_is_refused() {
    assert!(TransactionSizeBound::decode(Some(&[NONE; ENCODED_LEN - 1])).is_err());
    assert!(TransactionSizeBound::decode(Some(&[NONE; ENCODED_LEN + 1])).is_err());
    // The message names the number of values received, then the number expected.
    assert_eq!(
        TransactionSizeBound::decode(Some(&[NONE; 5]))
            .unwrap_err()
            .to_string(),
        format!("A transaction size bound has 5 values, not {ENCODED_LEN}")
    );
    let mut negative = [NONE; ENCODED_LEN];
    negative[1] = -2;
    assert!(TransactionSizeBound::decode(Some(&negative)).is_err());
}

#[test]
fn consensus_accepts_what_consensus_accepts() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = external_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);

    assert!(proposal.check_transaction_size().is_ok());
    assert_eq!(
        check(&st, &proposal, &TransactionSizeBound::CONSENSUS),
        Ok(())
    );
}

/// The phone's proving cap refuses a Send Max of more notes than it can prove, naming the count
/// of shielded actions: before NU6.3 an Orchard action carries a spend and an output, so the
/// count is the number of notes spent.
#[test]
fn a_send_max_over_the_shielded_action_cap_is_refused_with_its_count() {
    let mut st = wallet_with_orchard_notes_on(PRE_NU6_3, MORE_THAN_A_PHONE_PROVES);
    let recipient = external_recipient(&st);
    let bound = TransactionSizeBound::CONSENSUS.with_max_shielded_actions(PHONE_ACTIONS);

    let error = propose_max(
        &mut st,
        SendMaxRequest::spendable_to(recipient.clone(), None).within(bound),
    )
    .expect_err("the send-max is refused");

    assert_eq!(
        error,
        ProposalRejection::ExceedsSizeBound {
            step_index: 0,
            violation: SizeBoundViolation::ShieldedActions {
                count: MORE_THAN_A_PHONE_PROVES,
                limit: PHONE_ACTIONS,
            },
        }
    );

    // Without the bound, the same wallet proposes, and its counts are what the bound judged.
    let proposal = propose_max_to(&mut st, recipient);
    assert_eq!(
        counts(&st, &proposal).shielded_actions(),
        MORE_THAN_A_PHONE_PROVES
    );
}

#[test]
fn a_send_max_within_every_cap_is_proposed() {
    let mut st = wallet_with_orchard_notes_on(PRE_NU6_3, MORE_THAN_A_PHONE_PROVES);
    let recipient = external_recipient(&st);
    let bound = TransactionSizeBound::CONSENSUS
        .with_max_bytes(MAX_BLOCK_BYTES / 2)
        .with_max_shielded_actions(MORE_THAN_A_PHONE_PROVES)
        .with_max_pool_actions(ShieldedPool::Orchard, MORE_THAN_A_PHONE_PROVES)
        .with_max_pool_actions(ShieldedPool::Sapling, 0)
        .with_max_transparent_inputs(0)
        .with_max_transparent_outputs(0)
        .with_max_shielded_payment_outputs(1);

    let proposal = propose_max(
        &mut st,
        SendMaxRequest::spendable_to(recipient, None).within(bound),
    )
    .expect("the send-max fits");

    assert_eq!(
        proposal.input_count_in_pool(PoolType::ORCHARD),
        MORE_THAN_A_PHONE_PROVES
    );
}

#[test]
fn a_zero_pool_cap_forbids_the_pool() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = external_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);
    let orchard_actions = counts(&st, &proposal).pool_actions[pool_index(ShieldedPool::Orchard)];

    let error = check(
        &st,
        &proposal,
        &TransactionSizeBound::CONSENSUS.with_max_pool_actions(ShieldedPool::Orchard, 0),
    );

    assert_eq!(
        error,
        Err(ProposalRejection::ExceedsSizeBound {
            step_index: 0,
            violation: SizeBoundViolation::PoolActions {
                pool: ShieldedPool::Orchard,
                count: orchard_actions,
                limit: 0,
            },
        })
    );
    // A cap on a pool the transaction does not use does not refuse it.
    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_pool_actions(ShieldedPool::Sapling, 0),
        ),
        Ok(())
    );
}

/// A Sapling output is padded to the Sapling builder's two-output minimum, and the bound counts
/// the padding, as ZIP 218 does.
#[test]
fn sapling_padding_is_counted() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = sapling_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);

    let counts = counts(&st, &proposal);

    assert_eq!(counts.pool_actions[pool_index(ShieldedPool::Sapling)], 2);
    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_pool_actions(ShieldedPool::Sapling, 1),
        ),
        Err(ProposalRejection::ExceedsSizeBound {
            step_index: 0,
            violation: SizeBoundViolation::PoolActions {
                pool: ShieldedPool::Sapling,
                count: 2,
                limit: 1,
            },
        })
    );
}

#[test]
fn a_transparent_payment_counts_as_a_transparent_output_not_a_shielded_one() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = transparent_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);

    let counts = counts(&st, &proposal);
    assert_eq!(counts.transparent_outputs, 1);
    assert_eq!(counts.transparent_inputs, 0);
    assert_eq!(counts.shielded_payment_outputs, 0);

    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_transparent_outputs(0),
        ),
        Err(ProposalRejection::ExceedsSizeBound {
            step_index: 0,
            violation: SizeBoundViolation::TransparentOutputs { count: 1, limit: 0 },
        })
    );
    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_shielded_payment_outputs(0),
        ),
        Ok(())
    );
}

#[test]
fn a_shielded_payment_counts_against_the_payment_output_cap() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = external_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);

    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_shielded_payment_outputs(0),
        ),
        Err(ProposalRejection::ExceedsSizeBound {
            step_index: 0,
            violation: SizeBoundViolation::ShieldedPaymentOutputs { count: 1, limit: 0 },
        })
    );
}

/// A byte limit below a block is reported as consensus' own is, so a caller handles a relay's
/// limit as it handles a block's.
#[test]
fn a_byte_limit_below_a_block_is_reported_as_too_large() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = external_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);
    let size = proposal.estimated_serialized_size();

    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_bytes(size - 1),
        ),
        Err(ProposalRejection::TransactionTooLarge)
    );
    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_bytes(size)
        ),
        Ok(())
    );
}

/// From NU6.3 the Orchard pool disables cross-address transfers, so a spend and an output no
/// longer share an action: the bound counts the actions the builder will produce.
#[test]
fn orchard_actions_follow_the_consensus_rules_of_the_target_height() {
    let notes = 3;
    let mut st = wallet_with(ironwood_active_network(), &[Received::Orchard(100_000); 3]);
    let recipient = external_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);

    let counts = counts(&st, &proposal);

    assert_eq!(
        counts.pool_actions[pool_index(ShieldedPool::Orchard)]
            + counts.pool_actions[pool_index(ShieldedPool::Ironwood)],
        counts.shielded_actions()
    );
    assert!(
        counts.shielded_actions() > notes,
        "spends and the payment each take an action: {counts:?}"
    );
}

/// The ordinary transfer is held to the bound its caller passes, and refused with the same
/// typed category, so the review screen's re-proposal of a Send Max fails as the Send Max does.
#[test]
fn a_transfer_over_the_bound_is_refused_with_the_same_category() {
    let mut st = wallet_with_orchard_notes_on(PRE_NU6_3, MORE_THAN_A_PHONE_PROVES);
    let recipient = external_recipient(&st);
    let account = st.test_account().unwrap().id();
    let network = *st.network();
    let bound = TransactionSizeBound::CONSENSUS.with_max_shielded_actions(PHONE_ACTIONS);

    let error = crate::propose_transfer_inner(
        st.wallet_mut(),
        &network,
        account,
        recipient,
        // Needs nearly every note: what 78 of them net after the marginal fee each costs to spend.
        Zatoshis::const_from_u64(78 * (small_note_value() - marginal_fee())),
        None,
        &bound,
    )
    .expect_err("the transfer is refused");

    assert!(
        matches!(
            error.downcast_ref::<ProposalRejection>(),
            Some(ProposalRejection::ExceedsSizeBound {
                violation: SizeBoundViolation::ShieldedActions {
                    limit: PHONE_ACTIONS,
                    ..
                },
                ..
            })
        ),
        "{error:?}"
    );
}

#[test]
fn violations_cross_the_jni_boundary_as_their_fixed_dimensions() {
    let cases = [
        (
            SizeBoundViolation::ShieldedActions { count: 2, limit: 1 },
            "shielded_actions",
            None,
        ),
        (
            SizeBoundViolation::PoolActions {
                pool: ShieldedPool::Ironwood,
                count: 2,
                limit: 1,
            },
            "pool_actions",
            Some(ShieldedPool::Ironwood),
        ),
        (
            SizeBoundViolation::TransparentInputs { count: 2, limit: 1 },
            "transparent_inputs",
            None,
        ),
        (
            SizeBoundViolation::TransparentOutputs { count: 2, limit: 1 },
            "transparent_outputs",
            None,
        ),
        (
            SizeBoundViolation::ShieldedPaymentOutputs { count: 2, limit: 1 },
            "shielded_payment_outputs",
            None,
        ),
    ];

    for (violation, dimension, pool) in cases {
        assert_eq!(violation.dimension(), dimension);
        assert_eq!(violation.pool(), pool);
        assert_eq!(violation.count_and_limit(), (2, 1));
    }
}

/// A send to a TEX address takes two transactions; a byte limit holds each of them, as consensus'
/// own does, not their sum.
#[test]
fn a_byte_limit_holds_each_transaction_of_a_multi_step_proposal() {
    let mut st = funded_wallet(Received::Orchard);
    let recipient = tex_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);

    let sizes = step_serialized_sizes(&proposal);
    assert_eq!(sizes.len(), 2);
    assert_eq!(
        sizes.iter().sum::<usize>(),
        proposal.estimated_serialized_size()
    );
    let largest = *sizes.iter().max().unwrap();
    assert!(largest < proposal.estimated_serialized_size());

    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_bytes(largest)
        ),
        Ok(())
    );
    assert_eq!(
        check(
            &st,
            &proposal,
            &TransactionSizeBound::CONSENSUS.with_max_bytes(largest - 1),
        ),
        Err(ProposalRejection::TransactionTooLarge)
    );
}

/// The canonical shapes, counted as the builder builds them: before NU6.3 an Orchard action
/// carries a spend and an output, so three notes to one recipient take three actions; one Sapling
/// spend to one Sapling output takes one spend and the two-output minimum. Upstream's
/// `check_transaction_size_within` counts the same shapes conservatively (each spend and output
/// plus the two-action padding: six and six), so a cap tightens by about two once this check
/// collapses to it.
#[test]
fn canonical_shapes_are_counted_as_built() {
    let mut st = wallet_with(PRE_NU6_3, &[Received::Orchard(100_000); 3]);
    let recipient = external_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);
    assert_eq!(counts(&st, &proposal).pool_actions, [0, 3, 0]);

    let mut st = funded_wallet(Received::Sapling);
    let recipient = sapling_recipient(&st);
    let proposal = propose_max_to(&mut st, recipient);
    assert_eq!(counts(&st, &proposal).pool_actions, [3, 0, 0]);
}
