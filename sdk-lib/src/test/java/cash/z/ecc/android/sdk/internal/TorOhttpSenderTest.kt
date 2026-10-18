package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.JniHttpHeader
import cash.z.ecc.android.sdk.internal.model.JniHttpResponseBytes
import cash.z.ecc.android.sdk.internal.model.TorHttpTimeoutException
import cash.z.ecc.android.sdk.internal.model.TorHttpTransportException
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TorOhttpSenderTest {
    private class FakeTransport(
        private val chunks: List<() -> ByteArray> = emptyList(),
        private val response: () -> JniHttpResponseBytes
    ) : TorHttpTransport {
        var lastTimeoutMs: Long? = null
        var calls = 0

        override suspend fun <T> postStreaming(
            url: String,
            headers: List<JniHttpHeader>,
            body: ByteArray,
            timeoutMs: Long,
            read: (status: Int, headers: Array<JniHttpHeader>, next: () -> ByteArray?) -> T
        ): T {
            calls++
            lastTimeoutMs = timeoutMs
            val head = response()
            val pending = (if (chunks.isEmpty()) listOf({ head.body }) else chunks).toMutableList()
            return read(head.status, head.headers) { if (pending.isEmpty()) null else pending.removeAt(0)() }
        }

        override suspend fun post(
            url: String,
            headers: List<JniHttpHeader>,
            body: ByteArray,
            timeoutMs: Long
        ): JniHttpResponseBytes {
            calls++
            lastTimeoutMs = timeoutMs
            return response()
        }

        override suspend fun get(
            url: String,
            headers: List<JniHttpHeader>,
            timeoutMs: Long
        ): JniHttpResponseBytes {
            calls++
            lastTimeoutMs = timeoutMs
            return response()
        }

        override suspend fun dispose() = Unit
    }

    private fun jniResponse(
        status: Int,
        body: ByteArray = ByteArray(0)
    ) = JniHttpResponseBytes(status = status, version = "HTTP/1.1", headers = emptyArray(), body = body)

    @Test
    fun `post passes the configured timeout to the native call`(): Unit =
        runBlocking {
            val transport = FakeTransport { jniResponse(200, byteArrayOf(1, 2, 3)) }

            val result =
                TorOhttpSender(transport).post("https://relay.example/relay", byteArrayOf(9), emptyList(), 12_345)

            assertContentEquals(byteArrayOf(1, 2, 3), result)
            assertEquals(12_345L, transport.lastTimeoutMs)
        }

    @Test
    fun `get passes the configured timeout to the native call`(): Unit =
        runBlocking {
            val transport = FakeTransport { jniResponse(200, byteArrayOf(4)) }

            TorOhttpSender(transport).get("https://relay.example/ohttp-configs", emptyList(), 7_000)

            assertEquals(7_000L, transport.lastTimeoutMs)
        }

    @Test
    fun `a native timeout propagates unchanged and is not retried`(): Unit =
        runBlocking {
            val timeout = TorHttpTimeoutException("HTTP request over Tor timed out after 50 ms")
            val transport = FakeTransport { throw timeout }

            val thrown =
                assertFailsWith<TorHttpTimeoutException> {
                    TorOhttpSender(transport).post("https://relay.example/relay", byteArrayOf(), emptyList(), 50)
                }

            assertSame(timeout, thrown)
            assertEquals(1, transport.calls)
        }

    @Test
    fun `a native transport failure propagates unchanged`(): Unit =
        runBlocking {
            val transport = FakeTransport { throw TorHttpTransportException("circuit failed") }

            assertFailsWith<TorHttpTransportException> {
                TorOhttpSender(transport).get("https://relay.example/ohttp-configs", emptyList(), 1_000)
            }
        }

    @Test
    fun `422 ohttp-key response carries status and problem body`(): Unit =
        runBlocking {
            val problem = """{"type":"https://zodl.com/problems/ohttp-key","status":422}"""
            val transport = FakeTransport { jniResponse(422, problem.toByteArray()) }

            val thrown =
                assertFailsWith<OhttpException> {
                    TorOhttpSender(transport).post("https://relay.example/relay", byteArrayOf(), emptyList(), 1_000)
                }

            // OhttpTransport asks the native core about this status to decide on a key refresh; the
            // problem body is only quoted in the message.
            assertEquals(422, thrown.httpStatus)
            assertTrue(assertNotNull(thrown.message).contains("ohttp-key", ignoreCase = true))
        }

    @Test
    fun `a 429 carries the relay's Retry-After`(): Unit =
        runBlocking {
            val transport =
                FakeTransport {
                    JniHttpResponseBytes(
                        status = 429,
                        version = "HTTP/1.1",
                        headers = arrayOf(JniHttpHeader("retry-after", "30")),
                        body = ByteArray(0)
                    )
                }

            val thrown =
                assertFailsWith<OhttpException> {
                    TorOhttpSender(transport).post("https://relay.example/relay", byteArrayOf(), emptyList(), 1_000)
                }

            assertEquals(429, thrown.httpStatus)
            assertEquals(30L, thrown.retryAfterSeconds)
        }

    @Test
    fun `non-200 GET response carries status and body`(): Unit =
        runBlocking {
            val transport = FakeTransport { jniResponse(503, "unavailable".toByteArray()) }

            val thrown =
                assertFailsWith<OhttpException> {
                    TorOhttpSender(transport).get("https://relay.example/ohttp-configs", emptyList(), 1_000)
                }

            assertEquals(503, thrown.httpStatus)
            assertTrue(assertNotNull(thrown.message).contains("unavailable"))
        }

    @Test
    fun `a key config larger than the cap is refused but a sealed response of that size is not`(): Unit =
        runBlocking {
            val oversized = ByteArray(OhttpSender.MAX_KEY_CONFIG_BYTES + 1)
            val transport = FakeTransport { jniResponse(200, oversized) }

            assertFailsWith<OhttpException> {
                TorOhttpSender(transport).get("https://relay.example/ohttp-configs/signed", emptyList(), 1_000)
            }
            assertEquals(
                oversized.size,
                TorOhttpSender(transport).post("https://relay.example/relay", byteArrayOf(), emptyList(), 1_000).size
            )
        }

    @Test
    fun `a streamed answer reaches the reader chunk by chunk as the native body delivers it`(): Unit =
        runBlocking {
            val delivered = mutableListOf<Int>()
            val transport =
                FakeTransport(
                    chunks =
                        listOf(
                            { byteArrayOf(1, 2).also { delivered.add(1) } },
                            { byteArrayOf(3).also { delivered.add(2) } }
                        )
                ) { jniResponse(200) }

            val firstRead =
                TorOhttpSender(transport).postStreaming(RELAY, byteArrayOf(), emptyList(), 9_000) {
                    val first = it.read()
                    val seenBeforeSecond = delivered.toList()
                    Triple(first, seenBeforeSecond, it.readBytes().toList())
                }

            assertEquals(1, firstRead.first)
            assertEquals(listOf(1), firstRead.second)
            assertEquals(listOf<Byte>(2, 3), firstRead.third)
            assertEquals(9_000L, transport.lastTimeoutMs)
        }

    @Test
    fun `a streamed non-200 carries its status and Retry-After and never reaches the reader`(): Unit =
        runBlocking {
            val transport =
                FakeTransport {
                    JniHttpResponseBytes(
                        status = 429,
                        version = "HTTP/1.1",
                        headers = arrayOf(JniHttpHeader("Retry-After", "12")),
                        body = "slow down".toByteArray()
                    )
                }

            val thrown =
                assertFailsWith<OhttpException> {
                    TorOhttpSender(transport).postStreaming(RELAY, byteArrayOf(), emptyList(), 1_000) {
                        error("the reader must not run")
                    }
                }

            assertEquals(429, thrown.httpStatus)
            assertEquals(12L, thrown.retryAfterSeconds)
            assertTrue(assertNotNull(thrown.message).contains("slow down"))
        }

    @Test
    fun `a native timeout or transport failure mid-body fails like the direct sender`(): Unit =
        runBlocking {
            val timeout: () -> ByteArray = { throw TorHttpTimeoutException("timed out") }
            val transportFailure: () -> ByteArray = { throw TorHttpTransportException("circuit closed") }
            val timingOut = FakeTransport(chunks = listOf({ byteArrayOf(1) }, timeout)) { jniResponse(200) }
            val broken = FakeTransport(chunks = listOf({ byteArrayOf(1) }, transportFailure)) { jniResponse(200) }

            assertFailsWith<java.net.SocketTimeoutException> {
                TorOhttpSender(timingOut).postStreaming(RELAY, byteArrayOf(), emptyList(), 1_000) {
                    it.readBytes()
                }
            }
            val thrown =
                assertFailsWith<OhttpException> {
                    TorOhttpSender(broken).postStreaming(RELAY, byteArrayOf(), emptyList(), 1_000) {
                        it.readBytes()
                    }
                }
            assertEquals(null, thrown.httpStatus)
        }

    private companion object {
        const val RELAY = "https://relay.example/relay"
    }
}
