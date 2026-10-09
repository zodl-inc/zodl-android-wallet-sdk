package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_IDENTITY
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_REVIEW
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_SIGN
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_STREAM
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_VERSION
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_VK
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_VK_CONTINUE
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.identity
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.ok
import cash.z.ecc.android.sdk.ledger.LedgerAccountBinding
import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import cash.z.ecc.android.sdk.ledger.LedgerAppLauncher
import cash.z.ecc.android.sdk.ledger.LedgerDevice
import cash.z.ecc.android.sdk.ledger.LedgerDeviceIdentity
import cash.z.ecc.android.sdk.ledger.LedgerExchangeNotStartedException
import cash.z.ecc.android.sdk.ledger.LedgerZcashApp
import cash.z.ecc.android.sdk.ledger.SwitchReconnectPolicy
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The app-query command `LedgerZcashApp` sends, and a dashboard reply to it. */
private val GET_APP = byteArrayOf(0xB0.toByte(), 0x01, 0x00, 0x00, 0x00)

private fun dashboardReply(): ByteArray {
    val name = "BOLOS".toByteArray(Charsets.US_ASCII)
    val version = "2.2.3".toByteArray(Charsets.US_ASCII)
    return byteArrayOf(0x01, name.size.toByte()) + name + byteArrayOf(version.size.toByte()) + version +
        byteArrayOf(0x01, 0x02) + byteArrayOf(0x90.toByte(), 0x00)
}

/**
 * A device on the fake protocol that exports a viewing key for account 0, answers the identity probe
 * with identity `'a'`, signs, and answers the app query as the dashboard. Its first `CMD_VK` reply
 * waits for [releaseExport] instead of answering at once — the device waiting on the user's approval
 * of the export, which has no timeout — so a ceremony is provably in progress while another caller
 * tries to start; later chunks answer at once.
 */
private class SignaledDeviceTransport : LedgerApduTransport {
    val sent = mutableListOf<ByteArray>()
    var closes = 0
        private set
    private val exportReleased = CompletableDeferred<Unit>()
    val isWaitingForExportSignal = CompletableDeferred<Unit>()

    val commands: List<Byte> get() = sent.map { it[0] }

    override suspend fun exchange(
        apdu: ByteArray,
        timeout: Duration?
    ): ByteArray {
        sent.add(apdu.copyOf())
        if (apdu.contentEquals(GET_APP)) {
            return dashboardReply()
        }
        return when (apdu[0]) {
            CMD_VERSION -> {
                ok(1)
            }

            CMD_IDENTITY -> {
                ok('a'.code.toByte())
            }

            CMD_VK -> {
                isWaitingForExportSignal.complete(Unit)
                exportReleased.await()
                ok()
            }

            else -> {
                ok()
            }
        }
    }

    override suspend fun close() {
        closes++
    }

    fun releaseExport() {
        exportReleased.complete(Unit)
    }
}

/** A transport that throws [LedgerExchangeNotStartedException] for every exchange after the first [answered]. */
private class RefusingTransport(
    private val answered: Int
) : LedgerApduTransport {
    val sent = mutableListOf<ByteArray>()
    var closes = 0
        private set

    override suspend fun exchange(
        apdu: ByteArray,
        timeout: Duration?
    ): ByteArray {
        if (sent.size >= answered) {
            throw LedgerExchangeNotStartedException()
        }
        sent.add(apdu.copyOf())
        return when (apdu[0]) {
            CMD_VERSION -> ok(1)
            CMD_IDENTITY -> ok('a'.code.toByte())
            else -> ok()
        }
    }

    override suspend fun close() {
        closes++
    }
}

/** A transport whose every exchange fails on the connection: a pairing's first read never gets past it. */
private class StalledTransport : LedgerApduTransport {
    var closes = 0
        private set

    override suspend fun exchange(
        apdu: ByteArray,
        timeout: Duration?
    ): ByteArray = throw cash.z.ecc.android.sdk.exception.LedgerException
        .Timeout()

    override suspend fun close() {
        closes++
    }
}

/** A one-shot signal: [wait] returns once [open] has been called, at once if it already has. */
private class Latch {
    private val deferred = CompletableDeferred<Unit>()
    val isOpen: Boolean get() = deferred.isCompleted

    fun open() {
        deferred.complete(Unit)
    }

    suspend fun wait() = deferred.await()
}

