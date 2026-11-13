package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.jni.RustZip317

/**
 * Constants of the ZIP 317 fee rule, as defined by librustzcash.
 */
object Zip317 {
    @Volatile
    private var cachedMarginalFee: Zatoshi? = null

    /**
     * The ZIP 317 marginal fee: the fee paid per logical action of a transaction (5,000 zatoshi).
     *
     * The value comes from `zcash_primitives` through the Rust backend. The first call loads the native library if
     * nothing has yet, on a background dispatcher, and suspends rather than blocking the caller; the value is then
     * cached, and later calls return it without crossing the JNI boundary.
     */
    suspend fun marginalFee(): Zatoshi =
        cachedMarginalFee ?: Zatoshi(RustZip317.marginalFee()).also { cachedMarginalFee = it }
}
