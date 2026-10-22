package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpProfileInfo
import co.electriccoin.lightwallet.client.OhttpSealedRequest
import co.electriccoin.lightwallet.client.OhttpVerifiedKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * The transport around the native OHTTP core, with a fake [co.electriccoin.lightwallet.client.OhttpCodec]
 * and sender: the key cache, the signed config fetch, the one sealing per attempt, the retry on a
 * rejected key and the handling of the native opener on every exit path.
 */
class OhttpTransportTest {
    private val sender = RecordingSender()
    private val codec = FakeOhttpCodec()
    private val request =
        OhttpCodecRequest(
            method = "GET",
            scheme = "https",
            authority = "example.zodl.com",
            path = "/test",
            headers = listOf("Accept" to "application/json"),
            body = ByteArray(0),
        )

    private fun transport(
        codec: FakeOhttpCodec = this.codec,
        initialKey: ByteArray? = null,
        initialExpirySeconds: Long? = null,
    ) = OhttpTransport(
        relayUrl = "https://relay.test/relay",
        gatewayHost = "ohttp-gateway-testnet.example",
        codec = codec,
        sender = sender,
        initialKey = initialKey,
        initialExpirySeconds = initialExpirySeconds,
    )

    private fun queueSignedConfigs(count: Int = 1) =
        repeat(count) { sender.queueGet { "envelope".toByteArray() } }

    @Test
    fun `keyConfig fetches the signed config the codec names and hands it to the codec to verify`() {
        queueSignedConfigs()

        val key = runBlocking { transport().keyConfig() }

        assertArrayEquals(FakeOhttpCodec.KEY, key)
        assertEquals(listOf("https://relay.test/relay"), codec.signedConfigUrls)
        assertEquals(1, sender.getCalls.size)
        assertEquals("https://relay.test/ohttp-configs/signed", sender.getCalls[0].first)
        assertEquals(FakeOhttpCodec.PROFILE.keyFetchHeaders + FIXED_GET_HEADERS, sender.getCalls[0].second)
        assertEquals(0, sender.postCalls.size)
        val (host, json, _) = codec.verified.single()
        assertEquals("ohttp-gateway-testnet.example", host)
        assertEquals("envelope", String(json))
    }

    @Test
    fun `keyConfig caches the key until the codec's not_after`() {
        queueSignedConfigs()
        val transport = transport()

        runBlocking {
            transport.keyConfig()
            transport.keyConfig()
        }

        assertEquals(1, sender.getCalls.size)
    }

    @Test
    fun `keyConfig fetches again once not_after has passed`() {
        val expired =
            FakeOhttpCodec(verify = { _, _, nowSeconds ->
                OhttpVerifiedKey(FakeOhttpCodec.KEY, notAfter = nowSeconds, network = "testnet")
            })
        queueSignedConfigs(2)
        val transport = transport(expired)

        runBlocking {
            transport.keyConfig()
            transport.keyConfig()
        }

        assertEquals(2, sender.getCalls.size)
    }

    @Test
    fun `keyConfig honours the refresh margin of the profile`() {
        val margin = FakeOhttpCodec(profile = profile(cacheRefreshMarginSecs = 7200))
        queueSignedConfigs(2)
        val transport = transport(margin)

        runBlocking {
            transport.keyConfig()
            transport.keyConfig()
        }

        // The key is valid for an hour, less than the two hour margin: never fresh.
        assertEquals(2, sender.getCalls.size)
    }

    @Test
    fun `a forced refresh drops the cached key before the fetch so that a failed fetch leaves none`() {
        queueSignedConfigs()
        sender.queueGet { throw OhttpException("relay down") }
        queueSignedConfigs()
        val transport = transport()

        runBlocking {
            transport.keyConfig()
            assertFailsWith<OhttpException> { transport.keyConfig(forceRefresh = true) }
            // The stale key must not come back from the cache.
            transport.keyConfig()
        }

        assertEquals(3, sender.getCalls.size)
    }

