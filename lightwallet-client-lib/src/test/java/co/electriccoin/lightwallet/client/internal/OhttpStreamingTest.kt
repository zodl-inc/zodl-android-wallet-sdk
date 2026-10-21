package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpProfileInfo
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.dataRecord
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.finalRecord
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.headRecord
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.streamStart
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.streamed
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.tamperedRecord
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.trailersRecord
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Server-streaming calls over [OhttpTransport.stream]: messages are delivered as their chunks arrive. */
class OhttpStreamingTest {
    private val marshaller =
        object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }

    private val method =
        MethodDescriptor
            .newBuilder(marshaller, marshaller)
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("test.Service", "Stream"))
            .build()

    private val grpcHead = headRecord(200, listOf("content-type" to "application/grpc"))
    private val okTrailers = trailersRecord(listOf("grpc-status" to "0"))

    @Test
    fun `the first message is delivered before the last chunk arrives`() {
        val feed = ChunkFeed()
        val sender = RecordingSender().apply { queuePostStream { feed } }
        val codec = FakeOhttpCodec()
        val call = Call(codec, sender)

        feed.send(streamStart() + grpcHead + dataRecord(frame(MESSAGE_1)))
        assertTrue("first message before the stream ends", call.firstMessage.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals(1, call.messages.size)
        assertTrue("call still open", call.closed.count == 1L)

        val second = frame(MESSAGE_2)
        feed.send(dataRecord(second.copyOfRange(0, 3)))
        Thread.sleep(DELAY_MS)
        feed.send(dataRecord(second.copyOfRange(3, second.size)) + okTrailers + finalRecord())
        feed.end()

        assertEquals(Status.Code.OK, call.awaitClose().code)
        assertEquals(listOf(MESSAGE_1.toList(), MESSAGE_2.toList()), call.messages.map { it.toList() })
        call.assertReleased(codec)
    }

    @Test
    fun `a stream cut before its final chunk is UNAVAILABLE after the messages it carried`() {
        val feed = ChunkFeed()
        val sender = RecordingSender().apply { queuePostStream { feed } }
        val codec = FakeOhttpCodec()
        val call = Call(codec, sender)

        feed.send(streamStart() + grpcHead + dataRecord(frame(MESSAGE_1)))
        assertTrue(call.firstMessage.await(WAIT_S, TimeUnit.SECONDS))
        feed.end()

        assertEquals(Status.Code.UNAVAILABLE, call.awaitClose().code)
        assertEquals(1, call.messages.size)
        call.assertReleased(codec)
    }

    @Test
    fun `a chunk that does not open mid-stream is INTERNAL`() {
        val sender = RecordingSender()
        sender.queuePost { streamed(grpcHead, dataRecord(frame(MESSAGE_1)), tamperedRecord()) }
        val codec = FakeOhttpCodec()
        val call = Call(codec, sender)

        assertEquals(Status.Code.INTERNAL, call.awaitClose().code)
        call.assertReleased(codec)
    }

    @Test
    fun `a stream past the size cap is refused while it is read`() {
        val cap = 256
        val feed = ChunkFeed()
        val sender = RecordingSender().apply { queuePostStream { feed } }
        val codec = FakeOhttpCodec(profile = profileWithCap(cap.toLong()))
        val call = Call(codec, sender)

        feed.send(streamStart() + grpcHead + dataRecord(frame(MESSAGE_1)))
        assertTrue(call.firstMessage.await(WAIT_S, TimeUnit.SECONDS))
        feed.send(dataRecord(frame(ByteArray(cap))))

        assertEquals(Status.Code.UNAVAILABLE, call.awaitClose().code)
        assertTrue("never decoded past the cap: ${codec.pushedBytes.get()}", codec.pushedBytes.get() <= cap)
        call.assertReleased(codec)
    }

    @Test
    fun `a missing trailer status and a partial frame at the end are INTERNAL`() {
        val cases =
            listOf(
                streamed(grpcHead, dataRecord(frame(MESSAGE_1)), trailersRecord(emptyList()), finalRecord()),
                streamed(grpcHead, dataRecord(frame(MESSAGE_1).copyOf(4)), okTrailers, finalRecord()),
                streamed(headRecord(503, emptyList()), okTrailers, finalRecord()),
            )
        for (body in cases) {
            val sender = RecordingSender().apply { queuePost { body } }
            val codec = FakeOhttpCodec()

            val call = Call(codec, sender)

            assertEquals(Status.Code.INTERNAL, call.awaitClose().code)
            call.assertReleased(codec)
        }
    }

    @Test
    fun `a stale refusal is held back and the request is sent again`() {
        val sender = RecordingSender()
        sender.queuePost {
            val stale = headRecord(400, listOf("ohttp-freshness" to "rejected"))
            streamed(stale, trailersRecord(emptyList()), finalRecord())
        }
        sender.queuePost { streamed(grpcHead, dataRecord(frame(MESSAGE_1)), okTrailers, finalRecord()) }
        val codec = FakeOhttpCodec()
        val call = Call(codec, sender)

        assertEquals(Status.Code.OK, call.awaitClose().code)
        assertEquals(2, sender.postCalls.size)
        assertEquals(1, call.messages.size)
        call.assertReleased(codec)
    }

    @Test
    fun `cancelling a call mid-stream closes the relay connection and releases the call`() {
        val relay = HangingRelay(streamed(grpcHead, dataRecord(frame(MESSAGE_1))))
        val codec = FakeOhttpCodec()
        val call = Call(codec, DirectOhttpSender(), relayUrl = relay.url)

        assertTrue(call.firstMessage.await(WAIT_S, TimeUnit.SECONDS))
        call.clientCall.cancel("user left", null)

        assertEquals(Status.Code.CANCELLED, call.awaitClose().code)
        assertTrue("relay connection closed", relay.clientClosed.await(WAIT_S, TimeUnit.SECONDS))
        call.assertReleased(codec)
        relay.close()
    }

    @Test
    fun `a call deadline that passes mid-stream is DEADLINE_EXCEEDED`() {
        val relay = HangingRelay(streamed(grpcHead, dataRecord(frame(MESSAGE_1))))
        val codec = FakeOhttpCodec()
        val options = CallOptions.DEFAULT.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
        val call = Call(codec, DirectOhttpSender(), relayUrl = relay.url, options = options)

        assertTrue(call.firstMessage.await(WAIT_S, TimeUnit.SECONDS))

        assertEquals(Status.Code.DEADLINE_EXCEEDED, call.awaitClose().code)
        assertTrue(relay.clientClosed.await(WAIT_S, TimeUnit.SECONDS))
        call.assertReleased(codec)
        relay.close()
    }

    @Test
    fun `the transport hands the head, the body and the trailers over as they are decoded`() {
        val feed = ChunkFeed()
    @Test
    fun `a response that ends before its final chunk after the deadline passed is DEADLINE_EXCEEDED`() {
        val feed = ChunkFeed()
        val sender = RecordingSender().apply { queuePostStream { feed } }
        val codec = FakeOhttpCodec()
        val options = CallOptions.DEFAULT.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
        val call = Call(codec, sender, options = options)

        feed.send(streamStart() + grpcHead + dataRecord(frame(MESSAGE_1)))
        assertTrue(call.firstMessage.await(WAIT_S, TimeUnit.SECONDS))
        // The watchdog's disconnect can surface as an early end of the body rather than an I/O error.
        while (options.deadline?.isExpired != true) Thread.sleep(POLL_MS)
        feed.end()

        assertEquals(Status.Code.DEADLINE_EXCEEDED, call.awaitClose().code)
        call.assertReleased(codec)
    }

        val sender = RecordingSender().apply { queuePostStream { feed } }
        val transport = transport(FakeOhttpCodec(), sender)
        val events = LinkedBlockingQueue<String>()
        val sink =
            object : OhttpResponseSink {
                override fun onHead(
                    status: Int,
                    headers: List<Pair<String, String>>
                ) {
                    events.add("head $status")
                }

                override fun onData(bytes: ByteArray) {
                    events.add("data ${bytes.size}")
                }

                override fun onTrailers(trailers: List<Pair<String, String>>) {
                    events.add("trailers ${trailers.size}")
                }

                override fun onEnd() {
                    events.add("end")
                }
            }
        val reader = thread(isDaemon = true) { runBlocking { transport.stream(request(), TIMEOUT_MS, sink) } }

        feed.send(streamStart() + grpcHead)
        assertEquals("head 200", events.poll(WAIT_S, TimeUnit.SECONDS))
        feed.send(dataRecord(ByteArray(7)))
        assertEquals("data 7", events.poll(WAIT_S, TimeUnit.SECONDS))
        feed.send(okTrailers + finalRecord())
        assertEquals("trailers 1", events.poll(WAIT_S, TimeUnit.SECONDS))
        feed.end()
        assertEquals("end", events.poll(WAIT_S, TimeUnit.SECONDS))
        reader.join(TimeUnit.SECONDS.toMillis(WAIT_S))
    }

    @Test
    fun `the frame reader splits messages across pushes and keeps a partial one`() {
        val reader = GrpcFrameReader()
        val bytes = frame(MESSAGE_1) + frame(MESSAGE_2)

        assertTrue(reader.push(bytes.copyOf(3)).isEmpty())
        val first = reader.push(bytes.copyOfRange(3, frame(MESSAGE_1).size + 2))
        assertEquals(1, first.size)
        assertArrayEquals(MESSAGE_1, first[0])
        assertTrue(reader.hasPartialFrame())
        val second = reader.push(bytes.copyOfRange(frame(MESSAGE_1).size + 2, bytes.size))
        assertArrayEquals(MESSAGE_2, second.single())
        assertTrue(!reader.hasPartialFrame())
    }

    private fun profileWithCap(cap: Long): OhttpProfileInfo =
        FakeOhttpCodec.PROFILE.let {
            OhttpProfileInfo(
                postHeaders = it.postHeaders,
                keyFetchHeaders = it.keyFetchHeaders,
                maxSealedResponseBytes = cap,
                maxKeyConfigBytes = it.maxKeyConfigBytes,
                cacheRefreshMarginSecs = it.cacheRefreshMarginSecs,
                dropBeforeRefetch = it.dropBeforeRefetch,
                traceOnKeyFetch = it.traceOnKeyFetch,
                bodyWindow = it.bodyWindow,
            )
        }

    private fun transport(
        codec: FakeOhttpCodec,
        sender: co.electriccoin.lightwallet.client.OhttpSender,
        relayUrl: String = "https://relay.example/relay",
    ) = OhttpTransport(
        relayUrl = relayUrl,
        gatewayHost = "ohttp-gateway.example",
        codec = codec,
        sender = sender,
        initialKey = FakeOhttpCodec.KEY,
    )

    private fun request() =
        co.electriccoin.lightwallet.client.OhttpCodecRequest(
            method = "POST",
            scheme = "https",
            authority = "ohttp-gateway.example",
            path = "/test.Service/Stream",
            headers = emptyList(),
            body = ByteArray(0),
        )

    /** A started, half-closed call that records what its listener gets. */
    private inner class Call(
        codec: FakeOhttpCodec,
        sender: co.electriccoin.lightwallet.client.OhttpSender,
        relayUrl: String = "https://relay.example/relay",
        options: CallOptions = CallOptions.DEFAULT,
    ) {
        val registry: MutableSet<OhttpClientCall<*, *>> = ConcurrentHashMap.newKeySet()
        val active = AtomicInteger(0)
        val clientCall =
            OhttpClientCall(
                method,
                options,
                "ohttp-gateway.example",
                transport(codec, sender, relayUrl),
                {},
                active,
                registry,
            )
        val messages = CopyOnWriteArrayList<ByteArray>()
        val firstMessage = CountDownLatch(1)
        val closed = CountDownLatch(1)

        @Volatile
        var status: Status? = null

        init {
            registry.add(clientCall)
            clientCall.start(
                object : ClientCall.Listener<ByteArray>() {
                    override fun onMessage(message: ByteArray) {
                        messages.add(message)
                        firstMessage.countDown()
                    }

                    override fun onClose(
                        status: Status,
                        trailers: Metadata
                    ) {
                        this@Call.status = status
                        closed.countDown()
                    }
                },
                Metadata(),
            )
            clientCall.request(Int.MAX_VALUE)
            clientCall.sendMessage(byteArrayOf(1))
            clientCall.halfClose()
        }

        fun awaitClose(): Status {
            assertTrue("call must close", closed.await(WAIT_S, TimeUnit.SECONDS))
            return status!!
        }

        fun assertReleased(codec: FakeOhttpCodec) {
            val released = { registry.isEmpty() && active.get() == 0 && codec.liveStreams.isEmpty() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
            while (!released() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
            assertTrue("call left the registry", registry.isEmpty())
            assertEquals(0, active.get())
            assertTrue("decoder closed", codec.liveStreams.isEmpty())
            assertTrue("opener spent", codec.liveOpeners.isEmpty())
        }
    }

    /** A body whose bytes arrive when the test sends them; [end] ends it. */
    private class ChunkFeed : InputStream() {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private var current = ByteArray(0)
        private var pos = 0

        fun send(bytes: ByteArray) = chunks.add(bytes)

        fun end() = chunks.add(END)

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int
        ): Int {
            if (pos == current.size) {
                current = chunks.poll(WAIT_S, TimeUnit.SECONDS) ?: throw IOException("feed stalled")
                pos = 0
                if (current === END) {
                    chunks.add(END)
                    return -1
                }
            }
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        companion object {
            private val END = ByteArray(0)
        }
    }

    /** A relay on a local socket that answers 200 with [body] and then hangs until the client closes. */
    private class HangingRelay(
        body: ByteArray,
    ) {
        private val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}/relay"
        val clientClosed = CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(BUFFER))
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                        // Whatever is left of the request, then the end the client's close makes.
                        runCatching { socket.getInputStream().readBytes() }
                        clientClosed.countDown()
                    }
                }
            }
        }

        fun close() = server.close()
    }

    private companion object {
        val MESSAGE_1 = byteArrayOf(10, 11, 12)
        val MESSAGE_2 = ByteArray(40) { it.toByte() }
        const val WAIT_S = 10L
        const val DELAY_MS = 100L
        const val POLL_MS = 10L
        const val DEADLINE_MS = 1_500L
        const val TIMEOUT_MS = 30_000
        const val BUFFER = 64 * 1024
        const val BYTE_MASK = 0xFF

        fun frame(message: ByteArray): ByteArray =
            byteArrayOf(0) + ByteBuffer.allocate(4).putInt(message.size).array() + message
    }
}
