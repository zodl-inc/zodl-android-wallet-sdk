package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * One exchange is one request on the wire: the HTTP layer never sends a sealed request twice. OkHttp's
 * `RetryAndFollowUpInterceptor` would re-post the same bytes on a 503 with `Retry-After: 0` (and on a
 * 408, and on a 421 from a coalesced HTTP/2 connection) - a byte-identical resend the gateway refuses as
 * a replay, and a second delivery of a write if the first 503 came from before decapsulation (ZIP "One
 * call, one exchange"). The relay in production answers its own errors with `Retry-After: 0` (observed
 * 2026-10-23 on a 404), so the trigger is one status code away.
 */
class DirectOhttpSenderOneRequestTest {
    private val relay = MockWebServer()
    private lateinit var connections: RelayConnections
    private lateinit var sender: DirectOhttpSender

    @Before
    fun start() {
        relay.start()
        connections = RelayConnections()
        sender = DirectOhttpSender(connections)
    }

    @After
    fun stop() {
        connections.evictAll()
        relay.shutdown()
    }

    @Test
    fun `a 503 with Retry-After 0 is reported with its status and the sealed request is not posted again`() =
        assertNotPostedAgain(SERVICE_UNAVAILABLE, "Retry-After" to "0")

    @Test
    fun `a 421 is reported with its status and the sealed request is not posted again`() =
        assertNotPostedAgain(MISDIRECTED_REQUEST)

    @Test
    fun `a 408 is reported with its status and the sealed request is not posted again`() =
        assertNotPostedAgain(REQUEST_TIMEOUT)

    private fun assertNotPostedAgain(
        status: Int,
        header: Pair<String, String>? = null,
    ) {
        val first = MockResponse().setResponseCode(status).setBody("first answer")
        header?.let { (name, value) -> first.setHeader(name, value) }
        relay.enqueue(first)
        relay.enqueue(MockResponse().setBody("must never be requested"))

        val thrown =
            assertFailsWith<OhttpException> {
                runBlocking {
                    sender.post(relay.url("/relay").toString(), byteArrayOf(1, 2, 3), emptyList(), TIMEOUT_MS)
                }
            }

        assertEquals(status, thrown.httpStatus)
        if (header != null) assertEquals(0L, thrown.retryAfterSeconds)
        assertEquals("the relay saw exactly one request", 1, relay.requestCount)
        assertEquals(listOf<Byte>(1, 2, 3), relay.takeRequest().body.readByteArray().toList())
    }

    /** A GET has no body to make one-shot; the per-exchange guard refuses the follow-up before it is sent. */
    @Test
    fun `a GET answered 503 with Retry-After 0 is not sent again`() {
        relay.enqueue(MockResponse().setResponseCode(SERVICE_UNAVAILABLE).setHeader("Retry-After", "0"))
        relay.enqueue(MockResponse().setBody("must never be requested"))

        val thrown =
            assertFailsWith<IOException> {
                runBlocking { sender.get(relay.url("/ohttp-keys").toString(), emptyList(), TIMEOUT_MS) }
            }

        assertTrue(thrown.message.orEmpty(), thrown.message.orEmpty().contains("send the request again"))
        assertEquals("the relay saw exactly one request", 1, relay.requestCount)
    }

    @Test
    fun `the sealed body is one-shot and refuses a second write`() {
        val body = SealedRequestBody(byteArrayOf(1, 2, 3))
        assertTrue(body.isOneShot())
        assertEquals(3L, body.contentLength())
        assertNull(body.contentType())

        val sink = Buffer()
        body.writeTo(sink)
        assertEquals(listOf<Byte>(1, 2, 3), sink.readByteArray().toList())

        val second = Buffer()
        assertFailsWith<IOException> { body.writeTo(second) }
        assertEquals("nothing of the second write reaches the wire", 0L, second.size)
    }

    @Test
    fun `an exchange ticket is spent by its first request only`() {
        val ticket = RelayConnections.ExchangeTicket()
        assertTrue(ticket.sent.compareAndSet(false, true))
        assertFalse(ticket.sent.compareAndSet(false, true))
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val SERVICE_UNAVAILABLE = 503
        private const val MISDIRECTED_REQUEST = 421
        private const val REQUEST_TIMEOUT = 408
    }
}