    @Test
    fun `a forced refresh keeps the cached key when the profile does not drop it`() {
        val keeping = FakeOhttpCodec(profile = profile(dropBeforeRefetch = false))
        queueSignedConfigs()
        sender.queueGet { throw OhttpException("relay down") }
        val transport = transport(keeping)

        runBlocking {
            transport.keyConfig()
            assertFailsWith<OhttpException> { transport.keyConfig(forceRefresh = true) }
            transport.keyConfig()
        }

        assertEquals(2, sender.getCalls.size)
    }

    @Test
    fun `a rejected signed config is not cached and surfaces as a verification failure`() {
        val rejecting = FakeOhttpCodec(verify = FakeOhttpCodec.rejecting("Invalid Ed25519 signature"))
        queueSignedConfigs(2)
        val transport = transport(rejecting)

        runBlocking {
            assertFailsWith<KeyConfigVerificationFailedException> { transport.keyConfig() }
            assertFailsWith<KeyConfigVerificationFailedException> { transport.keyConfig() }
        }

        assertEquals(2, sender.getCalls.size)
    }

    @Test
    fun `a failed relay fetch falls back to the DNS record, verified like the relay one`() {
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh-a.test/dns-query", "https://doh-b.test/dns-query"))
        sender.queueGet { throw OhttpException("relay down") }
        sender.queueGet { "dns-envelope".toByteArray() }

        val key = runBlocking { transport(codec).keyConfig() }

        assertArrayEquals(FakeOhttpCodec.KEY, key)
        assertEquals(
            "https://doh-a.test/dns-query?name=ohttp-keys-testnet.example&type=TXT",
            sender.getCalls[1].first,
        )
        assertEquals("dns-envelope", String(codec.verified.single().second))
    }

    @Test
    fun `a DoH query carries the relay fetch's fixed headers, with the DNS Accept in place of the relay's`() {
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh-a.test/dns-query"))
        sender.queueGet { throw OhttpException("relay down") }
        sender.queueGet { "dns-envelope".toByteArray() }

        runBlocking { transport(codec).keyConfig() }

        val (relayGet, dohGet) = sender.getCalls.map { it.second }
        assertEquals(listOf("Accept" to "application/json") + FIXED_GET_HEADERS, relayGet)
        assertEquals(listOf("Accept" to "application/dns-json") + FIXED_GET_HEADERS, dohGet)
    }

    @Test
    fun `a 429 on the key fetch is thrown without the DNS fallback`() {
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh-a.test/dns-query"))
        val limited = RecordingSender()
        val refused = OhttpException("rate limited", 429, retryAfterSeconds = 30)
        limited.queueGet { throw refused }
        val transport =
            OhttpTransport("https://relay-429-no-dns.test/relay", "ohttp-gateway-testnet.example", codec, limited)

        val thrown = assertFailsWith<OhttpException> { runBlocking { transport.keyConfig() } }

        assertSame(refused, thrown)
        assertEquals("no DNS query after the 429", 1, limited.getCalls.size)
    }

    @Test
    fun `the relay's pause holds the key fetch without the DNS fallback`() {
        val relay = "https://relay-paused-key.test/relay"
        RelayBackoff.note(relay, retryAfterSeconds = 30)
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh-a.test/dns-query"))
        val transport = OhttpTransport(relay, "ohttp-gateway-testnet.example", codec, sender)

        val thrown = assertFailsWith<OhttpException> { runBlocking { transport.keyConfig() } }

        assertEquals(429, thrown.httpStatus)
        assertEquals("nothing is fetched while the relay asked to wait", 0, sender.getCalls.size)
    }

    @Test
    fun `a pause begun during the relay fetch holds the DNS fallback too`() {
        val relay = "https://relay-paused-during-fetch.test/relay"
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh-a.test/dns-query"))
        sender.queueGet {
            RelayBackoff.note(relay, retryAfterSeconds = 30)
            throw OhttpException("relay down")
        }
        val transport = OhttpTransport(relay, "ohttp-gateway-testnet.example", codec, sender)

        val thrown = assertFailsWith<OhttpException> { runBlocking { transport.keyConfig() } }

        assertEquals(429, thrown.httpStatus)
        assertEquals("no DNS query while the relay asked to wait", 1, sender.getCalls.size)
    }

