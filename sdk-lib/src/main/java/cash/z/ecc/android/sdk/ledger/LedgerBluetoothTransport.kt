package cash.z.ecc.android.sdk.ledger

import android.content.Context
import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackendImpl
import cash.z.ecc.android.sdk.internal.ledger.ble.AndroidGattLink
import cash.z.ecc.android.sdk.internal.ledger.ble.LedgerBleChannel
import cash.z.ecc.android.sdk.internal.ledger.ble.negotiateFrameSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [LedgerApduTransport] over Bluetooth LE.
 *
 * Commands are framed at the frame size the device negotiated and written to its write characteristic
 * (the write-without-response one when the device offers it, as LedgerHQ's own transports do); replies
 * are reassembled from its notifications. Framing and reassembly are the SDK's native Ledger engine's.
 *
 * One exchange runs at a time. An exchange that fails — a timeout ([LedgerException.Timeout]), a
 * disconnect ([LedgerException.Disconnected]), a reply that does not reassemble, or a cancellation —
 * closes the transport, and every later exchange fails with [LedgerException.Disconnected]: the
 * device's reply to the failed command may still arrive, and it must not be taken for the answer to
 * the next one. Connect again to continue.
 *
 * The app must hold the Bluetooth permissions for the running API level; see `docs/Ledger.md`.
 *
 * @param model The model whose Ledger service the connected device offers.
 */
class LedgerBluetoothTransport private constructor(
    private val channel: LedgerBleChannel,
    val model: LedgerDeviceModel
) : LedgerApduTransport {
    override suspend fun exchange(
        apdu: ByteArray,
        timeout: Duration?
    ): ByteArray = channel.exchange(apdu, timeout)

    override suspend fun close() = channel.close()

    companion object {
        /**
         * The default for [connect]'s timeout: long enough for a first connection, where the user
         * confirms the pairing code on both the phone and the device.
         */
        val DEFAULT_CONNECT_TIMEOUT: Duration = 60.seconds

        /**
         * Connects to [device], pairs with it if the phone has not bonded with it yet (the OS shows
         * its pairing prompt and the device shows a code), subscribes to its Ledger service and
         * negotiates the frame size.
         *
         * @param context Any context; the application context is kept.
         * @param device A device from [LedgerBluetoothScanner.devices].
         * @param connectTimeout How long the whole setup may take.
         * @throws LedgerException.BluetoothUnavailable if the phone has no Bluetooth LE.
         * @throws LedgerException.BluetoothUnauthorized if a required permission is not granted.
         * @throws LedgerException.BluetoothDisabled if Bluetooth is off.
         * @throws LedgerException.DeviceNotFound if the device is not a Ledger or not reachable.
         * @throws LedgerException.PairingRefused if Bluetooth pairing is declined or fails.
         * @throws LedgerException.Disconnected if the link drops once connected, during setup.
         * @throws LedgerException.ConnectionFailed for any other setup failure.
         */
        @Suppress("TooGenericExceptionCaught")
        suspend fun connect(
            context: Context,
            device: LedgerBluetoothDevice,
            connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT
        ): LedgerBluetoothTransport {
            val backend = TypesafeLedgerBackendImpl.new()
            val link = AndroidGattLink.open(context, device.identifier, connectTimeout)
            try {
                val frameSize = negotiateFrameSize(link, backend)
                return LedgerBluetoothTransport(
                    channel = LedgerBleChannel(link, backend, frameSize),
                    model = link.model ?: device.model
                )
            } catch (e: Throwable) {
                link.close()
                throw e
            }
        }
    }
}
