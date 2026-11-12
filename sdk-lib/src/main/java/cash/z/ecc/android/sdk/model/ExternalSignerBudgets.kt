package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.jni.RustExternalSignerBudgets

/**
 * Limits of external signers, as defined by librustzcash.
 */
object ExternalSignerBudgets {
    /** Where the value is read; replaced only by tests. */
    internal var source: suspend () -> Int = RustExternalSignerBudgets::qrSigningRoundMaxActions

    @Volatile
    private var cachedQrSigningRoundMaxActions: Int? = null

    /**
     * The most Orchard-family actions one signing round of a signer that signs over animated QR codes (such as
     * Keystone) may carry, counted across every transaction signed in that round, not per transaction. Use it to
     * bound what a QR signer is asked to sign at once, for example with
     * [TransactionSizeBound.maxShieldedActions].
     *
     * The value comes from `zcash_pool_migration` (`SigningRoundBudget::KEYSTONE`) through the Rust backend; it needs
     * no wallet, and no migration to have been started. The first call loads the native library if nothing has yet,
     * on a background dispatcher, and suspends rather than blocking the caller; the value is then cached.
     */
    suspend fun qrSigningRoundMaxActions(): Int =
        cachedQrSigningRoundMaxActions ?: source().also { cachedQrSigningRoundMaxActions = it }

    /** Forgets the cached value; tests only. */
    internal fun resetForTest() {
        cachedQrSigningRoundMaxActions = null
    }
}
