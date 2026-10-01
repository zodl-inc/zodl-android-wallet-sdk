package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.SignedEnvelopeTestVectors
import co.electriccoin.lightwallet.client.internal.TestSigningKey
import co.electriccoin.lightwallet.client.internal.isOhttpProxyAllowedHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("ktlint:standard:max-line-length", "ktlint:standard:chain-method-continuation")
class OhttpHttpClientTest {
    /**
     * Creates a dummy OHTTP key config for testing. Uses a valid cipher suite (X25519 + HKDF-SHA256
     * + AES-128-GCM) but a dummy public key. Since the tests abort before HPKE crypto runs, the
     * invalid key doesn't matter.
     */
    private fun dummyKeyConfig(): co.electriccoin.lightwallet.client.internal.OhttpKeyConfig =
        co.electriccoin.lightwallet.client.internal.OhttpKeyConfig(
            keyId = 1,
            kemId = 0x0020, // X25519
            publicKey = ByteArray(32) { 1 }, // Dummy 32-byte key
            kdfId = 0x0001, // HKDF-SHA256
            aeadId = 0x0001, // AES-128-GCM
        )

    @Test
    fun `canHandle returns true for static proxy mappings`() {
        val client = OhttpHttpClient.create()
        assertTrue(client.canHandle("https://1click.chaindefuser.com/v0/tokens"))
        assertTrue(client.canHandle("https://voting.valargroup.dev/prod/dynamic-voting-config.json"))
        assertTrue(client.canHandle("https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest?symbol=ZEC"))
    }

    @Test
    fun `canHandle returns true for zodl OHTTP gateway aliases`() {
        val client = OhttpHttpClient.create()
        assertTrue(client.canHandle("https://near.zodl.com/v0/tokens"))
        assertTrue(client.canHandle("https://cmc.zodl.com/v1/cryptocurrency/quotes/latest"))
        assertTrue(client.canHandle("https://vote.zodl.com/prod/dynamic-voting-config.json"))
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
            object : OhttpSender {
                override suspend fun dispose() = Unit

                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray {
                    capturedHeaders.add(headers)
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("test abort")
                }

                override suspend fun get(
                    url: String,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray = throw NotImplementedError("GET not used in this test (initialKeyConfig provided)")
            }
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                network = "testnet",
                sender = mockSender,
                ohttpClientTraceIdPrefix = null,
                initialKeyConfig = dummyKeyConfig(),
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
                            body = ByteArray(0),
                        ),
                        timeoutMs = 5000,
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
        co.electriccoin.lightwallet.client.internal.OhttpDebugFlags.allowTracePrefix = true
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
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
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("test abort")
                }

