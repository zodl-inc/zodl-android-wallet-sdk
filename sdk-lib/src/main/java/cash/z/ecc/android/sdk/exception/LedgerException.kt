package cash.z.ecc.android.sdk.exception

/**
 * Failures of the Ledger hardware-wallet integration: pairing an account, displaying an address,
 * signing a PCZT over a [cash.z.ecc.android.sdk.ledger.LedgerApduTransport], and the Bluetooth LE
 * transport itself.
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
     * Signing found that the connected device is not the one the account's binding names.
     * Pairing reads the device identity once and can no longer raise this. Nothing of the
     * transaction was sent to it.
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
     * This device has no Bluetooth LE support, or a Bluetooth LE scan could not be started.
     *
     * @param scanErrorCode The `ScanCallback.SCAN_FAILED_*` code when a scan failed to start.
     */
    class BluetoothUnavailable internal constructor(
        val scanErrorCode: Int? = null
    ) : LedgerException("Bluetooth LE is not available on this device.")

    /**
     * A Bluetooth permission the app needs for this API level is not granted; see `docs/Ledger.md`.
     *
     * @param missingPermissions The permissions not granted, when known.
     */
    class BluetoothUnauthorized internal constructor(
        val missingPermissions: List<String>,
        cause: Throwable? = null
    ) : LedgerException("Bluetooth permission has not been granted.", cause)

    /**
     * Bluetooth is turned off.
     */
    class BluetoothDisabled internal constructor() : LedgerException("Bluetooth is turned off.")

    /**
     * The Ledger device could not be found: the address is not a Bluetooth device, or the device
     * that answered offers no Ledger service.
     */
    class DeviceNotFound internal constructor() : LedgerException("The Ledger device could not be found.")

    /**
     * The Bluetooth connection to the device could not be set up. Make sure the device is on,
     * unlocked, nearby, and not connected to another phone.
     */
    class ConnectionFailed internal constructor(
        override val reason: String?,
        cause: Throwable? = null
    ) : LedgerException("Could not connect to the Ledger device.", cause)

    /**
     * The device disconnected, or the transport was closed, or an earlier exchange failed and left
     * the connection unusable. Connect again.
     */
    class Disconnected internal constructor() : LedgerException("The Ledger device is disconnected.")

    /**
     * Bluetooth pairing with the device was refused or failed. The user has to accept the pairing
     * request on both the phone and the device; if the device was reset or paired elsewhere, the
     * user has to remove it from the phone's Bluetooth settings first.
     */
    class PairingRefused internal constructor(
        override val reason: String?
    ) : LedgerException("Bluetooth pairing with the Ledger device failed.")

    /**
     * The device did not answer in time. The connection is closed; connect again.
     */
    class Timeout internal constructor() : LedgerException("The Ledger device did not answer in time.")

    /**
     * An unexpected failure on this side of the transport.
     */
    class Internal internal constructor(
        cause: Throwable?
    ) : LedgerException("An internal error occurred in the Ledger integration.", cause)
}
