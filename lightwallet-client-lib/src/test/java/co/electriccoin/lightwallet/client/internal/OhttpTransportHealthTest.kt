@file:Suppress("MaxLineLength")

package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.health.OhttpHealthCenter
import co.electriccoin.lightwallet.client.health.OhttpHealthObservation
import co.electriccoin.lightwallet.client.health.OhttpHealthOutcome
import co.electriccoin.lightwallet.client.health.OhttpHealthRoute
import co.electriccoin.lightwallet.client.health.OhttpHealthScope
import co.electriccoin.lightwallet.client.health.OhttpHealthStage
import co.electriccoin.lightwallet.client.health.newOhttpHealthOperation
import co.electriccoin.lightwallet.client.health.withOhttpOperation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.test.assertFailsWith

/** What the transport reports to the health center, whatever the call's own result is. */
class OhttpTransportHealthTest {
    private val sender = RecordingSender()
    private val codec = FakeOhttpCodec()
    private val center = OhttpHealthCenter()

    // The relay's 429 pause is process-wide and keyed by URL: every test needs a relay of its own.
    private val relayUrl = "https://relay-${UUID.randomUUID()}.test/relay"
    private val route = OhttpHealthRoute("ohttp-gateway-testnet.example", relayUrl, viaTor = false)

    private fun request(authority: String = "ohttp-gateway-testnet.example") =
        OhttpCodecRequest("POST", "https", authority, "/svc/Method", emptyList(), ByteArray(0))

    private fun transport() =
        OhttpTransport(
            relayUrl = relayUrl,
            gatewayHost = "ohttp-gateway-testnet.example",
            codec = codec,
            sender = sender,
            initialKey = ByteArray(33) { 7 },
            health = center,
        )

    private fun observations() = center.snapshot(route).observations

    private fun OhttpHealthObservation.isRouteUsable() =
        scope == OhttpHealthScope.ROUTE && outcome == OhttpHealthOutcome.Usable

    @Test
    fun `a delivered response proves the route and the backend answer is recorded in the request's scope`() {
        sender.queuePost { "relay answer".toByteArray() }

        runBlocking { transport().roundTrip(request(), 5_000) }

        val all = observations()
        assertTrue(all.any { it.isRouteUsable() })
        val backend = all.single { it.scope == OhttpHealthScope.LIGHTWALLETD }
        assertEquals(OhttpHealthOutcome.Usable, backend.outcome)
        assertEquals(OhttpHealthStage.COMPLETE, backend.stage)
    }

    @Test
    fun `the scope follows the request's authority`() {
        sender.queuePost { "relay answer".toByteArray() }

        runBlocking { transport().roundTrip(request(authority = "cmc.zodl.com"), 5_000) }

        assertTrue(observations().any { it.scope == OhttpHealthScope.EXCHANGE_RATES })
        assertTrue(observations().none { it.scope == OhttpHealthScope.LIGHTWALLETD })
    }

    @Test
    fun `an unreachable relay is transport unavailable and the failure still reaches the caller`() {
        sender.queuePost { throw SocketTimeoutException("no answer") }

        assertFailsWith<SocketTimeoutException> { runBlocking { transport().roundTrip(request(), 5_000) } }

        val failure = observations().single { it.outcome == OhttpHealthOutcome.TransportUnavailable }
        assertEquals(OhttpHealthScope.LIGHTWALLETD, failure.scope)
        assertEquals(OhttpHealthStage.CONNECTION, failure.stage)
        assertTrue(observations().none { it.isRouteUsable() })
    }

    @Test
    fun `an outer relay 5xx is transport unavailable`() {
        sender.queuePost { throw OhttpException("bad gateway", 502) }

        assertFailsWith<OhttpException> { runBlocking { transport().roundTrip(request(), 5_000) } }

        assertTrue(observations().any { it.outcome == OhttpHealthOutcome.TransportUnavailable })
    }

    @Test
    fun `an outer 429 is a rate limit and never outage evidence`() {
        sender.queuePost { throw OhttpException("slow down", 429, retryAfterSeconds = 5) }

        assertFailsWith<OhttpException> { runBlocking { transport().roundTrip(request(), 5_000) } }

        val all = observations()
        assertTrue(all.any { it.outcome is OhttpHealthOutcome.RateLimited })
        assertTrue(all.none { it.outcome == OhttpHealthOutcome.TransportUnavailable })
    }

    @Test
    fun `a backend 5xx inside a delivered response is not the route's fault`() {
        codec.answer = OhttpCodecResponse(503, emptyList(), emptyList(), byteArrayOf(1))
        sender.queuePost { "relay answer".toByteArray() }

        runBlocking { transport().roundTrip(request(), 5_000) }

        val all = observations()
        assertTrue(all.any { it.isRouteUsable() })
        assertTrue(all.any { it.outcome == OhttpHealthOutcome.BackendUnavailable })
        assertTrue(all.none { it.outcome == OhttpHealthOutcome.TransportUnavailable })
    }

