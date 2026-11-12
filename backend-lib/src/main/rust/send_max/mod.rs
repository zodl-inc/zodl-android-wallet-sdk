//! Sending an account's entire spendable shielded balance to one recipient.
//!
//! The user's Send Max and gift card sweep ([`SendMaxRequest::spendable_to`]) go through
//! [`propose`], the only place that pins their fee rule, confirmations policy and input locking
//! behaviour. Its failures are
//! classified by [`crate::proposal_error`], as every other proposal's are.
//!
//! The selection itself is upstream's [`propose_send_max_transfer`]: it selects every spendable
//! note in the chosen pools and computes the ZIP 317 fee internally, so the payment is exactly
//! the selected value minus that fee and no change is left behind.

use std::fmt;

use zcash_address::ZcashAddress;
use zcash_client_backend::{
    data_api::{
        InputSource, MaxSpendMode, WalletRead, WalletWrite,
        wallet::{
            ConfirmationsPolicy, input_selection::LockedInputPolicy, propose_send_max_transfer,
        },
    },
    fees::StandardFeeRule,
    proposal::Proposal,
};
use zcash_protocol::{ShieldedPool, consensus::Parameters, memo::MemoBytes};

use crate::proposal_error::{ProposalRejection, classify};
use crate::size_bound::{self, TransactionSizeBound};

#[cfg(test)]
pub(crate) mod tests;

/// A send-max proposal over a wallet whose received notes are identified by `NoteRef`.
pub(crate) type SendMaxProposal<NoteRef> = Proposal<StandardFeeRule, NoteRef>;

/// The shielded pools a user send-max spends from: all of them. Transparent funds are not
/// swept; they are shielded separately.
pub(crate) const SEND_MAX_POOLS: [ShieldedPool; 3] = [
    ShieldedPool::Sapling,
    ShieldedPool::Orchard,
    ShieldedPool::Ironwood,
];

/// What to send the maximum of, and to whom.
#[derive(Debug, Clone)]
pub(crate) struct SendMaxRequest {
    /// The shielded pools whose notes are spent.
    pub(crate) pools: &'static [ShieldedPool],
    /// Whether notes that are not yet spendable are left behind or fail the proposal.
    pub(crate) mode: MaxSpendMode,
    /// The single recipient of the payment.
    pub(crate) recipient: ZcashAddress,
    /// The memo carried by the payment, if any.
    pub(crate) memo: Option<MemoBytes>,
    /// The limits the transaction must stay within; consensus alone unless the caller asks for
    /// stricter ones ([`SendMaxRequest::within`]).
    pub(crate) bound: TransactionSizeBound,
}

impl SendMaxRequest {
    /// The user's Send Max, and a gift card sweep: every shielded pool, and only what is
    /// spendable now ([`MaxSpendMode::MaxSpendable`]). Notes that are not yet spendable under
    /// the default confirmations policy stay where they are rather than failing the proposal.
    pub(crate) fn spendable_to(recipient: ZcashAddress, memo: Option<MemoBytes>) -> Self {
        Self {
            pools: &SEND_MAX_POOLS,
            mode: MaxSpendMode::MaxSpendable,
            recipient,
            memo,
            bound: TransactionSizeBound::CONSENSUS,
        }
    }

    /// This request, failing rather than proposing a transaction that exceeds `bound`.
    ///
    /// The selection is not reduced to what fits: every spendable note is still selected, and a
    /// proposal that exceeds the bound is refused as
    /// [`ProposalRejection::ExceedsSizeBound`] (or [`ProposalRejection::TransactionTooLarge`] for
    /// its byte limit).
    pub(crate) fn within(self, bound: TransactionSizeBound) -> Self {
        Self { bound, ..self }
    }
}

/// The confirmations policy every send-max spends under: ZIP 315's default, 3 confirmations for
/// trusted outputs and 10 for untrusted ones.
///
/// A gift card's notes were sent by the card's issuer, who still holds the card's key, so they
/// are untrusted and wait 10 confirmations before the claim spends them; this must never be
/// lowered here. The destination wallet then records the claim itself as trusted (see
/// `Synchronizer.recordTrustedTransaction` in the Kotlin SDK), which waits only 3
/// confirmations. Because the issuer still holds the card's key, a reorg deep enough to drop
/// the claim transaction (the trusted count, 3 blocks) would let the issuer get a competing
/// spend of the card's notes mined instead. Recording the claim as trusted is therefore sound
/// only when the issuer is trusted, as for cards issued by ZODL.
pub(crate) fn send_max_confirmations_policy() -> ConfirmationsPolicy {
    ConfirmationsPolicy::default()
}

/// Proposes sending the maximum `request` describes from `account`.
///
/// Always ZIP 317 fees and [`send_max_confirmations_policy`]. Locked outputs, which belong to
/// another in-flight proposal, are never selected, and the selected inputs are not locked: the
/// proposal is built to be signed and broadcast, not held open for a later commitment.
pub(crate) fn propose<DbT, P>(
    db: &mut DbT,
    params: &P,
    account: <DbT as InputSource>::AccountId,
    request: SendMaxRequest,
) -> Result<SendMaxProposal<<DbT as InputSource>::NoteRef>, ProposalRejection>
where
    DbT: WalletWrite + InputSource<Error = <DbT as WalletRead>::Error>,
    <DbT as InputSource>::NoteRef: Copy + Eq + Ord + fmt::Display,
    <DbT as WalletRead>::Error: fmt::Display,
    P: Parameters + Clone,
{
    let proposal = propose_send_max_transfer::<_, _, _, std::convert::Infallible>(
        db,
        params,
        account,
        request.pools,
        &StandardFeeRule::Zip317,
        request.recipient,
        request.memo,
        request.mode,
        send_max_confirmations_policy(),
        &LockedInputPolicy::Exclude,
        None,
    )
    .map_err(classify)?;

    // `propose_transfer` rejects a proposal whose transaction would not fit in a block, but
    // `propose_send_max_transfer` does not yet, so without this a send-max that spends too many
    // notes would only fail once its transaction is built. `check_within` makes that consensus
    // check first, as `propose_transfer` does, and then holds the proposal to the request's
    // bound.
    size_bound::check_within(&proposal, params, &request.bound)?;

    Ok(proposal)
}
