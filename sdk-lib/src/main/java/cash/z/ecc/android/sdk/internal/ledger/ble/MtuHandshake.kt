package cash.z.ecc.android.sdk.internal.ledger.ble

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackend
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `MTU_OP`, the tag of the Ledger MTU handshake frame and its reply. */
private const val MTU_OP: Byte = 0x08

/** How long the device has to answer the handshake. */
internal val MTU_HANDSHAKE_TIMEOUT = 30.seconds

/**
 * Runs the Ledger MTU handshake over a freshly subscribed link and returns the frame size the device
 * negotiated (the whole frame, header included).
 *
 * The handshake frame is the first write on a new link. LedgerHQ's Android transport reads a refusal
 * of that write as the user having refused the Bluetooth pairing, and so does this. A write the
 * Bluetooth stack never confirms is not a refusal: it is [LedgerException.ConnectionFailed]. A link
 * that drops under the write stays [LedgerException.Disconnected]. The caller's own cancellation, a
 * timeout of its own included, is rethrown as it is.
 *
 * @throws LedgerException.PairingRefused if the device refuses the handshake frame on an
 *         unauthenticated link.
 * @throws LedgerException.Disconnected if the link drops before the handshake completes.
 * @throws LedgerException.ConnectionFailed if the handshake frame's write or the device's answer does
 *         not complete in time.
 */
internal suspend fun negotiateFrameSize(
    link: LedgerBleLink,
    backend: TypesafeLedgerBackend,
    timeout: Duration = MTU_HANDSHAKE_TIMEOUT
): Int {
    writeMtuRequest(link, backend)
    val reply =
        try {
            withTimeout(timeout) { awaitMtuReply(link) }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw LedgerException.ConnectionFailed(reason = "the device did not answer the MTU handshake", cause = e)
        }
    val frameSize = backend.parseBleMtuResponse(reply)
    Twig.debug { "Ledger BLE frame size is $frameSize" }
    return frameSize
}

private suspend fun writeMtuRequest(
    link: LedgerBleLink,
    backend: TypesafeLedgerBackend
) {
    try {
        link.write(backend.bleMtuRequest())
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw LedgerException.ConnectionFailed(reason = "the device did not accept the MTU handshake write", cause = e)
    } catch (e: LedgerException.PairingRefused) {
        Twig.warn { "Ledger MTU handshake write was refused (${e.reason})" }
        throw LedgerException.PairingRefused(reason = "the device refused the first write on the link")
    }
}

private suspend fun awaitMtuReply(link: LedgerBleLink): ByteArray {
    while (true) {
        val notification =
            link.notifications.receiveCatching().getOrElse {
                throw it as? LedgerException ?: LedgerException.Disconnected()
            }
        // Anything else before the reply belongs to no command and is dropped, as LedgerHQ's own
        // transports drop it.
        if (notification.isNotEmpty() && notification[0] == MTU_OP) {
            return notification
        }
    }
}
