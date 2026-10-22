package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * [DirectOhttpSender] reuses its relay connection across exchanges (`RecordedRequest.sequenceNumber` is
 * the request's index on its connection: 0 = a fresh connection) and never past
 * [RelayConnections.MAX_CONNECTION_AGE_NANOS], driven here by a fake clock.
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
    fun `a connection just under the maximum lifetime is still reused`() {
        repeat(TWO) { relay.enqueue(MockResponse().setBody("ok")) }

        runBlocking {
            sender.post(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), TIMEOUT_MS)
            nowNanos += RelayConnections.MAX_CONNECTION_AGE_NANOS - 1
            sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
        }

        assertEquals(0, relay.takeRequest().sequenceNumber)
        assertEquals(1, relay.takeRequest().sequenceNumber)
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

    @Test
    fun `the maximum lifetime is slipstream's ten minutes`() =
        assertEquals(TimeUnit.MINUTES.toNanos(TEN), RelayConnections.MAX_CONNECTION_AGE_NANOS)

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

    @Test
    fun `only the caller's headers are sent and the body arrives intact`() {
        relay.enqueue(MockResponse().setBody("ok"))

        runBlocking {
            sender.post(
                relay.url("/relay").toString(),
                byteArrayOf(1, 2, 3),
                listOf("Content-Type" to "message/ohttp-chunked-req", "User-Agent" to "ZODL-OHTTP/1.0"),
                TIMEOUT_MS,
            )
        }

        val request = relay.takeRequest()
        assertEquals("message/ohttp-chunked-req", request.getHeader("Content-Type"))
        assertEquals("ZODL-OHTTP/1.0", request.getHeader("User-Agent"))
        assertNotEquals("close", request.getHeader("Connection")?.lowercase())
        assertEquals(listOf<Byte>(1, 2, 3), request.body.readByteArray().toList())
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val FOUND = 302
        private const val TWO = 2
        private const val THREE = 3
        private const val TEN = 10L
    }
}
