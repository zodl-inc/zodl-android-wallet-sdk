package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import cash.z.ecc.android.sdk.ledger.LedgerAppLauncher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LedgerZcashAppTest {
    private val queryTimeout = 5.seconds

    private val launcher =
        LedgerAppLauncher(
            queryTimeout = queryTimeout,
            pollInterval = 1.milliseconds,
            transitionTimeout = 200.milliseconds
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
            assertTrue(original.closed, "the replaced transport is closed")
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
    fun a_fatal_reconnect_failure_propagates() =
        runBlocking<Unit> {
            val original =
                ScriptedTransport(listOf(dashboard), failAt = 1 to LedgerException.Disconnected())

            assertFailsWith<LedgerException.BluetoothDisabled> {
                launcher.ensureZcashAppOpen(original) { throw LedgerException.BluetoothDisabled() }
            }
        }
}
