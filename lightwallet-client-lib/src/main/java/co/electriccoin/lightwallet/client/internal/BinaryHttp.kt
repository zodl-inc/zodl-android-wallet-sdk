package co.electriccoin.lightwallet.client.internal

import java.io.ByteArrayOutputStream

/**
 * Binary HTTP (RFC 9292) encoder/decoder — the inner message format used by OHTTP (RFC 9458).
 *
 * Requests are always emitted in known-length form (framing indicator 0). Responses in both
 * known-length (1) and indeterminate-length (3) form are accepted. Integers use the QUIC
 * variable-length encoding (RFC 9000 §16) as mandated by RFC 9292 §1.1.
 */
internal object BinaryHttp {
    private const val FRAMING_KNOWN_LENGTH_REQUEST = 0L
    private const val FRAMING_KNOWN_LENGTH_RESPONSE = 1L
    private const val FRAMING_INDETERMINATE_RESPONSE = 3L

    class Request(
        val method: String,
        val scheme: String,
        val authority: String,
        val path: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray
    )

    class Response(
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
        val trailers: List<Pair<String, String>>
    ) {
        /** Case-insensitive lookup across headers then trailers. */
        fun field(name: String): String? =
            (headers.asSequence() + trailers.asSequence())
                .firstOrNull { it.first.equals(name, ignoreCase = true) }
                ?.second
    }

    fun encodeRequest(request: Request): ByteArray {
        val out = ByteArrayOutputStream(request.body.size + 256)
        writeVarint(out, FRAMING_KNOWN_LENGTH_REQUEST)
        writeLengthPrefixed(out, request.method.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, request.scheme.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, request.authority.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, request.path.toByteArray(Charsets.US_ASCII))
        writeKnownLengthFieldSection(out, request.headers)
        writeLengthPrefixed(out, request.body)
        writeKnownLengthFieldSection(out, emptyList()) // trailers
        return out.toByteArray()
    }

    fun decodeResponse(bytes: ByteArray): Response {
        val reader = Reader(bytes)
        return when (val framing = reader.readVarint()) {
            FRAMING_KNOWN_LENGTH_RESPONSE -> decodeKnownLengthResponse(reader)
            FRAMING_INDETERMINATE_RESPONSE -> decodeIndeterminateResponse(reader)
            else -> throw IllegalArgumentException("Unsupported Binary HTTP framing indicator: $framing")
        }
    }

    private fun decodeKnownLengthResponse(reader: Reader): Response {
        var status: Int
        var headers: List<Pair<String, String>>
        // Skip informational (1xx) responses — RFC 9292 §3.5.1
        do {
            status = reader.readVarint().toInt()
            headers = if (reader.hasRemaining()) readKnownLengthFieldSection(reader) else emptyList()
        } while (status in 100..199 && reader.hasRemaining())

        val body = if (reader.hasRemaining()) reader.readLengthPrefixed() else ByteArray(0)
        val trailers = if (reader.hasRemaining()) readKnownLengthFieldSection(reader) else emptyList()
        return Response(status, headers, body, trailers)
    }

    private fun decodeIndeterminateResponse(reader: Reader): Response {
        var status: Int
        var headers: List<Pair<String, String>>
        do {
            status = reader.readVarint().toInt()
            headers = if (reader.hasRemaining()) readIndeterminateFieldSection(reader) else emptyList()
        } while (status in 100..199 && reader.hasRemaining())

        val body = ByteArrayOutputStream()
        while (reader.hasRemaining()) {
            val chunk = reader.readLengthPrefixed()
            if (chunk.isEmpty()) break
            body.write(chunk)
        }
        val trailers = if (reader.hasRemaining()) readIndeterminateFieldSection(reader) else emptyList()
        return Response(status, headers, body.toByteArray(), trailers)
    }

    private fun writeKnownLengthFieldSection(out: ByteArrayOutputStream, fields: List<Pair<String, String>>) {
        val section = ByteArrayOutputStream()
        for ((name, value) in fields) {
            writeLengthPrefixed(section, name.lowercase().toByteArray(Charsets.US_ASCII))
            writeLengthPrefixed(section, value.toByteArray(Charsets.ISO_8859_1))
        }
        writeLengthPrefixed(out, section.toByteArray())
    }

    private fun readKnownLengthFieldSection(reader: Reader): List<Pair<String, String>> {
        val sectionBytes = reader.readLengthPrefixed()
        val section = Reader(sectionBytes)
        val fields = ArrayList<Pair<String, String>>()
        while (section.hasRemaining()) {
            val name = String(section.readLengthPrefixed(), Charsets.US_ASCII)
            val value = String(section.readLengthPrefixed(), Charsets.ISO_8859_1)
            fields.add(name to value)
        }
        return fields
    }

    private fun readIndeterminateFieldSection(reader: Reader): List<Pair<String, String>> {
        val fields = ArrayList<Pair<String, String>>()
        while (reader.hasRemaining()) {
            val nameBytes = reader.readLengthPrefixed()
            if (nameBytes.isEmpty()) break // content terminator
            val value = String(reader.readLengthPrefixed(), Charsets.ISO_8859_1)
            fields.add(String(nameBytes, Charsets.US_ASCII) to value)
        }
        return fields
    }

    private fun writeLengthPrefixed(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    /** QUIC variable-length integer (RFC 9000 §16). */
    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "varint must be non-negative" }
        when {
            value < (1L shl 6) -> out.write(value.toInt())
            value < (1L shl 14) -> {
                out.write(((value ushr 8) or 0x40).toInt())
                out.write((value and 0xFF).toInt())
            }
            value < (1L shl 30) -> {
                out.write(((value ushr 24) or 0x80).toInt())
                out.write(((value ushr 16) and 0xFF).toInt())
                out.write(((value ushr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }
            value < (1L shl 62) -> {
                out.write(((value ushr 56) or 0xC0).toInt())
                for (shift in 48 downTo 0 step 8) {
                    out.write(((value ushr shift) and 0xFF).toInt())
                }
            }
            else -> throw IllegalArgumentException("varint too large: $value")
        }
    }

    class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun hasRemaining(): Boolean = pos < bytes.size

        private fun readByte(): Int {
            if (pos >= bytes.size) throw IllegalArgumentException("Truncated Binary HTTP message")
            return bytes[pos++].toInt() and 0xFF
        }

        fun readVarint(): Long {
            val first = readByte()
            val length = 1 shl (first ushr 6)
            var value = (first and 0x3F).toLong()
            for (i in 1 until length) {
                value = (value shl 8) or readByte().toLong()
            }
            return value
        }

        fun readLengthPrefixed(): ByteArray {
            val length = readVarint()
            if (length > (bytes.size - pos)) throw IllegalArgumentException("Truncated Binary HTTP field")
            val out = bytes.copyOfRange(pos, pos + length.toInt())
            pos += length.toInt()
            return out
        }
    }
}
