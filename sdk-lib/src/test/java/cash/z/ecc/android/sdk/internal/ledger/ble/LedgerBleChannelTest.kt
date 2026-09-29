package cash.z.ecc.android.sdk.internal.ledger.ble

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LedgerBleChannelTest {
    /**
     * A link whose device answers each complete command with [respond], split into frames the way the
     * fake engine frames them, unless told to stay silent.
     */
    private class FakeLink(
        private val backend: FakeLedgerBackend,
        private val frameSize: Int,
        private val respond: (ByteArray) -> ByteArray? = { it.reversedArray() }
    ) : LedgerBleLink {
        val written = mutableListOf<ByteArray>()
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        var closed = false
        val firstWrite = CompletableDeferred<Unit>()
        private val pending = mutableListOf<Byte>()

        override val notifications = channel

        override suspend fun write(frame: ByteArray) {
            if (closed) throw LedgerException.Disconnected()
            written.add(frame.copyOf())
            firstWrite.complete(Unit)
            pending.addAll(frame.drop(1))
            if (frame[0] == 1.toByte()) {
                val command = pending.toByteArray().also { pending.clear() }
                respond(command)?.let { reply -> backend.bleFrames(reply, frameSize).forEach { channel.trySend(it) } }
            }
        }

        override fun close() {
            closed = true
            channel.close(LedgerException.Disconnected())
        }
    }

    private val backend = FakeLedgerBackend()

    @Test
    fun a_command_is_framed_and_its_reply_reassembled() =
        runBlocking<Unit> {
            val link = FakeLink(backend, frameSize = 4)
            val channel = LedgerBleChannel(link, backend, frameSize = 4)
            val command = ByteArray(10) { it.toByte() }

            val reply = channel.exchange(command, 1.seconds)

            assertContentEquals(command.reversedArray(), reply)
            assertEquals(4, link.written.size, "10 bytes at 3 per frame")
            // A second exchange reuses the reset reassembly.
            assertContentEquals(byteArrayOf(2, 1), channel.exchange(byteArrayOf(1, 2), 1.seconds))
        }

    @Test
    fun a_timeout_closes_the_link_and_every_later_exchange_fails() =
        runBlocking<Unit> {
            val link = FakeLink(backend, frameSize = 8) { null }
            val channel = LedgerBleChannel(link, backend, frameSize = 8)

            assertFailsWith<LedgerException.Timeout> { channel.exchange(byteArrayOf(1), 50.milliseconds) }
            assertTrue(link.closed)
            assertEquals(1, backend.deframersClosed)
            assertFailsWith<LedgerException.Disconnected> { channel.exchange(byteArrayOf(1), 1.seconds) }
        }

    @Test
    fun a_disconnect_mid_exchange_is_a_disconnect() =
        runBlocking<Unit> {
            val link = FakeLink(backend, frameSize = 8) { null }
            val channel = LedgerBleChannel(link, backend, frameSize = 8)

            // A supervisor scope, so that the failing exchange does not cancel the test itself.
            supervisorScope {
                val exchange = async { channel.exchange(byteArrayOf(1, 2, 3), timeout = null) }
                link.firstWrite.await()
                link.channel.close(LedgerException.Disconnected())

                assertFailsWith<LedgerException.Disconnected> { exchange.await() }
            }
            assertTrue(link.closed)
        }

    @Test
    fun a_reply_that_does_not_reassemble_closes_the_link() =
        runBlocking<Unit> {
            val link = FakeLink(backend, frameSize = 8) { null }
            val channel = LedgerBleChannel(link, backend, frameSize = 8)

            supervisorScope {
                val exchange = async { channel.exchange(byteArrayOf(1), 1.seconds) }
                link.firstWrite.await()
                link.channel.trySend(byteArrayOf(7, 7))

                assertFailsWith<LedgerException.MalformedReply> { exchange.await() }
            }
            assertTrue(link.closed)
        }

    @Test
    fun a_notification_with_no_command_outstanding_poisons_the_channel() =
        runBlocking<Unit> {
            val link = FakeLink(backend, frameSize = 8)
            val channel = LedgerBleChannel(link, backend, frameSize = 8)
            link.channel.trySend(byteArrayOf(1, 0x6A))

            assertFailsWith<LedgerException.MalformedReply> { channel.exchange(byteArrayOf(1), 1.seconds) }
            assertTrue(link.closed)
            assertTrue(link.written.isEmpty(), "nothing is written on a channel out of step")
        }

    @Test
    fun cancelling_an_exchange_closes_the_link() =
        runBlocking<Unit> {
            val link = FakeLink(backend, frameSize = 8) { null }
            val channel = LedgerBleChannel(link, backend, frameSize = 8)

            val exchange = async { channel.exchange(byteArrayOf(1), timeout = null) }
            link.firstWrite.await()
            exchange.cancel()

            assertFailsWith<CancellationException> { exchange.await() }
            assertTrue(link.closed)
        }

    @Test
    fun the_callers_own_timeout_during_an_exchange_propagates_as_cancellation() =
        runBlocking<Unit> {
            listOf(1.seconds, null).forEach { timeout ->
                val link = FakeLink(backend, frameSize = 8) { null }
                val channel = LedgerBleChannel(link, backend, frameSize = 8)

                assertFailsWith<TimeoutCancellationException>("exchange timeout $timeout") {
                    withTimeout(20.milliseconds) { channel.exchange(byteArrayOf(1), timeout) }
                }
                assertTrue(link.closed, "the exchange left its reply uncollected")
            }
        }

    @Test
    fun the_mtu_handshake_reads_the_frame_size_and_skips_other_notifications() =
        runBlocking<Unit> {
            val link = FakeLink(backend, frameSize = 8) { null }
            link.channel.trySend(byteArrayOf(0x05, 0, 0))
            link.channel.trySend(byteArrayOf(0x08, 0, 0, 0, 1, 153.toByte()))

            assertEquals(153, negotiateFrameSize(link, backend, 1.seconds))
            assertContentEquals(byteArrayOf(0x08, 0, 0, 0, 0), link.written.single())
        }

    /** A link whose writes fail with [failure], as a GATT write the device refuses does. */
    private class FailingWriteLink(
        private val failure: LedgerException
    ) : LedgerBleLink {
        override val notifications = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun write(frame: ByteArray) = throw failure

        override fun close() = Unit
    }

    @Test
    fun a_refused_handshake_write_is_a_refused_pairing() =
        runBlocking<Unit> {
            val link = FailingWriteLink(LedgerException.PairingRefused(reason = null))

            val error = assertFailsWith<LedgerException.PairingRefused> { negotiateFrameSize(link, backend, 1.seconds) }
            assertEquals("the device refused the first write on the link", error.reason)
        }

    @Test
    fun a_link_dropped_under_the_handshake_write_stays_a_disconnect() =
        runBlocking<Unit> {
            assertFailsWith<LedgerException.Disconnected> {
                negotiateFrameSize(FailingWriteLink(LedgerException.Disconnected()), backend, 1.seconds)
            }
            val closed = FakeLink(backend, frameSize = 8).apply { close() }
            assertFailsWith<LedgerException.Disconnected> { negotiateFrameSize(closed, backend, 1.seconds) }
        }

    /** A link whose writes the Bluetooth stack never confirms, as a GATT write timing out does. */
    private class StalledWriteLink : LedgerBleLink {
        override val notifications = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun write(frame: ByteArray) {
            withTimeout(10.milliseconds) { awaitCancellation() }
        }

        override fun close() = Unit
    }

    @Test
    fun a_handshake_write_that_times_out_is_a_connection_failure() =
        runBlocking<Unit> {
            val error =
                assertFailsWith<LedgerException.ConnectionFailed> {
                    negotiateFrameSize(StalledWriteLink(), backend, 1.seconds)
                }
            assertIs<TimeoutCancellationException>(error.cause)
        }

    @Test
    fun the_callers_own_timeout_during_the_handshake_write_is_not_a_connection_failure() =
        runBlocking<Unit> {
            val link =
                object : LedgerBleLink {
                    override val notifications = Channel<ByteArray>(Channel.UNLIMITED)

                    override suspend fun write(frame: ByteArray) = awaitCancellation()

                    override fun close() = Unit
                }

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(20.milliseconds) { negotiateFrameSize(link, backend, 1.seconds) }
            }
        }
}
