package cash.z.ecc.android.sdk.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.exception.isLinkFailure
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.ledger.LedgerCeremony
import cash.z.ecc.android.sdk.internal.ledger.LedgerCeremonyHold
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
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
    private val POLL_TIMEOUT = 3.seconds
    private val POLL_INTERVAL = 200.milliseconds
    private val APP_TRANSITION_TIMEOUT = 10.seconds
    private val SWITCH_RECONNECT_TIMEOUT = 10.seconds
    private val SWITCH_RECONNECT_WINDOW = 10.seconds
    private val SWITCH_RECONNECT_FIRST_BACKOFF = 500.milliseconds
    private val SWITCH_RECONNECT_MAX_BACKOFF = 2.seconds
    private val APP_SWITCH_OVERALL_TIMEOUT = 60.seconds

    private fun launcher() =
        LedgerAppLauncher(
            queryTimeout = QUERY_TIMEOUT,
            pollTimeout = POLL_TIMEOUT,
            pollInterval = POLL_INTERVAL,
            transitionTimeout = APP_TRANSITION_TIMEOUT,
            reconnectPolicy =
                SwitchReconnectPolicy(
                    attemptTimeout = SWITCH_RECONNECT_TIMEOUT,
                    retryWindow = SWITCH_RECONNECT_WINDOW,
                    firstBackoff = SWITCH_RECONNECT_FIRST_BACKOFF,
                    maxBackoff = SWITCH_RECONNECT_MAX_BACKOFF
                ),
            switchOverallTimeout = APP_SWITCH_OVERALL_TIMEOUT
        )

    /**
     * Reads which app the device is running. Works on the dashboard and in any app.
     *
     * @throws LedgerException.DeviceRefused if the device refuses the query; a locked device's
     *         refusal is transient.
     * @throws LedgerException.MalformedReply if the reply is not an app name and version.
     * @throws LedgerException for a transport failure.
     */
    suspend fun currentApp(transport: LedgerApduTransport): LedgerRunningApp =
        // Holds `transport` for the query, so it never lands inside another ceremony's run of
        // commands; see `LedgerCeremony`.
        LedgerCeremony.run(transport) { launcher().currentApp(transport) }

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
     * reports the Zcash app, for up to 10 seconds after the reply, each poll waiting up to 3 seconds
     * for its answer. The time a reconnect that succeeds takes does not count towards those 10
     * seconds.
     *
     * The Bluetooth link may drop while the device switches apps; a dropped link is then replaced
     * with one from [reconnect], and the transport returned may be one of those. A poll that loses
     * the link or gets no answer is always followed by such a reconnect before the 10 seconds can end
     * the wait; once they have passed, a link replaced after them gets one poll. Each of those
     * reconnects has 10 seconds and is cancelled past them: the phone has already bonded with the
     * device, so none of it waits on a Bluetooth pairing. A reconnect that fails is tried again,
     * since the device may still be rebooting into the app: first after 500 milliseconds, then after
     * twice the previous wait, at most 2 seconds, and never past 10 seconds from the first of the
     * reconnects that failed in a row. The first reconnect that fails once those 10 seconds have
     * passed propagates its failure; one that succeeds ends the row, and the next failure starts over
     * from 500 milliseconds and a new 10 seconds. A reconnect that fails also starts the 10 seconds
     * of polling over. However the link behaves, waiting for the device to switch apps ends 60
     * seconds after the wait started, at the next poll or reconnect: with the failure of the last
     * reconnect if it failed, and with [LedgerException.WrongApp] otherwise; no backoff runs past
     * those 60 seconds. The reconnect after a failed first query also has 10 seconds, and is not
     * retried.
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
     * @throws LedgerException.ConnectionFailed if the reconnect after a failed first query, or the
     *         last reconnect during the switch, runs out of its 10 seconds; another link failure from
     *         [reconnect] propagates as it is, also when the switch ends after its 60 seconds.
     * @throws LedgerException for any other failure.
     */
    suspend fun ensureZcashAppOpen(
        transport: LedgerApduTransport,
        reconnect: suspend () -> LedgerApduTransport
    ): LedgerApduTransport =
        // Holds `transport` for the whole switch, so no other ceremony the SDK runs over it can put a
        // command between the query and the open; see `LedgerCeremony`. A transport `reconnect`
        // returns is new and is not under this hold while `reconnect` runs; the switch takes that
        // transport's own ceremony before its first command on it and keeps it until it is replaced
        // in turn or the switch is over, exactly as `LedgerDevice.pairAccount` does.
        LedgerCeremony.run(transport) { launcher().ensureZcashAppOpen(transport, reconnect) }
}