    @Test
    fun `a non-OK gRPC status in the trailers is classified like the engine does`() {
        codec.answer = OhttpCodecResponse(200, emptyList(), listOf("grpc-status" to "14"), byteArrayOf(1))
        sender.queuePost { "relay answer".toByteArray() }

        runBlocking { transport().roundTrip(request(), 5_000) }

        assertTrue(observations().any { it.outcome == OhttpHealthOutcome.BackendUnavailable })
    }

    @Test
    fun `background work carries no operation`() {
        sender.queuePost { "relay answer".toByteArray() }

        runBlocking { transport().roundTrip(request(), 5_000) }

        assertTrue(observations().all { it.operation == null })
    }

    @Test
    fun `a request made inside a user operation carries its identity`() {
        val operation = newOhttpHealthOperation()
        sender.queuePost { "relay answer".toByteArray() }

        runBlocking { withOhttpOperation(operation) { transport().roundTrip(request(), 5_000) } }

        val observation = observations().single { it.scope == OhttpHealthScope.LIGHTWALLETD }
        assertEquals(operation, observation.operation)
    }

    @Test
    fun `a call cancelled while its exchange fails is cancelled, not an outage`() {
        lateinit var job: Job
        // Cancelling the caller closes the OkHttp call, which then reports an I/O failure.
        sender.queuePost {
            job.cancel()
            throw IOException("Canceled")
        }

        runBlocking {
            job = launch(start = CoroutineStart.LAZY) { runCatching { transport().roundTrip(request(), 5_000) } }
            job.start()
            job.join()
        }

        val outcomes = observations().map { it.outcome }
        assertTrue(outcomes.contains(OhttpHealthOutcome.Cancelled))
        assertTrue(outcomes.none { it == OhttpHealthOutcome.TransportUnavailable })
    }

    private class NoSink : OhttpResponseSink {
        override fun onHead(status: Int, headers: List<Pair<String, String>>) = Unit

        override fun onData(bytes: ByteArray) = Unit

        override fun onTrailers(trailers: List<Pair<String, String>>) = Unit

        override fun onEnd() = Unit
    }

    /** Hands out one part per read, never merging two. */
    private class PartsStream(
        private val parts: List<ByteArray>,
    ) : InputStream() {
        private var index = 0
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (index >= parts.size) return -1
            val part = parts[index]
            val n = minOf(len, part.size - offset)
            System.arraycopy(part, offset, b, off, n)
            offset += n
            if (offset == part.size) {
                index += 1
                offset = 0
            }
            return n
        }
    }

    private val grpcHead = FakeOhttpCodec.headRecord(200, listOf("content-type" to "application/grpc"))

    @Test
    fun `a streamed response proves the route at its head and records the backend answer from its trailers`() {
        sender.queuePost {
            FakeOhttpCodec.streamed(
                grpcHead,
                FakeOhttpCodec.dataRecord(ByteArray(3)),
                FakeOhttpCodec.trailersRecord(listOf("grpc-status" to "14")),
                FakeOhttpCodec.finalRecord(),
            )
        }

        runBlocking { transport().stream(request(), 5_000, NoSink()) }

        val all = observations()
        assertTrue(all.any { it.isRouteUsable() })
        assertTrue(all.any { it.scope == OhttpHealthScope.LIGHTWALLETD && it.outcome == OhttpHealthOutcome.BackendUnavailable })
        assertTrue(all.none { it.outcome == OhttpHealthOutcome.TransportUnavailable })
    }

    @Test
    fun `a streamed response that fails after its head still proved the route`() {
        // The body arrives in separate reads, as on the wire: the head is handed over before the bad chunk.
        sender.queuePostStream {
            PartsStream(
                listOf(
                    FakeOhttpCodec.streamStart() + grpcHead,
                    FakeOhttpCodec.dataRecord(ByteArray(3)),
                    FakeOhttpCodec.tamperedRecord(),
                ),
            )
        }

        assertFailsWith<OhttpException> { runBlocking { transport().stream(request(), 5_000, NoSink()) } }

        val all = observations()
        assertTrue("the head arrived, so the route answered", all.any { it.isRouteUsable() })
        val failure = all.single { it.scope == OhttpHealthScope.LIGHTWALLETD }
        assertEquals(OhttpHealthStage.RESPONSE_BODY, failure.stage)
        assertTrue(failure.outcome != OhttpHealthOutcome.TransportUnavailable)
    }

    @Test
    fun `an unreachable relay fails a stream as transport unavailable at the connection`() {
        sender.queuePost { throw SocketTimeoutException("no answer") }

        assertFailsWith<SocketTimeoutException> { runBlocking { transport().stream(request(), 5_000, NoSink()) } }

        val failure = observations().single { it.outcome == OhttpHealthOutcome.TransportUnavailable }
        assertEquals(OhttpHealthStage.CONNECTION, failure.stage)
    }
}
