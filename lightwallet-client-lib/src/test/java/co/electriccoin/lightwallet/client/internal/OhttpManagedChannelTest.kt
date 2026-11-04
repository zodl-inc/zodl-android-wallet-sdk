package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpException
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

class OhttpManagedChannelTest {
    @get:Rule
    val exampleGateways = ExampleGatewaysRule()

    private val marshaller =
        object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }

    private val method =
        MethodDescriptor
            .newBuilder(marshaller, marshaller)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("test.Service", "Method"))
            .build()

    @Test
    fun `cancel before start does not leave the channel unable to terminate`() {
        val channel =
            OhttpManagedChannel(
                authority = "ohttp-gateway.example",
                transport =
                    OhttpTransport(
                        relayUrl = "https://relay.example/relay",
                        gatewayHost = "ohttp-gateway.example",
                        codec = FakeOhttpCodec(),
                    ),
            )

        val call = channel.newCall(method, CallOptions.DEFAULT)
        call.cancel("cancelled before start", null)
        channel.shutdown()

        assertTrue("activeCalls must not go negative", channel.isTerminated)
    }

    @Test
    fun `a call that completes normally leaves the channel's call registry`() {
        val sender = RecordingSender()
        sender.queuePost { "answer".toByteArray() }
        val codec =
            FakeOhttpCodec(
                answer =
                    OhttpCodecResponse(
                        status = 200,
                        headers = listOf("content-type" to "application/grpc"),
                        trailers = listOf("grpc-status" to "0"),
                        body = ByteArray(0),
                    ),
            )
        val transport =
            OhttpTransport(
                relayUrl = "https://relay.example/relay",
                gatewayHost = "ohttp-gateway.example",
                codec = codec,
                sender = sender,
                initialKey = FakeOhttpCodec.KEY,
            )
        val registry = ConcurrentHashMap.newKeySet<OhttpClientCall<*, *>>()
        val call =
            OhttpClientCall(
                method,
                CallOptions.DEFAULT,
                "ohttp-gateway.example",
                transport,
                {},
                AtomicInteger(0),
                registry,
            )
        registry.add(call)

        val closed = CountDownLatch(1)
        var closeStatus: Status? = null
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

        assertTrue("call must close", closed.await(5, TimeUnit.SECONDS))
        assertEquals(Status.Code.OK, closeStatus?.code)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (registry.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("completed call must leave the registry", registry.isEmpty())
    }

    @Test
    fun `a rejected signed key configuration closes the call UNAVAILABLE`() {
        val sender = RecordingSender()
        sender.queueGet { "{}".toByteArray() }
        val codec = FakeOhttpCodec(verify = FakeOhttpCodec.rejecting("not_after is in the past"))

        val status = closeStatusOf(codec, sender, initialKey = null)

        assertEquals(Status.Code.UNAVAILABLE, status.code)
        assertTrue(sender.postCalls.isEmpty())
    }

    @Test
    fun `a 429 on the key fetch closes the call UNAVAILABLE, as one on the post does`() {
        // Its own relay: a 429 without Retry-After pauses the relay for 30 s, process-wide.
        val relayUrl = "https://relay-429-key.example/relay"
        val sender = RecordingSender()
        sender.queueGet { throw OhttpException("rate limited", 429) }
        val codec = FakeOhttpCodec(resolvers = arrayOf("https://doh.example/dns-query"))

        val status = closeStatusOf(codec, sender, initialKey = null, relayUrl = relayUrl)

        assertEquals(Status.Code.UNAVAILABLE, status.code)
        assertEquals("no DNS query after the 429", 1, sender.getCalls.size)
        assertTrue(sender.postCalls.isEmpty())
        val paused = assertFailsWith<OhttpException> { RelayBackoff.check(relayUrl) }
        assertEquals(RelayBackoff.DEFAULT_RETRY_AFTER_SECONDS, paused.retryAfterSeconds)
    }

    @Test
    fun `an inner status other than 200 is INTERNAL`() {
        for (inner in listOf(403, 404, 413, 429, 503)) {
            val sender = RecordingSender()
            sender.queuePost { "answer".toByteArray() }
            val codec = FakeOhttpCodec(answer = grpcResponse(status = inner, trailers = emptyList()))

            assertEquals("inner $inner", Status.Code.INTERNAL, closeStatusOf(codec, sender).code)
        }
    }

    @Test
    fun `the trailer grpc-status wins over the header one`() {
        val sender = RecordingSender()
        sender.queuePost { "answer".toByteArray() }
        val codec =
            FakeOhttpCodec(
                answer =
                    grpcResponse(
                        headers = listOf("content-type" to "application/grpc", "grpc-status" to "0"),
                        trailers = listOf("grpc-status" to "5"),
                    ),
            )

        assertEquals(Status.Code.NOT_FOUND, closeStatusOf(codec, sender).code)
    }

    @Test
    fun `with the trailers appended to the headers, as the native core does, the trailer status wins`() {
        val sender = RecordingSender()
        sender.queuePost { "answer".toByteArray() }
        val codec =
            FakeOhttpCodec(
                answer =
                    grpcResponse(
                        headers =
                            listOf(
                                "content-type" to "application/grpc",
                                "grpc-status" to "0",
                                "grpc-status" to "5",
                            ),
                        trailers = emptyList(),
                    ),
            )

        assertEquals(Status.Code.NOT_FOUND, closeStatusOf(codec, sender).code)
    }

    @Test
    fun `an outer 413 is RESOURCE_EXHAUSTED and other refusals UNAVAILABLE`() {
        for ((outer, expected) in listOf(413 to Status.Code.RESOURCE_EXHAUSTED, 426 to Status.Code.UNAVAILABLE)) {
            val sender = RecordingSender()
            sender.queuePost { throw OhttpException("refused", outer) }

            assertEquals("outer $outer", expected, closeStatusOf(FakeOhttpCodec(), sender).code)
        }
    }

    @Test
    fun `a transport timeout before the call deadline is UNAVAILABLE`() {
        val sender = RecordingSender()
        sender.queuePost { throw java.net.SocketTimeoutException("read timed out") }

        assertEquals(Status.Code.UNAVAILABLE, closeStatusOf(FakeOhttpCodec(), sender).code)
    }

    private fun grpcResponse(
        status: Int = 200,
        headers: List<Pair<String, String>> = listOf("content-type" to "application/grpc"),
        trailers: List<Pair<String, String>> = listOf("grpc-status" to "0"),
    ) = OhttpCodecResponse(status = status, headers = headers, trailers = trailers, body = ByteArray(0))

    private fun closeStatusOf(
        codec: FakeOhttpCodec,
        sender: RecordingSender,
        initialKey: ByteArray? = FakeOhttpCodec.KEY,
        relayUrl: String = "https://relay.example/relay",
    ): Status {
        val transport =
            OhttpTransport(
                relayUrl = relayUrl,
                gatewayHost = "ohttp-gateway.example",
                codec = codec,
                sender = sender,
                initialKey = initialKey,
            )
        val registry = ConcurrentHashMap.newKeySet<OhttpClientCall<*, *>>()
        val call =
            OhttpClientCall(
                method,
                CallOptions.DEFAULT,
                "ohttp-gateway.example",
                transport,
                {},
                AtomicInteger(0),
                registry,
            )
        registry.add(call)
        val closed = CountDownLatch(1)
        var closeStatus: Status? = null
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
        assertTrue("call must close", closed.await(5, TimeUnit.SECONDS))
        return closeStatus!!
    }
}
