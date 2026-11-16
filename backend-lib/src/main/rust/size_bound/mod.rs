//! Bounding a proposal's transactions below what consensus allows.
//!
//! A wallet may need its transactions to be smaller than a block: a relay may refuse
//! transactions above its own mempool limit, proving a transaction takes memory in proportion to
//! its shielded actions, and an external signer may only accept so many inputs or outputs. A
//! [`TransactionSizeBound`] states those limits, and [`check_within`] holds a proposal to them.
//!
//! The shape mirrors the bound `zcash_client_backend` is gaining upstream
//! (`zcash_client_backend::proposal::TransactionSizeBound` with
//! `Proposal::check_transaction_size_within`). Until a release carries it, [`check_within`]
//! evaluates the bound with the counters and the size estimator the pinned release already
//! exposes. Once it does, [`check_within`] becomes a single call to the upstream check and this
//! type a thin conversion into the upstream one; every caller stays as it is.
//!
//! The bound is applied to the user's transfers, Send Max and gift card sweep, and to creating
//! their transactions or PCZT. It is never applied to shielding or to the Orchard to Ironwood
//! migration lanes.

use anyhow::anyhow;
use nonempty::NonEmpty;
use orchard::ValuePool;
use zcash_client_backend::proposal::{Proposal, Step, StepOutput, StepOutputIndex};
use zcash_primitives::transaction::{
    builder::BundlePadding, components::orchard::bundle_version_for_branch,
};
use zcash_protocol::{
    PoolType, ShieldedPool,
    consensus::{BlockHeight, BranchId, Parameters},
    constants::MAX_BLOCK_BYTES,
};

use crate::proposal_error::{ProposalRejection, classify_proposal_error};

#[cfg(test)]
mod tests;

/// The shielded pools a bound can cap individually, in the order of [`TransactionSizeBound`]'s
/// per-pool caps and of its encoding over JNI.
const BOUNDED_POOLS: [ShieldedPool; 3] = [
    ShieldedPool::Sapling,
    ShieldedPool::Orchard,
    ShieldedPool::Ironwood,
];

/// A builder setting one cap of a [`TransactionSizeBound`].
type WithCap = fn(TransactionSizeBound, usize) -> TransactionSizeBound;

/// The number of values a bound is encoded as over JNI (see [`TransactionSizeBound::decode`]).
pub(crate) const ENCODED_LEN: usize = 8;

/// The limits every transaction of a proposal must stay within.
///
/// [`TransactionSizeBound::CONSENSUS`] (also the [`Default`]) is consensus alone: a transaction
/// no larger than a block, and no cap on any count. Every other bound is at least as strict as
/// consensus, since the byte limit is never raised above a block.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TransactionSizeBound {
    max_bytes: usize,
    max_shielded_actions: Option<usize>,
    max_pool_actions: [Option<usize>; 3],
    max_transparent_inputs: Option<usize>,
    max_transparent_outputs: Option<usize>,
    max_shielded_payment_outputs: Option<usize>,
}

impl Default for TransactionSizeBound {
    fn default() -> Self {
        Self::CONSENSUS
    }
}

impl TransactionSizeBound {
    /// Consensus only: [`MAX_BLOCK_BYTES`], and no count caps.
    pub(crate) const CONSENSUS: Self = Self {
        max_bytes: MAX_BLOCK_BYTES,
        max_shielded_actions: None,
        max_pool_actions: [None; 3],
        max_transparent_inputs: None,
        max_transparent_outputs: None,
        max_shielded_payment_outputs: None,
    };

    /// Caps each transaction's estimated serialized size. A cap above a block is a block.
    pub(crate) const fn with_max_bytes(mut self, n: usize) -> Self {
        self.max_bytes = if n < MAX_BLOCK_BYTES {
            n
        } else {
            MAX_BLOCK_BYTES
        };
        self
    }

    /// Caps Sapling spends and outputs plus Orchard and Ironwood actions, counted after padding
    /// (the ZIP 218 metric).
    pub(crate) const fn with_max_shielded_actions(mut self, n: usize) -> Self {
        self.max_shielded_actions = Some(n);
        self
    }

    /// Caps one pool's actions (for Sapling, its spends plus outputs). `0` forbids the pool.
    pub(crate) const fn with_max_pool_actions(mut self, pool: ShieldedPool, n: usize) -> Self {
        self.max_pool_actions[pool_index(pool)] = Some(n);
        self
    }

