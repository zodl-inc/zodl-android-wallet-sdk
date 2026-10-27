package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.dataRecord
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.finalRecord
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.headRecord
import co.electriccoin.lightwallet.client.internal.FakeOhttpCodec.Companion.streamStart
import co.electriccoin.lightwallet.client.internal.RecordingSender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Flexa's REST and SSE calls through [OhttpHttpClient]: inner authority, credentials, headers, streaming. */
class OhttpHttpClientStreamingTest {
    @Test
    fun `api_flexa_co is sealed to the flexa_zodl_com alias with its Authorization kept`() {
        val codec = FakeOhttpCodec()
        codec.answer = OhttpCodecResponse(200, listOf("date" to DATE), emptyList(), "{}".toByteArray())
        val sender =
            RecordingSender().apply {
                queueGet { "envelope".toByteArray() }
                queuePost { "answer".toByteArray() }
            }
        val client = OhttpHttpClient.create(codec, sender)

        assertTrue(client.canHandle("https://api.flexa.co/accounts/me"))
        assertTrue(client.canHandle("https://flexa.zodl.com/accounts/me"))
        val response =
            runBlocking {
                client.execute(
                    OhttpHttpClient.Request(
                        url = "https://api.flexa.co/exchange_rates?assets=a,b&unit_of_account=iso4217/USD",
                        headers = listOf("Authorization" to "Basic OnRva2Vu"),
                    )
                )
            }

        val sealed = codec.sealedRequests.single()
        assertEquals("flexa.zodl.com", sealed.authority)
        assertEquals("/exchange_rates?assets=a,b&unit_of_account=iso4217/USD", sealed.path)
        assertEquals(listOf("Authorization" to "Basic OnRva2Vu"), sealed.headers)
        assertEquals(listOf("date" to DATE), response.headers)
        assertEquals("{}", response.body.decodeToString())
    }

    @Test
    fun `a streamed answer reaches the listener chunk by chunk before it ends`() {
        val feed = Feed()
        val codec = FakeOhttpCodec()
        val sender =
            RecordingSender().apply {
                queueGet { "envelope".toByteArray() }
                queuePostStream { feed }
            }
        val client = OhttpHttpClient.create(codec, sender)
        val heads = CopyOnWriteArrayList<Pair<Int, List<Pair<String, String>>>>()
        val data = CopyOnWriteArrayList<String>()
        val firstData = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val listener =
            object : OhttpHttpClient.ResponseListener {
                override fun onHead(
                    status: Int,
                    headers: List<Pair<String, String>>,
                ) {
                    heads.add(status to headers)
                }

                override fun onData(bytes: ByteArray) {
                    data.add(bytes.decodeToString())
                    firstData.countDown()
                }

                override fun onEnd() = ended.countDown()
            }

        val call =
            thread {
                runBlocking {
                    client.executeStreaming(
                        OhttpHttpClient.Request(
                            url = "https://api.flexa.co/events?type=commerce_session.created",
                            headers = listOf("Accept" to "text/event-stream", "Last-Event-ID" to "ev_1"),
                        ),
                        timeoutMs = 10_000,
                        listener = listener,
                    )
                }
            }
        feed.send(streamStart() + headRecord(200, listOf("content-type" to "text/event-stream")))
        feed.send(dataRecord("id: ev_2\ndata: {}\n\n".toByteArray()))

        assertTrue("first event before the stream ends", firstData.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals(1L, ended.count)
        assertEquals(listOf(200 to listOf("content-type" to "text/event-stream")), heads.toList())

        feed.send(dataRecord("id: ev_3\ndata: {}\n\n".toByteArray()) + finalRecord())
        feed.end()
        call.join(WAIT_S * MILLIS)

        assertTrue(ended.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals(listOf("id: ev_2\ndata: {}\n\n", "id: ev_3\ndata: {}\n\n"), data.toList())
        val sealed = codec.sealedRequests.single()
        assertEquals("flexa.zodl.com", sealed.authority)
        assertTrue(sealed.headers.contains("Last-Event-ID" to "ev_1"))
        assertTrue(codec.liveStreams.isEmpty())
    }

    private class Feed : InputStream() {
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

    private companion object {
        const val WAIT_S = 10L
        const val MILLIS = 1000L
        const val BYTE_MASK = 0xff
        const val DATE = "Sun, 18 Oct 2026 12:00:00 GMT"
    }
}
