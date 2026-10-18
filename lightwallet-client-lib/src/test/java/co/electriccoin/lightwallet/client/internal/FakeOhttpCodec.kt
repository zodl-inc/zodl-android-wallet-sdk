package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpCodec
import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpCompletion
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpInternalException
import co.electriccoin.lightwallet.client.OhttpProfileInfo
import co.electriccoin.lightwallet.client.OhttpResponseDecoder
import co.electriccoin.lightwallet.client.OhttpResponseEvents
import co.electriccoin.lightwallet.client.OhttpResponseHead
import co.electriccoin.lightwallet.client.OhttpResponseOpener
import co.electriccoin.lightwallet.client.OhttpSealedRequest
import co.electriccoin.lightwallet.client.OhttpVerifiedKey
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * An [OhttpCodec] for JVM unit tests, which cannot load the native library. It only models the
 * contract the transport relies on, not the protocol (the real one is tested in `ohttp.rs` and in
 * `RustOhttpToolTest`):
 *
 * - every `encapsulate` returns a new body and a new opener, and every opener must be
 *   spent by [OhttpResponseOpener.open] or [OhttpResponseOpener.openStream], or released by
 *   [OhttpResponseOpener.close], exactly once ([liveOpeners] is what is left); every stream must be
 *   closed ([liveStreams] is what is left);
 * - [complete] classifies like the core's Android profile: an outer 400 or 422 on the first
 *   attempt asks for a new key, another non-200 status is a refusal, 200 is [answer].
 */