    /// Caps each transaction's transparent inputs.
    pub(crate) const fn with_max_transparent_inputs(mut self, n: usize) -> Self {
        self.max_transparent_inputs = Some(n);
        self
    }

    /// Caps each transaction's transparent outputs, change included.
    pub(crate) const fn with_max_transparent_outputs(mut self, n: usize) -> Self {
        self.max_transparent_outputs = Some(n);
        self
    }

    /// Caps each transaction's shielded outputs that are payments rather than the wallet's own
    /// change.
    pub(crate) const fn with_max_shielded_payment_outputs(mut self, n: usize) -> Self {
        self.max_shielded_payment_outputs = Some(n);
        self
    }

    /// The component-wise minimum of two bounds: a transaction within it is within both.
    ///
    /// The Kotlin `TransactionSizeBound` intersects its bounds before they cross JNI, so only
    /// the tests use this; it is kept so that this type keeps the upstream type's shape.
    #[cfg_attr(not(test), allow(dead_code))]
    pub(crate) fn intersect(&self, other: &Self) -> Self {
        Self {
            max_bytes: self.max_bytes.min(other.max_bytes),
            max_shielded_actions: min_cap(self.max_shielded_actions, other.max_shielded_actions),
            max_pool_actions: [0, 1, 2]
                .map(|i| min_cap(self.max_pool_actions[i], other.max_pool_actions[i])),
            max_transparent_inputs: min_cap(
                self.max_transparent_inputs,
                other.max_transparent_inputs,
            ),
            max_transparent_outputs: min_cap(
                self.max_transparent_outputs,
                other.max_transparent_outputs,
            ),
            max_shielded_payment_outputs: min_cap(
                self.max_shielded_payment_outputs,
                other.max_shielded_payment_outputs,
            ),
        }
    }

    pub(crate) fn max_bytes(&self) -> usize {
        self.max_bytes
    }

    pub(crate) fn max_shielded_actions(&self) -> Option<usize> {
        self.max_shielded_actions
    }

    pub(crate) fn max_pool_actions(&self, pool: ShieldedPool) -> Option<usize> {
        self.max_pool_actions[pool_index(pool)]
    }

    pub(crate) fn max_transparent_inputs(&self) -> Option<usize> {
        self.max_transparent_inputs
    }

    pub(crate) fn max_transparent_outputs(&self) -> Option<usize> {
        self.max_transparent_outputs
    }

    pub(crate) fn max_shielded_payment_outputs(&self) -> Option<usize> {
        self.max_shielded_payment_outputs
    }

    /// Decodes the bound the Kotlin `TransactionSizeBound` sends over JNI: `None` (a null array)
    /// is [`TransactionSizeBound::CONSENSUS`]; otherwise exactly [`ENCODED_LEN`] values, in the
    /// order max bytes, max shielded actions, max Sapling, Orchard and Ironwood actions, max
    /// transparent inputs, max transparent outputs, max shielded payment outputs, where `-1`
    /// means no cap.
    pub(crate) fn decode(encoded: Option<&[i64]>) -> anyhow::Result<Self> {
        let Some(values) = encoded else {
            return Ok(Self::CONSENSUS);
        };
        let values: &[i64; ENCODED_LEN] = values.try_into().map_err(|_| {
            anyhow!(
                "A transaction size bound has {} values, not {}",
                values.len(),
                ENCODED_LEN
            )
        })?;
        let cap = |value: i64| -> anyhow::Result<Option<usize>> {
            match value {
                -1 => Ok(None),
                n => usize::try_from(n)
                    .map(Some)
                    .map_err(|_| anyhow!("A transaction size bound cap is out of range")),
            }
        };
        let [
            bytes,
            actions,
            sapling,
            orchard,
            ironwood,
            t_in,
            t_out,
            payments,
        ] = *values;
        let caps: [(i64, WithCap); ENCODED_LEN] = [
            (bytes, Self::with_max_bytes),
            (actions, Self::with_max_shielded_actions),
            (sapling, |b, n| {
                b.with_max_pool_actions(ShieldedPool::Sapling, n)
            }),
            (orchard, |b, n| {
                b.with_max_pool_actions(ShieldedPool::Orchard, n)
            }),
            (ironwood, |b, n| {
                b.with_max_pool_actions(ShieldedPool::Ironwood, n)
            }),
            (t_in, Self::with_max_transparent_inputs),
            (t_out, Self::with_max_transparent_outputs),
            (payments, Self::with_max_shielded_payment_outputs),
        ];
        let mut bound = Self::CONSENSUS;
        for (value, with) in caps {
            if let Some(n) = cap(value)? {
                bound = with(bound, n);
            }
        }
        Ok(bound)
    }
}

