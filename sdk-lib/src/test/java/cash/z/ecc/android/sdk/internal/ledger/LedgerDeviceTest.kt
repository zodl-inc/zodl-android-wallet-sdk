package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.BAD_STATE
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_ADDRESS
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
import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import cash.z.ecc.android.sdk.ledger.LedgerDevice
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LedgerDeviceTest {
    private val account = Zip32AccountIndex.new(0)
    private val accountUfvk = UnifiedFullViewingKey("uviewtest1fake")

    private fun pairingReplies(tag: Char = 'a') =
        listOf(ok(1), ok(tag.code.toByte()), ok(), ok(), ok(tag.code.toByte()))

    /** Hands out [transports] in order, one per reconnect, and counts the reconnects. */
    private class Reconnects(
        vararg transports: ScriptedTransport
    ) {
        private val pending = ArrayDeque(transports.toList())
        var count = 0
            private set

        val reconnect: suspend () -> LedgerApduTransport = {
            count++
            pending.removeFirst()
        }
    }

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
            assertEquals(
                listOf(NORMAL_TIMEOUT, NORMAL_TIMEOUT, null, NORMAL_TIMEOUT),
                transport.timeouts,
                "the reads before the export keep the engine's timeout by default, the export request waits on the user"
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
    fun pairing_account_0_checks_the_exported_key_against_the_identity() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend()
            val transport = ScriptedTransport(pairingReplies().take(4))

            val pairing = device(transport, backend).pairAccount(account)

            assertEquals(identity('a'), pairing.binding.deviceIdentity.encoding)
            assertEquals(1, backend.identityChecks)
        }

    @Test
    fun pairing_account_0_refuses_a_key_of_another_device() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(ufvkIdentityTag = 'b')
            val transport = ScriptedTransport(pairingReplies().take(4))

            assertFailsWith<LedgerException.DeviceMismatch> {
                device(transport, backend).pairAccount(account)
            }
            assertEquals(1, backend.exportsClosed)
            assertFalse(transport.closed, "the device answered; the channel is still in step")
        }

    @Test
    fun pairing_another_account_cannot_check_the_key_against_the_identity() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(ufvkIdentityTag = 'b')
            val transport = ScriptedTransport(pairingReplies().take(4))

            val pairing = device(transport, backend).pairAccount(Zip32AccountIndex.new(1))

            assertEquals(identity('a'), pairing.binding.deviceIdentity.encoding)
            assertEquals(0, backend.identityChecks)
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
                device(transport).displayUnifiedAddress(accountUfvk, account)
            }
            assertEquals(listOf<Duration?>(null), transport.timeouts, "the display waits on the user")
        }

    @Test
    fun a_displayed_address_equal_to_the_derived_one_is_returned() =
        runBlocking<Unit> {
            val expected = FakeLedgerBackend().expectedUnifiedAddress(accountUfvk, ZcashNetwork.Testnet)
            val transport = ScriptedTransport(listOf(ok(*expected.toByteArray())))

            val address = device(transport).displayUnifiedAddress(accountUfvk, account, transparentAddressIndex = 3)

            assertEquals(expected, address)
            assertEquals(listOf(CMD_ADDRESS), transport.sent.map { it.single() })
        }

    @Test
    fun a_displayed_address_other_than_the_derived_one_is_a_device_mismatch() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(ok(*"utest1-another".toByteArray())))

            assertFailsWith<LedgerException.DeviceMismatch> {
                device(transport).displayUnifiedAddress(accountUfvk, account)
            }
            assertFalse(transport.closed, "the device answered; the channel is still in step")
        }

    @Test
    fun an_invalid_viewing_key_is_refused_before_the_address_is_shown() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(emptyList())

            assertFailsWith<LedgerException.InvalidInput> {
                device(transport).displayUnifiedAddress(UnifiedFullViewingKey(""), account)
            }
            assertTrue(transport.sent.isEmpty(), "nothing is shown on the device")
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

    @Test
    fun a_stalled_read_before_the_export_is_retried_once_on_a_fresh_connection() =
        runBlocking<Unit> {
            val stalled = ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Timeout())
            val fresh = ScriptedTransport(pairingReplies())
            val reconnects = Reconnects(fresh)
            val device = device(stalled)

            val pairing = device.pairAccount(account, reconnect = reconnects.reconnect)

            assertEquals(identity('a'), pairing.binding.deviceIdentity.encoding)
            assertEquals(1, reconnects.count)
            assertTrue(stalled.closed)
            assertEquals(listOf(CMD_VERSION), stalled.sent.map { it.single() })
            assertEquals(
                listOf(CMD_VERSION, CMD_IDENTITY, CMD_VK, CMD_VK_CONTINUE),
                fresh.sent.map { it.single() }
            )
            assertFalse(fresh.closed, "the caller closes the reconnected transport")
            assertSame(fresh, device.transport)
        }

    @Test
    fun a_disconnect_on_the_identity_read_asks_both_reads_again() =
        runBlocking<Unit> {
            val dropped = ScriptedTransport(listOf(ok(1)), failAt = 1 to LedgerException.Disconnected())
            val fresh = ScriptedTransport(pairingReplies())
            val reconnects = Reconnects(fresh)

            device(dropped).pairAccount(account, reconnect = reconnects.reconnect)

            assertEquals(1, reconnects.count)
            assertTrue(dropped.closed)
            assertEquals(CMD_VERSION, fresh.sent.first().single())
        }

    @Test
    fun a_second_stall_before_the_export_fails() =
        runBlocking<Unit> {
            val stalled = ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Timeout())
            val stalledAgain = ScriptedTransport(emptyList(), failAt = 0 to LedgerException.Timeout())
            val reconnects = Reconnects(stalledAgain)

            assertFailsWith<LedgerException.Timeout> {
                device(stalled).pairAccount(account, reconnect = reconnects.reconnect)
            }
            assertEquals(1, reconnects.count)
            assertTrue(stalled.closed)
            assertTrue(stalledAgain.closed)
            assertEquals(1, stalledAgain.sent.size, "nothing is sent after the second failure")
        }

    @Test
    fun a_failure_after_the_export_command_is_not_retried() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend()
            val transport =
                ScriptedTransport(
                    listOf(ok(1), ok('a'.code.toByte())),
                    failAt = 2 to LedgerException.Timeout()
                )
            val reconnects = Reconnects()

            assertFailsWith<LedgerException.Timeout> {
                device(transport, backend).pairAccount(account, reconnect = reconnects.reconnect)
            }
            assertEquals(0, reconnects.count)
            assertEquals(listOf(CMD_VERSION, CMD_IDENTITY, CMD_VK), transport.sent.map { it.single() })
            assertTrue(transport.closed)
            assertEquals(1, backend.exportsClosed)
        }

    @Test
    fun a_refusal_before_the_export_is_not_retried() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(listOf(status(BAD_STATE)))
            val reconnects = Reconnects()

            assertFailsWith<LedgerException.DeviceRefused> {
                device(transport).pairAccount(account, reconnect = reconnects.reconnect)
            }
            assertEquals(0, reconnects.count)
            assertFalse(transport.closed)
        }

    @Test
    fun the_callers_own_timeout_during_a_read_propagates_as_cancellation_without_a_reconnect() =
        runBlocking<Unit> {
            var closed = false
            val silent =
                object : LedgerApduTransport {
                    override suspend fun exchange(
                        apdu: ByteArray,
                        timeout: Duration?
                    ): ByteArray = awaitCancellation()

                    override suspend fun close() {
                        closed = true
                    }
                }
            val reconnects = Reconnects()

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50.milliseconds) {
                    LedgerDevice(silent, ZcashNetwork.Testnet, FakeLedgerBackend())
                        .pairAccount(account, reconnect = reconnects.reconnect)
                }
            }
            assertEquals(0, reconnects.count)
            assertTrue(closed, "the read left its reply uncollected")
        }

    @Test
    fun the_reads_before_the_export_take_the_callers_deadline() =
        runBlocking<Unit> {
            val transport = ScriptedTransport(pairingReplies())

            device(transport).pairAccount(account, readTimeout = 3.seconds)

            assertEquals(listOf(3.seconds, 3.seconds, null, NORMAL_TIMEOUT), transport.timeouts)
        }
}
