package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.BlockSpacing
import cash.z.ecc.android.sdk.model.ZcashNetwork

/**
 * What a migration may start at the wallet's next height, relative to the NU7 activation. A migration
 * transaction is built for the consensus branch of the height it was planned at, and no block
 * accepts one built for the branch before NU7 once NU7 is active. Only new scheduled work waits:
 * there is no cutoff window, and valid committed transactions keep being proved and broadcast.
 */
internal sealed interface MigrationGatePhase {
    /** No restriction: NU7 is active, unknown for the network, or the gate is bypassed. */
    data object Open : MigrationGatePhase

    /** New scheduled work waits for NU7. The immediate (single transaction) lane stays available. */
    data class PausedUntil(
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

        else -> {
            MigrationGatePhase.PausedUntil(activation)
        }
    }
}

/** Why a transaction must not be submitted. */
internal enum class SubmissionRefusal {
    WRONG_BRANCH,
    EXPIRED
}

/**
 * Whether a raw transaction can still be mined at [nextHeight]: it was built for the consensus
 * era of that height and has not expired by then. Both are read from the transaction's fixed prefix.
 * An unreadable transaction does not refuse, so one this check cannot judge is never blocked by it. A
 * zero expiry height means the transaction does not expire.
 */
internal fun checkSubmittable(
    rawTx: ByteArray,
    network: ZcashNetwork,
    nextHeight: BlockHeight
): SubmissionRefusal? {
    val expiryHeight = rawTransactionExpiryHeight(rawTx)
    return when {
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
}
