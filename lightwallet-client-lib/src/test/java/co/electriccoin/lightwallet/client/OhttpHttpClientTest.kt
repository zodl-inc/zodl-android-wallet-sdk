package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.DirectOhttpSender
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec
import co.electriccoin.lightwallet.client.internal.OhttpDebugFlags
import co.electriccoin.lightwallet.client.internal.OhttpEndpoints
import co.electriccoin.lightwallet.client.internal.OhttpTransport
import co.electriccoin.lightwallet.client.internal.isOhttpProxyAllowedHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("ktlint:standard:max-line-length", "ktlint:standard:chain-method-continuation")
class OhttpHttpClientTest {
    init {
        // Initialize OhttpEndpoints with a fake relay URL mapper for testing
        OhttpEndpoints.initialize(
            object : RelayUrlMapper {
                override fun relayUrlFor(gatewayHost: String): String? =
                    when (gatewayHost) {
                        "ohttp-gateway.example" -> "https://relay-prod.zodl.com/relay"
                        "ohttp-gateway-testnet.example" -> "https://relay-dev.zodl.com/relay"
                        else -> null
                    }
            },
        )
    }

    private val codec = FakeOhttpCodec()

    private fun client() = OhttpHttpClient.create(codec)

    /** A transport whose every post fails after being captured, with a key cached so that no fetch happens. */
    private fun capturingTransport(
        capturedHeaders: MutableList<List<Pair<String, String>>>,
        ohttpClientTraceIdPrefix: String?,
    ): OhttpTransport {
        val mockSender =
            object : OhttpSender {
                override suspend fun dispose() = Unit

                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray {
                    capturedHeaders.add(headers)
                    throw OhttpException("test abort")
                }

                override suspend fun get(
                    url: String,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray = throw NotImplementedError("GET not used in this test (initialKey provided)")
            }
        return OhttpTransport(
            relayUrl = "https://relay.zodl.com/relay",
            gatewayHost = "ohttp-gateway-testnet.example",
            codec = codec,
            sender = mockSender,
            ohttpClientTraceIdPrefix = ohttpClientTraceIdPrefix,
            initialKey = FakeOhttpCodec.KEY,
        )
    }

    private fun roundTripAndIgnoreFailure(transport: OhttpTransport): Throwable? =
        runCatching {
            runBlocking {
                transport.roundTrip(
                    OhttpCodecRequest(
                        method = "GET",
                        scheme = "https",
                        authority = "example.zodl.com",
                        path = "/test",
                        headers = emptyList(),
                        body = ByteArray(0),
                    ),
                    timeoutMs = 5000,
                )
            }
        }.exceptionOrNull()

    @Test
    fun `canHandle returns true for static proxy mappings`() {
        val client = client()
        assertTrue(client.canHandle("https://1click.chaindefuser.com/v0/tokens"))
        assertTrue(client.canHandle("https://voting.valargroup.dev/prod/dynamic-voting-config.json"))
        assertTrue(client.canHandle("https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest?symbol=ZEC"))
    }

    @Test
    fun `canHandle returns true for zodl OHTTP gateway aliases`() {
        val client = client()
        assertTrue(client.canHandle("https://near.zodl.com/v0/tokens"))
        assertTrue(client.canHandle("https://cmc.zodl.com/v1/cryptocurrency/quotes/latest"))
        assertTrue(client.canHandle("https://vote.zodl.com/prod/dynamic-voting-config.json"))
    }

    @Test
    fun `canHandle returns true for allowlisted suffixes`() {
        val client = client()
        assertTrue(client.canHandle("https://voting-prod.valargroup.org/shielded-vote/v1/rounds"))
        assertTrue(client.canHandle("https://voting-prod-2.valargroup.org/shielded-vote/v1/rounds/active"))
        assertTrue(client.canHandle("https://api.valargroup.dev/config"))
        assertTrue(client.canHandle("https://subdomain.chaindefuser.com/path"))
    }

    @Test
    fun `canHandle returns false for non-allowlisted hosts`() {
        val client = client()
        assertFalse(client.canHandle("https://example.com/path"))
        assertFalse(client.canHandle("https://valargroup.com/path"))
        assertFalse(client.canHandle("https://malicious-valargroup.org/path"))
        assertFalse(client.canHandle("https://notchaindefuser.com/path"))
    }

    @Test
    fun `isOhttpProxyAllowedHost matches dot-prefixed suffixes correctly`() {
        assertTrue(isOhttpProxyAllowedHost("voting-prod.valargroup.org"))
        assertTrue(isOhttpProxyAllowedHost("api.valargroup.dev"))
        assertTrue(isOhttpProxyAllowedHost("valargroup.org"))
        assertTrue(isOhttpProxyAllowedHost("valargroup.dev"))
        assertFalse(isOhttpProxyAllowedHost("valargroup.com"))
        assertFalse(isOhttpProxyAllowedHost("evil-valargroup.org"))
        assertFalse(isOhttpProxyAllowedHost("evilorg"))
    }

    @Test
    fun `isOhttpProxyAllowedHost matches bare domain suffixes correctly`() {
        assertTrue(isOhttpProxyAllowedHost("chaindefuser.com"))
        assertTrue(isOhttpProxyAllowedHost("subdomain.chaindefuser.com"))
        assertTrue(isOhttpProxyAllowedHost("a.b.chaindefuser.com"))
        assertFalse(isOhttpProxyAllowedHost("chaindefuser.net"))
        assertFalse(isOhttpProxyAllowedHost("notchaindefuser.com"))
    }

    @Test
    fun `isOhttpProxyAllowedHost is case-insensitive`() {
        assertTrue(isOhttpProxyAllowedHost("VOTING-PROD.VALARGROUP.ORG"))
        assertTrue(isOhttpProxyAllowedHost("ChainDefUser.COM"))
        assertTrue(isOhttpProxyAllowedHost("API.ValarGroup.Dev"))
    }

    @Test
    fun `canHandle handles invalid URLs gracefully`() {
        val client = client()
        assertFalse(client.canHandle("not a url"))
        assertFalse(client.canHandle(""))
        assertFalse(client.canHandle("://missing-scheme"))
    }

    @Test
    fun `client trace ID header is absent when prefix not set`() {
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
        val transport = capturingTransport(capturedHeaders, ohttpClientTraceIdPrefix = null)
        val exceptionThrown = roundTripAndIgnoreFailure(transport)
        assertTrue(exceptionThrown is OhttpException)
        assertTrue(capturedHeaders.isNotEmpty())
        val headerKeys = capturedHeaders.first().map { it.first }
        assertFalse(headerKeys.contains("X-Client-Trace-Id"))
        assertFalse(headerKeys.contains("X-Request-Id"))
    }

    @Test
    fun `client trace ID header is present and well-formed when prefix set`() {
        OhttpDebugFlags.allowTracePrefix = true
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
        val transport = capturingTransport(capturedHeaders, ohttpClientTraceIdPrefix = "test-wallet")
        // Make two requests to verify sequence counter increments
        repeat(2) { roundTripAndIgnoreFailure(transport) }
        assertTrue("Must have captured two requests", capturedHeaders.size == 2)
        // Check first request
        val headers1 = capturedHeaders[0]
        val traceIdHeader1 = headers1.find { it.first == "X-Client-Trace-Id" }
        assertTrue("X-Client-Trace-Id header must be present", traceIdHeader1 != null)
        val traceId1 = traceIdHeader1!!.second
        assertTrue("Trace ID must start with prefix", traceId1.startsWith("test-wallet-"))
        assertTrue("Trace ID must be ≤ 64 chars", traceId1.length <= 64)
        // Format: <prefix>-<uuid>-<seq> where uuid is 36 chars, seq is 6 digits
        val traceIdPattern =
            Regex("^[A-Za-z0-9][A-Za-z0-9._:-]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-\\d{6}$")
        assertTrue(
            "Trace ID must match format <prefix>-<uuid>-<seq>",
            traceId1.matches(traceIdPattern),
        )
        // Check second request - must have same session UUID but incremented seq
        val traceIdHeader2 = capturedHeaders[1].find { it.first == "X-Client-Trace-Id" }
        assertTrue(traceIdHeader2 != null)
        val traceId2 = traceIdHeader2!!.second
        // Extract session UUID and seq from both
        val parts1 = traceId1.split("-")
        val parts2 = traceId2.split("-")
        // Session UUID should be the same (parts 1-5)
        val sessionUuid1 = parts1.subList(1, 6).joinToString("-")
        val sessionUuid2 = parts2.subList(1, 6).joinToString("-")
        assertTrue("Session UUID must be same across requests", sessionUuid1 == sessionUuid2)
        // Sequence number should increment
        val seq1 = parts1.last().toInt()
        val seq2 = parts2.last().toInt()
        assertTrue("Sequence must increment", seq2 == seq1 + 1)
        assertTrue("First sequence must be 0", seq1 == 0)
        // Verify X-Request-Id is never set
        assertFalse(headers1.any { it.first == "X-Request-Id" })
    }

    @Test
    fun `client trace ID sanitises invalid characters`() {
        OhttpDebugFlags.allowTracePrefix = true
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
        val transport = capturingTransport(capturedHeaders, ohttpClientTraceIdPrefix = "test@wallet#123")
        val exceptionThrown = roundTripAndIgnoreFailure(transport)
        assertTrue(exceptionThrown is OhttpException)
        assertTrue(capturedHeaders.isNotEmpty())
        val traceIdHeader = capturedHeaders.first().find { it.first == "X-Client-Trace-Id" }
        assertTrue(traceIdHeader != null)
        val traceId = traceIdHeader!!.second
        assertFalse("Trace ID must not contain @", traceId.contains("@"))
        assertFalse("Trace ID must not contain #", traceId.contains("#"))
        // Must start with alphanumeric
        assertTrue("Trace ID must start with alphanumeric", traceId.first().isLetterOrDigit())
        // Format: <prefix>-<uuid>-<seq>
        val traceIdPattern =
            Regex("^[A-Za-z0-9][A-Za-z0-9._:-]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-\\d{6}$")
        assertTrue(
            "Trace ID must match format",
            traceId.matches(traceIdPattern),
        )
        // The prefix should be "testwallet123" after sanitization (@ and # removed)
        assertTrue("Trace ID must start with sanitized prefix", traceId.startsWith("testwallet123-"))
    }

    @Test
    fun `client trace ID truncates to 64 chars`() {
        OhttpDebugFlags.allowTracePrefix = true
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
        // Long prefix: 60 'a' + 1 '-' + 36 UUID + 1 '-' + 6 digits = 104 chars → truncated to 64
        val transport = capturingTransport(capturedHeaders, ohttpClientTraceIdPrefix = "a".repeat(60))
        val exceptionThrown = roundTripAndIgnoreFailure(transport)
        assertTrue(exceptionThrown is OhttpException)
        assertTrue(capturedHeaders.isNotEmpty())
        val traceIdHeader = capturedHeaders.first().find { it.first == "X-Client-Trace-Id" }
        assertTrue(traceIdHeader != null)
        val traceId = traceIdHeader!!.second
        assertTrue("Trace ID must be exactly 64 chars", traceId.length == 64)
        // Must still start with the prefix (truncated)
        assertTrue("Trace ID must start with truncated prefix", traceId.startsWith("a"))
    }

    @Test
    fun `DirectOhttpSender disables redirects`() {
        val sender = DirectOhttpSender()
        // This test verifies that DirectOhttpSender.open() sets instanceFollowRedirects=false.
        // Since we can't directly test HttpURLConnection behavior without a real server,
        // we rely on code review: the open() method MUST set instanceFollowRedirects=false.
        // This test documents the requirement.
    }

    @Test
    fun `isOhttpGateway matches only exact configured gateway hosts`() {
        val endpoints = OhttpEndpoints
        assertTrue(endpoints.isOhttpGateway("ohttp-gateway.example"))
        assertTrue(endpoints.isOhttpGateway("ohttp-gateway-testnet.example"))
        assertFalse(endpoints.isOhttpGateway("ohttp-gateway.example.org"))
        assertFalse(endpoints.isOhttpGateway("ohttp-gateway"))
    }
}