    @Test
    fun `when the relay and every DNS record fail verification the relay error is thrown`() {
        val codec =
            FakeOhttpCodec(
                verify = FakeOhttpCodec.rejecting("Invalid Ed25519 signature"),
                resolvers = arrayOf("https://doh-a.test/dns-query", "https://doh-b.test/dns-query"),
            )
        queueSignedConfigs(3)

        runBlocking {
            assertFailsWith<KeyConfigVerificationFailedException> { transport(codec).keyConfig() }
        }

        assertEquals(3, sender.getCalls.size)
        assertEquals(3, codec.verified.size)
    }

    @Test
    fun `a non-https relay fails before any request, without the DNS fallback`() {
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh-a.test/dns-query"))
        val transport =
            OhttpTransport(
                relayUrl = "http://relay.test/relay",
                gatewayHost = "ohttp-gateway-testnet.example",
                codec = codec,
                sender = sender,
            )

        runBlocking { assertFailsWith<OhttpException> { transport.keyConfig() } }

        assertEquals(0, sender.getCalls.size)
    }

    @Test
    fun `a pre-seeded key without expiry is used without any fetch`() {
        val key = ByteArray(33) { 7 }

        val used = runBlocking { transport(initialKey = key).keyConfig() }

        assertSame(key, used)
        assertEquals(0, sender.getCalls.size)
    }

    @Test
    fun `roundTrip seals once, posts with the profile headers and completes the first attempt`() {
        val key = ByteArray(33) { 7 }
        sender.queuePost { "relay answer".toByteArray() }

        val response = runBlocking { transport(initialKey = key).roundTrip(request, timeoutMs = 5_000) }

        assertSame(codec.answer, response)
        assertArrayEquals(key, codec.sealedKeys.single())
        assertSame(request, codec.sealedRequests.single())
        val (url, body, headers) = sender.postCalls.single()
        assertEquals("https://relay.test/relay", url)
        assertArrayEquals(codec.sealedBodies.single(), body)
        assertEquals(FakeOhttpCodec.PROFILE.postHeaders + FIXED_USER_AGENT, headers)
        val completion = codec.completed.single()
        assertEquals(200, completion.outerStatus)
        assertEquals(0, completion.attempt)
        assertEquals("relay answer", String(completion.body))
        assertTrue("the opener must be spent", codec.liveOpeners.isEmpty())
        assertTrue("a spent opener must not be freed", codec.freed.isEmpty())
    }

    @Test
    fun `a freshness refusal is sealed afresh and sent once more`() {
        val ok = OhttpCodecResponse(200, emptyList(), emptyList(), byteArrayOf(2))
        codec.answers += listOf(freshnessRefusal(), ok)
        repeat(2) { sender.queuePost { "relay answer".toByteArray() } }

        val response = runBlocking { transport(initialKey = ByteArray(33) { 7 }).roundTrip(request, 5_000) }

        assertSame(ok, response)
        assertEquals(2, sender.postCalls.size)
        assertEquals(2, codec.sealedBodies.size)
        assertNotEquals(String(codec.sealedBodies[0]), String(codec.sealedBodies[1]))
        assertTrue("both openers must be spent", codec.liveOpeners.isEmpty())
    }

    @Test
    fun `a second freshness refusal is returned without a further retry`() {
        codec.answers += listOf(freshnessRefusal(), freshnessRefusal())
        repeat(2) { sender.queuePost { "relay answer".toByteArray() } }

        val response = runBlocking { transport(initialKey = ByteArray(33) { 7 }).roundTrip(request, 5_000) }

        assertTrue(response.isFreshnessRefusal())
        assertEquals(2, sender.postCalls.size)
    }

    @Test
    fun `an inner 400 without the freshness marker is not retried`() {
        val plain400 = OhttpCodecResponse(400, listOf("content-type" to "text/plain"), emptyList(), ByteArray(0))
        codec.answers += plain400
        sender.queuePost { "relay answer".toByteArray() }

        val response = runBlocking { transport(initialKey = ByteArray(33) { 7 }).roundTrip(request, 5_000) }

        assertSame(plain400, response)
        assertEquals(1, sender.postCalls.size)
    }