/** Polls [condition] until it holds, failing after two seconds. */
private suspend fun expectEventually(condition: suspend () -> Boolean) {
    withTimeout(2.seconds) {
        while (!condition()) {
            delay(1)
        }
    }
}

class LedgerCeremonyTest {
    private val account = Zip32AccountIndex.new(0)
    private val binding = LedgerAccountBinding(LedgerDeviceIdentity(identity('a')), Zip32AccountIndex.new(0))
    private val accountUuid = AccountUuid.new(ByteArray(16))

    private fun device(transport: LedgerApduTransport) =
        LedgerDevice(transport, ZcashNetwork.Testnet, FakeLedgerBackend())

    private suspend fun sign(transport: LedgerApduTransport): Pczt =
        LedgerPcztSigner(FakeLedgerBackend()).sign(
            dataDbFile = File("unused"),
            network = ZcashNetwork.Testnet,
            pczt = Pczt(byteArrayOf(1, 2, 3)),
            accountUuid = accountUuid,
            binding = binding,
            transport = transport,
            onProgress = null
        )

    /** The commands one pairing of account 0 sends on the fake protocol, in order. */
    private val pairingCommands = listOf(CMD_VERSION, CMD_IDENTITY, CMD_VK, CMD_VK_CONTINUE)

    /** The commands one signing ceremony sends: the runner's own probe, then the session's. */
    private val signingCommands =
        listOf(CMD_VERSION, CMD_VERSION, CMD_IDENTITY, CMD_STREAM, CMD_STREAM, CMD_REVIEW, CMD_SIGN)

    // Two ceremonies on one transport run one after the other

    @Test
    fun two_devices_over_one_transport_run_their_ceremonies_one_after_the_other() =
        runBlocking<Unit> {
            val transport = SignaledDeviceTransport()
            val first = device(transport)
            val second = device(transport)

            val pairing = async { first.pairAccount(account) }
            transport.isWaitingForExportSignal.await()

            val probe = async { second.appVersion() }
            expectEventually { LedgerCeremonyGates.queuedCeremonies(transport) == 1 }
            transport.releaseExport()

            assertEquals(UnifiedFullViewingKey("uviewtest1fake"), pairing.await().ufvk)
            assertTrue(probe.await().supportsPczt)
            assertEquals(
                pairingCommands + CMD_VERSION,
                transport.commands,
                "the second device's probe comes after every command of the first device's pairing"
            )
            assertEquals(0, transport.closes)
        }

    @Test
    fun a_signing_ceremony_and_a_pairing_over_the_same_transport_do_not_interleave() =
        runBlocking<Unit> {
            val transport = SignaledDeviceTransport()

            val pairing = async { device(transport).pairAccount(account) }
            transport.isWaitingForExportSignal.await()

            val signing = async { sign(transport) }
            expectEventually { LedgerCeremonyGates.queuedCeremonies(transport) == 1 }
            transport.releaseExport()

            pairing.await()
            signing.await()
            assertEquals(pairingCommands + signingCommands, transport.commands)
            assertEquals(0, transport.closes)
        }

    @Test
    fun the_app_query_waits_for_a_pairing_over_the_same_transport() =
        runBlocking<Unit> {
            val transport = SignaledDeviceTransport()

            val pairing = async { device(transport).pairAccount(account) }
            transport.isWaitingForExportSignal.await()

            val query = async { LedgerZcashApp.currentApp(transport) }
            expectEventually { LedgerCeremonyGates.queuedCeremonies(transport) == 1 }
            transport.releaseExport()

            pairing.await()
            assertTrue(query.await().isDashboard)
            assertEquals(pairingCommands.size + 1, transport.sent.size)
            assertTrue(transport.sent.last().contentEquals(GET_APP), "the app query comes after the pairing")
        }

    // A caller cancelled while queued closes nothing

    @Test
    fun a_call_on_a_second_device_cancelled_while_queued_closes_nothing() =
        runBlocking<Unit> {
            val transport = SignaledDeviceTransport()

            val pairing = async { device(transport).pairAccount(account) }
            transport.isWaitingForExportSignal.await()

            val probe = launch { device(transport).appVersion() }
            expectEventually { LedgerCeremonyGates.queuedCeremonies(transport) == 1 }
            probe.cancel()
            probe.join()

            transport.releaseExport()
            pairing.await()
            assertEquals(0, transport.closes, "a call cancelled while only queued for its turn closes nothing")
            assertEquals(pairingCommands, transport.commands)
        }

