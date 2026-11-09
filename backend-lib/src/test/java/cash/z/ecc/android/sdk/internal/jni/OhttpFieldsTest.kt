package cash.z.ecc.android.sdk.internal.jni

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OhttpFieldsTest {
    @Test
    fun `an empty list packs to nothing`() {
        assertContentEquals(ByteArray(0), OhttpFields.pack(emptyList()))
        assertEquals(emptyList(), OhttpFields.unpack(ByteArray(0)))
    }

    @Test
    fun `fields are 4-byte big-endian length prefixed names and values in order`() {
        val packed = OhttpFields.pack(listOf("te" to "trailers", "x" to ""))

        assertContentEquals(
            byteArrayOf(0, 0, 0, 2, 't'.code.toByte(), 'e'.code.toByte()) +
                byteArrayOf(0, 0, 0, 8) + "trailers".toByteArray() +
                byteArrayOf(0, 0, 0, 1, 'x'.code.toByte()) +
                byteArrayOf(0, 0, 0, 0),
            packed
        )
    }

    @Test
    fun `packed fields round trip, values as ISO-8859-1`() {
        val fields = listOf("content-type" to "application/grpc", "grpc-message" to "café")

        assertEquals(fields, OhttpFields.unpack(OhttpFields.pack(fields)))
    }

    @Test
    fun `characters outside the encodings are replaced, not thrown on`() {
        val unpacked = OhttpFields.unpack(OhttpFields.pack(listOf("né" to "€")))

        assertEquals(listOf("n?" to "?"), unpacked)
    }

    @Test
    fun `a malformed list is an IllegalArgumentException`() {
        val malformed =
            listOf(
                // Truncated length.
                byteArrayOf(0, 0, 1),
                // Length past the end.
                byteArrayOf(0, 0, 0, 9, 1),
                // Negative length.
                byteArrayOf(-1, -1, -1, -1),
                // A name without a value.
                byteArrayOf(0, 0, 0, 1, 'a'.code.toByte())
            )
        malformed.forEach { bytes ->
            assertFailsWith<IllegalArgumentException> { OhttpFields.unpack(bytes) }
        }
    }
}