/**
 * How [LedgerAppLauncher] reconnects while the device switches apps: each attempt has
 * [attemptTimeout]; a failed one is tried again after a backoff that starts at [firstBackoff] and
 * doubles up to [maxBackoff], until [retryWindow] has passed since the first of the attempts that
 * failed in a row.
 */
internal data class SwitchReconnectPolicy(
    val attemptTimeout: Duration,
    val retryWindow: Duration,
    val firstBackoff: Duration,
    val maxBackoff: Duration
)

/**
 * [LedgerZcashApp]'s orchestration, with its timings and its clock as parameters.
 */
@Suppress("LongParameterList")
internal class LedgerAppLauncher(
    private val queryTimeout: Duration,
    private val pollTimeout: Duration,
    private val pollInterval: Duration,
    private val transitionTimeout: Duration,
    private val reconnectPolicy: SwitchReconnectPolicy,
    private val switchOverallTimeout: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    suspend fun currentApp(transport: LedgerApduTransport): LedgerRunningApp = queryApp(transport, queryTimeout)

    private suspend fun queryApp(
        transport: LedgerApduTransport,
        timeout: Duration
    ): LedgerRunningApp {
        val reply = transport.exchange(getAppAndVersionApdu(), timeout)
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
            if (!running.isZcash) {
                if (!running.isDashboard) {
                    Twig.debug { "Ledger is running another app; closing it" }
                    switch.closeRunningApp()
                    switch.awaitApp { it.isDashboard }
                }
                Twig.debug { "Asking the Ledger to open the Zcash app" }
                switch.openZcashApp()
                switch.awaitApp { it.isZcash }
                Twig.debug { "The Ledger is running the Zcash app" }
            }
        } catch (e: Throwable) {
            switch.closeReplacement()
            switch.releaseReplacementHold()
            throw e
        }
        // The caller gets the transport to run its own ceremony on, so the switch's hold on a
        // replacement ends here; the caller's original stays held by `LedgerZcashApp` until this
        // returns.
        switch.releaseReplacementHold()
        return switch.current
    }

    private fun deadline() = timeSource.markNow() + transitionTimeout

    /**
     * The clock of one [AppSwitch.awaitApp] wait: its deadline, and the run of reconnects that
     * failed in a row.
     *
     * A failed reconnect is tried again after a backoff that starts at the policy's first backoff
     * and doubles up to its maximum. Once the policy's retry window has passed since the first of
     * the failed reconnects in a row, the next failed reconnect propagates its failure. A reconnect
     * that succeeds ends the row: the next failure starts a new window, with the first backoff.
     *
     * Neither the deadline nor the window bounds the wait on its own: a failed reconnect starts the
     * deadline over and a successful one ends the row, so a link that keeps alternating the two
     * never reaches either. [switchOverallTimeout], from the wait's start and never moved, ends it
     * through [throwIfOverallTimePassed], and no backoff runs past it.
     */
    private inner class SwitchWait {
        private var deadline = deadline()
        private val overallDeadline = timeSource.markNow() + switchOverallTimeout
        private var failingSince: TimeMark? = null
        private var backoff = reconnectPolicy.firstBackoff
        private var lastReconnectFailure: LedgerException? = null

        /** Whether the link was last replaced once the deadline had passed; it then gets one poll. */
        var replacedPastDeadline = false
            private set

        /**
         * Throws once the deadline has passed, and otherwise waits the poll interval, never past
         * [switchOverallTimeout].
         */
        suspend fun beforeNextPoll() {
            if (deadline.hasPassedNow()) {
                throw LedgerException.WrongApp(
                    statusWord = null,
                    reason = "the device did not switch apps in time"
                )
            }
            delay(minOf(pollInterval, overallRemaining()))
        }

        private fun overallRemaining() = -overallDeadline.elapsedNow()

        /**
         * Throws once [switchOverallTimeout] has passed since the wait started: the last reconnect's
         * failure if it failed, and [LedgerException.WrongApp] otherwise.
         */
        fun throwIfOverallTimePassed() {
            if (overallDeadline.hasPassedNow()) {
                throw lastReconnectFailure ?: LedgerException.WrongApp(
                    statusWord = null,
                    reason = "the device did not switch apps within the overall time"
                )
            }
        }

        /** Ends the run of failures, and moves the deadline by the time the reconnect [took]. */
        fun reconnected(took: Duration) {
            lastReconnectFailure = null
            failingSince = null
            backoff = reconnectPolicy.firstBackoff
            deadline += took
            replacedPastDeadline = deadline.hasPassedNow()
        }

        /**
         * Throws [failure] once the retry window or [switchOverallTimeout] has passed; otherwise
         * starts the deadline over and waits the backoff, never past the end of either.
         */
        suspend fun reconnectFailed(failure: LedgerException) {
            lastReconnectFailure = failure
            val since = failingSince ?: timeSource.markNow().also { failingSince = it }
            val remaining = minOf(reconnectPolicy.retryWindow - since.elapsedNow(), overallRemaining())
            if (!remaining.isPositive()) {
                throw failure
            }
            deadline = deadline()
            delay(minOf(backoff, remaining))
            backoff = minOf(backoff * 2, reconnectPolicy.maxBackoff)
        }
    }

    /**
     * The transport through an app switch: [current] is the caller's until a dropped link is
     * replaced, and [usable] is false once it has failed.
     *
     * The caller's transport is held for the switch by `LedgerZcashApp.ensureZcashAppOpen`. Every
     * replacement is held here instead, from before the switch's first command on it until the next
     * replacement takes over or the switch ends ([releaseReplacementHold]), so no other ceremony over
     * a replacement can put a command between two of the switch's; see [LedgerCeremony.adopt].
     */
    @Suppress("TooManyFunctions")
    private inner class AppSwitch(
        private val original: LedgerApduTransport,
        private val reconnect: suspend () -> LedgerApduTransport
    ) {
        var current: LedgerApduTransport = original
            private set
        private var usable = true

        /** The switch's hold on [current] while it is a replacement; `null` while it is the caller's own. */
        private var replacementHold: LedgerCeremonyHold? = null

        /**
         * The running app before anything else is sent. A query that stalls past its deadline or loses
         * the link is asked once more on a fresh connection from [reconnect], which has the policy's
         * attempt timeout to open it; a second failure, and any answer from the device, is final.
         */
        suspend fun queryOnFreshConnectionOnce(): LedgerRunningApp {
            queryAllowingDisconnect(queryTimeout)?.let { return it }
            Twig.debug { "Ledger app query failed; asking once more on a fresh connection" }
            adopt(reconnectInTime())
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
         * accepts.
         *
         * The device has [transitionTimeout] from the call, not counting the time reconnects that
         * succeed take, checked after each poll the device answers, so the wait ends at most one poll
         * past it. A poll that loses the link or stalls past [pollTimeout] is followed by a reconnect
         * instead, however late it ends; once [transitionTimeout] has passed, a link replaced after it
         * gets one poll. A reconnect that fails starts that time over from the failure, because the
         * device may still be rebooting into the app, and is tried again after a backoff; see
         * [SwitchWait].
         *
         * Whatever the link does, the wait ends once [switchOverallTimeout] has passed since the
         * call, checked before each poll and each reconnect: with the last reconnect's failure if it
         * failed, and [LedgerException.WrongApp] otherwise. A reconnect already under way keeps its
         * own attempt timeout.
         */
        suspend fun awaitApp(reached: (LedgerRunningApp) -> Boolean) {
            val wait = SwitchWait()
            while (true) {
                wait.throwIfOverallTimePassed()
                if (usable) {
                    val running = queryAllowingDisconnect(pollTimeout)
                    if (running != null && reached(running)) {
                        return
                    }
                    if (running != null || wait.replacedPastDeadline) {
                        wait.beforeNextPoll()
                    }
                } else {
                    val reconnecting = timeSource.markNow()
                    val reconnectFailure = replace()
                    if (reconnectFailure == null) {
                        wait.reconnected(took = reconnecting.elapsedNow())
                    } else {
                        wait.reconnectFailed(reconnectFailure)
                    }
                }
            }
        }

        /**
         * The running app, or null if the link dropped or no answer came within [timeout].
         */
        private suspend fun queryAllowingDisconnect(timeout: Duration): LedgerRunningApp? =
            try {
                queryApp(current, timeout)
            } catch (e: LedgerException) {
                if (!e.isLinkFailure()) throw e
                Twig.debug { "Ledger link dropped while switching apps (${e.javaClass.simpleName})" }
                markUnusable()
                null
            }

        /**
         * Replaces the dropped transport, which [markUnusable] has already closed, with one from
         * [reconnectInTime]. Returns the link failure when the device cannot be reached yet, and null
         * once replaced. The caller's own cancellation, a timeout of its own included, propagates.
         */
        private suspend fun replace(): LedgerException? =
            try {
                adopt(reconnectInTime())
                null
            } catch (e: LedgerException) {
                if (!e.isLinkFailure()) throw e
                Twig.debug { "Reconnecting to the Ledger failed (${e.javaClass.simpleName}); retrying" }
                e
            }

        /**
         * A transport from [reconnect], which has the policy's attempt timeout to open it. One that
         * arrives past the timeout is closed, and the timeout is [LedgerException.ConnectionFailed].
         * The caller's own cancellation, a timeout of its own included, propagates.
         */
        private suspend fun reconnectInTime(): LedgerApduTransport {
            var opened: LedgerApduTransport? = null
            try {
                return withTimeout(reconnectPolicy.attemptTimeout) { reconnect().also { opened = it } }
            } catch (e: TimeoutCancellationException) {
                opened?.let { closeQuietly(it) }
                currentCoroutineContext().ensureActive()
                Twig.debug { "Reconnecting to the Ledger took longer than ${reconnectPolicy.attemptTimeout}" }
                throw LedgerException.ConnectionFailed(reason = "reconnecting timed out", cause = e)
            }
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
         * Makes [replacement] the switch's transport, once the switch holds its ceremony. A replacement
         * nothing else uses is taken at once; one another ceremony holds is waited for, and a
         * cancellation while queued throws without adopting it, so the switch's cleanup closes nothing
         * of the holder's — [current] is still the transport that dropped, already closed.
         *
         * [reconnect] may hand back a transport the switch already holds: the previous replacement
         * again, or the caller's own transport, when the transport reconnects internally and `close`
         * only drops the link. The previous replacement's hold is therefore released before the new one
         * is taken (the previous replacement is already closed by then, so nothing slips in between),
         * and the caller's own transport is not taken at all, since `LedgerZcashApp` holds it for the
         * whole switch; either would otherwise wait on this switch itself.
         */
        private suspend fun adopt(replacement: LedgerApduTransport) {
            releaseReplacementHold()
            if (replacement !== original) {
                replacementHold = LedgerCeremony.adopt(replacement)
            }
            current = replacement
            usable = true
        }

        /**
         * Ends the switch's hold on its current replacement, if any: when the next replacement takes
         * over, when the switch hands the replacement to the caller, or when the switch fails.
         */
        fun releaseReplacementHold() {
            replacementHold?.release()
            replacementHold = null
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
