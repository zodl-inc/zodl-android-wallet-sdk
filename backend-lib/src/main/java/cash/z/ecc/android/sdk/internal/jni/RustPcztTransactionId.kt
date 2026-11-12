package cash.z.ecc.android.sdk.internal.jni

/** Internal bridge for the transaction id of a PCZT, derived by librustzcash. */
object RustPcztTransactionId {
    /**
     * The id of the transaction [pczt] describes, in its internal byte order: the same for the
     * unsigned, signed and proven PCZT and for the transaction extracted from it. Loads the native
     * library first if needed; opens no wallet.
     *
     * @throws RuntimeException if [pczt] is not a PCZT whose effects assemble into a transaction.
     */
    suspend fun of(pczt: ByteArray): ByteArray {
        RustBackend.loadLibrary()
        return getPcztTransactionId(pczt)
    }

    @JvmStatic
    private external fun getPcztTransactionId(pczt: ByteArray): ByteArray
}
