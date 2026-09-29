package cash.z.ecc.android.sdk.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.exception.isLinkFailure
import cash.z.ecc.android.sdk.internal.Twig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Brings a Ledger device to the Zcash app through the device's own dashboard commands, which answer
 * whatever app is running: which app is open, close it, open another one by name.
 *
 * A device returns to its dashboard after a first Bluetooth pairing, and every Zcash command then
 * fails. [ensureZcashAppOpen] opens the Zcash app for the user, who confirms it on the device, before
 * [LedgerDevice] is used.
 *
 * Nothing handled here is logged: not the commands, not the replies, not the app names.
 */
object LedgerZcashApp {
    private val QUERY_TIMEOUT = 10.seconds
    private val POLL_INTERVAL = 200.milliseconds
    private val APP_TRANSITION_TIMEOUT = 10.seconds

    /**
     * Reads which app the device is running. Works on the dashboard and in any app.
     *
     * @throws LedgerException.DeviceRefused if the device refuses the query; a locked device's
     *         refusal is transient.
     * @throws LedgerException.MalformedReply if the reply is not an app name and version.
     * @throws LedgerException for a transport failure.
     */
    suspend fun currentApp(transport: LedgerApduTransport): LedgerRunningApp =
        LedgerAppLauncher(QUERY_TIMEOUT, POLL_INTERVAL, APP_TRANSITION_TIMEOUT).currentApp(transport)

    /**
     * Makes sure the device is running the Zcash app, opening it if needed, and returns the transport
     * to keep using.
     *
     * The first query waits up to 10 seconds; if it stalls or the link fails, it is asked once more on
     * a fresh connection from [reconnect] before anything else is sent. The open command itself is
     * never sent twice.
     *
     * If the Zcash app is already open, nothing is sent beyond the query and the transport it was
     * answered on is returned.
     * Otherwise any other app is closed, the device is asked to open the Zcash app — it asks the user
     * to confirm, and the reply waits for that without a timeout — and the device is polled until it
     * reports the Zcash app, for up to 10 seconds after the reply. The Bluetooth link may drop while
     * the device switches apps; a dropped link is then replaced with one from [reconnect], and the
     * transport returned may be one of those.
     *
     * A transport whose link fails during the call is closed when it fails, before it is replaced.
     * The caller closes the one returned, and [transport] if the call fails; a transport opened
     * through [reconnect] is closed here when the call fails.
     *
     * @param transport A transport connected to the device.
     * @param reconnect Opens a new transport to the same device.
     * @throws LedgerException.AppNotInstalled if the Zcash app is not installed.
     * @throws LedgerException.AppOpenRejected if the user declines opening it on the device.
     * @throws LedgerException.DeviceRefused if the device is locked (transient) or refuses a
     *         command.
     * @throws LedgerException.WrongApp if the device does not reach the Zcash app in time, or cannot
     *         open it; the user has to open it on the device.
     * @throws LedgerException for any other failure.
     */
    suspend fun ensureZcashAppOpen(
        transport: LedgerApduTransport,
        reconnect: suspend () -> LedgerApduTransport
    ): LedgerApduTransport =
        LedgerAppLauncher(QUERY_TIMEOUT, POLL_INTERVAL, APP_TRANSITION_TIMEOUT)
            .ensureZcashAppOpen(transport, reconnect)
}

/**
 * [LedgerZcashApp]'s orchestration, with its timings as parameters.
 */
