package cash.z.ecc.android.sdk.ext

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.ext.ADDITIONAL_CHANGE_OUTPUT_REQUIRED
import cash.z.ecc.android.sdk.internal.ext.BLOCK_HEIGHT_DISCONTINUITY
import cash.z.ecc.android.sdk.internal.ext.INSUFFICIENT_BALANCE
import cash.z.ecc.android.sdk.internal.ext.PREV_HASH_MISMATCH
import cash.z.ecc.android.sdk.internal.ext.TREE_SIZE_MISMATCH
import cash.z.ecc.android.sdk.internal.ext.indicatesInsufficientFunds
import cash.z.ecc.android.sdk.internal.ext.isScanContinuityError
import cash.z.ecc.android.sdk.internal.ext.toProposalException
import cash.z.ecc.android.sdk.internal.jni.ProposalInsufficientFundsException
import cash.z.ecc.android.sdk.internal.jni.ProposalRejectedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExceptionExtTest {
    @Test
    fun is_scan_continuity_error() {
        assertTrue { RuntimeException(PREV_HASH_MISMATCH).isScanContinuityError() }
        assertTrue { RuntimeException(TREE_SIZE_MISMATCH).isScanContinuityError() }
        assertTrue { RuntimeException(BLOCK_HEIGHT_DISCONTINUITY).isScanContinuityError() }

        assertTrue { RuntimeException(PREV_HASH_MISMATCH.lowercase()).isScanContinuityError() }

        assertTrue { RuntimeException(PREV_HASH_MISMATCH.plus("Text")).isScanContinuityError() }
    }

    @Test
    fun is_not_scan_continuity_error() {
        assertFalse { RuntimeException("Text").isScanContinuityError() }
        assertFalse { RuntimeException("").isScanContinuityError() }
        assertFalse { RuntimeException(PREV_HASH_MISMATCH.drop(1)).isScanContinuityError() }
    }

    @Test
    fun indicates_insufficient_funds() {
        assertTrue {
            RuntimeException("Insufficient balance (have 0, need 1510000 including fee)")
                .indicatesInsufficientFunds()
        }
        assertTrue { RuntimeException(INSUFFICIENT_BALANCE.lowercase()).indicatesInsufficientFunds() }
        assertTrue { RuntimeException(INSUFFICIENT_BALANCE.uppercase()).indicatesInsufficientFunds() }
        assertTrue {
            RuntimeException("$ADDITIONAL_CHANGE_OUTPUT_REQUIRED 10000 zatoshis").indicatesInsufficientFunds()
        }
    }

    @Test
    fun indicates_insufficient_funds_by_type_whatever_the_message() {
        assertTrue { ProposalInsufficientFundsException("").indicatesInsufficientFunds() }
        assertTrue {
            RuntimeException("proposal failed", ProposalInsufficientFundsException("reworded"))
                .indicatesInsufficientFunds()
        }
    }

    @Test
    fun indicates_insufficient_funds_through_a_nested_cause() {
        val root = RuntimeException(INSUFFICIENT_BALANCE)
        val wrapped = IllegalStateException("proposal failed", RuntimeException("backend failed", root))

        assertTrue { wrapped.indicatesInsufficientFunds() }
    }

    @Test
    fun does_not_indicate_insufficient_funds() {
        assertFalse { RuntimeException("the database is locked").indicatesInsufficientFunds() }
        assertFalse { RuntimeException().indicatesInsufficientFunds() }
        assertFalse { RuntimeException(INSUFFICIENT_BALANCE.drop(1)).indicatesInsufficientFunds() }
    }

    @Test
    fun insufficient_funds_detection_terminates_on_a_cause_cycle() {
        val first = SelfCausedException("first")
        val second = SelfCausedException("second")
        first.initCauseTo(second)
        second.initCauseTo(first)

        assertFalse { first.indicatesInsufficientFunds() }
    }

    @Test
    fun insufficient_funds_detection_is_bounded_in_depth() {
        var deepest: Throwable = RuntimeException(INSUFFICIENT_BALANCE)
        repeat(20) { deepest = RuntimeException("wrapper", deepest) }

        assertFalse { deepest.indicatesInsufficientFunds() }
    }

    @Test
    fun to_proposal_exception_prefers_the_insufficient_funds_type() {
        val cause = RuntimeException(INSUFFICIENT_BALANCE)

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertIs<TransactionEncoderException.InsufficientFundsException>(exception)
        assertSame(cause, exception.rootCause)
    }

    @Test
    fun to_proposal_exception_falls_back_to_the_per_operation_wrapper() {
        val cause = RuntimeException("the database is locked")

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromUriException)

        assertIs<TransactionEncoderException.ProposalFromUriException>(exception)
        assertSame(cause, exception.rootCause)
    }

    @Test
    fun to_proposal_exception_maps_a_too_large_rejection() {
        val cause = ProposalRejectedException(ProposalRejectedException.TRANSACTION_TOO_LARGE)

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertIs<TransactionEncoderException.ProposalTooLargeException>(exception)
        assertSame(cause, exception.rootCause)
    }

    @Test
    fun to_proposal_exception_maps_a_transparent_memo_rejection() {
        val cause = ProposalRejectedException(ProposalRejectedException.TRANSPARENT_MEMO)

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertIs<TransactionEncoderException.ProposalRecipientCannotReceiveMemoException>(exception)
        assertSame(cause, exception.rootCause)
    }

    @Test
    fun to_proposal_exception_maps_an_unsupported_address_rejection() {
        val cause = ProposalRejectedException(ProposalRejectedException.UNSUPPORTED_ADDRESS)

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertIs<TransactionEncoderException.ProposalUnsupportedRecipientException>(exception)
        assertSame(cause, exception.rootCause)
    }

    @Test
    fun to_proposal_exception_finds_a_rejection_through_a_nested_cause() {
        val rejection = ProposalRejectedException(ProposalRejectedException.TRANSACTION_TOO_LARGE)
        val wrapped = IllegalStateException("proposal failed", rejection)

        val exception = wrapped.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertIs<TransactionEncoderException.ProposalTooLargeException>(exception)
        assertSame(wrapped, exception.rootCause)
    }

    @Test
    fun to_proposal_exception_prefers_a_typed_rejection_over_insufficient_funds_text() {
        val rejection = ProposalRejectedException(ProposalRejectedException.TRANSACTION_TOO_LARGE)
        val wrapped = IllegalStateException(INSUFFICIENT_BALANCE, rejection)

        val exception = wrapped.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertIs<TransactionEncoderException.ProposalTooLargeException>(exception)
        assertSame(wrapped, exception.rootCause)
    }

    @Test
    fun to_proposal_exception_maps_a_sync_required_rejection() {
        val cause = ProposalRejectedException(ProposalRejectedException.SYNC_REQUIRED)

        for (fallback in listOf<(Throwable) -> TransactionEncoderException>(
            TransactionEncoderException::ProposalFromParametersException,
            TransactionEncoderException::ProposalFromUriException,
            TransactionEncoderException::ProposalShieldingException
        )) {
            val exception = cause.toProposalException(fallback)

            assertIs<TransactionEncoderException.ProposalSyncRequiredException>(exception)
            assertSame(cause, exception.rootCause)
        }
    }

    @Test
    fun to_proposal_exception_falls_back_for_a_rejection_without_a_typed_exception() {
        val cause = ProposalRejectedException("a_reason_added_later")

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertIs<TransactionEncoderException.ProposalFromParametersException>(exception)
        assertSame(cause, exception.rootCause)
    }

    @Test
    fun rejection_messages_carry_only_the_reason() {
        val cause = ProposalRejectedException(ProposalRejectedException.TRANSPARENT_MEMO)

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertEquals("proposal rejected: transparent_memo", cause.message)
        assertFalse { exception.message.orEmpty().contains(cause.message.orEmpty()) }
    }

    /** A [Throwable] whose cause can be pointed at another instance, so a cycle can be constructed. */
    private class SelfCausedException(
        message: String
    ) : RuntimeException(message) {
        private var selfCause: Throwable? = null

        fun initCauseTo(other: Throwable) {
            selfCause = other
        }

        override val cause: Throwable?
            get() = selfCause
    }
}
