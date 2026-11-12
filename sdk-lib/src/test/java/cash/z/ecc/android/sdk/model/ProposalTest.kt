package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProposalTest {
    private fun unsafe(
        payment: Long,
        fee: Long
    ): ProposalUnsafe =
        mock(ProposalUnsafe::class.java).also {
            `when`(it.totalPaymentAmount()).thenReturn(payment)
            `when`(it.totalFeeRequired()).thenReturn(fee)
        }

    @Test
    fun total_payment_amount_is_what_the_backend_computes() {
        val proposal = Proposal.fromUnsafe(unsafe(payment = 90_000L, fee = 10_000L))

        assertEquals(Zatoshi(90_000L), proposal.totalPaymentAmount())
        assertEquals(Zatoshi(10_000L), proposal.totalFeeRequired())
    }

    @Test
    fun a_shielding_shaped_proposal_with_no_payments_is_accepted() {
        val proposal = Proposal.fromUnsafe(unsafe(payment = 0L, fee = 15_000L))

        assertEquals(Zatoshi(0L), proposal.totalPaymentAmount())
        assertEquals(Zatoshi(15_000L), proposal.totalFeeRequired())
    }

    @Test
    fun a_proposal_whose_payments_are_negative_is_rejected_instead_of_clamped() {
        assertFailsWith<IllegalArgumentException> {
            Proposal.fromUnsafe(unsafe(payment = -5_000L, fee = 10_000L))
        }
    }
}
