package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubmitTransactionTest {
    private val txId = FirstClassByteArray(ByteArray(32) { it.toByte() })

    @Test
    fun `OverTor failure maps to isTorFailure true`() {
        val response: Response<SendResponseUnsafe> =
            Response.Failure.OverTor(cause = RuntimeException("circuit build failed"))

        val result = response.toSubmitResult(txId)

        require(result is TransactionSubmitResult.Failure)
        assertTrue(result.isTorFailure)
        assertTrue(result.grpcError)
    }

    @Test
    fun `a generic Connection failure maps to isTorFailure false`() {
        val response: Response<SendResponseUnsafe> =
            Response.Failure.Connection<SendResponseUnsafe>(cause = RuntimeException("no network"))

        val result = response.toSubmitResult(txId)

        require(result is TransactionSubmitResult.Failure)
        assertFalse(result.isTorFailure)
        assertTrue(result.grpcError)
    }

    @Test
    fun `a mempool-rejection success-envelope failure maps to isTorFailure false`() {
        val response: Response<SendResponseUnsafe> =
            Response.Success(SendResponseUnsafe(code = 1, message = "rejected"))

        val result = response.toSubmitResult(txId)

        require(result is TransactionSubmitResult.Failure)
        assertFalse(result.isTorFailure)
        assertFalse(result.grpcError)
        assertEquals(1, result.code)
    }

    private val noTor = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false)

    private fun rawTx(
        branchId: Long,
        expiry: Long
    ): FirstClassByteArray {
        fun le(value: Long) = ByteArray(4) { i -> ((value shr (8 * i)) and 0xFF).toByte() }
        return FirstClassByteArray(
            le(0x8000_0005L) + le(0x26A7_270AL) + le(branchId) + le(0L) + le(expiry) + ByteArray(16)
        )
    }

    private fun clientAtHeight(
        height: Long,
        serverKnowsTx: Boolean
    ): CombinedWalletClient =
        clientAtHeight(
            height,
            if (serverKnowsTx) RawTransactionUnsafe.MainChain(ByteArray(0), BlockHeightUnsafe(height - 1)) else null
        )

    private fun clientAtHeight(
        height: Long,
        serverTx: RawTransactionUnsafe?
    ): CombinedWalletClient =
        mock(CombinedWalletClient::class.java).also { client ->
            runBlocking {
                `when`(client.getLatestBlockHeight(ServiceMode.Direct))
                    .thenReturn(Response.Success(BlockHeightUnsafe(height)))
                `when`(client.fetchTransaction(txId.byteArray, ServiceMode.Direct)).thenReturn(
                    if (serverTx != null) {
                        Response.Success(serverTx)
                    } else {
                        Response.Failure.Server.NotFound(RuntimeException("not found"), 5, "not found")
                    }
                )
            }
        }

    @Test
    fun `an expired transaction the server already has is a success and not a failure`() =
        runBlocking<Unit> {
            val tx = rawTx(ConsensusBranchId.NU6_3.id, expiry = 1_000L)
            val client = clientAtHeight(height = 2_000L, serverKnowsTx = true)

            val result = client.submitTransaction(tx, txId, noTor, ZcashNetwork.Mainnet) { null }

            assertEquals(TransactionSubmitResult.Success(txId), result)
            verify(client, never()).submitTransaction(tx.byteArray, ServiceMode.Direct)
        }

    @Test
    fun `an expired transaction the server does not have is refused and not sent`() =
        runBlocking<Unit> {
            val tx = rawTx(ConsensusBranchId.NU6_3.id, expiry = 1_000L)
            val client = clientAtHeight(height = 2_000L, serverKnowsTx = false)

            val result = client.submitTransaction(tx, txId, noTor, ZcashNetwork.Mainnet) { null }

            require(result is TransactionSubmitResult.Failure)
            assertEquals(SUBMIT_REFUSED_EXPIRED, result.code)
            verify(client, never()).submitTransaction(tx.byteArray, ServiceMode.Direct)
        }

    @Test
    fun `an expired transaction only in the mempool is refused and not reported as sent`() =
        runBlocking<Unit> {
            val tx = rawTx(ConsensusBranchId.NU6_3.id, expiry = 1_000L)
            val client = clientAtHeight(height = 2_000L, serverTx = RawTransactionUnsafe.Mempool(ByteArray(0)))

            val result = client.submitTransaction(tx, txId, noTor, ZcashNetwork.Mainnet) { null }

            require(result is TransactionSubmitResult.Failure)
            assertEquals(SUBMIT_REFUSED_EXPIRED, result.code)
        }

    @Test
    fun `an expired transaction only in an orphaned block is refused and not reported as sent`() =
        runBlocking<Unit> {
            val tx = rawTx(ConsensusBranchId.NU6_3.id, expiry = 1_000L)
            val client = clientAtHeight(height = 2_000L, serverTx = RawTransactionUnsafe.OrphanedBlock(ByteArray(0)))

            val result = client.submitTransaction(tx, txId, noTor, ZcashNetwork.Mainnet) { null }

            require(result is TransactionSubmitResult.Failure)
            assertEquals(SUBMIT_REFUSED_EXPIRED, result.code)
        }

    @Test
    fun `an unreadable server height is a failure without asking the server about the transaction`() =
        runBlocking<Unit> {
            val tx = rawTx(ConsensusBranchId.NU6_3.id, expiry = 1_000L)
            val client = mock(CombinedWalletClient::class.java)
            `when`(client.getLatestBlockHeight(ServiceMode.Direct))
                .thenReturn(Response.Failure.Connection<BlockHeightUnsafe>(cause = RuntimeException("offline")))

            val result = client.submitTransaction(tx, txId, noTor, ZcashNetwork.Mainnet) { null }

            require(result is TransactionSubmitResult.Failure)
            assertTrue(result.grpcError)
            verify(client, never()).fetchTransaction(txId.byteArray, ServiceMode.Direct)
            verify(client, never()).submitTransaction(tx.byteArray, ServiceMode.Direct)
        }
}
