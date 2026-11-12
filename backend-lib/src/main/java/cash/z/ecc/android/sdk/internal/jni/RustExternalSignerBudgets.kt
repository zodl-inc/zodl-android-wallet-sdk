package cash.z.ecc.android.sdk.internal.jni

/** Internal bridge for the external signer budgets held by librustzcash. */
object RustExternalSignerBudgets {
    /**
     * The most actions one QR signing round may carry, as `zcash_pool_migration` defines it. Loads the native
     * library first if needed; needs no wallet or migration engine.
     */
    suspend fun qrSigningRoundMaxActions(): Int {
        RustBackend.loadLibrary()
        return getQrSigningRoundMaxActions()
    }

    @JvmStatic
    private external fun getQrSigningRoundMaxActions(): Int
}
