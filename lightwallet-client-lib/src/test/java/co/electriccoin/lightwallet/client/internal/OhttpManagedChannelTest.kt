package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpCodecResponse
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OhttpManagedChannelTest {
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
}
