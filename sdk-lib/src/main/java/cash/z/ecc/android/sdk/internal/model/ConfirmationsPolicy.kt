package cash.z.ecc.android.sdk.internal.model

/**
 * The ZIP 315 confirmations policy this SDK applies, mirroring librustzcash's
 * `ConfirmationsPolicy::default()` (`{trusted: 3, untrusted: 10, allow_zero_conf_shielding: true}`),
 * which the Rust backend uses for balances and spends.
 *
 * Kotlin needs these numbers wherever it reasons about confirmations itself: the Slipstream
 * engine passes them to its wallet summary, and [cash.z.ecc.android.sdk.model.TransactionState]
 * uses them to decide when a transaction is confirmed.
 */
internal object ConfirmationsPolicy {
    /** Confirmations after which a trusted transaction's outputs are spendable. */
    const val TRUSTED_CONFIRMATIONS = 3

    /** Confirmations after which an untrusted transaction's outputs are spendable. */
    const val UNTRUSTED_CONFIRMATIONS = 10

    /** Whether shielding transparent funds may spend zero-confirmation UTXOs. */
    const val ALLOW_ZERO_CONF_SHIELDING = true

    /**
     * Confirmations after which a transaction is reported as confirmed. A received transaction
     * the wallet trusts (e.g. a gift-card claim recorded as trusted) is confirmed once its outputs
     * are spendable, at [TRUSTED_CONFIRMATIONS]; every other transaction, including anything this
     * wallet sent, at [UNTRUSTED_CONFIRMATIONS].
     */
    fun requiredConfirmations(
        isSentTransaction: Boolean,
        isTrusted: Boolean
    ): Int =
        if (isTrusted && !isSentTransaction) {
            TRUSTED_CONFIRMATIONS
        } else {
            UNTRUSTED_CONFIRMATIONS
        }
}
