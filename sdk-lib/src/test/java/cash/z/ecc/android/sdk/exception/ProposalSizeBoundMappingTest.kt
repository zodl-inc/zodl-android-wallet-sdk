package cash.z.ecc.android.sdk.exception

import cash.z.ecc.android.sdk.internal.ext.toProposalException
import cash.z.ecc.android.sdk.internal.jni.ProposalSizeBoundException
import cash.z.ecc.android.sdk.model.SizeBoundViolation
import cash.z.ecc.android.sdk.model.TransactionPool
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProposalSizeBoundMappingTest {
    private val actions = ProposalSizeBoundException(ProposalSizeBoundException.SHIELDED_ACTIONS, null, 80, 75)

    @Test
    fun a_proposal_over_the_bound_is_too_large_with_the_exceeded_cap() {
        val mapped =
            RuntimeException("wrapper", actions)
                .toProposalException(TransactionEncoderException::ProposalFromParametersException)

        val tooLarge = assertIs<TransactionEncoderException.ProposalTooLargeException>(mapped)
        assertEquals(
            SizeBoundViolation(SizeBoundViolation.Dimension.SHIELDED_ACTIONS, pool = null, count = 80, limit = 75),
            tooLarge.violation
        )
    }

    @Test
    fun a_pool_cap_names_its_pool() {
        val pool = ProposalSizeBoundException(ProposalSizeBoundException.POOL_ACTIONS, "SAPLING", 2, 0)

        val tooLarge =
            assertIs<TransactionEncoderException.ProposalTooLargeException>(
                pool.toProposalException(TransactionEncoderException::ProposalFromUriException)
            )

        assertEquals(TransactionPool.SAPLING, tooLarge.violation?.pool)
        assertEquals(SizeBoundViolation.Dimension.POOL_ACTIONS, tooLarge.violation?.dimension)
    }

    @Test
    fun every_dimension_the_backend_reports_is_known() {
        mapOf(
            ProposalSizeBoundException.SHIELDED_ACTIONS to SizeBoundViolation.Dimension.SHIELDED_ACTIONS,
            ProposalSizeBoundException.POOL_ACTIONS to SizeBoundViolation.Dimension.POOL_ACTIONS,
            ProposalSizeBoundException.TRANSPARENT_INPUTS to SizeBoundViolation.Dimension.TRANSPARENT_INPUTS,
            ProposalSizeBoundException.TRANSPARENT_OUTPUTS to SizeBoundViolation.Dimension.TRANSPARENT_OUTPUTS,
            ProposalSizeBoundException.SHIELDED_PAYMENT_OUTPUTS to
                SizeBoundViolation.Dimension.SHIELDED_PAYMENT_OUTPUTS
        ).forEach { (name, dimension) ->
            val found = ProposalSizeBoundException(name, null, 1, 0).proposalTooLargeReason()
            assertEquals(dimension, found?.violation?.dimension, name)
        }
    }

    @Test
    fun an_unknown_dimension_is_still_too_large_without_a_violation() {
        val unknown = ProposalSizeBoundException("a_future_cap", null, 2, 1)

        val tooLarge =
            assertIs<TransactionEncoderException.ProposalTooLargeException>(
                unknown.toProposalException(TransactionEncoderException::ProposalFromParametersException)
            )
        assertNull(tooLarge.violation)
        val unknownPool = ProposalSizeBoundException(ProposalSizeBoundException.POOL_ACTIONS, "SPROUT", 1, 0)
        assertNull(unknownPool.proposalTooLargeReason()?.violation)
    }

    @Test
    fun creating_over_the_bound_is_found_on_the_suppressed_reason() {
        val backendFailure = RuntimeException("Error while creating transactions").apply { addSuppressed(actions) }

        val found = TransactionEncoderException.TransactionNotCreatedException(backendFailure).proposalTooLargeReason()

        assertSame(actions, found?.rootCause)
        assertEquals(80, found?.violation?.count)
    }
}
