package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.JniHttpHeader
import cash.z.ecc.android.sdk.internal.model.JniHttpResponseBytes
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.TorHttpTimeoutException
import cash.z.ecc.android.sdk.internal.model.TorHttpTransportException
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/**
 * Tor-backed OHTTP sender: routes the relay POST through an isolated Tor circuit. Used when
 * Tor is ON and the request targets a gateway-mapped external API (CMC, NEAR), ensuring the
 * relay never sees the app's clearnet IP. The gateway injects the API secret server-side. The
 * lightwalletd calls of a Tor-mode OHTTP channel use [postStreaming], so their answers are opened
 * as they arrive over the circuit.
 *
 * The `timeoutMs` of each call is an overall deadline enforced inside the native call (its
 * [cash.z.ecc.android.sdk.internal.model.TorHttpTimeoutException] is thrown as a
 * [SocketTimeoutException]); a Kotlin-side `withTimeout` could not interrupt the blocking JNI call.
 */
class TorOhttpSender internal constructor(
    private val transport: TorHttpTransport
) : OhttpSender {
    constructor(torClient: TorClient) : this(TorClientTransport(torClient))

    override val viaTor: Boolean
        get() = true

    override suspend fun post(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int
    ): ByteArray {
        // ZIP #10: Implemented on top of postStreaming() so the status is known before reading
        // the body, allowing us to bound non-2xx responses to 64 KiB while reading (not after).
        val jniHeaders = headers.map { (key, value) -> JniHttpHeader(key, value) }
        return asTransportFailure {
            transport.postStreaming(url, jniHeaders, body, timeoutMs.toLong()) { status, responseHeaders, next ->
                // Status is known before any body bytes are pulled: choose the read limit now.
                val maxBytes =
                    if (status == HttpURLConnection.HTTP_OK) {
                        OhttpSender.MAX_SEALED_RESPONSE_BYTES
                    } else {
                        MAX_ERROR_BODY_BYTES
                    }
                val input = ChunkInputStream(next, maxBytes)
                if (status != HttpURLConnection.HTTP_OK) {
                    // Read at most 64 KiB of the error body. The ChunkInputStream refuses past
                    // that limit, so the native stream is not pulled beyond it.
                    val errorBody =
                        runCatching { input.readBytes() }.getOrElse { e ->
                            // A size error (maxBytes exceeded) means the input stopped at exactly the cap;
                            // report the HTTP status, not the size error. Other errors rethrown.
                            if (e is OhttpException && e.message?.contains("exceeded") == true) {
                                ByteArray(0)
                            } else {
                                throw e
                            }
                        }
                    val retryAfter =
                        responseHeaders.firstOrNull { it.key.equals(RETRY_AFTER_HEADER, ignoreCase = true) }?.value
                    throw OhttpException(
                        "Relay/gateway returned HTTP $status (Tor path)${errorBody.asDetail()}",
                        status,
                        retryAfterSeconds = OhttpSender.retryAfterSeconds(retryAfter)
                    )
                }
                // 200: read the sealed response, up to MAX_SEALED_RESPONSE_BYTES.
                input.readBytes()
            }
        }
    }

    /** The body of a 200 reaches [read] as Tor delivers it. */
    override suspend fun <T> postStreaming(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
        read: (InputStream) -> T
    ): T {
        val jniHeaders = headers.map { (key, value) -> JniHttpHeader(key, value) }
        return asTransportFailure {
            transport.postStreaming(url, jniHeaders, body, timeoutMs.toLong()) { status, responseHeaders, next ->
                // ZIP #10: error responses (non-2xx) are bounded to 64 KiB as they are read.
                val maxBytes =
                    if (status == HttpURLConnection.HTTP_OK) {
                        OhttpSender.MAX_SEALED_RESPONSE_BYTES
                    } else {
                        MAX_ERROR_BODY_BYTES
                    }
                val input = ChunkInputStream(next, maxBytes)
                if (status != HttpURLConnection.HTTP_OK) {
                    // The status and Retry-After are known from the head; the diagnostic body is read
                    // best-effort, so one that stalls or breaks off does not lose them. The input is
                    // already bounded to 64 KiB by the ChunkInputStream maxBytes above.
                    val retryAfter =
                        responseHeaders.firstOrNull { it.key.equals(RETRY_AFTER_HEADER, ignoreCase = true) }?.value
                    val detail = runCatching { input.readUpTo(ERROR_MESSAGE_MAX_LENGTH) }.getOrDefault(ByteArray(0))
                    throw OhttpException(
                        "Relay/gateway returned HTTP $status (Tor path)${detail.asDetail()}",
                        status,
                        retryAfterSeconds = OhttpSender.retryAfterSeconds(retryAfter)
                    )
                }
                read(input)
            }
        }
    }

    override suspend fun get(
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int
    ): ByteArray {
        val jniHeaders = headers.map { (key, value) -> JniHttpHeader(key, value) }
        val response = asTransportFailure { transport.get(url, jniHeaders, timeoutMs.toLong()) }
        if (response.status != HttpURLConnection.HTTP_OK) {
            // ZIP #10: error responses bounded to 64 KiB, same as POST.
            val errorBody = response.body.take(MAX_ERROR_BODY_BYTES).toByteArray()
            throw OhttpException(
                "GET request failed: HTTP ${response.status} (Tor path)${errorBody.asDetail()}",
                response.status,
                retryAfterSeconds = OhttpSender.retryAfterSeconds(response.header(RETRY_AFTER_HEADER))
            )
        }
        return response.body.requireWithinSizeLimit(OhttpSender.MAX_KEY_CONFIG_BYTES)
    }

    /**
     * Releases the isolated [TorClient] this sender wraps (each
     * [cash.z.ecc.android.sdk.Synchronizer.ohttpSenderOverTor] call creates a fresh isolated
     * native Tor handle). The caller of
     * [cash.z.ecc.android.sdk.Synchronizer.ohttpSenderOverTor] owns this sender and must dispose it
     * once it is replaced (e.g. the Tor preference toggles) or no longer needed, or the underlying
     * native Tor runtime handle leaks for the rest of the process lifetime.
     */
    override suspend fun dispose() = transport.dispose()

    /**
     * A native timeout surfaces as a [SocketTimeoutException] and a transport failure (a streamed body
     * included) as an [OhttpException], so that a call over Tor, its key configuration fetch included,
     * fails as one over the direct sender does (UNAVAILABLE, not INTERNAL).
     */
    private inline fun <T> asTransportFailure(block: () -> T): T =
        try {
            block()
        } catch (e: TorHttpTimeoutException) {
            throw SocketTimeoutException(e.message).apply { initCause(e) }
        } catch (e: TorHttpTransportException) {
            throw OhttpException(e.message.orEmpty(), cause = e)
        }

    /**
     * Renders the JNI response body as `": <detail>"` for use in an [OhttpException] message, or
     * "" if empty. The body carries the gateway's problem-details JSON (e.g. type "ohttp-key").
     * Mirrors [co.electriccoin.lightwallet.client.internal.DirectOhttpSender]'s error-stream
     * handling so callers see the same gateway error detail regardless of whether Tor is on.
     */
    private fun JniHttpResponseBytes.header(name: String): String? =
        headers.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun ByteArray.asDetail(): String =
        toString(Charsets.UTF_8).take(ERROR_MESSAGE_MAX_LENGTH).let { if (it.isEmpty()) "" else ": $it" }

    /**
     * `torClient.httpPost`/`httpGet` already bound the body while reading it (their `maxResponseBytes`,
     * enforced by the native `collect_body_limited` frame by frame, which stops the read at the first
     * frame past the limit): this is the same limit checked again on the materialised [ByteArray], a
     * defence in depth with the same bounds as
     * [co.electriccoin.lightwallet.client.internal.DirectOhttpSender] (see [OhttpSender]).
     */
    private fun ByteArray.requireWithinSizeLimit(maxBytes: Int): ByteArray {
        if (size > maxBytes) {
            throw OhttpException("Response exceeded the $maxBytes byte limit (Tor path)")
        }
        return this
    }

    private companion object {
        const val ERROR_MESSAGE_MAX_LENGTH = 200
        const val RETRY_AFTER_HEADER = "Retry-After"

        // ZIP #10: outer error responses (non-2xx status) are bounded to 64 KiB, independently
        // of any caller-chosen response ceiling. The same guard as DirectOhttpSender.
        private const val MAX_ERROR_BODY_BYTES = 64 * 1024
    }
}

