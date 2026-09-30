package co.electriccoin.lightwallet.client.internal

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
                        ?.use { it.readFully(MAX_RESPONSE_SIZE_BYTES) }
                        ?.toString(Charsets.UTF_8)
                        ?.take(ERROR_MESSAGE_MAX_LENGTH)
                throw OhttpException(
                    "Relay/gateway returned HTTP $status${detail?.let { ": $it" } ?: ""}",
                    status,
                )
            }
            return connection.inputStream.use { it.readFully(MAX_RESPONSE_SIZE_BYTES) }
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
                        ?.use { it.readFully(MAX_RESPONSE_SIZE_BYTES) }
                        ?.toString(Charsets.UTF_8)
                        ?.take(ERROR_MESSAGE_MAX_LENGTH)
                throw OhttpException(
                    "GET request failed: HTTP $status${detail?.let { ": $it" } ?: ""}",
                    status,
                )
            }
            return connection.inputStream.use { it.readFully(MAX_RESPONSE_SIZE_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    // Each connection is already closed in its own request's `finally { connection.disconnect() }`
    // -- there is no cross-request resource to release, unlike TorOhttpSender's wrapped TorClient.
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
     * requested), so this must never buffer without a bound — the bulk-sync path in particular
     * relies on this to not OOM the client.
     */
    private fun InputStream.readFully(maxBytes: Int): ByteArray {
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

        // Generous enough for the largest legitimate response (a bulk GetBlockRange chunk,
        // capped at 2k blocks -- see slipstream-internal's TOR_OHTTP_MAX_CHUNK_BLOCKS), while
        // still bounding memory against a malicious/compromised relay or gateway.
        private const val MAX_RESPONSE_SIZE_BYTES = 64 * 1024 * 1024
    }
}