/// The index of `pool`'s cap in [`TransactionSizeBound::max_pool_actions`].
const fn pool_index(pool: ShieldedPool) -> usize {
    match pool {
        ShieldedPool::Sapling => 0,
        ShieldedPool::Orchard => 1,
        ShieldedPool::Ironwood => 2,
    }
}

/// The stricter of two optional caps, `None` being no cap.
#[cfg_attr(not(test), allow(dead_code))]
fn min_cap(a: Option<usize>, b: Option<usize>) -> Option<usize> {
    match (a, b) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (a, None) => a,
        (None, b) => b,
    }
}

/// Which count limit of a [`TransactionSizeBound`] a transaction exceeds. The byte limit is
/// reported as [`ProposalRejection::TransactionTooLarge`], as consensus' own is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SizeBoundViolation {
    ShieldedActions {
        count: usize,
        limit: usize,
    },
    PoolActions {
        pool: ShieldedPool,
        count: usize,
        limit: usize,
    },
    TransparentInputs {
        count: usize,
        limit: usize,
    },
    TransparentOutputs {
        count: usize,
        limit: usize,
    },
    ShieldedPaymentOutputs {
        count: usize,
        limit: usize,
    },
}

impl SizeBoundViolation {
    /// The stable name of the exceeded limit. These strings are the JNI contract with the Kotlin
    /// `ProposalSizeBoundException`.
    pub(crate) fn dimension(&self) -> &'static str {
        match self {
            SizeBoundViolation::ShieldedActions { .. } => "shielded_actions",
            SizeBoundViolation::PoolActions { .. } => "pool_actions",
            SizeBoundViolation::TransparentInputs { .. } => "transparent_inputs",
            SizeBoundViolation::TransparentOutputs { .. } => "transparent_outputs",
            SizeBoundViolation::ShieldedPaymentOutputs { .. } => "shielded_payment_outputs",
        }
    }

    /// The pool whose cap is exceeded, for [`SizeBoundViolation::PoolActions`].
    pub(crate) fn pool(&self) -> Option<ShieldedPool> {
        match self {
            SizeBoundViolation::PoolActions { pool, .. } => Some(*pool),
            _ => None,
        }
    }

    /// The transaction's count, and the limit it exceeds.
    pub(crate) fn count_and_limit(&self) -> (usize, usize) {
        match *self {
            SizeBoundViolation::ShieldedActions { count, limit }
            | SizeBoundViolation::PoolActions { count, limit, .. }
            | SizeBoundViolation::TransparentInputs { count, limit }
            | SizeBoundViolation::TransparentOutputs { count, limit }
            | SizeBoundViolation::ShieldedPaymentOutputs { count, limit } => (count, limit),
        }
    }
}

/// Holds every transaction of `proposal` to `bound`.
///
/// Consensus is always checked first, exactly as `Proposal::check_transaction_size` checks it, so
/// [`TransactionSizeBound::CONSENSUS`] behaves as that check does. A byte limit below a block is
/// held against each transaction's estimated serialized size (see [`step_serialized_sizes`]), as
/// consensus' own is. A count limit is reported as
/// [`ProposalRejection::ExceedsSizeBound`] for the first transaction, and the first limit in the
/// order of [`SizeBoundViolation`], that is exceeded.
pub(crate) fn check_within<P, FeeRuleT, NoteRef>(
    proposal: &Proposal<FeeRuleT, NoteRef>,
    params: &P,
    bound: &TransactionSizeBound,
) -> Result<(), ProposalRejection>
where
    P: Parameters,
    FeeRuleT: Clone,
    NoteRef: Clone,
{
    proposal
        .check_transaction_size()
        .map_err(classify_proposal_error)?;
    if *bound == TransactionSizeBound::CONSENSUS {
        return Ok(());
    }

    if bound.max_bytes() < MAX_BLOCK_BYTES
        && step_serialized_sizes(proposal)
            .into_iter()
            .any(|size| size > bound.max_bytes())
    {
        return Err(ProposalRejection::TransactionTooLarge);
    }

    let branch = BranchId::for_height(params, BlockHeight::from(proposal.min_target_height()));
    let steps = proposal.steps();
    for (step_index, step) in steps.iter().enumerate() {
        let counts = StepCounts::of(step, branch, &|s_ref| prior_output_pool(steps, s_ref));
        if let Some(violation) = counts.first_violation(bound) {
            return Err(ProposalRejection::ExceedsSizeBound {
                step_index,
                violation,
            });
        }
    }
    Ok(())
}