    @Test
    fun a_device_call_the_transport_refused_before_sending_closes_nothing() =
        runBlocking<Unit> {
            val transport = RefusingTransport(answered = 0)

            assertFailsWith<CancellationException> { device(transport).appVersion() }

            assertEquals(0, transport.sent.size)
            assertEquals(0, transport.closes, "an exchange the transport never sent must not close it")
        }

    @Test
    fun a_signing_ceremony_the_transport_refused_before_sending_closes_nothing() =
        runBlocking<Unit> {
            // The version probe is answered, so the ceremony holds the device before the first signing
            // command, the one refused.
            val transport = RefusingTransport(answered = 1)

            assertFailsWith<CancellationException> { sign(transport) }

            assertEquals(1, transport.sent.size)
            assertEquals(
                0,
                transport.closes,
                "an exchange the transport never sent must not close it, even once the ceremony holds the device"
            )
        }

    @Test
    fun a_transports_ceremony_gate_goes_away_with_its_last_user() =
        runBlocking<Unit> {
            val transport = SignaledDeviceTransport()
            val before = LedgerCeremonyGates.transportsInUse

            val pairing = async { device(transport).pairAccount(account) }
            transport.isWaitingForExportSignal.await()
            assertEquals(before + 1, LedgerCeremonyGates.transportsInUse)

            transport.releaseExport()
            pairing.await()
            assertEquals(before, LedgerCeremonyGates.transportsInUse)
        }

    // A pairing that reconnects holds the replacement

    @Test
    fun a_second_device_over_the_reconnected_transport_waits_for_the_pairing_to_finish() =
        runBlocking<Unit> {
            val stalled = StalledTransport()
            val fresh = SignaledDeviceTransport()
            val ledger = device(stalled)

            val pairing = async { ledger.pairAccount(account, reconnect = { fresh }) }
            fresh.isWaitingForExportSignal.await()

            val probe = async { device(fresh).appVersion() }
            expectEventually { LedgerCeremonyGates.queuedCeremonies(fresh) == 1 }
            fresh.releaseExport()

            pairing.await()
            probe.await()
            assertEquals(pairingCommands + CMD_VERSION, fresh.commands)
            assertTrue(
                stalled.closes >= 1,
                "the stalled transport is closed once the read retries on a fresh connection"
            )
            assertEquals(0, fresh.closes)
            assertSame(fresh, ledger.transport)
        }

    @Test
    fun a_pairing_cancelled_while_queued_for_a_replacement_another_ceremony_holds_closes_nothing() =
        runBlocking<Unit> {
            val fresh = SignaledDeviceTransport()
            val holderPairing = async { device(fresh).pairAccount(account) }
            fresh.isWaitingForExportSignal.await()

            val stalled = StalledTransport()
            val ledger = device(stalled)
            val pairing = async { ledger.pairAccount(account, reconnect = { fresh }) }
            expectEventually { LedgerCeremonyGates.queuedCeremonies(fresh) == 1 }
            pairing.cancel()
            assertFailsWith<CancellationException> { pairing.await() }

            assertEquals(0, fresh.closes, "a replacement the cancelled pairing never came to hold is not closed")
            assertSame(stalled, ledger.transport, "a replacement the pairing never came to hold is not adopted")

            fresh.releaseExport()
            holderPairing.await()
            assertEquals(
                pairingCommands,
                fresh.commands,
                "only the holder's pairing ever sent anything on the replacement"
            )
        }

    @Test
    fun a_reconnect_that_returns_the_same_transport_does_not_wait_on_itself() =
        runBlocking<Unit> {
            // The first read fails on the link; everything after answers.
            val transport =
                object : LedgerApduTransport {
                    val sent = mutableListOf<Byte>()

                    override suspend fun exchange(
                        apdu: ByteArray,
                        timeout: Duration?
                    ): ByteArray {
                        sent.add(apdu[0])
                        if (sent.size == 1) {
                            throw cash.z.ecc.android.sdk.exception.LedgerException
                                .Timeout()
                        }
                        return when (apdu[0]) {
                            CMD_VERSION -> ok(1)
                            CMD_IDENTITY -> ok('a'.code.toByte())
                            else -> ok()
                        }
                    }

                    override suspend fun close() = Unit
                }
            val ledger = device(transport)

            val pairing = withTimeout(5.seconds) { ledger.pairAccount(account, reconnect = { transport }) }

            assertEquals(UnifiedFullViewingKey("uviewtest1fake"), pairing.ufvk)
            assertEquals(listOf(CMD_VERSION) + pairingCommands, transport.sent)
            assertSame(transport, ledger.transport)
        }

