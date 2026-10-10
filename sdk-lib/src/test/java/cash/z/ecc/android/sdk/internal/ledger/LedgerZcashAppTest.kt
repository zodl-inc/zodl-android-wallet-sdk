package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import cash.z.ecc.android.sdk.ledger.LedgerAppLauncher
import cash.z.ecc.android.sdk.ledger.SwitchReconnectPolicy
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class LedgerZcashAppTest {
    private val queryTimeout = 5.seconds
    private val pollTimeout = 3.seconds

    private fun reconnectPolicy(attemptTimeout: Duration = 1.seconds) =
        SwitchReconnectPolicy(
            attemptTimeout = attemptTimeout,
            retryWindow = 200.milliseconds,
            firstBackoff = 1.milliseconds,
            maxBackoff = 4.milliseconds
        )

    private val launcher =
        LedgerAppLauncher(
            queryTimeout = queryTimeout,
            pollTimeout = pollTimeout,
            pollInterval = 1.milliseconds,
            transitionTimeout = 200.milliseconds,
            reconnectPolicy = reconnectPolicy(),
            switchOverallTimeout = 60.seconds
        )

    private val getApp = byteArrayOf(0xB0.toByte(), 0x01, 0x00, 0x00, 0x00)
    private val closeApp = byteArrayOf(0xB0.toByte(), 0xA7.toByte(), 0x00, 0x00, 0x00)
    private val openZcash =
        byteArrayOf(0xE0.toByte(), 0xD8.toByte(), 0x00, 0x00, 0x05) + "Zcash".toByteArray(Charsets.US_ASCII)

    private fun sw(statusWord: Int) = byteArrayOf((statusWord shr 8).toByte(), statusWord.toByte())

    private fun app(
        name: String,
        version: String = "1.0.0",
        flags: ByteArray? = null
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val versionBytes = version.toByteArray(Charsets.US_ASCII)
        val flagBytes = flags?.let { byteArrayOf(it.size.toByte()) + it } ?: byteArrayOf()
        return byteArrayOf(0x01, nameBytes.size.toByte()) + nameBytes +
            byteArrayOf(versionBytes.size.toByte()) + versionBytes + flagBytes + sw(0x9000)
    }

    /** Fresh on every read: the launcher wipes each reply once parsed. */
    private val zcash get() = app("Zcash", "4.2.0")

    /** Fresh on every read: the launcher wipes each reply once parsed. */
    private val dashboard get() = app("BOLOS", "2.2.3", flags = byteArrayOf(0x02))

    private fun noReconnect(): suspend () -> LedgerApduTransport = { error("no reconnect expected") }

    /** [cash.z.ecc.android.sdk.ledger.LedgerZcashApp]'s own timings, on [timeSource]. */
    private fun productionTimedLauncher(
        timeSource: TimeSource,
        pollTimeout: Duration = 3.seconds,
        switchOverallTimeout: Duration = 60.seconds
    ) = LedgerAppLauncher(
        queryTimeout = 10.seconds,
        pollTimeout = pollTimeout,
        pollInterval = 200.milliseconds,
        transitionTimeout = 10.seconds,
        reconnectPolicy =
            SwitchReconnectPolicy(
                attemptTimeout = 10.seconds,
                retryWindow = 10.seconds,
                firstBackoff = 500.milliseconds,
                maxBackoff = 2.seconds
            ),
        switchOverallTimeout = switchOverallTimeout,
        timeSource = timeSource
    )

    @Test
    fun the_app_query_is_parsed() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(dashboard))

            val running = launcher.currentApp(transport)

            assertEquals("BOLOS", running.name)
            assertEquals("2.2.3", running.version)
            assertTrue(running.isDashboard)
            assertFalse(running.isZcash)
            assertContentEquals(getApp, transport.sent.single())
            assertEquals(queryTimeout, transport.timeouts.single())
        }

    @Test
    fun every_dashboard_name_is_recognized() =
        runBlocking<Unit> {
            listOf("BOLOS", "OLOS", "OLOS\u0000").forEach { name ->
                assertTrue(launcher.currentApp(ScriptedTransport(listOf(app(name)))).isDashboard, name)
            }
        }

    @Test
    fun a_malformed_app_query_reply_is_refused() =
        runBlocking<Unit> {
            val malformed =
                listOf(
                    sw(0x9000),
                    byteArrayOf(0x90.toByte()),
                    byteArrayOf(0x02, 0x01, 'Z'.code.toByte(), 0x00) + sw(0x9000),
                    byteArrayOf(0x01, 0x09, 'Z'.code.toByte()) + sw(0x9000),
                    byteArrayOf(0x01, 0x01, 'Z'.code.toByte()) + sw(0x9000),
                    byteArrayOf(0x01, 0x01, 'Z'.code.toByte(), 0x04, '1'.code.toByte()) + sw(0x9000)
                )
            malformed.forEachIndexed { index, reply ->
                assertFailsWith<LedgerException.MalformedReply>("reply $index") {
                    launcher.currentApp(ScriptedTransport(listOf(reply)))
                }
            }
        }

    @Test
    fun a_locked_device_refuses_the_app_query_transiently() =
        runBlocking<Unit> {
            val error =
                assertFailsWith<LedgerException.DeviceRefused> {
                    launcher.currentApp(ScriptedTransport(listOf(sw(0x5515))))
                }
            assertEquals(0x5515, error.statusWord)
            assertTrue(error.isTransient)
            assertTrue(error.isRestartable)
        }

    @Test
    fun an_open_zcash_app_is_left_alone() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(zcash))

            val result = launcher.ensureZcashAppOpen(transport, noReconnect())

            assertSame(transport, result)
            assertEquals(1, transport.sent.size, "no open command is sent")
            assertFalse(transport.closed)
        }

    @Test
    fun the_dashboard_opens_the_zcash_app_and_polls_until_it_runs() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(dashboard, sw(0x9000), dashboard, dashboard, zcash))

            val result = launcher.ensureZcashAppOpen(transport, noReconnect())

            assertSame(transport, result)
            assertContentEquals(getApp, transport.sent[0])
            assertContentEquals(openZcash, transport.sent[1])
            assertNull(transport.timeouts[1], "opening waits on the user")
            transport.sent.drop(2).forEach { assertContentEquals(getApp, it) }
            assertEquals(queryTimeout, transport.timeouts[0])
            transport.timeouts.drop(2).forEach { assertEquals(pollTimeout, it) }
            assertEquals(5, transport.sent.size)
            assertFalse(transport.closed)
        }

    @Test
    fun another_app_is_closed_before_the_zcash_app_is_opened() =
        runBlocking<Unit> {
            val transport =
                ScriptedTransport(
                    listOf(app("Bitcoin"), sw(0x9000), app("Bitcoin"), dashboard, sw(0x9000), zcash)
                )

            val result = launcher.ensureZcashAppOpen(transport, noReconnect())

            assertSame(transport, result)
            val expected = listOf(getApp, closeApp, getApp, getApp, openZcash, getApp)
            assertEquals(expected.size, transport.sent.size)
            expected.zip(transport.sent).forEach { (want, got) -> assertContentEquals(want, got) }
        }

    @Test
    fun a_link_dropped_while_opening_is_reconnected_and_polled() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())
            val refused = LedgerException.ConnectionFailed(reason = null)
            val afterSwitch = ScriptedTransport(listOf(dashboard, zcash))
            var reconnects = 0

            val result =
                launcher.ensureZcashAppOpen(original) {
                    reconnects++
                    if (reconnects == 1) throw refused
                    afterSwitch
                }

            assertSame(afterSwitch, result)
            assertEquals(2, reconnects)
            assertContentEquals(openZcash, original.sent[1])
            assertEquals(1, original.closes, "the dropped transport is closed once")
            assertFalse(afterSwitch.closed)
            assertEquals(2, afterSwitch.sent.size)
        }

    @Test
    fun a_link_dropped_while_polling_is_reconnected() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(
                    listOf(dashboard, sw(0x9000)),
                    failAt = 2 to LedgerException.Timeout()
                )
            val afterSwitch = ScriptedTransport(listOf(zcash))

            val result = launcher.ensureZcashAppOpen(original) { afterSwitch }

            assertSame(afterSwitch, result)
            assertTrue(original.closed)
        }

    @Test
    fun declining_to_open_the_app_is_restartable() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(dashboard, sw(0x5501)))

            val error =
                assertFailsWith<LedgerException.AppOpenRejected> {
                    launcher.ensureZcashAppOpen(transport, noReconnect())
                }

            assertTrue(error.isRestartable)
            assertEquals(2, transport.sent.size)
        }

    @Test
    fun a_missing_zcash_app_is_reported() =
        runBlocking<Unit> {
            val error =
                assertFailsWith<LedgerException.AppNotInstalled> {
                    launcher.ensureZcashAppOpen(ScriptedTransport(listOf(dashboard, sw(0x6807))), noReconnect())
                }

            assertFalse(error.isRestartable)
        }

    @Test
    fun a_device_locked_while_opening_is_a_transient_refusal() =
        runBlocking<Unit> {
            val error =
                assertFailsWith<LedgerException.DeviceRefused> {
                    launcher.ensureZcashAppOpen(ScriptedTransport(listOf(dashboard, sw(0x5515))), noReconnect())
                }

            assertEquals(0x5515, error.statusWord)
            assertTrue(error.isTransient)
        }

    @Test
    fun an_unknown_refusal_to_open_asks_for_the_zcash_app() =
        runBlocking<Unit> {
            val error =
                assertFailsWith<LedgerException.WrongApp> {
                    launcher.ensureZcashAppOpen(ScriptedTransport(listOf(dashboard, sw(0x6D00))), noReconnect())
                }

            assertEquals(0x6D00, error.statusWord)
        }

    @Test
    fun a_device_that_never_reaches_the_zcash_app_times_out() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(dashboard, sw(0x9000)) + List(10_000) { dashboard })

            val error =
                assertFailsWith<LedgerException.WrongApp> {
                    launcher.ensureZcashAppOpen(transport, noReconnect())
                }

            assertNull(error.statusWord)
            assertTrue(transport.sent.size > 3, "the device was polled")
        }

    @Test
    fun a_reconnected_transport_is_closed_when_the_switch_times_out() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())
            val replacement = ScriptedTransport(List(10_000) { dashboard })

            assertFailsWith<LedgerException.WrongApp> {
                launcher.ensureZcashAppOpen(original) { replacement }
            }

            assertTrue(original.closed)
            assertTrue(replacement.closed)
        }

    @Test
    fun a_dropped_replacement_is_closed_once_when_the_switch_times_out() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())
            val dropped = ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())
            val replacement = ScriptedTransport(List(10_000) { dashboard })
            val transports = ArrayDeque(listOf(dropped, replacement))

            assertFailsWith<LedgerException.WrongApp> {
                launcher.ensureZcashAppOpen(original) { transports.removeFirst() }
            }

            assertEquals(1, original.closes)
            assertEquals(1, dropped.closes)
            assertEquals(1, replacement.closes)
        }

    @Test
    fun a_failed_reconnect_that_outlasts_the_deadline_still_reaches_the_zcash_app() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())
            val afterSwitch = ScriptedTransport(listOf(zcash))
            var reconnects = 0

            val result =
                launcher.ensureZcashAppOpen(original) {
                    reconnects++
                    if (reconnects == 1) {
                        delay(300.milliseconds)
                        throw LedgerException.ConnectionFailed(reason = null)
                    }
                    afterSwitch
                }

            assertSame(afterSwitch, result)
            assertEquals(2, reconnects)
        }

    @Test
    fun a_reconnect_that_hangs_is_cut_off_and_tried_again() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())
            val afterSwitch = ScriptedTransport(listOf(zcash))
            var reconnects = 0

            val result =
                LedgerAppLauncher(
                    queryTimeout = queryTimeout,
                    pollTimeout = pollTimeout,
                    pollInterval = 1.milliseconds,
                    transitionTimeout = 200.milliseconds,
                    reconnectPolicy = reconnectPolicy(attemptTimeout = 50.milliseconds),
                    switchOverallTimeout = 60.seconds
                ).ensureZcashAppOpen(original) {
                    reconnects++
                    if (reconnects == 1) awaitCancellation()
                    afterSwitch
                }

            assertSame(afterSwitch, result)
            assertEquals(2, reconnects)
        }

    @Test
    fun an_instantly_failing_reconnect_is_retried_with_backoff_until_the_window_runs_out() =
        runTest {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())
            val attemptsAt = mutableListOf<Duration>()
            val failures = mutableListOf<LedgerException>()
            val start = testScheduler.timeSource.markNow()
            val launcher =
                LedgerAppLauncher(
                    queryTimeout = 10.seconds,
                    pollTimeout = 3.seconds,
                    pollInterval = 200.milliseconds,
                    transitionTimeout = 10.seconds,
                    reconnectPolicy =
                        SwitchReconnectPolicy(
                            attemptTimeout = 10.seconds,
                            retryWindow = 10.seconds,
                            firstBackoff = 500.milliseconds,
                            maxBackoff = 2.seconds
                        ),
                    switchOverallTimeout = 60.seconds,
                    timeSource = testScheduler.timeSource
                )

            val error =
                assertFailsWith<LedgerException.ConnectionFailed> {
                    launcher.ensureZcashAppOpen(original) {
                        attemptsAt += start.elapsedNow()
                        throw LedgerException.ConnectionFailed(reason = null).also { failures += it }
                    }
                }

            assertSame(failures.last(), error, "the last failure propagates")
            assertEquals(
                listOf(0, 500, 1_500, 3_500, 5_500, 7_500, 9_500, 10_000).map { it.milliseconds },
                attemptsAt
            )
            assertEquals(10.seconds, start.elapsedNow())
            assertEquals(1, original.closes)
        }

    @Test
    fun a_poll_that_stalls_through_the_whole_transition_is_followed_by_a_reconnect() =
        runTest {
            val original =
                ScriptedTransport(
                    listOf(dashboard, sw(0x9000)),
                    failAt = 2 to LedgerException.Timeout(),
                    stallBeforeFailing = true
                )
            val afterSwitch = ScriptedTransport(listOf(zcash))
            var reconnects = 0

            val result =
                productionTimedLauncher(testScheduler.timeSource, pollTimeout = 10.seconds)
                    .ensureZcashAppOpen(original) {
                        reconnects++
                        afterSwitch
                    }

            assertSame(afterSwitch, result)
            assertEquals(1, reconnects)
            assertEquals(1, original.closes)
        }

    @Test
    fun a_stalled_poll_leaves_time_to_poll_the_fresh_connection() =
        runTest {
            val original =
                ScriptedTransport(
                    listOf(dashboard, sw(0x9000)),
                    failAt = 2 to LedgerException.Timeout(),
                    stallBeforeFailing = true
                )
            val afterSwitch = ScriptedTransport(List(25) { dashboard } + listOf(zcash))
            val start = testScheduler.timeSource.markNow()

            val result = productionTimedLauncher(testScheduler.timeSource).ensureZcashAppOpen(original) { afterSwitch }

            assertSame(afterSwitch, result)
            assertEquals(3.seconds, original.timeouts[2], "a poll waits 3 seconds")
            assertEquals(8.seconds, start.elapsedNow())
        }

    @Test
    fun a_recovered_link_starts_the_reconnect_backoff_and_window_over() =
        runTest {
            val original =
                ScriptedTransport(listOf(dashboard, sw(0x9000)), failAt = 2 to LedgerException.Disconnected())
            val recovered = ScriptedTransport(List(12) { dashboard }, failAt = 12 to LedgerException.Disconnected())
            val afterSwitch = ScriptedTransport(listOf(zcash))
            val attemptsAt = mutableListOf<Duration>()
            val start = testScheduler.timeSource.markNow()

            val result =
                productionTimedLauncher(testScheduler.timeSource).ensureZcashAppOpen(original) {
                    attemptsAt += start.elapsedNow()
                    when (attemptsAt.size) {
                        in 1..6, 8 -> throw LedgerException.ConnectionFailed(reason = null)
                        7 -> recovered
                        else -> afterSwitch
                    }
                }

            assertSame(afterSwitch, result)
            assertEquals(
                listOf(0, 500, 1_500, 3_500, 5_500, 7_500, 9_500, 11_900, 12_400).map { it.milliseconds },
                attemptsAt,
                "the failure after the recovery waits the first backoff, in a window of its own"
            )
        }

    /**
     * Reconnects that alternate failing and succeeding, starting with a failure, each fresh link
     * failing its first poll as [freshLink] makes it. Past [maxAttempts] it throws an
     * [IllegalStateException], so a wait that never ends fails the test instead of hanging it.
     */
    private class FlappingReconnect(
        private val freshLink: () -> ScriptedTransport,
        private val maxAttempts: Int = 1_000
    ) {
        val failures = mutableListOf<LedgerException>()
        val links = mutableListOf<ScriptedTransport>()
        var attempts = 0
            private set

        val reconnect: suspend () -> LedgerApduTransport = {
            attempts++
            check(attempts <= maxAttempts) { "the app switch wait never ended" }
            if (attempts % 2 == 1) {
                throw LedgerException.ConnectionFailed(reason = null).also { failures += it }
            }
            freshLink().also { links += it }
        }
    }

    @Test
    fun a_link_that_keeps_flapping_ends_the_app_switch_after_the_overall_cap() =
        runTest {
            val original =
                ScriptedTransport(
                    listOf(dashboard, sw(0x9000)),
                    failAt = 2 to LedgerException.Timeout(),
                    stallBeforeFailing = true
                )
            val flapping =
                FlappingReconnect(
                    freshLink = {
                        ScriptedTransport(
                            emptyList(),
                            failAt = 0 to LedgerException.Timeout(),
                            stallBeforeFailing = true
                        )
                    }
                )
            val start = testScheduler.timeSource.markNow()

            val error =
                assertFailsWith<LedgerException.WrongApp> {
                    productionTimedLauncher(testScheduler.timeSource).ensureZcashAppOpen(original, flapping.reconnect)
                }

            assertNull(error.statusWord)
            assertEquals(34, flapping.attempts, "the last reconnect succeeded, and its poll stalled past the cap")
            assertEquals(62.5.seconds, start.elapsedNow(), "the cap ends the wait at the next turn of the loop")
            assertEquals(1, original.closes)
            flapping.links.forEach { assertEquals(1, it.closes, "every replaced link is closed once") }
        }

    @Test
    fun a_flapping_link_whose_last_reconnect_failed_propagates_that_failure_at_the_overall_cap() =
        runTest {
            val original =
                ScriptedTransport(listOf(dashboard, sw(0x9000)), failAt = 2 to LedgerException.Disconnected())
            val flapping =
                FlappingReconnect(
                    freshLink = { ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Disconnected()) }
                )
            val start = testScheduler.timeSource.markNow()

            val error =
                assertFailsWith<LedgerException.ConnectionFailed> {
                    productionTimedLauncher(testScheduler.timeSource).ensureZcashAppOpen(original, flapping.reconnect)
                }

            assertSame(flapping.failures.last(), error, "the last reconnect's failure propagates")
            assertEquals(239, flapping.attempts)
            assertEquals(60.seconds, start.elapsedNow(), "the backoff does not run past the cap")
            flapping.links.forEach { assertEquals(1, it.closes, "every replaced link is closed once") }
        }

    @Test
    fun the_backoff_before_a_reconnect_is_cut_short_by_the_overall_cap() =
        runTest {
            val original =
                ScriptedTransport(listOf(dashboard, sw(0x9000)), failAt = 2 to LedgerException.Disconnected())
            val flapping =
                FlappingReconnect(
                    freshLink = { ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Disconnected()) }
                )
            val start = testScheduler.timeSource.markNow()

            val error =
                assertFailsWith<LedgerException.ConnectionFailed> {
                    productionTimedLauncher(testScheduler.timeSource, switchOverallTimeout = 59.8.seconds)
                        .ensureZcashAppOpen(original, flapping.reconnect)
                }

            assertSame(flapping.failures.last(), error)
            assertEquals(239, flapping.attempts, "no reconnect starts after the cap")
            assertEquals(59.8.seconds, start.elapsedNow())
        }

    @Test
    fun a_slow_successful_reconnect_leaves_the_whole_polling_time() =
        runTest {
            val original =
                ScriptedTransport(listOf(dashboard, sw(0x9000)), failAt = 2 to LedgerException.Disconnected())
            val afterSwitch = ScriptedTransport(List(25) { dashboard } + listOf(zcash))

            val result =
                productionTimedLauncher(testScheduler.timeSource).ensureZcashAppOpen(original) {
                    delay(9.seconds)
                    afterSwitch
                }

            assertSame(afterSwitch, result)
            assertEquals(26, afterSwitch.sent.size)
        }

    @Test
    fun the_callers_own_timeout_during_a_reconnect_propagates_as_cancellation() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())
            var reconnects = 0

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100.milliseconds) {
                    launcher.ensureZcashAppOpen(original) {
                        reconnects++
                        awaitCancellation()
                    }
                }
            }

            assertEquals(1, reconnects, "the caller's timeout is not a failed reconnect to retry")
        }

    @Test
    fun a_fatal_reconnect_failure_propagates() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())

            assertFailsWith<LedgerException.BluetoothDisabled> {
                launcher.ensureZcashAppOpen(original) { throw LedgerException.BluetoothDisabled() }
            }
        }

    @Test
    fun a_stalled_first_query_is_asked_once_more_on_a_fresh_connection() =
        runBlocking<Unit> {
            val stalled = ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Timeout())
            val fresh = ScriptedTransport(listOf(zcash))
            var reconnects = 0

            val result =
                launcher.ensureZcashAppOpen(stalled) {
                    reconnects++
                    fresh
                }

            assertSame(fresh, result)
            assertEquals(1, reconnects)
            assertTrue(stalled.closed, "the stalled transport is closed")
            assertContentEquals(getApp, fresh.sent.single())
        }

    @Test
    fun a_first_query_that_stalls_twice_fails_without_sending_anything_else() =
        runBlocking<Unit> {
            val stalled = ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Timeout())
            val stalledAgain = ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Timeout())
            var reconnects = 0

            assertFailsWith<LedgerException.Timeout> {
                launcher.ensureZcashAppOpen(stalled) {
                    reconnects++
                    stalledAgain
                }
            }

            assertEquals(1, reconnects)
            assertEquals(1, stalledAgain.sent.size)
            assertTrue(stalledAgain.closed, "the fresh transport is closed when the call fails")
        }

    @Test
    fun a_reconnect_that_hangs_after_a_failed_first_query_is_cut_off() =
        runTest {
            val stalled = ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Timeout())
            val start = testScheduler.timeSource.markNow()

            assertFailsWith<LedgerException.ConnectionFailed> {
                productionTimedLauncher(testScheduler.timeSource).ensureZcashAppOpen(stalled) {
                    awaitCancellation()
                }
            }

            assertEquals(10.seconds, start.elapsedNow())
            assertEquals(1, stalled.closes)
        }

    @Test
    fun a_locked_device_is_not_retried_on_a_fresh_connection() =
        runBlocking<Unit> {
            val locked = ScriptedTransport(listOf(sw(0x5515)))

            assertFailsWith<LedgerException.DeviceRefused> {
                launcher.ensureZcashAppOpen(locked, noReconnect())
            }
        }
}