    private fun freshnessRefusal() =
        OhttpCodecResponse(
            400,
            listOf(
                "content-type" to "text/plain",
                "Ohttp-Freshness" to "rejected",
                "ohttp-server-time" to "1800000000",
            ),
            emptyList(),
            ByteArray(0),
        )

    @Test
    fun `a rejected key refetches the config and seals the request afresh for the retry`() {
        queueSignedConfigs(2)
        sender.queuePost { throw OhttpException("Relay/gateway returned HTTP 400", 400) }
        sender.queuePost { "relay answer".toByteArray() }

        val response = runBlocking { transport().roundTrip(request, timeoutMs = 5_000) }

        assertSame(codec.answer, response)
        assertEquals("the key config is fetched, then refetched", 2, sender.getCalls.size)
        assertEquals("every attempt is sealed", 2, codec.sealedBodies.size)
        assertNotEquals(
            "a ciphertext must never be posted twice",
            String(codec.sealedBodies[0]),
            String(codec.sealedBodies[1]),
        )
        assertEquals(codec.sealedBodies.map { String(it) }, sender.postCalls.map { String(it.second) })
        assertEquals(listOf(400, 200), codec.completed.map { it.outerStatus })
        assertEquals(listOf(0, 1), codec.completed.map { it.attempt })
        assertTrue(codec.liveOpeners.isEmpty())
        assertTrue(codec.freed.isEmpty())
    }

    @Test
    fun `a 422 is retried whatever its body says`() {
        queueSignedConfigs(2)
        sender.queuePost { throw OhttpException("Relay/gateway returned HTTP 422: unrelated", 422) }
        sender.queuePost { "relay answer".toByteArray() }

        runBlocking { transport().roundTrip(request, timeoutMs = 5_000) }

        assertEquals(2, sender.postCalls.size)
        assertEquals(2, sender.getCalls.size)
    }

    @Test
    fun `a second rejection is final and quotes the relay's own error`() {
        queueSignedConfigs(2)
        sender.queuePost { throw OhttpException("first", 400) }
        val second = OhttpException("Relay/gateway returned HTTP 400: again", 400)
        sender.queuePost { throw second }

        val thrown =
            assertFailsWith<OhttpException> { runBlocking { transport().roundTrip(request, timeoutMs = 5_000) } }

        assertSame(second, thrown)
        assertEquals(2, sender.postCalls.size)
        assertEquals("a key is refetched once at most", 2, sender.getCalls.size)
        assertTrue("both openers are spent", codec.liveOpeners.isEmpty())
    }

    @Test
    fun `other relay statuses are not retried`() {
        queueSignedConfigs()
        val unavailable = OhttpException("Relay/gateway returned HTTP 503", 503)
        sender.queuePost { throw unavailable }

        val thrown =
            assertFailsWith<OhttpException> { runBlocking { transport().roundTrip(request, timeoutMs = 5_000) } }

        assertSame(unavailable, thrown)
        assertEquals(1, sender.postCalls.size)
        assertEquals(1, sender.getCalls.size)
        assertEquals(503, codec.completed.single().outerStatus)
        assertTrue(codec.liveOpeners.isEmpty())
    }

    @Test
    fun `a transport error frees the opener exactly once`() {
        queueSignedConfigs()
        val failure = OhttpException("connection reset")
        sender.queuePost { throw failure }

        val thrown =
            assertFailsWith<OhttpException> { runBlocking { transport().roundTrip(request, timeoutMs = 5_000) } }

        assertSame(failure, thrown)
        assertEquals(listOf(1L), codec.freed)
        assertTrue(codec.completed.isEmpty())
        assertTrue(codec.liveOpeners.isEmpty())
    }

