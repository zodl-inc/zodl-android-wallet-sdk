package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.RawTransaction
import cash.z.ecc.android.sdk.model.TransactionId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [recordTrustedTransaction], the step behind `Synchronizer.recordTrustedTransaction`: the
 * transaction is stored first, then marked trusted, and only if the ids agree.
 */
class RecordTrustedTransactionTest {
    private val raw = RawTransaction("raw transaction".toByteArray(), height = BlockHeight.new(3_000_000))
    private val txId = TransactionId.new("txid".toByteArray())

    /**
     * Records the backend calls in order; stores every transaction under [storedTxId]. With a
     * [trustFailure], `setTransactionTrust` throws it after recording the call, as the native
     * backend does for a transaction the wallet did not store.
     */
    private class RecordingBackend(
        private val storedTxId: ByteArray,
        private val trustFailure: Exception? = null
    ) : TypesafeBackend by mock(TypesafeBackend::class.java) {
        val calls = mutableListOf<String>()

        override suspend fun decryptAndStoreTransaction(
            tx: ByteArray,
            minedHeight: BlockHeight?
        ): FirstClassByteArray {
            calls += "decryptAndStoreTransaction(${tx.decodeToString()}, minedHeight=$minedHeight)"
            return FirstClassByteArray(storedTxId)
        }

        override suspend fun setTransactionTrust(
            txId: ByteArray,
            trusted: Boolean
        ) {
            calls += "setTransactionTrust(${txId.decodeToString()}, trusted=$trusted)"
            trustFailure?.let { throw it }
        }
    }

    /** The raw transaction's own height is ignored: it is stored as unmined. */
    @Test
    fun storesTheTransactionWithoutAHeightAndThenTrustsIt() =
        runBlocking {
            val backend = RecordingBackend(storedTxId = txId.value.byteArray)

            backend.recordTrustedTransaction(raw, txId)

            assertEquals(
                listOf(
                    "decryptAndStoreTransaction(raw transaction, minedHeight=null)",
                    "setTransactionTrust(txid, trusted=true)"
                ),
                backend.calls
            )
        }

    @Test
    fun aMismatchedIdStoresTheTransactionButTrustsNothing() =
        runBlocking {
            val backend = RecordingBackend(storedTxId = "other".toByteArray())

            assertFailsWith<IllegalArgumentException> { backend.recordTrustedTransaction(raw, txId) }

            assertEquals(listOf("decryptAndStoreTransaction(raw transaction, minedHeight=null)"), backend.calls)
        }

    /**
     * A transaction that does not involve the wallet is not stored, yet its id is still returned:
     * the backend then refuses to trust it, and that refusal must reach the caller rather than
     * read as a recorded trust status.
     */
    @Test
    fun aTransactionTheWalletDidNotStoreFailsToBeRecorded() =
        runBlocking {
            val notStored = RuntimeException("Transaction is not stored in this wallet")
            val backend = RecordingBackend(storedTxId = txId.value.byteArray, trustFailure = notStored)

            val thrown = assertFailsWith<RuntimeException> { backend.recordTrustedTransaction(raw, txId) }

            assertSame(notStored, thrown)
            assertEquals(
                listOf(
                    "decryptAndStoreTransaction(raw transaction, minedHeight=null)",
                    "setTransactionTrust(txid, trusted=true)"
                ),
                backend.calls
            )
        }
}
