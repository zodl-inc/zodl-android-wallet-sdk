package cash.z.ecc.android.sdk.ledger

import kotlin.time.Duration

/**
 * A request/response channel to one Ledger device.
 *
 * [LedgerDevice] and `Synchronizer.signPcztWithLedger` drive a device through it. The SDK's Bluetooth
 * LE implementation is [LedgerBluetoothTransport]; tests and other channels implement this interface
 * directly.
 *
 * # Contract
 *
 * - [exchange] sends one command APDU and returns the device's whole reply: response data followed
 *   by the two-byte status word. A transport never inspects the status word; a device that answers
 *   "denied" is a successful exchange.
 * - One exchange at a time. A transport that queues a second caller and the caller is cancelled
 *   before its exchange begins should throw [LedgerExchangeNotStartedException] rather than a plain
 *   cancellation: nothing was sent, and the SDK then leaves the transport open for the exchange that
 *   is actually running. The SDK's own ceremonies hold a transport for all of their commands (see
 *   `LedgerDevice`), so they never queue here themselves.
 * - **One reply per command.** An exchange that fails after its command was written (a timeout, a
 *   disconnect, a frame that does not reassemble) leaves the device's reply uncollected, and a later
 *   reply must never be taken for the answer to a later command. A transport in that state must
 *   refuse every further exchange; the recovery is a new connection. A transport never resends a
 *   command on its own: the one legitimate resend, after the device's `0x6901`, is the SDK's
 *   decision.
 * - Neither the command nor the reply may be logged: commands carry the transaction's secrets and
 *   replies carry keys, addresses and signatures.
 */
interface LedgerApduTransport {
    /**
     * Sends [apdu] and returns the reply.
     *
     * @param apdu The command.
     * @param timeout How long to wait for the reply, or `null` to wait indefinitely — the case for a
     *        reply that waits on the user reviewing something on the device.
     * @return The response data followed by the status word.
     */
    suspend fun exchange(
        apdu: ByteArray,
        timeout: Duration?
    ): ByteArray

    /**
     * Closes the channel. Idempotent; an exchange in progress fails.
     */
    suspend fun close()
}