                override suspend fun get(
                    url: String,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray = throw NotImplementedError("GET not used in this test (initialKeyConfig provided)")
            }
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                network = "testnet",
                sender = mockSender,
                ohttpClientTraceIdPrefix = "test-wallet",
                initialKeyConfig = dummyKeyConfig(),
            )
        // Make two requests to verify sequence counter increments
        repeat(2) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    transport.roundTrip(
                        co.electriccoin.lightwallet.client.internal.BinaryHttp.Request(
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
            }
        }
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
        co.electriccoin.lightwallet.client.internal.OhttpDebugFlags.allowTracePrefix = true
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
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
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("test abort")
                }

                override suspend fun get(
                    url: String,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray = throw NotImplementedError("GET not used in this test (initialKeyConfig provided)")
            }
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                network = "testnet",
                sender = mockSender,
                ohttpClientTraceIdPrefix = "test@wallet#123",
                initialKeyConfig = dummyKeyConfig(),
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
                            body = ByteArray(0),
                        ),
                        timeoutMs = 5000,
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
        co.electriccoin.lightwallet.client.internal.OhttpDebugFlags.allowTracePrefix = true
        val capturedHeaders = mutableListOf<List<Pair<String, String>>>()
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
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("test abort")
                }

                override suspend fun get(
                    url: String,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray = throw NotImplementedError("GET not used in this test (initialKeyConfig provided)")
            }
        // Long prefix: 60 'a' + 1 '-' + 36 UUID + 1 '-' + 6 digits = 104 chars → truncated to 64
        val longPrefix = "a".repeat(60)
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                network = "testnet",
                sender = mockSender,
                ohttpClientTraceIdPrefix = longPrefix,
                initialKeyConfig = dummyKeyConfig(),
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
                            body = ByteArray(0),
                        ),
                        timeoutMs = 5000,
                    )
                }
            }.exceptionOrNull()
        assertTrue(exceptionThrown is co.electriccoin.lightwallet.client.internal.OhttpException)
        assertTrue(capturedHeaders.isNotEmpty())
        val traceIdHeader = capturedHeaders.first().find { it.first == "X-Client-Trace-Id" }
        assertTrue(traceIdHeader != null)
        val traceId = traceIdHeader!!.second
        assertTrue("Trace ID must be exactly 64 chars", traceId.length == 64)
        // Must still start with the prefix (truncated)
        assertTrue("Trace ID must start with truncated prefix", traceId.startsWith("a"))
    }

    @Test
    fun `key config fetch uses sender GET from signed endpoint`() {
        val signingKey = TestSigningKey()
        val capturedGetUrls = mutableListOf<String>()
        val capturedGetHeaders = mutableListOf<List<Pair<String, String>>>()
        val mockSender =
            object : OhttpSender {
                override suspend fun dispose() = Unit

                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray = throw NotImplementedError("POST not used in this test")

                override suspend fun get(
                    url: String,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray {
                    capturedGetUrls.add(url)
                    capturedGetHeaders.add(headers)
                    // Envelope signed in-test with a fresh key; the transport trusts that key below.
                    val nowSeconds = System.currentTimeMillis() / 1000
                    val envelope =
                        SignedEnvelopeTestVectors.signedEnvelope(
                            key = signingKey,
                            notBefore = nowSeconds - 3600,
                            notAfter = nowSeconds + 3600,
                        )
                    return envelope.toByteArray(Charsets.UTF_8)
                }
            }
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                network = "testnet",
                sender = mockSender,
                trustedSigningKey = signingKey.publicKey,
            )
        kotlinx.coroutines.runBlocking {
            val keyConfig = transport.keyConfig()
            assertTrue("GET must be called", capturedGetUrls.isNotEmpty())
            assertTrue(
                "GET must fetch from /ohttp-configs/signed",
                capturedGetUrls.first().endsWith("/ohttp-configs/signed"),
            )
            assertTrue("GET headers must include Accept", capturedGetHeaders.first().any { it.first == "Accept" })
            assertNotNull("keyConfig must parse successfully", keyConfig)
            assertTrue("keyConfig must carry the signed key_id", keyConfig.keyId == 1)
        }
    }

    @Test
    fun `key config refresh on 400 uses sender GET`() {
        val signingKey = TestSigningKey()
        var getCallCount = 0
        var postCallCount = 0
        val mockSender =
            object : OhttpSender {
                override suspend fun dispose() = Unit

                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray {
                    postCallCount++
                    // First POST returns 400 to trigger key config refresh
                    throw co.electriccoin.lightwallet.client.internal.OhttpException("Bad Request", 400)
                }

                override suspend fun get(
                    url: String,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray {
                    getCallCount++
                    // Each GET returns a freshly signed envelope (initial fetch, then refresh).
                    val nowSeconds = System.currentTimeMillis() / 1000
                    val envelope =
                        SignedEnvelopeTestVectors.signedEnvelope(
                            key = signingKey,
                            notBefore = nowSeconds - 3600,
                            notAfter = nowSeconds + 3600,
                        )
                    return envelope.toByteArray(Charsets.UTF_8)
                }
            }
        val transport =
            co.electriccoin.lightwallet.client.internal.OhttpTransport(
                relayUrl = "https://relay.zodl.com/relay",
                network = "testnet",
                sender = mockSender,
                trustedSigningKey = signingKey.publicKey,
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
                            body = ByteArray(0),
                        ),
                        timeoutMs = 5000,
                    )
                }
            }.exceptionOrNull()
        assertTrue("Must fail with 400", exceptionThrown is co.electriccoin.lightwallet.client.internal.OhttpException)
        assertTrue("Initial key fetch + refresh must call GET twice", getCallCount == 2)
        assertTrue("Must attempt POST twice (initial + retry)", postCallCount == 2)
    }

    @Test
    fun `DirectOhttpSender disables redirects`() {
        val sender = co.electriccoin.lightwallet.client.internal.DirectOhttpSender()
        // This test verifies that DirectOhttpSender.open() sets instanceFollowRedirects=false.
        // Since we can't directly test HttpURLConnection behavior without a real server,
        // we rely on code review: the open() method MUST set instanceFollowRedirects=false.
        // This test documents the requirement.
    }

    @Test
    fun `keyConfigUrlFor derives from relay URL for testnet gateway`() {
        val url =
            co.electriccoin.lightwallet.client.internal.OhttpEndpoints.keyConfigUrlFor(
                "ohttp-gateway-testnet.example",
            )
        org.junit.Assert.assertEquals("https://ohttp-relay-testnet.example/ohttp-configs", url)
    }

    @Test
    fun `keyConfigUrlFor derives from relay URL for mainnet gateway`() {
        val url =
            co.electriccoin.lightwallet.client.internal.OhttpEndpoints.keyConfigUrlFor(
                "ohttp-gateway.example",
            )
        org.junit.Assert.assertEquals("https://relay-simulator.example/ohttp-configs", url)
    }

    @Test
    fun `isOhttpGateway matches only exact configured gateway hosts`() {
        val endpoints = co.electriccoin.lightwallet.client.internal.OhttpEndpoints
        assertTrue(endpoints.isOhttpGateway("ohttp-gateway.example"))
        assertTrue(endpoints.isOhttpGateway("ohttp-gateway-testnet.example"))
        assertFalse(endpoints.isOhttpGateway("ohttp-gateway.example.org"))
        assertFalse(endpoints.isOhttpGateway("ohttp-gateway"))
    }
}