/**
 * The slice of [TorClient] that [TorOhttpSender] needs. A seam so the sender's behavior (timeout
 * pass-through, error-body handling) can be unit tested without a native Tor runtime.
 */
internal interface TorHttpTransport {
    suspend fun post(
        url: String,
        headers: List<JniHttpHeader>,
        body: ByteArray,
        timeoutMs: Long
    ): JniHttpResponseBytes

    suspend fun get(
        url: String,
        headers: List<JniHttpHeader>,
        timeoutMs: Long
    ): JniHttpResponseBytes

    /** POSTs and hands [read] the status, the headers and a supplier of the body's bytes (null at its end). */
    suspend fun <T> postStreaming(
        url: String,
        headers: List<JniHttpHeader>,
        body: ByteArray,
        timeoutMs: Long,
        read: (status: Int, headers: Array<JniHttpHeader>, next: () -> ByteArray?) -> T
    ): T

    suspend fun dispose()
}

/** At most [maxBytes] of the stream. */
private fun InputStream.readUpTo(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var n = 0
    while (n < maxBytes) {
        val read = read(buffer, n, maxBytes - n)
        if (read < 0) break
        n += read
    }
    return buffer.copyOf(n)
}

/** A body read from [next] chunk by chunk, refused past [maxBytes]. */
private class ChunkInputStream(
    private val next: () -> ByteArray?,
    private val maxBytes: Int
) : InputStream() {
    private var chunk = ByteArray(0)
    private var pos = 0
    private var total = 0L
    private var ended = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int
    ): Int {
        while (len > 0 && pos == chunk.size && !ended) fill()
        val n = minOf(len, chunk.size - pos)
        if (n == 0 && len > 0) return -1
        System.arraycopy(chunk, pos, b, off, n)
        pos += n
        return n
    }

    private fun fill() {
        val more = next()
        if (more == null) {
            ended = true
            return
        }
        total += more.size
        if (total > maxBytes) throw OhttpException("Response exceeded the $maxBytes byte limit (Tor path)")
        chunk = more
        pos = 0
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}

