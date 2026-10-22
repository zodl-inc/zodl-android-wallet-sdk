package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default OHTTP sender: direct OkHttp exchanges with the relay. Used when Tor is OFF.
 *
 * Relay connections are pooled and reused across requests and across senders ([RelayConnections]: one
 * HTTP/2 connection multiplexes concurrent exchanges when the relay negotiates it), and never reused
 * past [RelayConnections.MAX_CONNECTION_AGE_MINUTES], the ZIP's reference rotation for Tor-off mode and
 * slipstream's `H2_MAX_CONNECTION_AGE`. Only the headers the caller passes are sent (plus what OkHttp
 * adds for a transparent response encoding); redirects are not followed.
 *
 * `timeoutMs` bounds each read and, through a watchdog that cancels the call, the whole exchange: a
 * relay that keeps dribbling bytes cannot hold a call open past it. Cancelling the caller cancels the
 * call too, which closes the connection under it (a pooled connection is simply not returned to the
 * pool).
 */
class DirectOhttpSender internal constructor(
    private val connections: RelayConnections,
) : OhttpSender {
    constructor() : this(RelayConnections.shared)

    override suspend fun post(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray =
        postWith(url, body, headers, timeoutMs) { answer ->
            answer.bodyStream().use {
                @Suppress("TooGenericExceptionCaught")
                try {
                    it.readBounded(OhttpSender.MAX_SEALED_RESPONSE_BYTES, answer.contentLengthHint())
                } catch (e: OutOfMemoryError) {
                    // A hostile or huge response: convert to OhttpException so the transport
                    // handles it as a failed request instead of crashing the app.
                    throw OhttpException("Response too large: out of memory reading sealed response", cause = e)
                }
            }
        }

    override suspend fun <T> postStreaming(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
        read: (InputStream) -> T,
    ): T = postWith(url, body, headers, timeoutMs) { answer -> answer.bodyStream().use(read) }

    override suspend fun get(
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray =
        exchange(timeoutMs, getRequest(url, headers)) { answer ->
            if (answer.code != HTTP_OK) {
                throw OhttpException(
                    "GET request failed: HTTP ${answer.code}${answer.errorDetail()}",
                    answer.code,
                    retryAfterSeconds = OhttpSender.retryAfterSeconds(answer.header(RETRY_AFTER_HEADER)),
                )
            }
            answer.bodyStream().use {
                @Suppress("TooGenericExceptionCaught")
                try {
                    it.readBounded(OhttpSender.MAX_KEY_CONFIG_BYTES, answer.contentLengthHint())
                } catch (e: OutOfMemoryError) {
                    throw OhttpException("Key config too large: out of memory", cause = e)
                }
            }
        }

    /** The POST, a non-200 thrown as [OhttpException]; [read] gets the answer of a 200. */
    private suspend fun <T> postWith(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
        read: (Answer) -> T,
    ): T =
        exchange(timeoutMs, postRequest(url, body, headers)) { answer ->
            if (answer.code != HTTP_OK) {
                throw OhttpException(
                    "Relay/gateway returned HTTP ${answer.code}${answer.errorDetail()}",
                    answer.code,
                    retryAfterSeconds = OhttpSender.retryAfterSeconds(answer.header(RETRY_AFTER_HEADER)),
                )
            }
            read(answer)
        }

    /**
     * One exchange on a pooled connection: [read] gets the answer, whose response is closed afterwards (a
     * body [read] did not finish is discarded with its connection). Whatever the outcome, the pools
     * retired by a rotation are swept, so a connection that outlived its generation in flight is closed
     * now.
     */
    private suspend fun <T> exchange(
        timeoutMs: Int,
        request: Request.Builder,
        read: (Answer) -> T,
    ): T {
        val call = connections.client(timeoutMs).newCall(request.build())
        try {
            return closingOnCancel(call) {
                withDeadline(call, timeoutMs) { expired ->
                    call.execute().use { response -> read(Answer(response, timeoutMs, expired)) }
                }
            }
        } finally {
            connections.reapRetired()
        }
    }

    /**
     * No-op: the relay connections are shared by every sender of the process ([RelayConnections.shared])
     * and outlive any one of them; idle ones close at the pool's keep-alive, all of them at rotation.
     */
    override suspend fun dispose() = Unit

    /**
     * Runs [block] (blocking I/O of [call]) so that cancelling the calling coroutine cancels the call
     * under it: a blocked read then fails at once instead of holding the exchange open.
     */
    private suspend inline fun <T> closingOnCancel(
        call: Call,
        crossinline block: () -> T,
    ): T =
        coroutineScope {
            val closer =
                launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
            try {
                block()
            } finally {
                closer.cancel()
            }
        }

    /**
     * Runs [block] (all of the call's I/O) within [timeoutMs]. When the deadline passes, the call is
     * cancelled under it, and the I/O error that follows is reported as a timeout. [block] gets the
     * flag the watchdog raises, for the body reads that see the cancelled call as an end of stream
     * rather than an error (see [Answer.bodyStream]).
     */
    private inline fun <T> withDeadline(
        call: Call,
        timeoutMs: Int,
        block: (expired: AtomicBoolean) -> T,
    ): T {
        val expired = AtomicBoolean(false)
        val watchdog =
            deadlines.schedule(
                {
                    expired.set(true)
                    call.cancel()
                },
                timeoutMs.toLong(),
                TimeUnit.MILLISECONDS,
            )
        try {
            return block(expired)
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (e: IOException) {
            throw if (expired.get()) exchangeTimeout(timeoutMs, e) else e
        } finally {
            watchdog.cancel(false)
        }
    }

    /**
     * The response of one exchange. Its body is read through the exchange's deadline: a call the
     * watchdog cancelled surfaces as a clean end of the body on OkHttp (the socket is simply closed), which
     * a caller would otherwise report as a truncated or short response a few milliseconds before its own
     * deadline runs out; here it is the [SocketTimeoutException] the exchange timeout is.
     */
    private class Answer(
        private val response: Response,
        private val timeoutMs: Int,
        private val expired: AtomicBoolean,
    ) {
        val code: Int get() = response.code

        fun header(name: String): String? = response.header(name)

        fun bodyStream(): InputStream =
            DeadlineGuardedInput(checkNotNull(response.body).byteStream(), timeoutMs, expired)

        /** The relay-declared Content-Length as the `Int` hint [readBounded] takes: -1 when unknown or absurd. */
        fun contentLengthHint(): Int {
            val length = checkNotNull(response.body).contentLength()
            return if (length in 1..Int.MAX_VALUE.toLong()) length.toInt() else -1
        }

        /**
         * The start of an error page, for the message of the exception that carries its status. Read within
         * the fixed [MAX_ERROR_BODY_BYTES] and cut there (a longer page is not read on), and a page that
         * cannot be read gives nothing: the status is the answer, so neither its length nor a failed read
         * replaces it.
         */
        fun errorDetail(): String =
            runCatching { bodyStream().readPrefix(MAX_ERROR_BODY_BYTES) }
                .getOrNull()
                ?.toString(Charsets.UTF_8)
                ?.take(ERROR_MESSAGE_MAX_LENGTH)
                ?.takeIf { it.isNotEmpty() }
                ?.let { ": $it" }
                .orEmpty()
    }

    /** A body stream whose end, or error, after the exchange deadline passed is the exchange timeout. */
    private class DeadlineGuardedInput(
        delegate: InputStream,
        private val timeoutMs: Int,
        private val expired: AtomicBoolean,
    ) : FilterInputStream(delegate) {
        override fun read(): Int = guarded { `in`.read() }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = guarded { `in`.read(b, off, len) }

        private inline fun guarded(read: () -> Int): Int {
            val n =
                try {
                    read()
                } catch (e: SocketTimeoutException) {
                    throw e
                } catch (e: IOException) {
                    throw if (expired.get()) exchangeTimeout(timeoutMs, e) else e
                }
            return endOrTimeout(n)
        }

        /** An end of stream ([n] < 0) after the deadline passed is the timeout; anything else is passed on. */
        private fun endOrTimeout(n: Int): Int =
            if (n < 0 && expired.get()) throw exchangeTimeout(timeoutMs, null) else n
    }

    companion object {
        private const val HTTP_OK = 200
        private const val ERROR_MESSAGE_MAX_LENGTH = 200
        private const val RETRY_AFTER_HEADER = "Retry-After"

        // The most read of an error page (a non-200 answer), whatever the caller's cap for a body: enough
        // for the ERROR_MESSAGE_MAX_LENGTH characters quoted. The same fixed 64 KiB guard as slipstream's
        // `read_error_body`, so an oversized error page never turns a 429 or 503 into a size error.
        private const val MAX_ERROR_BODY_BYTES = 64 * 1024

        private val deadlines =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ohttp-deadline").apply { isDaemon = true }
            }
    }
}

private fun getRequest(
    url: String,
    headers: List<Pair<String, String>>,
): Request.Builder =
    Request
        .Builder()
        .url(url)
        .get()
        .withHeaders(headers)

private fun postRequest(
    url: String,
    body: ByteArray,
    headers: List<Pair<String, String>>,
): Request.Builder =
    Request
        .Builder()
        .url(url)
        .post(body.toRequestBody())
        .withHeaders(headers)

private fun Request.Builder.withHeaders(headers: List<Pair<String, String>>): Request.Builder =
    apply { headers.forEach { (key, value) -> addHeader(key, value) } }

/** The exchange timeout as reported to the caller, [cause] being the I/O error the cancelled call produced. */
private fun exchangeTimeout(
    timeoutMs: Int,
    cause: IOException?,
): SocketTimeoutException =
    SocketTimeoutException("OHTTP exchange exceeded $timeoutMs ms").apply { cause?.let(::initCause) }

/** The first [maxBytes] of this stream, or all of it when shorter: never an error for a longer one. */
private fun InputStream.readPrefix(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var total = 0
    while (total < maxBytes) {
        val n = read(buffer, total, maxBytes - total)
        if (n < 0) break
        total += n
    }
    return buffer.copyOf(total)
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
