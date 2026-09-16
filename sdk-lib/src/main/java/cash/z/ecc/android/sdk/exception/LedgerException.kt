package cash.z.ecc.android.sdk.exception

/**
 * Failures of the Ledger hardware-wallet integration: pairing an account, displaying an address, and
 * signing a PCZT over a [cash.z.ecc.android.sdk.ledger.LedgerApduTransport].
 *
 * Messages are fixed text. Nothing here carries an APDU, a device reply, a PCZT, a viewing key, an
 * address, a device identity or a signature; [reason], where present, is built from text written
 * to be loggable and is safe to show in a log or a support report.
 *
 * [isRestartable] says whether starting the whole operation again — a fresh signing session over
 * the same PCZT, a fresh pairing — could succeed. It never means "resend the last command".
 */
sealed class LedgerException(
    message: String,
    cause: Throwable? = null
) : SdkException(message, cause) {
    /**
     * Whether starting the whole operation again could succeed.
     */
    open val isRestartable: Boolean = false

    /**
     * A human-readable description of what went wrong, when the engine provides one beyond the
     * fixed message. Safe to log.
     */
    open val reason: String? = null

    /**
     * The user declined on the device: the transaction review, the viewing key export, or the
     * address display.
     */
    class UserRejected internal constructor(
        override val isRestartable: Boolean
    ) : LedgerException("The request was declined on the Ledger device.")

    /**
     * The device is not running the Zcash app, or runs a version that does not know the command.
     * The user has to open the Zcash app on the device.
     *
     * @param statusWord The status word the device answered.
     */
    class WrongApp internal constructor(
        val statusWord: Int?,
        override val reason: String?
    ) : LedgerException("The Zcash app is not open on the Ledger device.")

    /**
     * The Zcash app on the device is too old for the request: it predates PCZT signing, or the
     * Ironwood pool a version 6 transaction needs. The user has to update the app.
     */
    class AppTooOld internal constructor(
        override val reason: String?
    ) : LedgerException("The Zcash app on the Ledger device is too old; update it and try again.")

    /**
     * The connected device is not the Ledger the account was paired with, or the device's identity
     * changed while an account was being paired. Nothing of the transaction was sent to it.
     */
    class DeviceMismatch internal constructor() :
        LedgerException("The connected Ledger device is not the one this account was paired with.")

    /**
     * The connected device's Zcash app version is not the one the signing session was built for;
     * the app was updated or swapped mid-operation. Nothing of the transaction was sent to it.
     */
    class CapsMismatch internal constructor(
        override val reason: String?
    ) : LedgerException("The Zcash app on the Ledger device changed during the operation.")

    /**
     * The Zcash app's per-run Orchard key derivation budget is spent. Closing and reopening the Zcash
     * app on the device clears it.
     */
    class DerivationBudgetExhausted internal constructor() :
        LedgerException("Close and reopen the Zcash app on the Ledger device, then try again.")

    /**
     * The device refused a command. Its context for the operation is gone.
     *
     * @param statusWord The status word the device answered.
     * @param isTransient Whether the status word describes a device condition that can clear on its
     *        own (a locked device, a desynchronized transport), as opposed to a function of what
     *        was sent.
     */
    class DeviceRefused internal constructor(
        val statusWord: Int,
        val isTransient: Boolean,
        override val isRestartable: Boolean,
        override val reason: String?
    ) : LedgerException("The Ledger device refused the request.")

    /**
     * The transaction cannot be signed by a Ledger device: a validation, shaping or device-limit rule
     * refuses it before anything is sent — Sapling funds, too many outputs to review, an account
     * without an Orchard key, and so on. [reason] names the rule.
     */
    class TransactionNotSignable internal constructor(
        override val reason: String?
    ) : LedgerException("This transaction cannot be signed with a Ledger device.")

    /**
     * The device's reply did not have the shape the protocol promises, a frame did not reassemble,
     * or a signature the device returned did not verify.
     */
    class MalformedReply internal constructor(
        override val reason: String?
    ) : LedgerException("The Ledger device sent a reply that could not be used.")

    /**
     * A value passed to the integration was refused before any device I/O: a stored device
     * identity, an account or address index, or an account that is not in the wallet.
     */
    class InvalidInput internal constructor(
        override val reason: String?
    ) : LedgerException("A value passed to the Ledger integration is invalid.")

    /**
     * An unexpected failure on this side of the transport.
     */
    class Internal internal constructor(
        cause: Throwable?
    ) : LedgerException("An internal error occurred in the Ledger integration.", cause)
}
