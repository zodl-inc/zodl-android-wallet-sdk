package co.electriccoin.lightwallet.client.internal

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Transport abstraction for the OHTTP relay POST leg, supporting both direct HttpURLConnection
 * and Tor-backed sending. The Tor path is used when Tor is enabled in the app and the target
 * is a gateway-mapped external API (CMC, NEAR) — the relay leg goes over Tor, hiding the
 * app's IP from the relay, and the gateway injects the secret server-side.
 */
interface OhttpSender {
    /**
     * POSTs [body] to [url] with the given [headers] and [timeoutMs], returning the response body.
     * @throws OhttpException on failure (network, HTTP error, timeout).
     */
    suspend fun post(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray

    /**
     * GETs [url] with the given [headers] and [timeoutMs], returning the response body.
     * Used for fetching the gateway key configuration. With Tor enabled, this ensures the key
     * config fetch also goes over Tor, preventing the client IP from being exposed to the gateway.
     * @throws OhttpException on failure (network, HTTP error, timeout).
     */
    suspend fun get(
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray
}

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
                        ?.use { it.readFully() }
                        ?.toString(Charsets.UTF_8)
                        ?.take(ERROR_MESSAGE_MAX_LENGTH)
                throw OhttpException(
                    "Relay/gateway returned HTTP $status${detail?.let { ": $it" } ?: ""}",
                    status,
                )
            }
            return connection.inputStream.use { it.readFully() }
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
                        ?.use { it.readFully() }
                        ?.toString(Charsets.UTF_8)
                        ?.take(ERROR_MESSAGE_MAX_LENGTH)
                throw OhttpException(
                    "GET request failed: HTTP $status${detail?.let { ": $it" } ?: ""}",
                    status,
                )
            }
            return connection.inputStream.use { it.readFully() }
        } finally {
            connection.disconnect()
        }
    }

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

    private fun InputStream.readFully(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val ERROR_MESSAGE_MAX_LENGTH = 200
        private const val READ_BUFFER_SIZE = 16 * 1024
    }
}
