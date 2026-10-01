package co.electriccoin.lightwallet.client.internal

import io.grpc.CallOptions
import io.grpc.MethodDescriptor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

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
                transport = OhttpTransport("https://relay.example/relay", "mainnet"),
            )

        val call = channel.newCall(method, CallOptions.DEFAULT)
        call.cancel("cancelled before start", null)
        channel.shutdown()

        assertTrue("activeCalls must not go negative", channel.isTerminated)
    }
}
