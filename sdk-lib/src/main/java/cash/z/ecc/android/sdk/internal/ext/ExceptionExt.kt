package cash.z.ecc.android.sdk.internal.ext

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.jni.ProposalInsufficientFundsException
import cash.z.ecc.android.sdk.internal.jni.ProposalRejectedException
import cash.z.ecc.android.sdk.internal.jni.ProposalSizeBoundException

@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal inline fun <R> tryNull(block: () -> R): R? =
    try {
        block()
    } catch (t: Throwable) {
        null
    }

/**
 * Execute the given block, converting exceptions into warnings. This can be further controlled by
 * filters so that only exceptions matching the given constraints are converted into warnings.
 *
 * @param ifContains only convert an exception into a warning if it contains the given text
 * @param unlessContains convert all exceptions into warnings unless they contain the given text
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <R> tryWarn(
    @Suppress("UNUSED_PARAMETER") message: String,
    ifContains: String? = null,
    unlessContains: String? = null,
    block: () -> R
): R? =
    try {
        block()
    } catch (t: Throwable) {
        val shouldThrowAnyway =
            (
                unlessContains != null &&
                    (t.message?.lowercase()?.contains(unlessContains.lowercase()) == true)
            ) ||
                (
                    ifContains != null &&
                        (t.message?.lowercase()?.contains(ifContains.lowercase()) == false)
                )
        if (shouldThrowAnyway) {
            throw t
        } else {
            Twig.debug(t) { "" }
            null
        }
    }

// Note: Do NOT change these texts as they match the ones from ScanError in
// librustzcash/zcash_client_backend/src/scanning.rs
internal const val PREV_HASH_MISMATCH =
    "The parent hash of proposed block does not correspond to the block hash at " +
        "height" // $NON-NLS
internal const val BLOCK_HEIGHT_DISCONTINUITY = "Block height discontinuity at height" // $NON-NLS
internal const val TREE_SIZE_MISMATCH =
    "note commitment tree size provided by a compact block did not match the " +
        "expected size at height" // $NON-NLS

/**
 * Check whether this error is the result of a failed continuity while scanning new blocks in the Rust layer.
 *
 * @return true in case of the check match, false otherwise
 */
internal fun Throwable.isScanContinuityError(): Boolean {
    val errorMessages =
        listOf(
            PREV_HASH_MISMATCH,
            BLOCK_HEIGHT_DISCONTINUITY,
            TREE_SIZE_MISMATCH
        )
    errorMessages.forEach { errMessage ->
        if (this.message?.lowercase()?.contains(errMessage.lowercase()) == true) {
            return true
        }
    }
    return false
}

// Note: Do NOT change these texts as they match the ones the proposal machinery of
// librustzcash/zcash_client_backend reports when an account cannot cover a spend. They are the only
// signal the FFI gives us for this failure (cf. issue #680).
internal const val INSUFFICIENT_BALANCE = "Insufficient balance" // $NON-NLS
internal const val ADDITIONAL_CHANGE_OUTPUT_REQUIRED =
    "The transaction requires an additional change output of" // $NON-NLS

private const val CAUSE_CHAIN_MAX_DEPTH = 10

/**
 * This error followed by its causes: at most the first [CAUSE_CHAIN_MAX_DEPTH] links, and each one
 * once. The walk is bounded and cycle-safe, as a cause chain coming across the FFI is not guaranteed
 * to be either.
 */
private fun Throwable.causeChain(): Sequence<Throwable> {
    val visited = mutableSetOf<Throwable>()
    return generateSequence(this) { it.cause }
        .take(CAUSE_CHAIN_MAX_DEPTH)
        .takeWhile { visited.add(it) }
}

/**
 * Check whether this error - or any error within the first [CAUSE_CHAIN_MAX_DEPTH] links of its cause
 * chain - is the Rust layer reporting that the account lacks the spendable funds a proposal needs:
 * a [ProposalInsufficientFundsException], where the backend raises one, or otherwise an error whose
 * message carries one of the backend's insufficient-funds texts.
 *
 * @return true in case of the check match, false otherwise
 */
internal fun Throwable.indicatesInsufficientFunds(): Boolean {
    val markers = listOf(INSUFFICIENT_BALANCE, ADDITIONAL_CHANGE_OUTPUT_REQUIRED)
    return causeChain().any { current ->
        val message = current.message
        current is ProposalInsufficientFundsException ||
            (message != null && markers.any { message.contains(it, ignoreCase = true) })
    }
}

/**
 * The typed exception for a [ProposalSizeBoundException] or [ProposalRejectedException] within the
 * first [CAUSE_CHAIN_MAX_DEPTH] links of this error's cause chain, or `null` when there is none or
 * its reason has no typed exception of its own.
 */
private fun Throwable.toRejectedProposalException(): TransactionEncoderException? {
    causeChain().filterIsInstance<ProposalSizeBoundException>().firstOrNull()?.let {
        return TransactionEncoderException.ProposalTooLargeException(this, it.toViolation())
    }
    return when (causeChain().filterIsInstance<ProposalRejectedException>().firstOrNull()?.reason) {
        ProposalRejectedException.TRANSACTION_TOO_LARGE -> {
            TransactionEncoderException.ProposalTooLargeException(this)
        }

        ProposalRejectedException.TRANSPARENT_MEMO -> {
            TransactionEncoderException.ProposalRecipientCannotReceiveMemoException(this)
        }

        ProposalRejectedException.UNSUPPORTED_ADDRESS -> {
            TransactionEncoderException.ProposalUnsupportedRecipientException(this)
        }

        ProposalRejectedException.SYNC_REQUIRED -> {
            TransactionEncoderException.ProposalSyncRequiredException(this)
        }

        else -> {
            null
        }
    }
}

/**
 * Maps a failed proposal attempt onto the typed exception that describes it: a proposal the backend
 * rejected with a [ProposalRejectedException] becomes the typed exception for its reason; otherwise
 * an insufficient-funds failure - whatever the propose operation was - becomes
 * [TransactionEncoderException.InsufficientFundsException]; anything else is handed to the
 * per-operation [fallback] wrapper.
 *
 * The typed rejection is checked first: it is the backend's own classification, while
 * [indicatesInsufficientFunds] also matches message texts anywhere in the cause chain, which a
 * typed rejection's chain may happen to contain.
 */
internal fun Throwable.toProposalException(
    fallback: (Throwable) -> TransactionEncoderException
): TransactionEncoderException =
    toRejectedProposalException()
        ?: if (indicatesInsufficientFunds()) {
            TransactionEncoderException.InsufficientFundsException(this)
        } else {
            fallback(this)
        }
