package co.electriccoin.lightwallet.client.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * [DirectOhttpSender] over TLS with HTTP/2 negotiated, as against the relay: exchanges multiplex on one
 * connection (concurrent ones included), and both a cancelled exchange and one past its deadline reset
 * their stream at once while the connection stays up for the next exchange (what the sender's KDoc
 * claims; on HTTP/1.1 the connection would be closed instead).
 */
class DirectOhttpSenderHttp2Test {
    private val relay = MockWebServer()
    private lateinit var connections: RelayConnections
    private lateinit var sender: DirectOhttpSender

    @Before
    fun start() {
        relay.start()
        val certificate =
            HeldCertificate
                .Builder()
                .addSubjectAlternativeName(relay.hostName)
                .addSubjectAlternativeName("localhost")
                .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        relay.useHttps(serverCertificates.sslSocketFactory(), false)
        connections =
            RelayConnections(
                configureClient = {
                    sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                },
            )
        sender = DirectOhttpSender(connections)
    }

    @After
    fun stop() {
        connections.evictAll()
        relay.shutdown()
    }

    private fun url() = relay.url("/relay").toString()

    @Test
    fun `exchanges multiplex on one HTTP 2 connection`() {
        repeat(THREE) { relay.enqueue(MockResponse().setBody("ok")) }

        runBlocking {
            sender.post(url(), byteArrayOf(1), emptyList(), TIMEOUT_MS)
            sender.post(url(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
            sender.get(relay.url("/ohttp-keys").toString(), emptyList(), TIMEOUT_MS)
        }

        repeat(THREE) { index ->
            val request = relay.takeRequest()
            assertTrue(request.requestLine, request.requestLine.endsWith("HTTP/2"))
            assertEquals(index, request.sequenceNumber)
        }
    }

    /**
     * Two exchanges in flight at once ride the one established connection as two streams: the second
     * request reaches the relay while the first answer is still held back.
     */
    @Test
    fun `concurrent exchanges are two streams of the one connection`() {
        relay.enqueue(MockResponse().setBody("warm-up"))
        repeat(TWO) {
            relay.enqueue(MockResponse().setBody("ok").setBodyDelay(HELD_BACK_MS, TimeUnit.MILLISECONDS))
        }

        runBlocking {
            sender.post(url(), byteArrayOf(0), emptyList(), TIMEOUT_MS)
            assertEquals(0, relay.takeRequest().sequenceNumber)

            val first = async(Dispatchers.IO) { sender.post(url(), byteArrayOf(1), emptyList(), TIMEOUT_MS) }
            val second = async(Dispatchers.IO) { sender.post(url(), byteArrayOf(2), emptyList(), TIMEOUT_MS) }
            val requestA = relay.takeRequest(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            val requestB = relay.takeRequest(PROMPT_MS, TimeUnit.MILLISECONDS)
            assertNotNull("the second request arrived while the first answer was held back", requestB)
            assertEquals(setOf(1, 2), setOf(requestA!!.sequenceNumber, requestB!!.sequenceNumber))
            assertEquals("ok", String(first.await()))
            assertEquals("ok", String(second.await()))
        }
    }

    /**
     * Cancelling the caller while it is blocked in the body read ends the exchange at once (not at the
     * deadline), resets the stream, and the connection serves the next exchange.
     */
    @Test
    fun `a cancelled exchange resets its stream at once and the connection serves the next exchange`() {
        relay.enqueue(MockResponse().setBody("never delivered").setBodyDelay(LONG_DELAY_SECONDS, TimeUnit.SECONDS))
        relay.enqueue(MockResponse().setBody("ok"))
        val readingBody = CountDownLatch(1)

        runBlocking {
            val blockedReader =
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        sender.postStreaming(url(), byteArrayOf(1), emptyList(), TIMEOUT_MS) {
                            readingBody.countDown()
                            it.read()
                        }
                    }
                }
            assertEquals(0, relay.takeRequest().sequenceNumber)
            val reached = readingBody.await(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            assertTrue("the caller reached the body read", reached)

            val cancelledAt = System.nanoTime()
            blockedReader.cancelAndJoin()
            val cancelTookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt)
            assertTrue("cancel ended the read in $cancelTookMs ms, not at the deadline", cancelTookMs < PROMPT_MS)

            sender.post(url(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
        }

        assertEquals("the same connection", 1, relay.takeRequest().sequenceNumber)
    }

    /**
     * A body that arrives after the deadline is the exchange timeout, never a late success; the stream is
     * reset at the deadline and the connection serves the next exchange.
     */
    @Test
    fun `a body arriving after the deadline is the timeout and the connection serves the next exchange`() {
        relay.enqueue(MockResponse().setBody("too late").setBodyDelay(LATE_BODY_MS, TimeUnit.MILLISECONDS))
        relay.enqueue(MockResponse().setBody("ok"))

        val startedAt = System.nanoTime()
        val thrown =
            assertFailsWith<SocketTimeoutException> {
                runBlocking { sender.post(url(), byteArrayOf(1), emptyList(), SHORT_TIMEOUT_MS) }
            }
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertEquals("OHTTP exchange exceeded $SHORT_TIMEOUT_MS ms", thrown.message)
        assertTrue("failed at the deadline ($tookMs ms), not when the late body came", tookMs < LATE_BODY_MS)
        assertEquals(0, relay.takeRequest().sequenceNumber)

        runBlocking { sender.post(url(), byteArrayOf(2), emptyList(), TIMEOUT_MS) }

        assertEquals("the same connection", 1, relay.takeRequest().sequenceNumber)
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val SHORT_TIMEOUT_MS = 500
        private const val LATE_BODY_MS = 2_000L
        private const val HELD_BACK_MS = 1_500L
        private const val PROMPT_MS = 1_000L
        private const val LONG_DELAY_SECONDS = 30L
        private const val TWO = 2
        private const val THREE = 3
    }
}