    @Test
    fun `an unexpected error from the sender frees the opener too`() {
        queueSignedConfigs()
        sender.queuePost { error("boom") }

        assertFailsWith<IllegalStateException> { runBlocking { transport().roundTrip(request, timeoutMs = 5_000) } }

        assertEquals(listOf(1L), codec.freed)
    }

    @Test
    fun `cancelling the coroutine while the post is in flight frees the opener`() {
        queueSignedConfigs()
        val posting = CompletableDeferred<Unit>()
        val hang = CompletableDeferred<ByteArray>()
        val hangingSender =
            object : co.electriccoin.lightwallet.client.OhttpSender {
                override suspend fun dispose() = Unit

                override suspend fun get(
                    url: String,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ) = "envelope".toByteArray()

                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: List<Pair<String, String>>,
                    timeoutMs: Int,
                ): ByteArray {
                    posting.complete(Unit)
                    return hang.await()
                }
            }
        val transport =
            OhttpTransport(
                relayUrl = "https://relay.test/relay",
                gatewayHost = "ohttp-gateway-testnet.example",
                codec = codec,
                sender = hangingSender,
            )

        runBlocking {
            val call = async(start = CoroutineStart.UNDISPATCHED) { transport.roundTrip(request, timeoutMs = 5_000) }
            posting.await()
            yield()
            call.cancelAndJoin()
        }