internal class FakeOhttpCodec(
    private val profile: OhttpProfileInfo = PROFILE,
    private val verify: (host: String, json: ByteArray, now: Long) -> OhttpVerifiedKey = { _, _, now ->
        OhttpVerifiedKey(key = KEY, notAfter = now + ONE_HOUR_SECONDS, network = "testnet")
    },
    var answer: OhttpCodecResponse = OhttpCodecResponse(200, emptyList(), emptyList(), byteArrayOf(1)),
) : OhttpCodec {
    /** Inner responses for successive opened answers; [answer] once these are used up. */
    val answers = mutableListOf<OhttpCodecResponse>()
    val verified = mutableListOf<Triple<String, ByteArray, Long>>()
    val sealedKeys = mutableListOf<ByteArray>()
    val sealedRequests = mutableListOf<OhttpCodecRequest>()
    val sealedBodies = mutableListOf<ByteArray>()
    val completed = mutableListOf<Completion>()
    val freed = mutableListOf<Long>()
    val signedConfigUrls = mutableListOf<String>()
    val liveOpeners = mutableSetOf<Long>()
    val liveStreams: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val pushedBytes = AtomicLong()
    private var nextOpener = 1L

    class Completion(
        val opener: Long,
        val outerStatus: Int,
        val contentType: String?,
        val body: ByteArray,
        val attempt: Int,
    )

    private inner class ResponseOpenerImpl(
        opener: Long,
    ) : OhttpResponseOpener {
        private val handle = AtomicLong(opener)

        override suspend fun open(
            outerStatus: Int,
            contentType: String?,
            body: ByteArray,
            attempt: Int,
        ): OhttpCompletion {
            val spent = handle.getAndSet(0)
            check(spent != 0L) { "The OHTTP response opener is already spent" }
            return complete(spent, outerStatus, contentType, body, attempt)
        }

        override fun openStream(
            outerStatus: Int,
            contentType: String?,
            attempt: Int,
        ): OhttpResponseDecoder {
            val spent = handle.getAndSet(0)
            check(spent != 0L) { "The OHTTP response opener is already spent" }
            check(liveOpeners.remove(spent)) { "opener $spent is not live (spent twice, freed or never issued)" }
            completed.add(Completion(spent, outerStatus, contentType, ByteArray(0), attempt))
            liveStreams.add(spent)
            return FakeDecoder(spent)
        }

        override fun close() {
            val abandoned = handle.getAndSet(0)
            if (abandoned != 0L) free(abandoned)
        }
    }

    /**
     * Opens a body written with [streamed] record by record, as the core opens chunks; any other body
     * opens at its end as the next canned answer ([answers], then [answer]). A [streamed] body that
     * ends before [finalRecord] is truncated ([OhttpException]); a record of an unknown kind, or bytes
     * after the final record, do not open ([OhttpInternalException]).
     */
    private inner class FakeDecoder(
        private val id: Long,
    ) : OhttpResponseDecoder {
        private val wire = ByteArrayOutputStream()
        private var pos = 0
        private var recordMode: Boolean? = null
        private var sawFinal = false

        override fun push(bytes: ByteArray): OhttpResponseEvents {
            check(id in liveStreams) { "stream $id is closed" }
            pushedBytes.addAndGet(bytes.size.toLong())
            wire.write(bytes)
            val all = wire.toByteArray()
            if (recordMode == null && all.size >= STREAM_MAGIC.size) {
                recordMode = all.copyOf(STREAM_MAGIC.size).contentEquals(STREAM_MAGIC)
                if (recordMode == true) pos = STREAM_MAGIC.size
            }
            return if (recordMode == true) parse(all, end = false) else NO_EVENTS
        }

        override fun finish(): OhttpResponseEvents {
            check(id in liveStreams) { "stream $id is closed" }
            if (recordMode != true) {
                val canned = if (answers.isEmpty()) answer else answers.removeAt(0)
                return OhttpResponseEvents(
                    OhttpResponseHead(canned.status, canned.headers),
                    canned.body,
                    canned.trailers,
                    end = true
                )
            }
            val events = parse(wire.toByteArray(), end = true)
            if (!sawFinal) throw OhttpException("The response ended before its final chunk")
            return events
        }

        override fun close() {
            liveStreams.remove(id)
        }

        private fun parse(
            all: ByteArray,
            end: Boolean,
        ): OhttpResponseEvents {
            var head: OhttpResponseHead? = null
            val body = ByteArrayOutputStream()
            var trailers: List<Pair<String, String>>? = null
            while (!sawFinal && all.size - pos >= RECORD_HEADER) {
                val length = ByteBuffer.wrap(all, pos + 1, 4).int
                if (all.size - pos - RECORD_HEADER < length) break
                val payload = all.copyOfRange(pos + RECORD_HEADER, pos + RECORD_HEADER + length)
                when (all[pos]) {
                    TAG_HEAD -> head = OhttpResponseHead(ByteBuffer.wrap(payload, 0, 4).int, fields(payload, 4))
                    TAG_DATA -> body.write(payload)
                    TAG_TRAILERS -> trailers = fields(payload, 0)
                    TAG_FINAL -> sawFinal = true
                    else -> throw OhttpInternalException("chunk does not open")
                }
                pos += RECORD_HEADER + length
            }
            if (sawFinal && pos < all.size) throw OhttpInternalException("bytes after the final chunk")
            return OhttpResponseEvents(head, body.toByteArray(), trailers, end = end && sawFinal)
        }
    }

    override fun profileInfo(): OhttpProfileInfo = profile

    override fun relayUrlFor(gatewayHost: String): String? =
        when (gatewayHost) {
            "ohttp-gateway.example" -> "https://relay-prod.zodl.com/relay"
            "ohttp-gateway-testnet.example" -> "https://relay-dev.zodl.com/relay"
            else -> null
        }

    override fun signedConfigUrl(relayUrl: String): String {
        signedConfigUrls.add(relayUrl)
        return relayUrl.removeSuffix("/relay") + "/ohttp-configs/signed"
    }

    override fun verifyKeyConfig(
        gatewayHost: String,
        envelopeJson: ByteArray,
        nowSeconds: Long,
    ): OhttpVerifiedKey {
        verified.add(Triple(gatewayHost, envelopeJson, nowSeconds))
        return verify(gatewayHost, envelopeJson, nowSeconds)
    }

    override fun encapsulate(
        key: ByteArray,
        request: OhttpCodecRequest,
    ): OhttpSealedRequest {
        val opener = nextOpener++
        liveOpeners.add(opener)
        sealedKeys.add(key)
        sealedRequests.add(request)
        val body = "sealed-$opener".toByteArray()
        sealedBodies.add(body)
        return OhttpSealedRequest(body = body, opener = ResponseOpenerImpl(opener))
    }

    private fun complete(
        opener: Long,
        outerStatus: Int,
        contentType: String?,
        body: ByteArray,
        attempt: Int,
    ): OhttpCompletion {
        check(liveOpeners.remove(opener)) { "opener $opener is not live (spent twice, freed or never issued)" }
        completed.add(Completion(opener, outerStatus, contentType, body, attempt))
        return when {
            attempt == 0 && (outerStatus == HTTP_BAD_REQUEST || outerStatus == HTTP_UNPROCESSABLE) -> {
                OhttpCompletion.RefetchKeyConfig
            }

            outerStatus != HTTP_OK -> {
                OhttpCompletion.Refused(outerStatus, String(body))
            }

            else -> {
                OhttpCompletion.Response(if (answers.isEmpty()) answer else answers.removeAt(0))
            }
        }
    }

    private fun free(opener: Long) {
        check(liveOpeners.remove(opener)) { "opener $opener is not live (spent, freed twice or never issued)" }
        freed.add(opener)
    }

    companion object {
        const val ONE_HOUR_SECONDS = 3600L
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNPROCESSABLE = 422

        val KEY = ByteArray(33) { 1 }

        val PROFILE =
            OhttpProfileInfo(
                postHeaders =
                    listOf(
                        "Content-Type" to "message/ohttp-chunked-req",
                        "Accept" to "message/ohttp-chunked-res",
                        "Accept-Encoding" to "identity",
                    ),
                keyFetchHeaders = listOf("Accept" to "application/json"),
                maxSealedResponseBytes = 268_500_992,
                maxKeyConfigBytes = 65_536,
                cacheRefreshMarginSecs = 0,
                dropBeforeRefetch = true,
                traceOnKeyFetch = false,
                bodyWindow = 200,
            )

        fun rejecting(message: String): (String, ByteArray, Long) -> OhttpVerifiedKey =
            { _, _, _ -> throw KeyConfigVerificationFailedException(message) }

        private val STREAM_MAGIC = "fake-ohttp-stream".toByteArray()
        private const val RECORD_HEADER = 5
        private const val TAG_HEAD: Byte = 1
        private const val TAG_DATA: Byte = 2
        private const val TAG_TRAILERS: Byte = 3
        private const val TAG_FINAL: Byte = 4
        private val NO_EVENTS = OhttpResponseEvents(null, ByteArray(0), null, end = false)

        /** The start of a body that [FakeOhttpCodec] opens record by record. */
        fun streamStart(): ByteArray = STREAM_MAGIC.copyOf()

        fun headRecord(
            status: Int,
            headers: List<Pair<String, String>>,
        ): ByteArray = record(TAG_HEAD, ByteBuffer.allocate(4).putInt(status).array() + encode(headers))

        fun dataRecord(bytes: ByteArray): ByteArray = record(TAG_DATA, bytes)

        fun trailersRecord(trailers: List<Pair<String, String>>): ByteArray = record(TAG_TRAILERS, encode(trailers))

        fun finalRecord(): ByteArray = record(TAG_FINAL, ByteArray(0))

        /** A record that does not open, as a tampered chunk. */
        fun tamperedRecord(): ByteArray = record(9, ByteArray(3))

        /** A whole streamed body. */
        fun streamed(vararg records: ByteArray): ByteArray = records.fold(streamStart()) { acc, r -> acc + r }

        private fun record(
            tag: Byte,
            payload: ByteArray,
        ): ByteArray = byteArrayOf(tag) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload

        private fun encode(fields: List<Pair<String, String>>): ByteArray =
            fields.joinToString("") { (name, value) -> "$name=$value\n" }.toByteArray()

        private fun fields(
            payload: ByteArray,
            offset: Int,
        ): List<Pair<String, String>> =
            String(payload, offset, payload.size - offset)
                .split('\n')
                .filter { it.isNotEmpty() }
                .map { it.substringBefore('=') to it.substringAfter('=') }
    }
}