internal class LedgerAppLauncher(
    private val queryTimeout: Duration,
    private val pollInterval: Duration,
    private val transitionTimeout: Duration
) {
    suspend fun currentApp(transport: LedgerApduTransport): LedgerRunningApp {
        val reply = transport.exchange(getAppAndVersionApdu(), queryTimeout)
        try {
            val statusWord = statusWord(reply)
            if (statusWord != SW_OK) {
                throw refusal(statusWord, "the device refused the app query")
            }
            return parseRunningApp(reply)
        } finally {
            reply.fill(0)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun ensureZcashAppOpen(
        transport: LedgerApduTransport,
        reconnect: suspend () -> LedgerApduTransport
    ): LedgerApduTransport {
        val switch = AppSwitch(transport, reconnect)
        try {
            val running = switch.queryOnFreshConnectionOnce()
            if (running.isZcash) {
                return switch.current
            }
            if (!running.isDashboard) {
                Twig.debug { "Ledger is running another app; closing it" }
                switch.closeRunningApp()
                switch.awaitApp(deadline()) { it.isDashboard }
            }
            Twig.debug { "Asking the Ledger to open the Zcash app" }
            switch.openZcashApp()
            switch.awaitApp(deadline()) { it.isZcash }
            Twig.debug { "The Ledger is running the Zcash app" }
            return switch.current
        } catch (e: Throwable) {
            switch.closeReplacement()
            throw e
        }
    }

    private fun deadline() = TimeSource.Monotonic.markNow() + transitionTimeout

    /**
     * The transport through an app switch: [current] is the caller's until a dropped link is
     * replaced, and [usable] is false once it has failed.
     */
    private inner class AppSwitch(
        private val original: LedgerApduTransport,
        private val reconnect: suspend () -> LedgerApduTransport
    ) {
        var current: LedgerApduTransport = original
            private set
        private var usable = true

        /**
         * The running app before anything else is sent. A query that stalls past its deadline or loses
         * the link is asked once more on a fresh connection from [reconnect]; a second failure, and
         * any answer from the device, is final.
         */
        suspend fun queryOnFreshConnectionOnce(): LedgerRunningApp {
            queryAllowingDisconnect()?.let { return it }
            Twig.debug { "Ledger app query failed; asking once more on a fresh connection" }
            current = reconnect()
            usable = true
            return currentApp(current)
        }

        suspend fun closeRunningApp() {
            val reply = exchangeAllowingDisconnect(closeAppApdu(), queryTimeout) ?: return
            val statusWord = statusWord(reply)
            if (statusWord != SW_OK) {
                throw refusal(statusWord, "the device refused to close the running app")
            }
        }

        suspend fun openZcashApp() {
            val reply = exchangeAllowingDisconnect(openZcashAppApdu(), timeout = null) ?: return
            openFailure(statusWord(reply))?.let { throw it }
        }

        /**
         * Polls the device, reconnecting when the link is down, until it runs an app [reached]
         * accepts. The deadline is checked between attempts.
         */
        suspend fun awaitApp(
            deadline: TimeSource.Monotonic.ValueTimeMark,
            reached: (LedgerRunningApp) -> Boolean
        ) {
            while (true) {
                if (poll(reached)) {
                    return
                }
                if (deadline.hasPassedNow()) {
                    throw LedgerException.WrongApp(
                        statusWord = null,
                        reason = "the device did not switch apps in time"
                    )
                }
                delay(pollInterval)
            }
        }

        private suspend fun poll(reached: (LedgerRunningApp) -> Boolean): Boolean {
            val running = if (usable || replace()) queryAllowingDisconnect() else null
            return running != null && reached(running)
        }

        /**
         * The running app, or null if the link dropped.
         */
        private suspend fun queryAllowingDisconnect(): LedgerRunningApp? =
            try {
                currentApp(current)
            } catch (e: LedgerException) {
                if (!e.isLinkFailure()) throw e
                Twig.debug { "Ledger link dropped while switching apps (${e.javaClass.simpleName})" }
                markUnusable()
                null
            }

        /**
         * Replaces the dropped transport, which [markUnusable] has already closed, with a new one;
         * false if the device cannot be reached yet.
         */
        private suspend fun replace(): Boolean {
            val replacement =
                try {
                    reconnect()
                } catch (e: LedgerException) {
                    if (!e.isLinkFailure()) throw e
                    Twig.debug { "Reconnecting to the Ledger failed (${e.javaClass.simpleName}); retrying" }
                    return false
                }
            current = replacement
            usable = true
            return true
        }

        private suspend fun exchangeAllowingDisconnect(
            apdu: ByteArray,
            timeout: Duration?
        ): ByteArray? =
            try {
                current.exchange(apdu, timeout)
            } catch (e: LedgerException) {
                if (!e.isLinkFailure()) throw e
                Twig.debug { "Ledger link dropped during an app switch (${e.javaClass.simpleName})" }
                markUnusable()
                null
            }

        private suspend fun markUnusable() {
            usable = false
            closeQuietly(current)
        }

        /**
         * Closes a transport opened through [reconnect] that is still open; one that failed was
         * closed then.
         */
        suspend fun closeReplacement() {
            if (current !== original && usable) {
                closeQuietly(current)
            }
        }
    }

    internal companion object {
        const val SW_OK = 0x9000
        const val SW_USER_REFUSED = 0x5501
        const val SW_LOCKED = 0x5515
        const val SW_APP_NOT_INSTALLED = 0x6807

        private const val CLA_DASHBOARD: Byte = 0xB0.toByte()
        private const val CLA_OPEN_APP: Byte = 0xE0.toByte()
        private const val INS_GET_APP_AND_VERSION: Byte = 0x01
        private const val INS_CLOSE_APP: Byte = 0xA7.toByte()
        private const val INS_OPEN_APP: Byte = 0xD8.toByte()
        private const val NO_PARAM: Byte = 0x00
        private const val FORMAT_APP_AND_VERSION: Byte = 0x01
        private const val HEX_RADIX = 16
        private const val STATUS_WORD_HEX_DIGITS = 4
        private const val STATUS_WORD_SIZE = 2
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8

        fun getAppAndVersionApdu() = byteArrayOf(CLA_DASHBOARD, INS_GET_APP_AND_VERSION, NO_PARAM, NO_PARAM, 0)

        fun closeAppApdu() = byteArrayOf(CLA_DASHBOARD, INS_CLOSE_APP, NO_PARAM, NO_PARAM, 0)

        fun openZcashAppApdu(): ByteArray {
            val name = LedgerRunningApp.ZCASH_APP_NAME.toByteArray(Charsets.US_ASCII)
            return byteArrayOf(CLA_OPEN_APP, INS_OPEN_APP, NO_PARAM, NO_PARAM, name.size.toByte()) + name
        }

        fun statusWord(reply: ByteArray): Int {
            if (reply.size < STATUS_WORD_SIZE) {
                throw LedgerException.MalformedReply(reason = "the device's reply has no status word")
            }
            return ((reply[reply.size - 2].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
                (reply[reply.size - 1].toInt() and BYTE_MASK)
        }

        /**
         * Parses `format ‖ nameLen ‖ name ‖ versionLen ‖ version ‖ [flagsLen ‖ flags]` followed by the
         * status word. Trailing flags are not read.
         */
        fun parseRunningApp(reply: ByteArray): LedgerRunningApp {
            val end = reply.size - STATUS_WORD_SIZE
            var cursor = 0

            fun malformed() = LedgerException.MalformedReply(reason = "the device's app query reply is malformed")

            fun readField(): String {
                if (cursor >= end) throw malformed()
                val length = reply[cursor].toInt() and BYTE_MASK
                val start = cursor + 1
                if (start + length > end) throw malformed()
                cursor = start + length
                return String(reply, start, length, Charsets.US_ASCII)
            }

            if (end < 1 || reply[cursor] != FORMAT_APP_AND_VERSION) throw malformed()
            cursor++
            val name = readField()
            val version = readField()
            return LedgerRunningApp(name = name, version = version)
        }

        /**
         * The locked-device refusal as the Ledger engine reports it: transient, and restartable.
         * Any other status word is final.
         */
        fun refusal(
            statusWord: Int,
            reason: String
        ): LedgerException.DeviceRefused {
            val locked = statusWord == SW_LOCKED
            return LedgerException.DeviceRefused(
                statusWord = statusWord,
                isTransient = locked,
                isRestartable = locked,
                reason = "$reason (${hex(statusWord)})"
            )
        }

        /**
         * What an answer to the open command means: nothing to raise when the device is opening the
         * app, else the failure.
         */
        fun openFailure(statusWord: Int): LedgerException? =
            when (statusWord) {
                SW_OK -> {
                    null
                }

                SW_USER_REFUSED -> {
                    LedgerException.AppOpenRejected()
                }

                SW_APP_NOT_INSTALLED -> {
                    LedgerException.AppNotInstalled()
                }

                SW_LOCKED -> {
                    refusal(statusWord, "the device is locked")
                }

                else -> {
                    LedgerException.WrongApp(
                        statusWord = statusWord,
                        reason = "the device could not open the Zcash app (${hex(statusWord)})"
                    )
                }
            }

        fun hex(statusWord: Int) =
            "0x" + statusWord.toString(HEX_RADIX).uppercase().padStart(STATUS_WORD_HEX_DIGITS, '0')

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun closeQuietly(transport: LedgerApduTransport) {
            try {
                withContext(NonCancellable) { transport.close() }
            } catch (e: Exception) {
                Twig.warn { "Closing the Ledger transport failed (${e.javaClass.simpleName})" }
            }
        }
    }
}
