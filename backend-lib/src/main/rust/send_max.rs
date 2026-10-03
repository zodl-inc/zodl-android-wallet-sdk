//! Sending an account's entire spendable shielded balance to one recipient.
//!
//! This is the general form of what `migration_send_max.rs` pins for the Orchard to Ironwood
//! migration: [`propose_send_max_transfer`] selects every spendable note in the chosen pools
//! and computes the ZIP 317 fee internally, so the payment is exactly the selected value minus
//! that fee and no change is left behind.

use anyhow::anyhow;
use rand::rngs::OsRng;
use zcash_address::ZcashAddress;
use zcash_client_backend::{
    data_api::{
        InputSource, MaxSpendMode,
        wallet::{
            ConfirmationsPolicy, input_selection::LockedInputPolicy, propose_send_max_transfer,
        },
    },
    fees::StandardFeeRule,
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

/// Proposes sending the account's currently spendable shielded balance to `recipient`.
///
/// Uses [`MaxSpendMode::MaxSpendable`]: notes that are not yet spendable under the default
/// confirmations policy are left where they are rather than failing the proposal. Fails with
/// an insufficient-funds error when nothing is spendable, or when the spendable value does not
/// cover the fee.
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
        ConfirmationsPolicy::default(),
        &LockedInputPolicy::Exclude,
        None,
    )
    .map_err(|e| anyhow!("Error creating transaction proposal: {}", e))
}
