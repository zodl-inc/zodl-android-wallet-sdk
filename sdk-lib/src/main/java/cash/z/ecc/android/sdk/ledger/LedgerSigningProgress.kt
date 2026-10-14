package cash.z.ecc.android.sdk.ledger

/**
 * Where a Ledger signing ceremony is, as reported to the `onProgress` callback of
 * `Synchronizer.signPcztWithLedger`, in this order.
 */
sealed interface LedgerSigningProgress {
    /**
     * Confirming that the connected device runs a Zcash app that can sign, and that it is the device
     * the account was paired with. No transaction data has been sent.
     */
    data object IdentifyingDevice : LedgerSigningProgress

    /**
     * Sending the transaction to the device.
     *
     * @param sent Commands exchanged so far, the two identification commands included.
     * @param total Commands the whole ceremony exchanges.
     */
    data class Streaming(
        val sent: Int,
        val total: Int
    ) : LedgerSigningProgress

    /**
     * The transaction is on the device's screen, waiting for the user to review and approve it. No
     * timeout applies from here on; tell the user to look at the device.
     */
    data object AwaitingReviewOnDevice : LedgerSigningProgress

    /**
     * The user approved; the device is producing signatures.
     */
    data object Signing : LedgerSigningProgress

    /**
     * Every signature has been collected and verified.
     */
    data object Complete : LedgerSigningProgress
}
