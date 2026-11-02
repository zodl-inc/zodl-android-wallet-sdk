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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
