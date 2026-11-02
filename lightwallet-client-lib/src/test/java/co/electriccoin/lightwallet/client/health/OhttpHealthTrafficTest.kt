package co.electriccoin.lightwallet.client.health

import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpInternalException
import co.electriccoin.lightwallet.client.OhttpRequestTooLargeException
import org.junit.Assert.assertEquals
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Test

class OhttpHealthTrafficTest {
    private val now = 5_000_000_000L

    @Test
    fun scopeFollowsTheAuthority() {
        assertEquals(OhttpHealthScope.EXCHANGE_RATES, OhttpHealthTraffic.scope("cmc.zodl.com"))
        assertEquals(OhttpHealthScope.SWAPS, OhttpHealthTraffic.scope("NEAR.zodl.com"))
        assertEquals(OhttpHealthScope.FLEXA, OhttpHealthTraffic.scope("flexa.zodl.com"))
        assertEquals(OhttpHealthScope.VOTING, OhttpHealthTraffic.scope("vote.zodl.com"))
        assertEquals(OhttpHealthScope.LIGHTWALLETD, OhttpHealthTraffic.scope("ohttp-gateway.example"))
        assertEquals(OhttpHealthScope.LIGHTWALLETD, OhttpHealthTraffic.scope("ohttp-gateway-testnet.example"))
        assertEquals(OhttpHealthScope.OTHER, OhttpHealthTraffic.scope("ohttp-gateway.example.org"))
    }

    @Test
    fun anUnreachableRouteIsTransportUnavailable() {
        listOf<Throwable>(
            SocketTimeoutException("timeout"),
            ConnectException("refused"),
            UnknownHostException("dns"),
            SSLHandshakeException("tls"),
            IOException("eof"),
        ).forEach {
            assertEquals(it.toString(), OhttpHealthOutcome.TransportUnavailable, OhttpHealthTraffic.outcome(it, now))
        }
    }

    @Test
    fun cancellationIsNeverOutageEvidence() {
        assertEquals(OhttpHealthOutcome.Cancelled, OhttpHealthTraffic.outcome(CancellationException("x"), now))
    }

    @Test
    fun outerRelayStatusesAreClassifiedByKind() {
        fun outcome(status: Int) = OhttpHealthTraffic.outcome(OhttpException("x", status), now)
        assertEquals(OhttpHealthOutcome.TransportUnavailable, outcome(502))
        assertEquals(OhttpHealthOutcome.TransportUnavailable, outcome(503))
        assertEquals(OhttpHealthOutcome.PolicyRejected, outcome(403))
        assertEquals(OhttpHealthOutcome.PolicyRejected, outcome(426))
        assertEquals(OhttpHealthOutcome.ResponseRejected, outcome(400))
        assertEquals(OhttpHealthOutcome.ResponseRejected, outcome(413))
        assertEquals(OhttpHealthOutcome.Inconclusive, outcome(302))
    }

    @Test
    fun aRateLimitIsAPauseNotAnOutage() {
        val withHeader = OhttpHealthTraffic.outcome(OhttpException("x", 429, retryAfterSeconds = 7), now)
        assertEquals(OhttpHealthOutcome.RateLimited(now + 7_000_000_000L), withHeader)

        val withoutHeader = OhttpHealthTraffic.outcome(OhttpException("x", 429), now)
        assertEquals(OhttpHealthOutcome.RateLimited(now + 30_000_000_000L), withoutHeader)
    }

    @Test
    fun aBackendFiveHundredInsideADeliveredResponseIsNotTheRoutesFault() {
        assertEquals(OhttpHealthOutcome.BackendUnavailable, OhttpHealthTraffic.innerStatusOutcome(503, nowNanos = now))
        assertEquals(OhttpHealthOutcome.Usable, OhttpHealthTraffic.innerStatusOutcome(200, nowNanos = now))
        assertEquals(OhttpHealthOutcome.PolicyRejected, OhttpHealthTraffic.innerStatusOutcome(401, nowNanos = now))
    }

    @Test
    fun grpcStatusesMapLikeTheEngine() {
        assertEquals(OhttpHealthOutcome.Usable, OhttpHealthTraffic.grpcOutcome(0))
        assertEquals(OhttpHealthOutcome.Cancelled, OhttpHealthTraffic.grpcOutcome(1))
        assertEquals(OhttpHealthOutcome.BackendUnavailable, OhttpHealthTraffic.grpcOutcome(14))
        assertEquals(OhttpHealthOutcome.BackendUnavailable, OhttpHealthTraffic.grpcOutcome(4))
        assertEquals(OhttpHealthOutcome.PolicyRejected, OhttpHealthTraffic.grpcOutcome(16))
        assertEquals(OhttpHealthOutcome.ResponseRejected, OhttpHealthTraffic.grpcOutcome(8))
    }

    @Test
    fun protocolFailuresAreNotOutageEvidence() {
        assertEquals(
            OhttpHealthOutcome.ConfigurationFailure,
            OhttpHealthTraffic.outcome(KeyConfigVerificationFailedException("bad config"), now)
        )
        assertEquals(OhttpHealthOutcome.ResponseRejected, OhttpHealthTraffic.outcome(OhttpInternalException("aead"), now))
        assertEquals(
            OhttpHealthOutcome.ResponseRejected,
            OhttpHealthTraffic.outcome(OhttpRequestTooLargeException("big"), now)
        )
        assertEquals(OhttpHealthOutcome.Inconclusive, OhttpHealthTraffic.outcome(OhttpException("unexplained"), now))
    }

    @Test
    fun aWrappedTransportFailureIsStillTransportUnavailable() {
        val wrapped = OhttpException("tor stream failed", cause = RuntimeException("broken circuit"))
        assertEquals(OhttpHealthOutcome.TransportUnavailable, OhttpHealthTraffic.outcome(wrapped, now))
        val tooLarge = OhttpException("oom", cause = OutOfMemoryError())
        assertEquals(OhttpHealthOutcome.ResponseRejected, OhttpHealthTraffic.outcome(tooLarge, now))
    }
}
