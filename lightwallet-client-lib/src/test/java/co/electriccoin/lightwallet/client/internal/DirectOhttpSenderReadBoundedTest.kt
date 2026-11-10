package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertFailsWith

class DirectOhttpSenderReadBoundedTest {
    /** Records the largest destination window ever offered to `read`, i.e. the buffer size in use. */
    private class WindowRecordingStream(
        private val data: ByteArray,
    ) : InputStream() {
        private var pos = 0
        var maxWindow = 0

        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            maxWindow = maxOf(maxWindow, b.size)
            if (pos >= data.size) return -1
            val n = minOf(len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun hostile_huge_content_length_does_not_allocate_declared_size() {
        val stream = WindowRecordingStream(ByteArray(10))

        // A declared length that is never delivered is reported as truncated...
        assertFailsWith<OhttpException> {
            stream.readBounded(maxBytes = 256 * 1024 * 1024, contentLengthHint = 200 * 1024 * 1024)
        }

        // ...and the declared size was never allocated up front.
        assertTrue(stream.maxWindow <= MAX_INITIAL_BUFFER_BYTES)
    }

    @Test
    fun body_longer_than_content_length_is_read_in_full() {
        val body = ByteArray(5_000) { (it % 251).toByte() }

        val result = ByteArrayInputStream(body).readBounded(maxBytes = 1_000_000, contentLengthHint = 100)

        assertArrayEquals(body, result)
    }

    @Test
    fun body_larger_than_initial_buffer_grows_and_is_intact() {
        val body = ByteArray(MAX_INITIAL_BUFFER_BYTES * 2 + 123) { (it % 247).toByte() }

        val result =
            ByteArrayInputStream(body).readBounded(maxBytes = body.size + 10, contentLengthHint = body.size)

        assertArrayEquals(body, result)
    }

    @Test
    fun exceeding_max_bytes_is_rejected() {
        val body = ByteArray(101)

        assertFailsWith<OhttpException> { ByteArrayInputStream(body).readBounded(maxBytes = 100) }
        assertFailsWith<OhttpException> {
            ByteArrayInputStream(body).readBounded(maxBytes = 100, contentLengthHint = 50)
        }
    }

    @Test
    fun body_of_exactly_max_bytes_is_accepted() {
        val body = ByteArray(100) { it.toByte() }

        assertArrayEquals(body, ByteArrayInputStream(body).readBounded(maxBytes = 100, contentLengthHint = 100))
    }

    @Test
    fun truncated_body_against_valid_content_length_is_rejected() {
        assertFailsWith<OhttpException> {
            ByteArrayInputStream(ByteArray(10)).readBounded(maxBytes = 1_000, contentLengthHint = 500)
        }
    }

    @Test
    fun honest_large_body_with_truthful_content_length_never_exceeds_its_own_size() {
        val body = ByteArray(MAX_INITIAL_BUFFER_BYTES * 3 + 123) { (it % 239).toByte() }
        val stream = WindowRecordingStream(body)

        val result = stream.readBounded(maxBytes = body.size * 2, contentLengthHint = body.size)

        assertArrayEquals(body, result)
        // The buffer grew to the declared length once half of it had arrived, never to twice it.
        assertTrue(stream.maxWindow <= body.size)
    }

    @Test
    fun boundary_above_the_initial_buffer_is_exact() {
        val max = MAX_INITIAL_BUFFER_BYTES * 2 + 7
        val exact = ByteArray(max) { (it % 233).toByte() }

        assertArrayEquals(exact, ByteArrayInputStream(exact).readBounded(maxBytes = max, contentLengthHint = max))
        assertArrayEquals(exact, ByteArrayInputStream(exact).readBounded(maxBytes = max))
        assertFailsWith<OhttpException> {
            ByteArrayInputStream(ByteArray(max + 1)).readBounded(maxBytes = max, contentLengthHint = max)
        }
        assertFailsWith<OhttpException> { ByteArrayInputStream(ByteArray(max + 1)).readBounded(maxBytes = max) }
    }
}
