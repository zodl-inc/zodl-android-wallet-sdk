//! Sending an account's entire spendable shielded balance to one recipient.
//!
//! This is the general form of what `migration_send_max.rs` pins for the Orchard to Ironwood
//! migration: [`propose_send_max_transfer`] selects every spendable note in the chosen pools
//! and computes the ZIP 317 fee internally, so the payment is exactly the selected value minus
//! that fee and no change is left behind.

use std::fmt;

use anyhow::anyhow;
use rand::rngs::OsRng;
use zcash_address::ZcashAddress;
use zcash_client_backend::{
    data_api::{
        InputSource, MaxSpendMode,
        error::Error as DataApiError,
        wallet::{
            ConfirmationsPolicy, input_selection::LockedInputPolicy, propose_send_max_transfer,
        },
    },
    fees::{ChangeError, StandardFeeRule},
    proposal::Proposal,
};
use zcash_client_sqlite::{AccountUuid, WalletDb, util::SystemClock};
use zcash_protocol::{ShieldedPool, consensus::Network, memo::MemoBytes};

/// The wallet database as the JNI layer holds it.
type Db = WalletDb<rusqlite::Connection, Network, SystemClock, OsRng>;

type SendMaxProposal = Proposal<StandardFeeRule, <Db as InputSource>::NoteRef>;

/// The shielded pools a send-max spends from: all of them. Transparent funds are not swept;
/// they are shielded separately.
pub(crate) const SEND_MAX_POOLS: [ShieldedPool; 3] = [
    ShieldedPool::Sapling,
    ShieldedPool::Orchard,
    ShieldedPool::Ironwood,
];

/// The error [`propose_send_max`] returns, inside its `anyhow::Error`, when the account's
/// spendable shielded value is zero or does not exceed the fee. The JNI layer looks for it with
/// `downcast_ref` to report the failure as a typed exception rather than as message text.
#[derive(Debug)]
pub(crate) struct InsufficientFunds;

impl fmt::Display for InsufficientFunds {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Insufficient balance to send the maximum amount")
    }
}

impl std::error::Error for InsufficientFunds {}

/// The confirmations policy a send-max spends under: ZIP 315's default, 3 confirmations for
/// trusted outputs and 10 for untrusted ones.
///
/// A gift card's notes were sent by the card's issuer, who still holds the card's key, so they
/// are untrusted and wait 10 confirmations before the claim spends them. That is what makes it
/// sound for the destination wallet to then record the claim itself as trusted (see
/// `Synchronizer.recordTrustedTransaction` in the Kotlin SDK): it must never be lowered here.
pub(crate) fn send_max_confirmations_policy() -> ConfirmationsPolicy {
    ConfirmationsPolicy::default()
}

/// Proposes sending the account's currently spendable shielded balance to `recipient`.
///
/// Uses [`MaxSpendMode::MaxSpendable`]: notes that are not yet spendable under the default
/// confirmations policy are left where they are rather than failing the proposal. Fails with
/// [`InsufficientFunds`] when nothing is spendable, or when the spendable value does not
/// exceed the fee.
pub(crate) fn propose_send_max(
    db_data: &mut Db,
    network: &Network,
    account: AccountUuid,
    recipient: ZcashAddress,
    memo: Option<MemoBytes>,
) -> anyhow::Result<SendMaxProposal> {
    propose_send_max_transfer::<_, _, _, std::convert::Infallible>(
        db_data,
        network,
        account,
        &SEND_MAX_POOLS,
        &StandardFeeRule::Zip317,
        recipient,
        memo,
        MaxSpendMode::MaxSpendable,
        send_max_confirmations_policy(),
        &LockedInputPolicy::Exclude,
        None,
    )
    .map_err(|e| match e {
        DataApiError::InsufficientFunds { .. }
        | DataApiError::Change(ChangeError::InsufficientFunds { .. }) => {
            anyhow::Error::new(InsufficientFunds)
        }
        e => anyhow!("Error creating transaction proposal: {}", e),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn card_notes_wait_for_the_untrusted_confirmation_count() {
        let policy = send_max_confirmations_policy();
        assert_eq!(policy.untrusted().get(), 10);
        assert_eq!(policy.trusted().get(), 3);
    }

    #[test]
    fn insufficient_funds_is_recognizable_by_type_and_by_the_legacy_marker() {
        let error = anyhow::Error::new(InsufficientFunds);
        assert!(error.downcast_ref::<InsufficientFunds>().is_some());
        // Kotlin's `indicatesInsufficientFunds` still matches this text when the typed
        // exception cannot be thrown.
        assert!(error.to_string().starts_with("Insufficient balance"));
    }
}
