package cash.z.ecc.android.sdk.internal.jni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Internal bridge for the transaction id of a PCZT, derived by librustzcash. */
object RustPcztTransactionId {
    /**
     * The id of the transaction [pczt] describes, in its internal byte order: the same for the
     * unsigned, signed and proven PCZT and for the transaction extracted from it. Loads the native
     * library first if needed; opens no wallet. Both the load and the native call (which parses the whole PCZT) run
     * on [Dispatchers.Default], so this never blocks the caller's dispatcher.
     *
     * @throws RuntimeException if [pczt] is not a PCZT whose effects assemble into a transaction.
     */
    suspend fun of(pczt: ByteArray): ByteArray =
        withContext(Dispatchers.Default) {
            RustBackend.loadLibrary()
            getPcztTransactionId(pczt)
        }

    @JvmStatic
    private external fun getPcztTransactionId(pczt: ByteArray): ByteArray
}