/// The estimated serialized size of each transaction of `proposal`, in step order.
///
/// `zcash_client_backend` estimates each step (`Step::estimated_serialized_size`) but exposes only
/// their sum (`Proposal::estimated_serialized_size`) in this release. A step's own estimate is
/// therefore read as the difference between the sums of the proposals made of the steps up to it
/// and up to the one before: every step's estimate depends only on itself and on the earlier
/// steps whose outputs it spends, so each difference is exactly that step's estimate.
pub(crate) fn step_serialized_sizes<FeeRuleT, NoteRef>(
    proposal: &Proposal<FeeRuleT, NoteRef>,
) -> Vec<usize>
where
    FeeRuleT: Clone,
    NoteRef: Clone,
{
    let steps = proposal.steps();
    if steps.len() == 1 {
        return vec![proposal.estimated_serialized_size()];
    }
    let mut sizes = Vec::with_capacity(steps.len());
    let mut total_before = 0;
    for end in 1..=steps.len() {
        let prefix = NonEmpty::from_vec(steps.iter().take(end).cloned().collect())
            .expect("a prefix of a non-empty proposal has a step");
        let total = Proposal::multi_step(
            proposal.fee_rule().clone(),
            proposal.min_target_height(),
            proposal.confirmations_policy(),
            prefix,
        )
        // A prefix of a valid proposal is valid: dropping later steps removes no reference an
        // earlier step depends on. Fall back to the whole proposal's sum, which never under-counts.
        .map_or_else(
            |_| proposal.estimated_serialized_size(),
            |p| p.estimated_serialized_size(),
        );
        sizes.push(total.saturating_sub(total_before));
        total_before = total;
    }
    sizes
}

/// What a bound limits, for one transaction.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct StepCounts {
    /// Actions per pool, in [`BOUNDED_POOLS`] order, after padding.
    pub(crate) pool_actions: [usize; 3],
    pub(crate) transparent_inputs: usize,
    pub(crate) transparent_outputs: usize,
    pub(crate) shielded_payment_outputs: usize,
}

impl StepCounts {
    /// The counts of `step`'s transaction when built under the consensus rules of `branch`.
    ///
    /// The action counts are `zcash_client_backend`'s own (`Step::orchard_action_count` and
    /// `Step::ironwood_action_count` with the padding the builder uses, and the Sapling
    /// builder's own padding), to which the shielded notes the step spends from earlier steps
    /// are added: those counts consider only the step's own notes, and an action per extra spend
    /// can only over-count. The over-count (one action per such spend, where the padded count
    /// might absorb it) is a known, safe-direction limitation: it can refuse a step that would
    /// fit, never admit one that does not, and only for a step spending a shielded output of an
    /// earlier step. The multi-step proposals made today (shielding, ZIP 320 TEX) pass only
    /// transparent outputs between steps, which are counted exactly.
    pub(crate) fn of<NoteRef>(
        step: &Step<NoteRef>,
        branch: BranchId,
        resolve_prior_output_pool: &impl Fn(&StepOutput) -> PoolType,
    ) -> Self {
        let mut prior_shielded = [0usize; 3];
        let mut prior_transparent = 0usize;
        for s_ref in step.prior_step_inputs() {
            match resolve_prior_output_pool(s_ref) {
                PoolType::Transparent => prior_transparent += 1,
                PoolType::Shielded(pool) => prior_shielded[pool_index(pool)] += 1,
            }
        }

        let sapling = sapling_actions(step);
        let orchard = orchard_style_actions(step, ValuePool::Orchard, branch);
        let ironwood = orchard_style_actions(step, ValuePool::Ironwood, branch);

        let shielded_payment_outputs = step
            .payment_pools()
            .values()
            .filter(|pool| matches!(pool, PoolType::Shielded(_)))
            .count();

        Self {
            pool_actions: [
                sapling + prior_shielded[0],
                orchard + prior_shielded[1],
                ironwood + prior_shielded[2],
            ],
            transparent_inputs: step.transparent_inputs().len() + prior_transparent,
            transparent_outputs: step.output_count_in_pool(PoolType::Transparent)
                + step.change_count_in_pool(PoolType::Transparent),
            shielded_payment_outputs,
        }
    }