    // An app switch holds a replacement another ceremony may already hold

    private fun launcher() =
        LedgerAppLauncher(
            queryTimeout = 5.seconds,
            pollTimeout = 3.seconds,
            pollInterval = 1.seconds,
            transitionTimeout = 10.seconds,
            reconnectPolicy =
                SwitchReconnectPolicy(
                    attemptTimeout = 10.seconds,
                    retryWindow = 10.seconds,
                    firstBackoff = 1.seconds,
                    maxBackoff = 1.seconds
                ),
            switchOverallTimeout = 60.seconds
        )

    /** Holds [transport]'s ceremony from another coroutine until [release] opens, reporting through [entered]. */
    private fun kotlinx.coroutines.CoroutineScope.holdCeremony(
        transport: LedgerApduTransport,
        entered: Latch,
        release: Latch
    ) = launch {
        LedgerCeremony.run(transport) {
            entered.open()
            release.wait()
        }
    }

    /** A transport that answers the app query as the Zcash app, after a first exchange that drops the link. */
    private class ZcashAfterReconnectTransport(
        private val dropsFirst: Boolean
    ) : LedgerApduTransport {
        val sent = mutableListOf<ByteArray>()
        var closes = 0
            private set

        override suspend fun exchange(
            apdu: ByteArray,
            timeout: Duration?
        ): ByteArray {
            if (dropsFirst && sent.isEmpty()) {
                sent.add(apdu.copyOf())
                throw cash.z.ecc.android.sdk.exception.LedgerException
                    .Disconnected()
            }
            sent.add(apdu.copyOf())
            val name = "Zcash".toByteArray(Charsets.US_ASCII)
            val version = "4.2.0".toByteArray(Charsets.US_ASCII)
            return byteArrayOf(0x01, name.size.toByte()) + name + byteArrayOf(version.size.toByte()) + version +
                byteArrayOf(0x90.toByte(), 0x00)
        }

        override suspend fun close() {
            closes++
        }
    }

    @Test
    fun the_switch_waits_for_a_ceremony_already_holding_its_replacement() =
        runBlocking<Unit> {
            val original = ZcashAfterReconnectTransport(dropsFirst = true)
            val afterSwitch = ZcashAfterReconnectTransport(dropsFirst = false)
            val entered = Latch()
            val release = Latch()
            val holder = holdCeremony(afterSwitch, entered, release)
            entered.wait()

            val switching = async { launcher().ensureZcashAppOpen(original) { afterSwitch } }
            expectEventually { LedgerCeremonyGates.queuedCeremonies(afterSwitch) == 1 }
            assertTrue(afterSwitch.sent.isEmpty(), "the switch sends nothing on a replacement another ceremony holds")

            release.open()
            holder.join()
            assertSame(afterSwitch, switching.await())
            assertEquals(1, afterSwitch.sent.size, "the query ran once the holder was done")

            // The switch's hold ended with the switch: a ceremony on the returned transport is admitted.
            val afterwards = Latch()
            holdCeremony(afterSwitch, afterwards, afterwards).join()
            assertTrue(afterwards.isOpen)
        }

    @Test
    fun the_switch_cancelled_while_queued_for_a_held_replacement_closes_nothing() =
        runBlocking<Unit> {
            val original = ZcashAfterReconnectTransport(dropsFirst = true)
            val afterSwitch = ZcashAfterReconnectTransport(dropsFirst = false)
            val entered = Latch()
            val release = Latch()
            val holder = holdCeremony(afterSwitch, entered, release)
            entered.wait()

            val switching = async { launcher().ensureZcashAppOpen(original) { afterSwitch } }
            expectEventually { LedgerCeremonyGates.queuedCeremonies(afterSwitch) == 1 }
            switching.cancel()
            assertFailsWith<CancellationException> { switching.await() }

            release.open()
            holder.join()
            assertEquals(0, afterSwitch.closes, "a replacement the cancelled switch never came to hold is not closed")
            assertTrue(afterSwitch.sent.isEmpty(), "the cancelled switch never sent anything on the replacement")
            assertEquals(1, original.closes, "the dropped original was closed when its link failed, and only then")
        }
}
