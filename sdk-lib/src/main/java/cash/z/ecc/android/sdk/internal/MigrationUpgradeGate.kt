package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.BlockSpacing
import cash.z.ecc.android.sdk.model.ZcashNetwork

/**
 * Number of blocks (about an hour at the 25 s NU7 spacing, or 1 h 15 min at 75 s) before the NU7
 * activation in which no migration work is started or submitted.
 */
internal const val NETWORK_UPGRADE_CUTOFF_BLOCKS = 48L

/**
 * What a migration may do at the wallet's next height, relative to the NU7 activation. A migration
 * transaction is built for the consensus branch of the height it was planned at, and no block
 * accepts one built for the branch before NU7 once NU7 is active.
 */
internal sealed interface MigrationGatePhase {
    /** No restriction: NU7 is active, unknown for the network, or the gate is bypassed. */
    data object Open : MigrationGatePhase

    /** Scheduled migration waits for NU7. The immediate (single transaction) lane stays available. */
    data class PausedUntil(
        val activation: BlockHeight
    ) : MigrationGatePhase

    /** The last [NETWORK_UPGRADE_CUTOFF_BLOCKS] blocks before NU7: every migration lane is closed. */
    data class Cutoff(
        val activation: BlockHeight
    ) : MigrationGatePhase
}

/**
 * The gate phase for a transaction that would be mined at [nextHeight], the height after the
 * wallet's chain tip.
 */
internal fun migrationGatePhase(
    network: ZcashNetwork,
    nextHeight: BlockHeight,
    bypass: Boolean
): MigrationGatePhase {
    val activation = BlockSpacing.nu7ActivationHeight(network)
    return when {
        activation == null || bypass || nextHeight.value >= activation.value -> {
            MigrationGatePhase.Open
        }

        nextHeight.value >= activation.value - NETWORK_UPGRADE_CUTOFF_BLOCKS -> {
            MigrationGatePhase.Cutoff(activation)
        }

        else -> {
            MigrationGatePhase.PausedUntil(activation)
        }
    }
}

/** Why a transaction must not be submitted. */
internal enum class SubmissionRefusal {
    WRONG_BRANCH,
    EXPIRED,
    CUTOFF
}

/**
 * Whether a raw transaction can still be mined at [nextHeight]: it was built for the consensus
 * era of that height and has not expired by then. An unreadable branch ID does not refuse, so a
 * transaction this check cannot judge is never blocked by it. A zero expiry height means the
 * transaction does not expire.
 */
internal fun checkSubmittable(
    rawTx: ByteArray,
    expiryHeight: Long?,
    network: ZcashNetwork,
    nextHeight: BlockHeight
): SubmissionRefusal? =
    when {
        isBuiltForOtherConsensusEra(rawTx, network, nextHeight) -> {
            SubmissionRefusal.WRONG_BRANCH
        }

        expiryHeight != null && expiryHeight != 0L && expiryHeight < nextHeight.value -> {
            SubmissionRefusal.EXPIRED
        }

        else -> {
            null
        }
    }
