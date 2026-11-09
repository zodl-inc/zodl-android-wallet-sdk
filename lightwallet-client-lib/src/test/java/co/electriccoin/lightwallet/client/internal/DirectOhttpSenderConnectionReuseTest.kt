package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * [DirectOhttpSender] reuses its relay connection across exchanges (`RecordedRequest.sequenceNumber` is
 * the request's index on its connection: 0 = a fresh connection) and never uses one past
 * [RelayConnections.MAX_CONNECTION_AGE_NANOS], driven here by a fake clock; and the relay sees the
 * caller's headers plus the sender's fixed defaults, nothing of OkHttp's own choosing.
 */
class DirectOhttpSenderConnectionReuseTest {
    private val relay = MockWebServer()
    private var nowNanos = 0L
    private lateinit var connections: RelayConnections
    private lateinit var sender: DirectOhttpSender

    @Before
    fun start() {
        relay.start()
        connections = RelayConnections(clock = { nowNanos })
        sender = DirectOhttpSender(connections)
    }

    @After
    fun stop() {
        connections.evictAll()
        relay.shutdown()
    }

    @Test
    fun `exchanges within the connection lifetime share one relay connection`() {
        repeat(THREE) { relay.enqueue(MockResponse().setBody("ok")) }

        runBlocking {
            sender.post(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), TIMEOUT_MS)
            nowNanos += TimeUnit.MINUTES.toNanos(1)
            sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
            sender.get(relay.url("/ohttp-keys").toString(), emptyList(), TIMEOUT_MS)
        }

        assertEquals(0, relay.takeRequest().sequenceNumber)
        assertEquals(1, relay.takeRequest().sequenceNumber)
        assertEquals(2, relay.takeRequest().sequenceNumber)
    }

    @Test
    fun `an exchange that ends just under the maximum lifetime still reuses the connection`() {
        repeat(TWO) { relay.enqueue(MockResponse().setBody("ok")) }

        runBlocking {
            sender.post(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), TIMEOUT_MS)
            nowNanos += RelayConnections.MAX_CONNECTION_AGE_NANOS - TIMEOUT_NANOS - 1
            sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
        }

        assertEquals(0, relay.takeRequest().sequenceNumber)
        assertEquals(1, relay.takeRequest().sequenceNumber)
    }

    /** The deadline of an exchange, not only its start, stays within the lifetime: nothing runs on an old one. */
    @Test
    fun `an exchange that could still be running at the maximum lifetime opens a new connection`() {
        repeat(TWO) { relay.enqueue(MockResponse().setBody("ok")) }

        runBlocking {
            sender.post(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), TIMEOUT_MS)
            nowNanos += RelayConnections.MAX_CONNECTION_AGE_NANOS - TIMEOUT_NANOS
            sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
        }

        assertEquals(0, relay.takeRequest().sequenceNumber)
        assertEquals("a new connection for an exchange ending at the lifetime", 0, relay.takeRequest().sequenceNumber)
    }

    @Test
    fun `a connection that reached the maximum lifetime is not reused`() {
        repeat(THREE) { relay.enqueue(MockResponse().setBody("ok")) }

        runBlocking {
            sender.post(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), TIMEOUT_MS)
            nowNanos += RelayConnections.MAX_CONNECTION_AGE_NANOS
            sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
            // The fresh pool is reused in turn until its own lifetime is up.
            sender.post(relay.url("/relay").toString(), byteArrayOf(3), emptyList(), TIMEOUT_MS)
        }

        assertEquals(0, relay.takeRequest().sequenceNumber)
        assertEquals("a new connection after the maximum lifetime", 0, relay.takeRequest().sequenceNumber)
        assertEquals(1, relay.takeRequest().sequenceNumber)
    }

    /** A connection last used before the age does not sit idle past it waiting for the next exchange. */
    @Test
    fun `idle connections are closed at the maximum lifetime without another exchange`() {
        val shortLived = RelayConnections(maxConnectionAgeNanos = TimeUnit.MILLISECONDS.toNanos(SHORT_LIFETIME_MS))
        val shortLivedSender = DirectOhttpSender(shortLived)
        relay.enqueue(MockResponse().setBody("ok"))
        try {
            val url = relay.url("/relay").toString()
            runBlocking { shortLivedSender.post(url, byteArrayOf(1), emptyList(), TIMEOUT_MS) }
            assertEquals("pooled after the exchange", 1, shortLived.idleConnections())

            val giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (shortLived.idleConnections() > 0 && System.nanoTime() < giveUpAt) Thread.sleep(POLL_MS)
            assertEquals("closed at the lifetime, no exchange needed", 0, shortLived.idleConnections())
        } finally {
            shortLived.evictAll()
        }
    }

    /** A deadline longer than the lifetime is bounded by the watchdog alone; it does not rotate the pool per call. */
    @Test
    fun `an exchange whose deadline exceeds the lifetime still shares the pool`() {
        repeat(TWO) { relay.enqueue(MockResponse().setBody("ok")) }
        val overLong = TimeUnit.NANOSECONDS.toMillis(RelayConnections.MAX_CONNECTION_AGE_NANOS).toInt() + 1

        runBlocking {
            sender.post(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), overLong)
            nowNanos += TimeUnit.MINUTES.toNanos(1)
            sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), overLong)
        }

        assertEquals(0, relay.takeRequest().sequenceNumber)
        assertEquals(1, relay.takeRequest().sequenceNumber)
    }

    @Test
    fun `the maximum lifetime is slipstream's ten minutes`() =
        assertEquals(TimeUnit.MINUTES.toNanos(TEN), RelayConnections.MAX_CONNECTION_AGE_NANOS)

    /** An idle connection is dropped before the relay drops it, and well within the lifetime. */
    @Test
    fun `the keep-alive is below the measured relay idle timeout and the maximum lifetime`() {
        assertTrue(RelayConnections.KEEP_ALIVE_SECONDS < RelayConnections.MEASURED_RELAY_IDLE_TIMEOUT_SECONDS)
        val keepAliveNanos = TimeUnit.SECONDS.toNanos(RelayConnections.KEEP_ALIVE_SECONDS)
        assertTrue(keepAliveNanos < RelayConnections.MAX_CONNECTION_AGE_NANOS)
    }

    @Test
    fun `a redirect is reported with its status and not followed`() {
        relay.enqueue(MockResponse().setResponseCode(FOUND).setHeader("Location", relay.url("/elsewhere")))
        relay.enqueue(MockResponse().setBody("must not be fetched"))

        val thrown =
            assertFailsWith<OhttpException> {
                runBlocking { sender.post(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), TIMEOUT_MS) }
            }

        assertEquals(FOUND, thrown.httpStatus)
        assertEquals(1, relay.requestCount)
    }

    /**
     * The exact header set on the wire (HTTP/1.1): the caller's two, the sender's `Accept-Encoding: identity`
     * and fixed `User-Agent` (so OkHttp adds neither its transparent `gzip` nor `okhttp/x.y.z`), and the HTTP
     * layer's `Host`, `Content-Length`, `Connection: Keep-Alive`.
     */
    @Test
    fun `the relay sees the caller's headers, identity encoding, the fixed user agent and nothing else`() {
        relay.enqueue(MockResponse().setBody("ok"))

        runBlocking {
            sender.post(
                relay.url("/relay").toString(),
                byteArrayOf(1, 2, 3),
                listOf("Content-Type" to "message/ohttp-chunked-req", "Accept" to "message/ohttp-chunked-res"),
                TIMEOUT_MS,
            )
        }

        val request = relay.takeRequest()
        assertEquals(
            setOf("content-type", "accept", "accept-encoding", "user-agent", "host", "content-length", "connection"),
            request.headerNames(),
        )
        assertEquals("message/ohttp-chunked-req", request.getHeader("Content-Type"))
        assertEquals("message/ohttp-chunked-res", request.getHeader("Accept"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals(OhttpSender.FIXED_USER_AGENT, request.getHeader("User-Agent"))
        assertEquals("keep-alive", request.getHeader("Connection")?.lowercase())
        assertEquals(listOf<Byte>(1, 2, 3), request.body.readByteArray().toList())
    }

    /** The transport passes its own encoding and agent: sent once each, as given, nothing added. */
    @Test
    fun `a caller's own encoding and user agent are sent once, as given`() {
        relay.enqueue(MockResponse().setBody("ok"))

        runBlocking {
            sender.post(
                relay.url("/relay").toString(),
                byteArrayOf(1),
                listOf(
                    "Content-Type" to "message/ohttp-chunked-req",
                    "Accept" to "message/ohttp-chunked-res",
                    "Accept-Encoding" to "identity",
                    "User-Agent" to "ZODL-OHTTP/1.0",
                ),
                TIMEOUT_MS,
            )
        }

        val request = relay.takeRequest()
        assertEquals(listOf("identity"), request.headers.values("Accept-Encoding"))
        assertEquals(listOf("ZODL-OHTTP/1.0"), request.headers.values("User-Agent"))
        assertEquals(
            setOf("content-type", "accept", "accept-encoding", "user-agent", "host", "content-length", "connection"),
            request.headerNames(),
        )
    }

    @Test
    fun `a GET carries the same fixed defaults`() {
        relay.enqueue(MockResponse().setBody("keys"))

        runBlocking { sender.get(relay.url("/ohttp-keys").toString(), emptyList(), TIMEOUT_MS) }

        val request = relay.takeRequest()
        assertEquals(
            setOf("accept-encoding", "user-agent", "host", "connection"),
            request.headerNames(),
        )
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals(OhttpSender.FIXED_USER_AGENT, request.getHeader("User-Agent"))
    }

    /** The header names the relay saw, lower-cased (OkHttp and the caller may differ in case). */
    private fun RecordedRequest.headerNames(): Set<String> = headers.names().mapTo(mutableSetOf()) { it.lowercase() }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private val TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS.toLong())
        private const val FOUND = 302
        private const val TWO = 2
        private const val THREE = 3
        private const val TEN = 10L
        private const val SHORT_LIFETIME_MS = 300L
        private const val WAIT_SECONDS = 5L
        private const val POLL_MS = 20L
    }
}