/**
 * An [co.electriccoin.lightwallet.client.OhttpSender] that records calls and answers from queues. A
 * streaming post takes the next of [queuePostStream] if any, else reads the next [queuePost] whole.
 */
internal class RecordingSender(
    private val getResults: MutableList<() -> ByteArray> = mutableListOf(),
    private val postResults: MutableList<() -> ByteArray> = mutableListOf(),
) : co.electriccoin.lightwallet.client.OhttpSender {
    val getCalls = mutableListOf<Triple<String, List<Pair<String, String>>, Int>>()
    val postCalls = mutableListOf<Triple<String, ByteArray, List<Pair<String, String>>>>()
    private val streamResults = mutableListOf<() -> InputStream>()

    fun queuePostStream(result: () -> InputStream) {
        streamResults.add(result)
    }

    override suspend fun <T> postStreaming(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
        read: (InputStream) -> T,
    ): T {
        if (streamResults.isEmpty()) return super.postStreaming(url, body, headers, timeoutMs, read)
        postCalls.add(Triple(url, body, headers))
        return streamResults.removeAt(0)().use(read)
    }

    fun queueGet(result: () -> ByteArray) {
        getResults.add(result)
    }

    fun queuePost(result: () -> ByteArray) {
        postResults.add(result)
    }

    override suspend fun dispose() = Unit

    override suspend fun get(
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray {
        getCalls.add(Triple(url, headers, timeoutMs))
        check(getResults.isNotEmpty()) { "no GET result queued" }
        return getResults.removeAt(0)()
    }

    override suspend fun post(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray {
        postCalls.add(Triple(url, body, headers))
        check(postResults.isNotEmpty()) { "no POST result queued" }
        return postResults.removeAt(0)()
    }
}
