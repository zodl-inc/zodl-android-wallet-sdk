package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.model.BlockSpacing
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubmissionRefusalTest {
    private val nu7 = BlockSpacing.nu7ActivationHeight(ZcashNetwork.Mainnet)!!.value
    private val txId = FirstClassByteArray(ByteArray(32) { it.toByte() })

    private fun rawTx(
        branchId: Long,
        expiry: Long
    ): FirstClassByteArray {
        fun le(value: Long) = ByteArray(4) { i -> ((value shr (8 * i)) and 0xFF).toByte() }
        return FirstClassByteArray(
            le(0x8000_0005L) + le(0x26A7_270AL) + le(branchId) + le(0L) + le(expiry) + ByteArray(16)
        )
    }

    private fun height(value: Long): Response<BlockHeightUnsafe> = Response.Success(BlockHeightUnsafe(value))

    @Test
    fun submits_a_transaction_for_the_next_blocks_branch() {
        val tx = rawTx(ConsensusBranchId.NU6_3.id, nu7 + 40)
        assertNull(submissionRefusal(tx, txId, ZcashNetwork.Mainnet, height(nu7 - 3)))
    }

    @Test
    fun refuses_an_old_branch_transaction_once_the_server_reaches_the_activation() {
        val tx = rawTx(ConsensusBranchId.NU6_3.id, nu7 + 40)
        // The server's latest block is the one before the activation, so the next block is NU7's first.
        val refusal = submissionRefusal(tx, txId, ZcashNetwork.Mainnet, height(nu7 - 1))
        assertNotNull(refusal)
        assertEquals(SUBMIT_REFUSED_WRONG_BRANCH, refusal.code)
        assertFalse(refusal.grpcError)
    }

    @Test
    fun refuses_an_expired_transaction() {
        val tx = rawTx(ConsensusBranchId.NU6_3.id, nu7 - 100)
        val refusal = submissionRefusal(tx, txId, ZcashNetwork.Mainnet, height(nu7 - 50))
        assertNotNull(refusal)
        assertEquals(SUBMIT_REFUSED_EXPIRED, refusal.code)
    }

    @Test
    fun submits_nothing_when_the_server_height_cannot_be_read() {
        val tx = rawTx(ConsensusBranchId.NU6_3.id, nu7 + 40)
        val failure = Response.Failure.Connection<BlockHeightUnsafe>(cause = java.io.IOException("offline"))
        val refusal = submissionRefusal(tx, txId, ZcashNetwork.Mainnet, failure)
        assertNotNull(refusal)
        assertTrue(refusal.grpcError)
    }
}
