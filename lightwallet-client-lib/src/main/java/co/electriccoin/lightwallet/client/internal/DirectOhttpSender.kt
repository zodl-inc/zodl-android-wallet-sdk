package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Default OHTTP sender: direct HttpURLConnection. Used when Tor is OFF.
 */
class DirectOhttpSender : OhttpSender {
    override suspend fun post(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray {
        val connection = open(url, timeoutMs)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val detail =
                    connection.errorStream
                        ?.use { it.readFully(MAX_ERROR_BODY_BYTES) }
                        ?.toString(Charsets.UTF_8)
                        ?.take(ERROR_MESSAGE_MAX_LENGTH)
                throw OhttpException(
                    "Relay/gateway returned HTTP $status${detail?.let { ": $it" } ?: ""}",
                    status,
                )
            }
            return connection.inputStream.use {
                @Suppress("TooGenericExceptionCaught")
                try {
                    it.readFully(OhttpSender.MAX_SEALED_RESPONSE_BYTES, connection.contentLength)
                } catch (e: OutOfMemoryError) {
                    // A hostile or huge response: convert to OhttpException so the transport
                    // handles it as a failed request instead of crashing the app.
                    throw OhttpException("Response too large: out of memory reading sealed response", cause = e)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun get(
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray {
        val connection = open(url, timeoutMs)
        try {
            connection.requestMethod = "GET"
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val detail =
                    connection.errorStream
                        ?.use { it.readFully(MAX_ERROR_BODY_BYTES) }
                        ?.toString(Charsets.UTF_8)
                        ?.take(ERROR_MESSAGE_MAX_LENGTH)
                throw OhttpException(
                    "GET request failed: HTTP $status${detail?.let { ": $it" } ?: ""}",
                    status,
                )
            }
            return connection.inputStream.use {
                @Suppress("TooGenericExceptionCaught")
                try {
                    it.readFully(OhttpSender.MAX_KEY_CONFIG_BYTES, connection.contentLength)
                } catch (e: OutOfMemoryError) {
                    throw OhttpException("Key config too large: out of memory", cause = e)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * No-op: each connection is closed in its own request's `finally { connection.disconnect() }`, so
     * there is no cross-request resource to release.
     */
    override suspend fun dispose() = Unit

    private fun open(
        url: String,
        timeoutMs: Int,
    ): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = timeoutMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        return connection
    }

    /**
     * Reads the whole stream, refusing past [maxBytes]. The relay/gateway is untrusted (a
     * malicious or compromised one could return an arbitrarily large body regardless of what was
     * requested), so this must never buffer without a bound. The hard cap is 256 MiB + 64 KiB
     * ([OhttpSender.MAX_SEALED_RESPONSE_BYTES]): the gateway legitimately returns >64 MiB
     * GetBlockRange responses for the 2022 spam blocks, so the cap cannot be lowered.
     */
    private fun InputStream.readFully(
        maxBytes: Int,
        contentLength: Int = -1
    ): ByteArray {
        // When Content-Length is known and within the cap, read directly into a pre-sized
        // ByteArray to avoid the extra copy that ByteArrayOutputStream.toByteArray() makes.
        if (contentLength in 1..maxBytes) {
            val result = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val n = read(result, offset, contentLength - offset)
                if (n < 0) {
                    throw OhttpException("Stream ended before reading $contentLength bytes (got $offset)")
                }
                offset += n
            }
            // Verify the stream is actually exhausted (no extra bytes beyond Content-Length).
            if (read() >= 0) {
                throw OhttpException("Response exceeded Content-Length of $contentLength bytes")
            }
            return result
        }

        // Content-Length unknown or invalid: use ByteArrayOutputStream with size limit.
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw OhttpException("Response exceeded the $maxBytes byte limit")
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val ERROR_MESSAGE_MAX_LENGTH = 200
        private const val READ_BUFFER_SIZE = 16 * 1024

        // Only the first ERROR_MESSAGE_MAX_LENGTH characters of an error body are quoted, but the
        // whole stream is drained up to this bound (unchanged from before the shared OHTTP core).
        private const val MAX_ERROR_BODY_BYTES = 64 * 1024 * 1024
    }
}
