package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.isOhttpProxyAllowedHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("ktlint:standard:max-line-length", "ktlint:standard:chain-method-continuation")
class OhttpHttpClientTest {
    @Test
    fun `canHandle returns true for static proxy mappings`() {
        val client = OhttpHttpClient.create()
        assertTrue(client.canHandle("https://1click.chaindefuser.com/v0/tokens"))
        assertTrue(client.canHandle("https://voting.valargroup.dev/prod/dynamic-voting-config.json"))
        assertTrue(client.canHandle("https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest?symbol=ZEC"))
    }

    @Test
    fun `canHandle returns true for allowlisted suffixes`() {
        val client = OhttpHttpClient.create()
        assertTrue(client.canHandle("https://voting-prod.valargroup.org/shielded-vote/v1/rounds"))
        assertTrue(client.canHandle("https://voting-prod-2.valargroup.org/shielded-vote/v1/rounds/active"))
        assertTrue(client.canHandle("https://api.valargroup.dev/config"))
        assertTrue(client.canHandle("https://subdomain.chaindefuser.com/path"))
    }

    @Test
    fun `canHandle returns false for non-allowlisted hosts`() {
        val client = OhttpHttpClient.create()
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
        val client = OhttpHttpClient.create()
        assertFalse(client.canHandle("not a url"))
        assertFalse(client.canHandle(""))
        assertFalse(client.canHandle("://missing-scheme"))
    }

    @Test
    fun `client trace ID header is absent when prefix not set`() {
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
        val mockSender =
            object : co.electriccoin.lightwallet.client.internal.OhttpSender {
                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int
                ): ByteArray {
                    capturedHeaders.add(headers)
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("test abort")
                }
            }
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                keyConfigUrl = "https://gateway.zodl.com/ohttp-keys",
                sender = mockSender,
                ohttpClientTraceIdPrefix = null
            )
        val exceptionThrown =
            runCatching {
                kotlinx.coroutines.runBlocking {
                    transport.roundTrip(
                        co.electriccoin.lightwallet.client.internal.BinaryHttp.Request(
                            method = "GET",
                            scheme = "https",
                            authority = "example.zodl.com",
                            path = "/test",
                            headers = emptyList(),
                            body = ByteArray(0)
                        ),
                        timeoutMs = 5000
                    )
                }
            }.exceptionOrNull()
        assertTrue(exceptionThrown is co.electriccoin.lightwallet.client.internal.OhttpException)
        assertTrue(capturedHeaders.isNotEmpty())
        val headerKeys = capturedHeaders.first().map { it.first }
        assertFalse(headerKeys.contains("X-Client-Trace-Id"))
        assertFalse(headerKeys.contains("X-Request-Id"))
    }

    @Test
    fun `client trace ID header is present and well-formed when prefix set`() {
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
        val mockSender =
            object : co.electriccoin.lightwallet.client.internal.OhttpSender {
                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int
                ): ByteArray {
                    capturedHeaders.add(headers)
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("test abort")
                }
            }
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                keyConfigUrl = "https://gateway.zodl.com/ohttp-keys",
                sender = mockSender,
                ohttpClientTraceIdPrefix = "test-wallet"
            )
        val exceptionThrown =
            runCatching {
                kotlinx.coroutines.runBlocking {
                    transport.roundTrip(
                        co.electriccoin.lightwallet.client.internal.BinaryHttp.Request(
                            method = "GET",
                            scheme = "https",
                            authority = "example.zodl.com",
                            path = "/test",
                            headers = emptyList(),
                            body = ByteArray(0)
                        ),
                        timeoutMs = 5000
                    )
                }
            }.exceptionOrNull()
        assertTrue(exceptionThrown is co.electriccoin.lightwallet.client.internal.OhttpException)
        assertTrue(capturedHeaders.isNotEmpty())
        val headers = capturedHeaders.first()
        val traceIdHeader = headers.find { it.first == "X-Client-Trace-Id" }
        assertTrue("X-Client-Trace-Id header must be present", traceIdHeader != null)
        val traceId = traceIdHeader!!.second
        assertTrue("Trace ID must start with prefix", traceId.startsWith("test-wallet-"))
        assertTrue("Trace ID must be ≤ 64 chars", traceId.length <= 64)
        assertTrue(
            "Trace ID must match allowed charset",
            traceId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"))
        )
        // Verify X-Request-Id is never set
        assertFalse(headers.any { it.first == "X-Request-Id" })
    }

    @Test
    fun `client trace ID sanitises invalid characters`() {
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
        val mockSender =
            object : co.electriccoin.lightwallet.client.internal.OhttpSender {
                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int
                ): ByteArray {
                    capturedHeaders.add(headers)
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("test abort")
                }
            }
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                keyConfigUrl = "https://gateway.zodl.com/ohttp-keys",
                sender = mockSender,
                ohttpClientTraceIdPrefix = "test@wallet#123"
            )
        val exceptionThrown =
            runCatching {
                kotlinx.coroutines.runBlocking {
                    transport.roundTrip(
                        co.electriccoin.lightwallet.client.internal.BinaryHttp.Request(
                            method = "GET",
                            scheme = "https",
                            authority = "example.zodl.com",
                            path = "/test",
                            headers = emptyList(),
                            body = ByteArray(0)
                        ),
                        timeoutMs = 5000
                    )
                }
            }.exceptionOrNull()
        assertTrue(exceptionThrown is co.electriccoin.lightwallet.client.internal.OhttpException)
        assertTrue(capturedHeaders.isNotEmpty())
        val traceIdHeader = capturedHeaders.first().find { it.first == "X-Client-Trace-Id" }
        assertTrue(traceIdHeader != null)
        val traceId = traceIdHeader!!.second
        assertFalse("Trace ID must not contain @", traceId.contains("@"))
        assertFalse("Trace ID must not contain #", traceId.contains("#"))
        assertTrue(
            "Trace ID must match allowed charset",
            traceId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"))
        )
    }

    @Test
    fun `client trace ID truncates to 64 chars`() {
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
        val mockSender =
            object : co.electriccoin.lightwallet.client.internal.OhttpSender {
                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int
                ): ByteArray {
                    capturedHeaders.add(headers)
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("test abort")
                }
            }
        val longPrefix = "a".repeat(60)
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                keyConfigUrl = "https://gateway.zodl.com/ohttp-keys",
                sender = mockSender,
                ohttpClientTraceIdPrefix = longPrefix
            )
        val exceptionThrown =
            runCatching {
                kotlinx.coroutines.runBlocking {
                    transport.roundTrip(
                        co.electriccoin.lightwallet.client.internal.BinaryHttp.Request(
                            method = "GET",
                            scheme = "https",
                            authority = "example.zodl.com",
                            path = "/test",
                            headers = emptyList(),
                            body = ByteArray(0)
                        ),
                        timeoutMs = 5000
                    )
                }
            }.exceptionOrNull()
        assertTrue(exceptionThrown is co.electriccoin.lightwallet.client.internal.OhttpException)
        assertTrue(capturedHeaders.isNotEmpty())
        val traceIdHeader = capturedHeaders.first().find { it.first == "X-Client-Trace-Id" }
        assertTrue(traceIdHeader != null)
        val traceId = traceIdHeader!!.second
        assertTrue("Trace ID must be exactly 64 chars", traceId.length == 64)
    }
}
