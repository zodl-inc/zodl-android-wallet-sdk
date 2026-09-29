package cash.z.ecc.android.sdk.internal.ledger.ble

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.ledger.LedgerBleDeframer
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackend
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A GATT connection to a Ledger, reduced to what the APDU channel needs.
 */
internal interface LedgerBleLink {
    /**
     * Writes one frame to the device's write characteristic, returning once the stack has taken it.
     *
     * @throws LedgerException.Disconnected if the link is down.
     * @throws LedgerException.PairingRefused if the device refuses the write on an unauthenticated
     *         link.
     */
    suspend fun write(frame: ByteArray)

    /**
     * Every notification of the device's notify characteristic, in arrival order. Closed with
     * [LedgerException.Disconnected] when the link drops.
     */
    val notifications: ReceiveChannel<ByteArray>

    /** Disconnects and releases the link. Idempotent. */
    fun close()
}

/**
 * The APDU channel over a [LedgerBleLink]: frames each command at the negotiated frame size,
 * reassembles the device's notifications into its reply, and enforces the transport rules the Ledger
 * engine requires.
 *
 * - One exchange at a time.
 * - **One reply per command.** An exchange that fails for any reason — a timeout, a disconnect, a
 *   frame that does not reassemble, cancellation — leaves the device's reply uncollected, so the
 *   channel is poisoned: the link is closed and every later exchange fails with
 *   [LedgerException.Disconnected]. A notification that arrives while no command is outstanding
 *   answers nothing, so it poisons the channel too.
 * - Frames and replies are never logged, and are wiped once used.
 */
internal class LedgerBleChannel(
    private val link: LedgerBleLink,
    private val backend: TypesafeLedgerBackend,
    private val frameSize: Int
) {
    private val mutex = Mutex()
    private val deframer: LedgerBleDeframer = backend.newBleDeframer()

    @Volatile
    private var dead = false

    // The timeout's own exception carries nothing `LedgerException.Timeout` does not say.
    @Suppress("ThrowsCount", "SwallowedException")
    suspend fun exchange(
        apdu: ByteArray,
        timeout: Duration?
    ): ByteArray =
        mutex.withLock {
            if (dead) {
                throw LedgerException.Disconnected()
            }
            if (link.notifications.tryReceive().isSuccess) {
                Twig.warn { "Ledger BLE notification arrived with no command outstanding; closing" }
                poison()
                throw LedgerException.MalformedReply(reason = "the device sent a reply to no command")
            }
            val frames = backend.bleFrames(apdu, frameSize)
            try {
                val reply =
                    if (timeout == null) {
                        send(frames)
                    } else {
                        withTimeout(timeout) { send(frames) }
                    }
                deframer.reset()
                reply
            } catch (e: TimeoutCancellationException) {
                poison()
                currentCoroutineContext().ensureActive()
                Twig.warn { "Ledger BLE exchange timed out after $timeout; closing" }
                throw LedgerException.Timeout()
            } catch (e: CancellationException) {
                poison()
                throw e
            } catch (e: LedgerException) {
                Twig.warn { "Ledger BLE exchange failed (${e.javaClass.simpleName}); closing" }
                poison()
                throw e
            } finally {
                frames.forEach { it.fill(0) }
            }
        }

    /** Closes the channel and its link. Idempotent. */
    fun close() {
        poison()
    }

    private suspend fun send(frames: List<ByteArray>): ByteArray {
        frames.forEach { link.write(it) }
        while (true) {
            val frame =
                link.notifications.receiveCatching().getOrElse {
                    throw it as? LedgerException ?: LedgerException.Disconnected()
                }
            try {
                deframer.push(frame)?.let { return it }
            } finally {
                frame.fill(0)
            }
        }
    }

    @Synchronized
    private fun poison() {
        if (!dead) {
            dead = true
            link.close()
            deframer.close()
        }
    }
}
