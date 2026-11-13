package cash.z.ecc.android.sdk.exception

import cash.z.ecc.android.sdk.internal.ext.toViolation
import cash.z.ecc.android.sdk.internal.jni.ProposalRejectedException
import cash.z.ecc.android.sdk.internal.jni.ProposalSizeBoundException

private const val CAUSE_CHAIN_MAX_DEPTH = 10

/**
 * Whether this failure is a transaction that would spend too many notes to fit in a block, or to
 * stay within the size bound the caller passed, and if so the typed
 * [TransactionEncoderException.ProposalTooLargeException] describing it; `null` otherwise.
 *
 * Proposing throws [TransactionEncoderException.ProposalTooLargeException] directly. Creating
 * transactions (`Synchronizer.createProposedTransactions`) or a PCZT
 * (`Synchronizer.createPcztFromProposal`) from a proposal that is too large still fails with the
 * exception it always has; the reason is attached to it only as a suppressed exception, which this
 * finds. The first [CAUSE_CHAIN_MAX_DEPTH] links of the cause chain are searched (following
 * [TransactionEncoderException.TransactionNotCreatedException.rootCause] too), with the
 * suppressed exceptions of each.
 */
fun Throwable.proposalTooLargeReason(): TransactionEncoderException.ProposalTooLargeException? {
    val visited = mutableSetOf<Throwable>()
    return generateSequence(this) {
        (it as? TransactionEncoderException.TransactionNotCreatedException)?.rootCause ?: it.cause
    }.take(CAUSE_CHAIN_MAX_DEPTH)
        .takeWhile { visited.add(it) }
        .flatMap { sequenceOf(it) + it.suppressed.asSequence() }
        .firstNotNullOfOrNull { it.asProposalTooLarge() }
}

private fun Throwable.asProposalTooLarge(): TransactionEncoderException.ProposalTooLargeException? =
    when {
        this is TransactionEncoderException.ProposalTooLargeException -> {
            this
        }

        this is ProposalRejectedException && reason == ProposalRejectedException.TRANSACTION_TOO_LARGE -> {
            TransactionEncoderException.ProposalTooLargeException(this)
        }

        this is ProposalSizeBoundException -> {
            TransactionEncoderException.ProposalTooLargeException(this, toViolation())
        }

        else -> {
            null
        }
    }
