package cash.z.ecc.android.sdk.exception

import cash.z.ecc.android.sdk.internal.jni.ProposalRejectedException
import org.junit.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProposalTooLargeReasonTest {
    private val reason = ProposalRejectedException(ProposalRejectedException.TRANSACTION_TOO_LARGE)

    private val backendFailure =
        RuntimeException("Error while creating transactions: too large").apply { addSuppressed(reason) }

    @Test
    fun findsTheSuppressedReasonOnTheBackendFailure() {
        assertSame(reason, backendFailure.proposalTooLargeReason()?.rootCause)
    }

    @Test
    fun followsTransactionNotCreatedRootCause() {
        val wrapped = TransactionEncoderException.TransactionNotCreatedException(backendFailure)

        assertSame(reason, wrapped.proposalTooLargeReason()?.rootCause)
    }

    @Test
    fun followsTheCauseChain() {
        assertSame(reason, IllegalStateException("wrapper", backendFailure).proposalTooLargeReason()?.rootCause)
    }

    @Test
    fun returnsAProposalTooLargeExceptionItself() {
        val proposed = TransactionEncoderException.ProposalTooLargeException(reason)

        assertSame(proposed, proposed.proposalTooLargeReason())
    }

    @Test
    fun anyOtherFailureHasNoReason() {
        val other = ProposalRejectedException(ProposalRejectedException.SYNC_REQUIRED)

        assertNull(RuntimeException("x").apply { addSuppressed(other) }.proposalTooLargeReason())
        assertNull(RuntimeException("the database is locked").proposalTooLargeReason())
    }
}
