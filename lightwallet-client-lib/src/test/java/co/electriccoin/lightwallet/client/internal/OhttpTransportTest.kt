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
        assertEquals(FakeOhttpCodec.PROFILE.keyFetchHeaders, sender.getCalls[0].second)
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
        assertEquals(FakeOhttpCodec.PROFILE.postHeaders, headers)
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
}
