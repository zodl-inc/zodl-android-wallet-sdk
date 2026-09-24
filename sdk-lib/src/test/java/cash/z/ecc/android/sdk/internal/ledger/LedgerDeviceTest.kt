package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_IDENTITY
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_VERSION
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_VK
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_VK_CONTINUE
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.DENY
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.NORMAL_TIMEOUT
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.NOT_ACCEPTED
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.identity
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.ok
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.status
import cash.z.ecc.android.sdk.ledger.LedgerDevice
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class LedgerDeviceTest {
    private val account = Zip32AccountIndex.new(0)

    private fun device(
        transport: ScriptedTransport,
        backend: FakeLedgerBackend = FakeLedgerBackend()
    ) = LedgerDevice(transport, ZcashNetwork.Testnet, backend)

    @Test
    fun pairing_reads_the_identity_once_before_exporting_the_viewing_key() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(vkContinuations = 1)
            val transport =
                ScriptedTransport(
                    listOf(
                        ok(1),
                        ok('a'.code.toByte()),
                        ok(),
                        ok()
                    )
                )

            val pairing = device(transport, backend).pairAccount(account)

            assertEquals(UnifiedFullViewingKey("uviewtest1fake"), pairing.ufvk)
            assertEquals(identity('a'), pairing.binding.deviceIdentity.encoding)
            assertEquals(account, pairing.binding.zip32AccountIndex)
            assertTrue(pairing.appVersion.supportsPczt)
            assertEquals(
                listOf(CMD_VERSION, CMD_IDENTITY, CMD_VK, CMD_VK_CONTINUE),
                transport.sent.map { it.single() }
            )
            // The export request waits on the user's approval; its continuation does not.
            assertEquals(
                listOf(NORMAL_TIMEOUT, NORMAL_TIMEOUT, null, NORMAL_TIMEOUT),
                transport.timeouts
            )
            assertEquals(1, backend.exportsClosed)
            assertFalse(transport.closed)
            assertFalse(pairing.toString().contains("uviewtest1fake"))
        }

    @Test
    fun pairing_never_probes_the_identity_after_the_export() =
        runBlocking<Unit> {
            val reference =
                device(ScriptedTransport(listOf(ok(1), ok('a'.code.toByte()), ok(), ok())))
                    .pairAccount(account)
            val transport =
                ScriptedTransport(
                    listOf(
                        ok(1),
                        ok('a'.code.toByte()),
                        ok(),
                        ok(),
                        ok('b'.code.toByte())
                    )
                )

            val pairing = device(transport).pairAccount(account)

            assertEquals(identity('a'), pairing.binding.deviceIdentity.encoding)
            assertEquals(reference.binding, pairing.binding)
            assertEquals(reference.ufvk, pairing.ufvk)
            assertEquals(1, transport.sent.count { it.single() == CMD_IDENTITY }, "one identity probe")
        }

    @Test
    fun pairing_is_refused_before_the_export_on_an_app_without_pczt_support() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(ok(0)))

            assertFailsWith<LedgerException.AppTooOld> {
                device(transport).pairAccount(account)
            }
            assertEquals(1, transport.sent.size, "nothing is exported")
        }

    @Test
    fun a_refused_frame_is_resent_identically() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(status(NOT_ACCEPTED), status(NOT_ACCEPTED), ok(1)))

            val version = device(transport).appVersion()

            assertTrue(version.supportsPczt)
            assertEquals(3, transport.sent.size)
            assertTrue(transport.sent.all { it.contentEquals(transport.sent.first()) })
        }

    @Test
    fun a_refusal_during_the_export_resends_the_request_and_keeps_waiting_on_the_user() =
        runBlocking<Unit> {
            val transport =
                ScriptedTransport(
                    listOf(
                        ok(1),
                        ok('a'.code.toByte()),
                        status(NOT_ACCEPTED),
                        ok(),
                        ok()
                    )
                )

            device(transport).pairAccount(account)

            assertEquals(
                listOf(CMD_VERSION, CMD_IDENTITY, CMD_VK, CMD_VK, CMD_VK_CONTINUE),
                transport.sent.map { it.single() }
            )
            assertNull(transport.timeouts[3])
        }

    @Test
    fun an_exhausted_retry_budget_is_a_transient_device_refusal() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(List(4) { status(NOT_ACCEPTED) })

            val error =
                assertFailsWith<LedgerException.DeviceRefused> {
                    device(transport).appVersion()
                }
            assertEquals(0x6901, error.statusWord)
            assertTrue(error.isTransient)
            assertTrue(error.isRestartable)
            assertEquals(4, transport.sent.size, "one request and the whole retry budget")
        }

    @Test
    fun a_declined_export_is_a_restartable_rejection_that_keeps_the_transport_open() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend()
            val transport = ScriptedTransport(listOf(ok(1), ok('a'.code.toByte()), status(DENY)))

            val error =
                assertFailsWith<LedgerException.UserRejected> {
                    device(transport, backend).pairAccount(account)
                }
            assertTrue(error.isRestartable)
            assertFalse(transport.closed)
            assertEquals(1, backend.exportsClosed)
        }

    @Test
    fun a_declined_address_is_a_rejection() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(status(DENY)))

            assertFailsWith<LedgerException.UserRejected> {
                device(transport).displayUnifiedAddress(account)
            }
            assertEquals(listOf<Duration?>(null), transport.timeouts, "the display waits on the user")
        }

    @Test
    fun a_failed_exchange_closes_the_transport() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(emptyList(), failAt = 0 to IOException("gone"))

            assertFailsWith<IOException> {
                device(transport).deviceIdentity()
            }
            assertTrue(transport.closed)
        }
}
