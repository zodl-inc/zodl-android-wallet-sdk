package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default OHTTP sender: direct HttpURLConnection. Used when Tor is OFF.
 *
 * `timeoutMs` bounds each read and, through a watchdog that closes the connection, the whole
 * exchange: a relay that keeps dribbling bytes cannot hold a call open past it. Every request uses a
 * connection of its own (`Connection: close`).
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
            return withDeadline(connection, timeoutMs) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                connection.outputStream.use { it.write(body) }

                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw OhttpException(
                        "Relay/gateway returned HTTP $status${connection.errorDetail()}",
                        status,
                        retryAfterSeconds =
                            OhttpSender.retryAfterSeconds(connection.getHeaderField(RETRY_AFTER_HEADER)),
                    )
                }
                connection.inputStream.use {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        it.readBounded(OhttpSender.MAX_SEALED_RESPONSE_BYTES, connection.contentLength)
                    } catch (e: OutOfMemoryError) {
                        // A hostile or huge response: convert to OhttpException so the transport
                        // handles it as a failed request instead of crashing the app.
                        throw OhttpException("Response too large: out of memory reading sealed response", cause = e)
                    }
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
            return withDeadline(connection, timeoutMs) {
                connection.requestMethod = "GET"
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw OhttpException(
                        "GET request failed: HTTP $status${connection.errorDetail()}",
                        status,
                        retryAfterSeconds =
                            OhttpSender.retryAfterSeconds(connection.getHeaderField(RETRY_AFTER_HEADER)),
                    )
                }
                connection.inputStream.use {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        it.readBounded(OhttpSender.MAX_KEY_CONFIG_BYTES, connection.contentLength)
                    } catch (e: OutOfMemoryError) {
                        throw OhttpException("Key config too large: out of memory", cause = e)
                    }
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
        connection.connectTimeout = minOf(CONNECT_TIMEOUT_MS, timeoutMs)
        connection.readTimeout = timeoutMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        // No keep-alive: a pooled connection to the relay could otherwise live as long as the app, and
        // its lifetime is linkable metadata the relay sees (ZIP: SHOULD NOT with Tor mode off).
        connection.setRequestProperty("Connection", "close")
        return connection
    }

    private fun HttpURLConnection.errorDetail(): String =
        errorStream
            ?.use { it.readBounded(MAX_ERROR_BODY_BYTES) }
            ?.toString(Charsets.UTF_8)
            ?.take(ERROR_MESSAGE_MAX_LENGTH)
            ?.let { ": $it" }
            .orEmpty()

    /**
     * Runs [block] (all of the connection's I/O) within [timeoutMs]. When the deadline passes, the
     * connection is closed under it, and the I/O error that follows is reported as a timeout.
     */
    private inline fun <T> withDeadline(
        connection: HttpURLConnection,
        timeoutMs: Int,
        block: () -> T,
    ): T {
        val expired = AtomicBoolean(false)
        val watchdog =
            deadlines.schedule(
                {
                    expired.set(true)
                    connection.disconnect()
                },
                timeoutMs.toLong(),
                TimeUnit.MILLISECONDS,
            )
        try {
            return block()
        } catch (e: IOException) {
            if (expired.get()) {
                throw SocketTimeoutException("OHTTP exchange exceeded $timeoutMs ms").apply { initCause(e) }
            }
            throw e
        } finally {
            watchdog.cancel(false)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val ERROR_MESSAGE_MAX_LENGTH = 200
        private const val RETRY_AFTER_HEADER = "Retry-After"

        // Only the first ERROR_MESSAGE_MAX_LENGTH characters of an error body are quoted, but the
        // whole stream is drained up to this bound (unchanged from before the shared OHTTP core).
        private const val MAX_ERROR_BODY_BYTES = 64 * 1024 * 1024

        private val deadlines =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ohttp-deadline").apply { isDaemon = true }
            }
    }
}

/** Upper bound for the initial buffer, whatever the relay-declared Content-Length claims. */
internal const val MAX_INITIAL_BUFFER_BYTES = 1024 * 1024

/**
 * Reads the whole stream, refusing past [maxBytes]. The relay/gateway is untrusted (a
 * malicious or compromised one could return an arbitrarily large body regardless of what was
 * requested), so this must never buffer without a bound. The hard cap is 256 MiB + 64 KiB
 * ([OhttpSender.MAX_SEALED_RESPONSE_BYTES]): the gateway legitimately returns >64 MiB
 * GetBlockRange responses for the 2022 spam blocks, so the cap cannot be lowered.
 *
 * [contentLengthHint] is relay-supplied and therefore only a hint: the initial buffer is at most
 * [MAX_INITIAL_BUFFER_BYTES] and then grows by doubling as bytes actually arrive (see [growFor]). A body longer
 * than the hint is read in full (up to [maxBytes]); a body shorter than a valid hint is reported
 * as truncated.
 */
internal fun InputStream.readBounded(
    maxBytes: Int,
    contentLengthHint: Int = -1,
): ByteArray {
    val expected = if (contentLengthHint in 1..maxBytes) contentLengthHint else -1
    val initialSize =
        if (expected > 0) minOf(expected, MAX_INITIAL_BUFFER_BYTES) else DEFAULT_INITIAL_BUFFER_BYTES
    var buffer = ByteArray(initialSize)
    var total = 0
    var done = false
    while (!done) {
        if (total == buffer.size) {
            // Full buffer: probe one byte before growing, so a body that ends exactly here (the
            // common case with a truthful Content-Length) never pays for a second, larger buffer.
            val next = read()
            if (next < 0) {
                done = true
            } else {
                buffer = buffer.growFor(maxBytes, expected, total)
                buffer[total++] = next.toByte()
            }
        } else {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) done = true else total += n
        }
        if (total > maxBytes) failResponseTooLarge(maxBytes)
    }
    if (expected > 0 && total < expected) {
        throw OhttpException("Stream ended before reading $expected bytes (got $total)")
    }
    return if (total == buffer.size) buffer else buffer.copyOf(total)
}

private const val DEFAULT_INITIAL_BUFFER_BYTES = 16 * 1024

/**
 * Grows a full buffer. Doubling is clamped to maxBytes (+1 so that one extra byte past the cap is
 * still detected); a buffer that already holds maxBytes + 1 bytes' worth of room means the cap is
 * exceeded. Once the relay has really delivered at least half of a valid Content-Length
 * ([expected]), jump straight to that length so an honest large body peaks at its own size instead
 * of at twice it; a hostile huge hint therefore still costs the relay as many real bytes as it
 * makes us allocate.
 */
private fun ByteArray.growFor(
    maxBytes: Int,
    expected: Int,
    total: Int,
): ByteArray {
    val grown =
        if (expected > size && total.toLong() * 2 >= expected) {
            expected
        } else {
            minOf(size.toLong() * 2, maxBytes.toLong() + 1).toInt()
        }
    if (grown <= size) failResponseTooLarge(maxBytes)
    return copyOf(grown)
}

private fun failResponseTooLarge(maxBytes: Int): Nothing =
    throw OhttpException("Response exceeded the $maxBytes byte limit")
