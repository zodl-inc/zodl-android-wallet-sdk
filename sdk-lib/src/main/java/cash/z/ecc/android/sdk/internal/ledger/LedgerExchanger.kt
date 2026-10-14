package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import cash.z.ecc.android.sdk.ledger.LedgerExchangeNotStartedException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/** The status word the device SDK answers a frame it refused before the app saw it. */
private const val CMD_NOT_ACCEPTED = 0x6901

/**
 * Moves commands over a [LedgerApduTransport] under the engine's rules:
 *
 * - an exchange that fails for any reason — cancellation included — leaves the device's reply
 *   uncollected, so the transport is closed before the failure propagates, and no later reply on it
 *   can be taken for the answer to another command. The one exception is
 *   [LedgerExchangeNotStartedException], the transport's own report that it refused the exchange
 *   before sending anything: the device never saw the command, so the transport stays open for
 *   whoever is using it, and the exception propagates as it is;
 * - a one-shot command the device refuses with `0x6901` is resent, identically, after the engine's
 *   backoff, up to the engine's retry budget.
 *
 * Commands and replies are never logged; they are wiped once used.
 */
internal class LedgerExchanger(
    private val transport: LedgerApduTransport,
    private val policy: LedgerPolicy
) {
    /**
     * Exchanges [apdu], closing the transport if the exchange fails — unless the transport reports
     * that it never started the exchange.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun exchange(
        apdu: ByteArray,
        timeout: Duration?
    ): ByteArray =
        try {
            transport.exchange(apdu, timeout)
        } catch (e: LedgerExchangeNotStartedException) {
            throw e
        } catch (e: Throwable) {
            Twig.warn { "Ledger exchange failed (${e.javaClass.simpleName}); closing the transport" }
            closeQuietly()
            throw e
        }

    /**
     * Runs a one-shot command: exchanges [apdu] and returns what [parse] makes of the reply, resending
     * while the device refuses the frame with `0x6901`.
     *
     * The reply is wiped as soon as [parse] returns, before its result reaches the caller, so [parse]
     * must copy any bytes of the reply it returns rather than return the reply or a view of it.
     *
     * @throws LedgerException.DeviceRefused once the retry budget is spent.
     */
    @Suppress("SwallowedException")
    suspend fun <T> query(
        apdu: ByteArray,
        timeout: Duration?,
        parse: (ByteArray) -> T
    ): T {
        try {
            repeat(policy.cmdNotAcceptedRetryBudget + 1) { attempt ->
                if (attempt > 0) {
                    delay(policy.cmdNotAcceptedBackoff)
                }
                val reply = exchange(apdu, timeout)
                try {
                    return parse(reply)
                } catch (e: CommandNotAcceptedException) {
                    Twig.debug { "Ledger device refused a frame (${e.javaClass.simpleName}); resending" }
                } finally {
                    reply.fill(0)
                }
            }
        } finally {
            apdu.fill(0)
        }
        throw LedgerException.DeviceRefused(
            statusWord = CMD_NOT_ACCEPTED,
            isTransient = true,
            isRestartable = true,
            reason = "the device refused the command repeatedly (0x6901)"
        )
    }

    /**
     * Closes the transport, even from a cancelled coroutine, ignoring a failure to close.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun closeQuietly() {
        try {
            withContext(NonCancellable) { transport.close() }
        } catch (e: Exception) {
            Twig.warn { "Closing the Ledger transport failed (${e.javaClass.simpleName})" }
        }
    }
}
