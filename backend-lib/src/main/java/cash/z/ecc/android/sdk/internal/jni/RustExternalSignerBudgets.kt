package cash.z.ecc.android.sdk.internal.jni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Internal bridge for the external signer budgets held by librustzcash. */
object RustExternalSignerBudgets {
    /**
     * The most actions one QR signing round may carry, as `zcash_pool_migration` defines it. Loads the native
     * library first if needed; needs no wallet or migration engine. Both the load and the native call run on
     * [Dispatchers.Default], so this never blocks the caller's dispatcher.
     */
    suspend fun qrSigningRoundMaxActions(): Int =
        withContext(Dispatchers.Default) {
            RustBackend.loadLibrary()
            getQrSigningRoundMaxActions()
        }

    @JvmStatic
    private external fun getQrSigningRoundMaxActions(): Int
}
