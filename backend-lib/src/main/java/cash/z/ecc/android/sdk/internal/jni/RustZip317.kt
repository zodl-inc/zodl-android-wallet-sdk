package cash.z.ecc.android.sdk.internal.jni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Internal bridge for the ZIP 317 fee constants held by librustzcash. */
object RustZip317 {
    /**
     * The ZIP 317 marginal fee in zatoshis, as `zcash_primitives` defines it. Loads the native library first if
     * needed. The native call runs on [Dispatchers.Default], so this never blocks the caller's dispatcher.
     */
    suspend fun marginalFee(): Long {
        RustBackend.loadLibrary()
        return withContext(Dispatchers.Default) { getMarginalFee() }
    }

    @JvmStatic
    private external fun getMarginalFee(): Long
}