    /// Sapling spends and outputs plus Orchard and Ironwood actions.
    pub(crate) fn shielded_actions(&self) -> usize {
        self.pool_actions.iter().sum()
    }

    /// The first limit of `bound` these counts exceed, if any.
    pub(crate) fn first_violation(
        &self,
        bound: &TransactionSizeBound,
    ) -> Option<SizeBoundViolation> {
        let exceeds = |count: usize, cap: Option<usize>| cap.filter(|limit| count > *limit);

        if let Some(limit) = exceeds(self.shielded_actions(), bound.max_shielded_actions()) {
            return Some(SizeBoundViolation::ShieldedActions {
                count: self.shielded_actions(),
                limit,
            });
        }
        for pool in BOUNDED_POOLS {
            let count = self.pool_actions[pool_index(pool)];
            if let Some(limit) = exceeds(count, bound.max_pool_actions(pool)) {
                return Some(SizeBoundViolation::PoolActions { pool, count, limit });
            }
        }
        if let Some(limit) = exceeds(self.transparent_inputs, bound.max_transparent_inputs()) {
            return Some(SizeBoundViolation::TransparentInputs {
                count: self.transparent_inputs,
                limit,
            });
        }
        if let Some(limit) = exceeds(self.transparent_outputs, bound.max_transparent_outputs()) {
            return Some(SizeBoundViolation::TransparentOutputs {
                count: self.transparent_outputs,
                limit,
            });
        }
        if let Some(limit) = exceeds(
            self.shielded_payment_outputs,
            bound.max_shielded_payment_outputs(),
        ) {
            return Some(SizeBoundViolation::ShieldedPaymentOutputs {
                count: self.shielded_payment_outputs,
                limit,
            });
        }
        None
    }
}

/// The Sapling spends plus outputs the builder produces for `step`, with the Sapling builder's
/// default padding.
fn sapling_actions<NoteRef>(step: &Step<NoteRef>) -> usize {
    let spends = step.input_count_in_pool(PoolType::SAPLING);
    let outputs =
        step.output_count_in_pool(PoolType::SAPLING) + step.change_count_in_pool(PoolType::SAPLING);
    let bundle_type = sapling::builder::BundleType::DEFAULT;
    // The default bundle type accepts any count; the fallback is never taken.
    bundle_type.num_spends(spends).unwrap_or(spends)
        + bundle_type.num_outputs(spends, outputs).unwrap_or(outputs)
}

/// The actions the builder produces for `step`'s bundle in `pool` under `branch`: the Orchard
/// bundle with the default padding, the Ironwood bundle with the padding the step records.
fn orchard_style_actions<NoteRef>(
    step: &Step<NoteRef>,
    pool: ValuePool,
    branch: BranchId,
) -> usize {
    let (pool_type, padding) = match pool {
        ValuePool::Orchard => (PoolType::ORCHARD, BundlePadding::DEFAULT),
        ValuePool::Ironwood => (PoolType::IRONWOOD, step.ironwood_bundle_padding()),
    };
    let spends = step.input_count_in_pool(pool_type);
    let outputs = step.output_count_in_pool(pool_type) + step.change_count_in_pool(pool_type);
    let counted = bundle_version_for_branch(branch, pool).and_then(|version| {
        match pool {
            ValuePool::Orchard => step.orchard_action_count(padding, version),
            ValuePool::Ironwood => step.ironwood_action_count(padding, version),
        }
        .ok()
    });
    // A pool that is not active under `branch` cannot be built at all; if the step uses it
    // anyway, every spend and output is counted as its own action so the bound is not
    // under-counted.
    counted.unwrap_or(spends + outputs)
}

/// The pool of the earlier step's output that `s_ref` spends.
fn prior_output_pool<NoteRef>(steps: &NonEmpty<Step<NoteRef>>, s_ref: &StepOutput) -> PoolType {
    let prior = &steps[s_ref.step_index()];
    match s_ref.output_index() {
        StepOutputIndex::Payment(i) => *prior
            .payment_pools()
            .get(&i)
            .expect("a proposal step pays every payment it references"),
        StepOutputIndex::Change(i) => prior.balance().proposed_change()[i].output_pool(),
    }
}