        assertEquals(listOf(1L), codec.freed)
        assertTrue(codec.completed.isEmpty())
        assertTrue(codec.liveOpeners.isEmpty())
    }

    @Test
    fun `a sealing failure leaves no opener behind`() {
        queueSignedConfigs()
        val tooLarge = OhttpException("an answer larger than 10485760 bytes")
        val failing =
            object : co.electriccoin.lightwallet.client.OhttpCodec by codec {
                override fun encapsulate(
                    key: ByteArray,
                    request: OhttpCodecRequest,
                ): OhttpSealedRequest = throw tooLarge
            }
        val transport =
            OhttpTransport(
                relayUrl = "https://relay.test/relay",
                gatewayHost = "ohttp-gateway-testnet.example",
                codec = failing,
                sender = sender,
            )

        val thrown = assertFailsWith<OhttpException> { runBlocking { transport.roundTrip(request, 5_000) } }

        assertSame(tooLarge, thrown)
        assertEquals(0, sender.postCalls.size)
    }

    @Test
    fun `an opener cannot be spent twice and closing a spent one frees nothing`() {
        val opener = codec.encapsulate(FakeOhttpCodec.KEY, request).opener

        runBlocking { opener.open(200, null, ByteArray(0), 0) }
        assertFailsWith<IllegalStateException> { runBlocking { opener.open(200, null, ByteArray(0), 0) } }
        opener.close()
        opener.close()

        assertEquals(1, codec.completed.size)
        assertTrue(codec.freed.isEmpty())
        assertTrue(codec.liveOpeners.isEmpty())
    }

    @Test
    fun `closing an unused opener frees it once`() {
        val opener = codec.encapsulate(FakeOhttpCodec.KEY, request).opener

        opener.close()
        opener.close()

        assertEquals(listOf(1L), codec.freed)
        assertFailsWith<IllegalStateException> { runBlocking { opener.open(200, null, ByteArray(0), 0) } }
    }

    @Test
    fun `a 429 with Retry-After keeps every transport off that relay until the delay is over`() {
        val relay = "https://relay-429.test/relay"
        val limited = RecordingSender()
        limited.queuePost { throw OhttpException("rate limited", 429, retryAfterSeconds = 30) }
        val (first, second) =
            List(2) {
                OhttpTransport(relay, "ohttp-gateway-testnet.example", codec, limited, initialKey = FakeOhttpCodec.KEY)
            }

        val refused = assertFailsWith<OhttpException> { runBlocking { first.roundTrip(request, timeoutMs = 5_000) } }
        val waiting = assertFailsWith<OhttpException> { runBlocking { second.roundTrip(request, timeoutMs = 5_000) } }

        assertEquals(429, refused.httpStatus)
        assertEquals(429, waiting.httpStatus)
        assertEquals("nothing is posted while the relay asked to wait", 1, limited.postCalls.size)
        assertTrue(codec.liveOpeners.isEmpty())
    }

    @Test
    fun `a 429 on the key fetch defers the next fetch through that relay`() {
        val limited = RecordingSender()
        limited.queueGet { throw OhttpException("rate limited", 429, retryAfterSeconds = 30) }
        val transport =
            OhttpTransport("https://relay-429-get.test/relay", "ohttp-gateway-testnet.example", codec, limited)

        repeat(2) {
            val thrown =
                assertFailsWith<OhttpException> { runBlocking { transport.roundTrip(request, timeoutMs = 5_000) } }
            assertEquals(429, thrown.httpStatus)
        }

        assertEquals(1, limited.getCalls.size)
        assertEquals(0, limited.postCalls.size)
    }

    @Test
    fun `the call timeout bounds the key fetch and the posts together`() {
        sender.queueGet {
            Thread.sleep(300)
            "envelope".toByteArray()
        }

        assertFailsWith<java.net.SocketTimeoutException> {
            runBlocking { transport().roundTrip(request, timeoutMs = 200) }
        }

        assertTrue(sender.getCalls.single().third <= 200)
        assertEquals("no post once the call's time is up", 0, sender.postCalls.size)
    }

    @Test
    fun `the key fetch's time is shared by the relay fetch and the DNS resolvers`() {
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh-a.test/dns-query", "https://doh-b.test/dns-query"))
        sender.queueGet {
            Thread.sleep(500)
            throw OhttpException("relay down")
        }
        sender.queueGet { "dns-envelope".toByteArray() }

        runBlocking { transport(codec).keyConfig(timeoutMs = 5_000) }

        assertEquals(2, sender.getCalls.size)
        val dnsTimeout = sender.getCalls[1].third
        assertTrue("the resolver gets what the relay left, not the whole time: $dnsTimeout", dnsTimeout <= 4_500)
    }

    @Test
    fun `a relay fetch that used up the key fetch's time leaves no DNS attempt, and its error is thrown`() {
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh-a.test/dns-query"))
        val relayDown = OhttpException("relay down")
        sender.queueGet {
            Thread.sleep(300)
            throw relayDown
        }

        val thrown = assertFailsWith<OhttpException> { runBlocking { transport(codec).keyConfig(timeoutMs = 200) } }

        assertSame(relayDown, thrown)
        assertEquals(1, sender.getCalls.size)
    }

    @Test
    fun `the relay back-off is shared with the codec's native table both ways`() {
        val relay = "https://relay-native.test/relay"
        val noted = mutableListOf<Pair<String, Long>>()
        val native =
            object : co.electriccoin.lightwallet.client.OhttpCodec by FakeOhttpCodec() {
                override fun noteRelayBackoff(
                    relayUrl: String,
                    delayMs: Long,
                ) {
                    noted += relayUrl to delayMs
                }

                override fun relayBackoffRemainingMs(relayUrl: String) = if (relayUrl == relay) 5_000L else 0L
            }

        val waiting = assertFailsWith<OhttpException> { RelayBackoff.check(relay, codec = native) }
        assertEquals(5L, waiting.retryAfterSeconds)
        RelayBackoff.check("https://other.test/relay", codec = native)

        RelayBackoff.noteIfRateLimited(
            "https://relay-mirror.test/relay",
            OhttpException("rate limited", 429, retryAfterSeconds = 7),
            native,
        )
        assertEquals(listOf("https://relay-mirror.test/relay" to 7_000L), noted)
    }

    @Test
    fun `a key fetch refused by the relay's own pause does not move the pause end`() {
        val relay = "https://relay-pause-not-renoted.test/relay"
        val noted = mutableListOf<Pair<String, Long>>()
        val native =
            object : co.electriccoin.lightwallet.client.OhttpCodec by FakeOhttpCodec() {
                override fun noteRelayBackoff(
                    relayUrl: String,
                    delayMs: Long,
                ) {
                    noted += relayUrl to delayMs
                }
            }
        // A pause that began half a second ago: its refusal, rounded up to whole seconds and noted
        // again, would end half a second after it.
        val began = System.currentTimeMillis() - 500
        RelayBackoff.note(relay, retryAfterSeconds = 30, nowMillis = began)
        val transport = OhttpTransport(relay, "ohttp-gateway-testnet.example", native, sender)

        repeat(2) {
            val thrown = assertFailsWith<OhttpException> { runBlocking { transport.keyConfig() } }
            assertEquals(429, thrown.httpStatus)
        }

        // The pause ends when it was noted to, not later.
        RelayBackoff.check(relay, nowMillis = began + 30_000)
        assertTrue("the local refusal is not noted in the native table", noted.isEmpty())
        assertEquals("nothing is fetched while the relay asked to wait", 0, sender.getCalls.size)
    }

    @Test
    fun `a 429 without Retry-After pauses the relay for the default 30 s`() {
        val relay = "https://relay-429-no-header.test/relay"
        val noted = mutableListOf<Pair<String, Long>>()
        val native =
            object : co.electriccoin.lightwallet.client.OhttpCodec by FakeOhttpCodec() {
                override fun noteRelayBackoff(
                    relayUrl: String,
                    delayMs: Long,
                ) {
                    noted += relayUrl to delayMs
                }
            }
        val limited = RecordingSender()
        limited.queuePost { throw OhttpException("rate limited", 429) }
        val gateway = "ohttp-gateway-testnet.example"
        val (first, second) =
            List(2) { OhttpTransport(relay, gateway, native, limited, initialKey = FakeOhttpCodec.KEY) }
        val before = System.currentTimeMillis()

        val refused = assertFailsWith<OhttpException> { runBlocking { first.roundTrip(request, timeoutMs = 5_000) } }
        val waiting = assertFailsWith<OhttpException> { runBlocking { second.roundTrip(request, timeoutMs = 5_000) } }

        assertEquals(429, refused.httpStatus)
        assertEquals(429, waiting.httpStatus)
        assertEquals("nothing is posted while the relay asked to wait", 1, limited.postCalls.size)
        assertEquals(listOf(relay to RelayBackoff.DEFAULT_RETRY_AFTER_SECONDS * 1_000), noted)
        assertFailsWith<OhttpException> { RelayBackoff.check(relay, nowMillis = before + 29_000) }
        RelayBackoff.check(relay, nowMillis = before + 31_000)
    }

    @Test
    fun `the relay back-off is capped and ends`() {
        val relay = "https://relay-backoff.test/relay"
        RelayBackoff.note(relay, retryAfterSeconds = 3_600, nowMillis = 0)

        val waiting = assertFailsWith<OhttpException> { RelayBackoff.check(relay, nowMillis = 1_000) }
        assertEquals(RelayBackoff.MAX_RETRY_AFTER_SECONDS - 1, waiting.retryAfterSeconds)

        RelayBackoff.check(relay, nowMillis = RelayBackoff.MAX_RETRY_AFTER_SECONDS * 1_000)
        RelayBackoff.note(relay, retryAfterSeconds = null, nowMillis = 0)
        RelayBackoff.check(relay, nowMillis = 0)
    }

    private fun profile(
        cacheRefreshMarginSecs: Long = 0,
        dropBeforeRefetch: Boolean = true,
    ) = FakeOhttpCodec.PROFILE.let {
        OhttpProfileInfo(
            postHeaders = it.postHeaders,
            keyFetchHeaders = it.keyFetchHeaders,
            maxSealedResponseBytes = it.maxSealedResponseBytes,
            maxKeyConfigBytes = it.maxKeyConfigBytes,
            cacheRefreshMarginSecs = cacheRefreshMarginSecs,
            dropBeforeRefetch = dropBeforeRefetch,
            traceOnKeyFetch = it.traceOnKeyFetch,
            bodyWindow = it.bodyWindow,
        )
    }

    private companion object {
        val FIXED_USER_AGENT = listOf("User-Agent" to "ZODL-OHTTP/1.0")
        val FIXED_GET_HEADERS = listOf("Accept-Encoding" to "identity") + FIXED_USER_AGENT
    }
}