/**
 * OHTTP already has its own retry-on-400/422 logic (key rotation); no additional retries at the Tor
 * layer (would multiply total attempts).
 */
private const val NO_TOR_RETRIES = 0

private class TorClientTransport(
    private val torClient: TorClient
) : TorHttpTransport {
    override suspend fun post(
        url: String,
        headers: List<JniHttpHeader>,
        body: ByteArray,
        timeoutMs: Long
    ) = torClient.httpPost(
        url,
        headers,
        body,
        retryLimit = NO_TOR_RETRIES,
        maxResponseBytes = OhttpSender.MAX_SEALED_RESPONSE_BYTES,
        timeoutMs = timeoutMs
    )

    override suspend fun get(
        url: String,
        headers: List<JniHttpHeader>,
        timeoutMs: Long
    ) = torClient.httpGet(
        url,
        headers,
        retryLimit = NO_TOR_RETRIES,
        maxResponseBytes = OhttpSender.MAX_KEY_CONFIG_BYTES,
        timeoutMs = timeoutMs
    )

    override suspend fun <T> postStreaming(
        url: String,
        headers: List<JniHttpHeader>,
        body: ByteArray,
        timeoutMs: Long,
        read: (status: Int, headers: Array<JniHttpHeader>, next: () -> ByteArray?) -> T
    ): T =
        torClient.httpPostStreaming(url, headers, body, NO_TOR_RETRIES, timeoutMs) { status, responseHeaders, stream ->
            read(status, responseHeaders, stream::read)
        }

    override suspend fun dispose() = torClient.dispose()
}
