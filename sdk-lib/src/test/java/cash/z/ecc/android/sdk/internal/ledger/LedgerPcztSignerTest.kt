package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.BAD_STATE
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_REVIEW
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.CMD_STREAM
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.DENY
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.NORMAL_TIMEOUT
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.NOT_ACCEPTED
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.identity
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.ok
import cash.z.ecc.android.sdk.internal.ledger.FakeLedgerProtocol.status
import cash.z.ecc.android.sdk.ledger.LedgerAccountBinding
import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import cash.z.ecc.android.sdk.ledger.LedgerDeviceIdentity
import cash.z.ecc.android.sdk.ledger.LedgerSigningProgress
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class LedgerPcztSignerTest {
    private val binding = LedgerAccountBinding(LedgerDeviceIdentity(identity('a')), Zip32AccountIndex.new(0))
    private val accountUuid = AccountUuid.new(ByteArray(16))

    private suspend fun sign(
        backend: FakeLedgerBackend,
        transport: LedgerApduTransport,
        progress: MutableList<LedgerSigningProgress> = mutableListOf()
    ): Pczt =
        LedgerPcztSigner(backend).sign(
            dataDbFile = File("unused"),
            network = ZcashNetwork.Testnet,
            pczt = Pczt(byteArrayOf(1, 2, 3)),
            accountUuid = accountUuid,
            binding = binding,
            transport = transport,
            onProgress = { progress.add(it) }
        )

    /** The firmware probe, then the session: two identification commands, the stream, the review
     * and the signature. */
    private fun ceremonyReplies(streamPackets: Int) = List(1 + 2 + streamPackets + 2) { ok(1) }

    @Test
    fun a_ceremony_reports_its_progress_in_order_and_returns_the_signed_pczt() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(streamPackets = 2)
            val transport = ScriptedTransport(ceremonyReplies(streamPackets = 2))
            val progress = mutableListOf<LedgerSigningProgress>()

            val signed = sign(backend, transport, progress)

            assertContentEquals(byteArrayOf(0x5A), signed.toByteArray())
            assertEquals(
                listOf(
                    LedgerSigningProgress.IdentifyingDevice,
                    LedgerSigningProgress.Streaming(sent = 2, total = 6),
                    LedgerSigningProgress.Streaming(sent = 3, total = 6),
                    LedgerSigningProgress.AwaitingReviewOnDevice,
                    LedgerSigningProgress.Signing,
                    LedgerSigningProgress.Complete
                ),
                progress
            )
            // No timeout from the review packet on.
            assertEquals(List(5) { NORMAL_TIMEOUT } + listOf(null, null), transport.timeouts)
            assertEquals(1, backend.sessionsClosed)
            assertFalse(transport.closed)
        }

    @Test
    fun a_refused_frame_mid_stream_is_resent_and_the_ceremony_completes() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(streamPackets = 1)
            val replies = ceremonyReplies(streamPackets = 1).toMutableList()
            // The stream packet (exchange 3) is refused once.
            replies.add(3, status(NOT_ACCEPTED))
            val transport = ScriptedTransport(replies)

            sign(backend, transport)

            assertEquals(CMD_STREAM, transport.sent[3].single())
            assertEquals(CMD_STREAM, transport.sent[4].single())
        }

    @Test
    fun a_transport_failure_closes_the_transport_and_the_session() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(streamPackets = 2)
            val failure = IOException("disconnected")
            val transport = ScriptedTransport(ceremonyReplies(streamPackets = 2), failAt = 4 to failure)

            val thrown = assertFailsWith<IOException> { sign(backend, transport) }

            assertEquals(failure, thrown)
            assertTrue(transport.closed)
            assertEquals(1, backend.sessionsClosed)
        }

    @Test
    fun a_rejected_review_is_restartable_and_leaves_the_transport_open() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(streamPackets = 1)
            val replies = ceremonyReplies(streamPackets = 1).toMutableList()
            // The review packet is exchange 4.
            replies[4] = status(DENY)
            val transport = ScriptedTransport(replies)

            val error = assertFailsWith<LedgerException.UserRejected> { sign(backend, transport) }

            assertEquals(CMD_REVIEW, transport.sent.last().single())
            assertTrue(error.isRestartable)
            assertFalse(transport.closed)
            assertEquals(1, backend.sessionsClosed)
        }

    @Test
    fun a_rejected_review_can_be_signed_again_on_the_same_transport() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(streamPackets = 1)
            val rejected = ceremonyReplies(streamPackets = 1).take(5).toMutableList()
            rejected[4] = status(DENY)
            val transport = ScriptedTransport(rejected + ceremonyReplies(streamPackets = 1))

            assertFailsWith<LedgerException.UserRejected> { sign(backend, transport) }
            val signed = sign(backend, transport)

            assertContentEquals(byteArrayOf(0x5A), signed.toByteArray())
            assertEquals(5 + ceremonyReplies(streamPackets = 1).size, transport.sent.size)
            assertFalse(transport.closed)
            assertEquals(2, backend.sessionsClosed)
        }

    @Test
    fun a_device_refusal_ends_the_ceremony_without_closing_the_transport() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(streamPackets = 2)
            val replies = ceremonyReplies(streamPackets = 2).toMutableList()
            replies[3] = status(BAD_STATE)
            val transport = ScriptedTransport(replies)

            val error = assertFailsWith<LedgerException.DeviceRefused> { sign(backend, transport) }

            assertTrue(error.isTransient)
            assertFalse(transport.closed)
        }

    @Test
    fun cancelling_the_ceremony_closes_the_transport() =
        runBlocking<Unit> {
            val backend = FakeLedgerBackend(streamPackets = 1)
            val reachedReview = CompletableDeferred<Unit>()
            val transport =
                object : LedgerApduTransport {
                    var closed = false
                    private var exchanges = 0

                    override suspend fun exchange(
                        apdu: ByteArray,
                        timeout: Duration?
                    ): ByteArray {
                        exchanges++
                        if (apdu.single() == CMD_REVIEW) {
                            reachedReview.complete(Unit)
                            awaitCancellation()
                        }
                        return ok(1)
                    }

                    override suspend fun close() {
                        closed = true
                    }
                }

            val ceremony = async { sign(backend, transport) }
            reachedReview.await()
            ceremony.cancel()

            assertFailsWith<CancellationException> { ceremony.await() }
            assertTrue(transport.closed)
            assertEquals(1, backend.sessionsClosed)
        }
}
