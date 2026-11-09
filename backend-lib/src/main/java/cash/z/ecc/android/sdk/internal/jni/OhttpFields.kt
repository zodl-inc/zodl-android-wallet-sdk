package cash.z.ecc.android.sdk.internal.jni

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The packed header list of the OHTTP JNI surface: each name and value as a 4-byte big-endian length
 * and the bytes, in order (`len(name) name len(value) value ...`), the format of `pack_fields` /
 * `unpack_fields` of the native core. One `ByteArray` crosses the boundary instead of two per header.
 *
 * Request names are written as ASCII and values as ISO-8859-1 (characters outside the encoding
 * become `?`); response fields are read as ISO-8859-1, as the Binary HTTP bytes carry them.
 */
internal object OhttpFields {
    private const val LENGTH_BYTES = 4

    fun pack(fields: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, value) in fields) {
            writePart(out, name.toByteArray(Charsets.US_ASCII))
            writePart(out, value.toByteArray(Charsets.ISO_8859_1))
        }
        return out.toByteArray()
    }

    /** @throws IllegalArgumentException if [packed] is not a well-formed list */
    fun unpack(packed: ByteArray): List<Pair<String, String>> {
        val buffer = ByteBuffer.wrap(packed)
        val parts = ArrayList<String>()
        while (buffer.hasRemaining()) {
            require(buffer.remaining() >= LENGTH_BYTES) { "Truncated field length" }
            val length = buffer.getInt()
            require(length >= 0 && length <= buffer.remaining()) { "Truncated field" }
            val bytes = ByteArray(length)
            buffer.get(bytes)
            parts.add(String(bytes, Charsets.ISO_8859_1))
        }
        require(parts.size % 2 == 0) { "A name without a value" }
        return parts.chunked(2) { (name, value) -> name to value }
    }

    private fun writePart(
        out: ByteArrayOutputStream,
        bytes: ByteArray,
    ) {
        out.write(ByteBuffer.allocate(LENGTH_BYTES).putInt(bytes.size).array())
        out.write(bytes)
    }
}
