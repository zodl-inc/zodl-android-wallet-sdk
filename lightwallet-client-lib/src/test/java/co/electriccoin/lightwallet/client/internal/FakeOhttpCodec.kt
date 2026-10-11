package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpCodec
import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpCompletion
import co.electriccoin.lightwallet.client.OhttpProfileInfo
import co.electriccoin.lightwallet.client.OhttpResponseOpener
import co.electriccoin.lightwallet.client.OhttpSealedRequest
import co.electriccoin.lightwallet.client.OhttpVerifiedKey
import java.util.concurrent.atomic.AtomicLong

/**
 * An [OhttpCodec] for JVM unit tests, which cannot load the native library. It only models the
 * contract the transport relies on, not the protocol (the real one is tested in `ohttp.rs` and in
 * `RustOhttpToolTest`):
 *
 * - every `encapsulate` returns a new body and a new opener, and every opener must be
 *   spent by [OhttpResponseOpener.open] or released by [OhttpResponseOpener.close] exactly once
 *   ([liveOpeners] is what is left);
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

        override fun close() {
            val abandoned = handle.getAndSet(0)
            if (abandoned != 0L) free(abandoned)
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
    }
}

/** An [co.electriccoin.lightwallet.client.OhttpSender] that records calls and answers from queues. */
internal class RecordingSender(
    private val getResults: MutableList<() -> ByteArray> = mutableListOf(),
    private val postResults: MutableList<() -> ByteArray> = mutableListOf(),
) : co.electriccoin.lightwallet.client.OhttpSender {
    val getCalls = mutableListOf<Triple<String, List<Pair<String, String>>, Int>>()
    val postCalls = mutableListOf<Triple<String, ByteArray, List<Pair<String, String>>>>()

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
