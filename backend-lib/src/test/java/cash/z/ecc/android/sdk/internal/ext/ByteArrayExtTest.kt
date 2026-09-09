package cash.z.ecc.android.sdk.internal.ext

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ByteArrayExtTest {
    @Test
    fun clearContents_zeroes_the_array() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        bytes.clearContents()

        assertContentEquals(ByteArray(5), bytes)
    }

    @Test
    fun useAndClear_zeroes_the_array_after_returning_the_block_result() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        val result = bytes.useAndClear { it.sum() }

        assertEquals(15, result)
        assertContentEquals(ByteArray(5), bytes)
    }

    @Test
    fun useAndClear_zeroes_the_array_when_the_block_throws() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        assertFailsWith(IllegalStateException::class) {
            bytes.useAndClear { error("boom") }
        }

        assertContentEquals(ByteArray(5), bytes)
    }

    @Test
    fun useAndClear_exposes_the_receiver_to_the_block() {
        val bytes = byteArrayOf(1, 2, 3)
        var sawOriginalContents = false

        bytes.useAndClear {
            sawOriginalContents = it.contentEquals(byteArrayOf(1, 2, 3))
        }

        assertTrue(sawOriginalContents)
    }
}
