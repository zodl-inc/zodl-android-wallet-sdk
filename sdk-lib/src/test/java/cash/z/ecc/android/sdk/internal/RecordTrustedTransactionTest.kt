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

/**
 * [recordTrustedTransaction], the step behind `Synchronizer.recordTrustedTransaction`: the
 * transaction is stored first, then marked trusted, and only if the ids agree.
 */
class RecordTrustedTransactionTest {
    private val raw = RawTransaction("raw transaction".toByteArray(), height = BlockHeight.new(3_000_000))
    private val txId = TransactionId.new("txid".toByteArray())

    /** Records the backend calls in order; stores every transaction under [storedTxId]. */
    private class RecordingBackend(
        private val storedTxId: ByteArray
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
        }
    }

    @Test
    fun storesTheTransactionWithoutAHeightAndThenTrustsIt() =
        runBlocking {
            // The raw transaction's own height is ignored: it is stored as unmined.
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
}
