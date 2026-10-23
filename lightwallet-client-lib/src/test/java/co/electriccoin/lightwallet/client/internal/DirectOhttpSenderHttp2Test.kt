package co.electriccoin.lightwallet.client.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * [DirectOhttpSender] over TLS with HTTP/2 negotiated, as against the relay: exchanges multiplex on one
 * connection, and both a cancelled exchange and one past its deadline reset their stream while the
 * connection stays up for the next exchange (what the sender's KDoc claims; on HTTP/1.1 the connection
 * would be closed instead).
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

    @Test
    fun `exchanges multiplex on one HTTP 2 connection`() {
        repeat(THREE) { relay.enqueue(MockResponse().setBody("ok")) }

        runBlocking {
            sender.post(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), TIMEOUT_MS)
            sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
            sender.get(relay.url("/ohttp-keys").toString(), emptyList(), TIMEOUT_MS)
        }

        repeat(THREE) { index ->
            val request = relay.takeRequest()
            assertTrue(request.requestLine, request.requestLine.endsWith("HTTP/2"))
            assertEquals(index, request.sequenceNumber)
        }
    }

    @Test
    fun `a cancelled exchange resets its stream and the connection serves the next exchange`() {
        relay.enqueue(MockResponse().setBody("never delivered").setBodyDelay(LONG_DELAY_SECONDS, TimeUnit.SECONDS))
        relay.enqueue(MockResponse().setBody("ok"))

        runBlocking {
            val blockedReader =
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        sender.postStreaming(relay.url("/relay").toString(), byteArrayOf(1), emptyList(), TIMEOUT_MS) {
                            it.read()
                        }
                    }
                }
            assertEquals(0, relay.takeRequest().sequenceNumber)
            delay(SETTLE_MS)
            blockedReader.cancelAndJoin()

            sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), TIMEOUT_MS)
        }

        assertEquals("the same connection", 1, relay.takeRequest().sequenceNumber)
    }

    @Test
    fun `an exchange past its deadline is the timeout and the connection serves the next exchange`() {
        relay.enqueue(MockResponse().setBody("too late").setBodyDelay(LONG_DELAY_SECONDS, TimeUnit.SECONDS))
        relay.enqueue(MockResponse().setBody("ok"))

        val url = relay.url("/relay").toString()
        val thrown =
            assertFailsWith<SocketTimeoutException> {
                runBlocking { sender.post(url, byteArrayOf(1), emptyList(), SHORT_TIMEOUT_MS) }
            }
        assertEquals("OHTTP exchange exceeded $SHORT_TIMEOUT_MS ms", thrown.message)
        assertEquals(0, relay.takeRequest().sequenceNumber)

        runBlocking { sender.post(relay.url("/relay").toString(), byteArrayOf(2), emptyList(), TIMEOUT_MS) }

        assertEquals("the same connection", 1, relay.takeRequest().sequenceNumber)
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val SHORT_TIMEOUT_MS = 500
        private const val LONG_DELAY_SECONDS = 30L
        private const val SETTLE_MS = 300L
        private const val THREE = 3
    }
}
