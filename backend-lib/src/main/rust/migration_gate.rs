//! The NU7 consensus-branch check for a running pool migration.
//!
//! A migration transaction is built and signed for the consensus branch of the height it was
//! planned at. NU7 activation changes the branch ID, and from that height on no block accepts a
//! transaction built for the branch before it. A scheduled run that spans the activation can
//! therefore be left with transactions that can never be mined.

use pczt::Pczt;
use zcash_pool_migration::engine::MigrationTxState;
use zcash_protocol::consensus::{BlockHeight, BranchId, NetworkUpgrade, Parameters};

/// The consensus branch ID recorded in a serialized PCZT, or `None` when it cannot be parsed.
pub(crate) fn pczt_consensus_branch_id(pczt: &[u8]) -> Option<u32> {
    Pczt::parse(pczt)
        .ok()
        .map(|parsed| *parsed.global().consensus_branch_id())
}

/// What a run's stored transactions say about the upgrade, as the Kotlin side decodes it:
/// `0` clear, `1` interrupted, `2` await scan.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum UpgradeVerdict {
    /// No stored transaction is stranded by the upgrade.
    Clear,
    /// A transaction built for the old branch is unmined and can no longer be mined.
    Interrupted,
    /// A transaction built for the old branch may already have been sent. Wait until the wallet
    /// has scanned the block before the activation, so one mined just before NU7 is not mistaken
    /// for a stranded one.
    AwaitScan,
}

impl UpgradeVerdict {
    pub(crate) fn code(self) -> i32 {
        match self {
            UpgradeVerdict::Clear => 0,
            UpgradeVerdict::Interrupted => 1,
            UpgradeVerdict::AwaitScan => 2,
        }
    }
}

/// Judges a run's `(state, pczt)` rows once the wallet's next height has reached the NU7
/// activation. Before it, or on a network with no NU7 height, nothing is stranded.
pub(crate) fn upgrade_verdict<'a, P: Parameters>(
    params: &P,
    rows: impl IntoIterator<Item = (MigrationTxState, &'a [u8])>,
    next_height: BlockHeight,
    fully_scanned: Option<BlockHeight>,
) -> UpgradeVerdict {
    let Some(activation) = params.activation_height(NetworkUpgrade::Nu7) else {
        return UpgradeVerdict::Clear;
    };
    if next_height < activation {
        return UpgradeVerdict::Clear;
    }
    let next_branch = u32::from(BranchId::for_height(params, next_height));
    let mut awaiting_scan = false;
    for (state, pczt) in rows {
        // A transaction that was never proved or broadcast cannot have reached the network.
        let may_have_been_sent = match state {
            MigrationTxState::AwaitingSignature | MigrationTxState::Signed => false,
            MigrationTxState::Proved | MigrationTxState::Broadcast { .. } => true,
            MigrationTxState::Mined { .. } => continue,
        };
        let Some(branch) = pczt_consensus_branch_id(pczt) else {
            continue;
        };
        if branch == next_branch {
            continue;
        }
        if !may_have_been_sent {
            return UpgradeVerdict::Interrupted;
        }
        awaiting_scan = true;
    }
    if !awaiting_scan {
        UpgradeVerdict::Clear
    } else if fully_scanned.is_some_and(|scanned| scanned >= activation - 1) {
        UpgradeVerdict::Interrupted
    } else {
        UpgradeVerdict::AwaitScan
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pczt::roles::creator::Creator;
    use zcash_protocol::TxId;
    use zcash_protocol::consensus::MAIN_NETWORK;

    const MAINNET_NU7: u32 = 3_543_405;

    fn h(height: u32) -> BlockHeight {
        BlockHeight::from_u32(height)
    }

    fn pczt_for(branch: BranchId) -> Vec<u8> {
        Creator::new(u32::from(branch), 3_600_000, 133, None, None)
            .expect("a PCZT can be created for every branch from NU5 on")
            .build()
            .expect("an empty PCZT builds")
            .serialize()
            .expect("an empty PCZT serializes")
    }

    fn broadcast() -> MigrationTxState {
        MigrationTxState::Broadcast {
            txid: TxId::from_bytes([1u8; 32]),
        }
    }

    fn mined() -> MigrationTxState {
        MigrationTxState::Mined {
            txid: TxId::from_bytes([2u8; 32]),
            height: h(3_543_000),
        }
    }

    fn verdict(
        rows: &[(MigrationTxState, &[u8])],
        next: u32,
        scanned: Option<u32>,
    ) -> UpgradeVerdict {
        upgrade_verdict(&MAIN_NETWORK, rows.iter().copied(), h(next), scanned.map(h))
    }

    #[test]
    fn clear_before_the_activation() {
        let old = pczt_for(BranchId::Nu6_3);
        assert_eq!(
            verdict(&[(MigrationTxState::Signed, &old)], MAINNET_NU7 - 1, None),
            UpgradeVerdict::Clear
        );
    }

    #[test]
    fn unsent_old_branch_transaction_is_interrupted_at_the_activation() {
        let old = pczt_for(BranchId::Nu6_3);
        assert_eq!(
            verdict(&[(MigrationTxState::Signed, &old)], MAINNET_NU7, None),
            UpgradeVerdict::Interrupted
        );
    }

    #[test]
    fn current_branch_and_mined_transactions_are_clear() {
        let old = pczt_for(BranchId::Nu6_3);
        let new = pczt_for(BranchId::Nu7);
        assert_eq!(
            verdict(
                &[(MigrationTxState::Signed, &new), (mined(), &old)],
                MAINNET_NU7,
                None
            ),
            UpgradeVerdict::Clear
        );
    }

    #[test]
    fn a_possibly_sent_old_branch_transaction_waits_for_the_scan() {
        let old = pczt_for(BranchId::Nu6_3);
        let rows = [(broadcast(), old.as_slice())];
        assert_eq!(
            verdict(&rows, MAINNET_NU7, Some(MAINNET_NU7 - 2)),
            UpgradeVerdict::AwaitScan
        );
        assert_eq!(verdict(&rows, MAINNET_NU7, None), UpgradeVerdict::AwaitScan);
        assert_eq!(
            verdict(&rows, MAINNET_NU7, Some(MAINNET_NU7 - 1)),
            UpgradeVerdict::Interrupted
        );
    }

    #[test]
    fn unreadable_pczt_is_ignored() {
        assert_eq!(
            verdict(&[(MigrationTxState::Signed, &[0u8, 1, 2])], MAINNET_NU7, None),
            UpgradeVerdict::Clear
        );
    }
}
