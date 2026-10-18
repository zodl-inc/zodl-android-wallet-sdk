package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

/** [DirectOhttpSender] against a local socket that plays the relay. */
class DirectOhttpSenderDeadlineTest {
    @Test
    fun `a relay that keeps dribbling bytes is cut off at the exchange timeout`() {
        val server = ServerSocket(0)
        val relay =
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(BUFFER))
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                        repeat(DRIBBLE_BYTES) {
                            out.write(0)
                            out.flush()
                            Thread.sleep(DRIBBLE_INTERVAL_MS)
                        }
                    }
                }
            }

        val started = System.nanoTime()
        assertFailsWith<SocketTimeoutException> {
            runBlocking {
                DirectOhttpSender().post(relayUrl(server), ByteArray(1), emptyList(), TIMEOUT_MS)
            }
        }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI

        assertTrue("cut off after $elapsedMs ms", elapsedMs < TIMEOUT_MS * 4)
        server.close()
        relay.join(JOIN_MS)
    }

    @Test
    fun `a 429 carries the relay's Retry-After`() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(BUFFER))
                    socket.getOutputStream().write(
                        "HTTP/1.1 429 Too Many Requests\r\nRetry-After: 42\r\nContent-Length: 0\r\n\r\n".toByteArray()
                    )
                    socket.getOutputStream().flush()
                }
            }
        }

        val thrown =
            assertFailsWith<OhttpException> {
                runBlocking {
                    DirectOhttpSender().post(relayUrl(server), ByteArray(1), emptyList(), 5_000)
                }
            }

        assertEquals(429, thrown.httpStatus)
        assertEquals(42L, thrown.retryAfterSeconds)
        server.close()
    }

    @Test
    fun `a gRPC call over a dribbling relay closes at its own sub-second deadline`() {
        val server = ServerSocket(0)
        thread(isDaemon = true) { dribble(server) }
        val transport =
            OhttpTransport(
                relayUrl = relayUrl(server),
                gatewayHost = "ohttp-gateway.example",
                codec = FakeOhttpCodec(),
                sender = DirectOhttpSender(),
                initialKey = FakeOhttpCodec.KEY,
            )
        val call =
            OhttpClientCall(
                method,
                CallOptions.DEFAULT.withDeadlineAfter(SHORT_DEADLINE_MS, TimeUnit.MILLISECONDS),
                "ohttp-gateway.example",
                transport,
                {},
                AtomicInteger(0),
                ConcurrentHashMap.newKeySet(),
            )
        val closed = CountDownLatch(1)
        var closeStatus: Status? = null
        val started = System.nanoTime()
        call.start(
            object : ClientCall.Listener<ByteArray>() {
                override fun onClose(
                    status: Status,
                    trailers: Metadata,
                ) {
                    closeStatus = status
                    closed.countDown()
                }
            },
            Metadata(),
        )
        call.request(1)
        call.sendMessage(byteArrayOf(1))
        call.halfClose()

        assertTrue(closed.await(JOIN_MS, TimeUnit.MILLISECONDS))
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertEquals(Status.Code.DEADLINE_EXCEEDED, closeStatus?.code)
        // The relay would dribble for 10 s; a busy CI runner needs headroom over the 300 ms deadline.
        assertTrue("closed after $elapsedMs ms", elapsedMs < SHORT_DEADLINE_MS * 10)
        server.close()
    }

    private fun dribble(server: ServerSocket) {
        runCatching {
            server.accept().use { socket ->
                socket.getInputStream().read(ByteArray(BUFFER))
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                repeat(DRIBBLE_BYTES) {
                    out.write(0)
                    out.flush()
                    Thread.sleep(DRIBBLE_INTERVAL_MS)
                }
            }
        }
    }

    private val method =
        MethodDescriptor
            .newBuilder(BytesMarshaller, BytesMarshaller)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("test.Service", "Method"))
            .build()

    private object BytesMarshaller : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

        override fun parse(stream: InputStream): ByteArray = stream.readBytes()
    }

    private fun relayUrl(server: ServerSocket) = "http://127.0.0.1:${server.localPort}/relay"

    private companion object {
        const val TIMEOUT_MS = 500
        const val SHORT_DEADLINE_MS = 300L
        const val DRIBBLE_BYTES = 100
        const val DRIBBLE_INTERVAL_MS = 100L
        const val BUFFER = 8192
        const val JOIN_MS = 15_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
