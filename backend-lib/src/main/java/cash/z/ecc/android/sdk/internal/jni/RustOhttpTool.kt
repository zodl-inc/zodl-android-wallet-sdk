package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.JniOhttpException
import cash.z.ecc.android.sdk.internal.model.JniOhttpKey
import cash.z.ecc.android.sdk.internal.model.JniOhttpOutcome
import cash.z.ecc.android.sdk.internal.model.JniOhttpProfile
import cash.z.ecc.android.sdk.internal.model.JniOhttpSealed
import cash.z.ecc.android.sdk.internal.model.JniOhttpStreamEvents
import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpCodec
import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpCompletion
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpInternalException
import co.electriccoin.lightwallet.client.OhttpProfileInfo
import co.electriccoin.lightwallet.client.OhttpRequestTooLargeException
import co.electriccoin.lightwallet.client.OhttpResponseDecoder
import co.electriccoin.lightwallet.client.OhttpResponseEvents
import co.electriccoin.lightwallet.client.OhttpResponseHead
import co.electriccoin.lightwallet.client.OhttpResponseOpener
import co.electriccoin.lightwallet.client.OhttpSealedRequest
import co.electriccoin.lightwallet.client.OhttpVerifiedKey
import java.util.concurrent.atomic.AtomicLong

/**
 * [OhttpCodec] over the shared `zodl-ohttp-client` core, Android profile (`ohttp.rs`).
 *
 * The response opener (the secret that opens the gateway's answer) never enters the JVM: `encapsulate`
 * returns an [OhttpResponseOpener] wrapping an opaque native handle. The opener swaps the handle for 0
 * before using it, so each one is used once; [OhttpResponseOpener.openStream] turns it into the native
 * handle of a stream, which an [OhttpResponseDecoder] holds until it is closed.
 */
@Suppress("TooManyFunctions")
class RustOhttpTool private constructor() : OhttpCodec {
    /**
     * Implementation of [OhttpResponseOpener] wrapping a native handle. The handle is swapped for 0
     * before it is used, so it is spent or freed exactly once even if a coroutine is cancelled
     * between sealing and opening.
     */
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
            return this@RustOhttpTool.complete(spent, outerStatus, contentType, body, attempt)
        }

        override fun openStream(
            outerStatus: Int,
            contentType: String?,
            attempt: Int,
        ): OhttpResponseDecoder {
            val spent = handle.getAndSet(0)
            check(spent != 0L) { "The OHTTP response opener is already spent" }
            return ResponseDecoderImpl(
                translating { Companion.streamOpen(PROFILE_ANDROID, spent, outerStatus, contentType, attempt) }
            )
        }

        override fun close() {
            val abandoned = handle.getAndSet(0)
            if (abandoned != 0L) this@RustOhttpTool.freeOpener(abandoned)
        }
    }

    /**
     * Implementation of [OhttpResponseDecoder] wrapping the native handle of a stream. Every call holds
     * [lock], so the native state is never used by two threads or after it is freed.
     */
    private inner class ResponseDecoderImpl(
        stream: Long,
    ) : OhttpResponseDecoder {
        private val lock = Any()
        private var handle = stream

        override fun push(bytes: ByteArray): OhttpResponseEvents =
            synchronized(lock) { events(translating { Companion.streamPush(live(), bytes) }) }

        override fun finish(): OhttpResponseEvents =
            synchronized(lock) { events(translating { Companion.streamFinish(live()) }) }

        override fun close() =
            synchronized(lock) {
                if (handle != 0L) Companion.streamFree(handle)
                handle = 0
            }

        private fun live(): Long = handle.also { check(it != 0L) { "The OHTTP response stream is closed" } }
    }

    private val profile: OhttpProfileInfo by lazy {
        translating { Companion.profileInfo(PROFILE_ANDROID) }.let { native ->
            OhttpProfileInfo(
                postHeaders = OhttpFields.unpack(native.postHeaders),
                keyFetchHeaders = OhttpFields.unpack(native.keyFetchHeaders),
                maxSealedResponseBytes = native.maxSealedResponse,
                maxKeyConfigBytes = native.maxKeyConfig,
                cacheRefreshMarginSecs = native.cacheRefreshMarginSecs,
                dropBeforeRefetch = native.dropBeforeRefetch,
                traceOnKeyFetch = native.traceOnKeyFetch,
                bodyWindow = native.bodyWindow,
            )
        }
    }

    override fun profileInfo(): OhttpProfileInfo = profile

    override fun relayUrlFor(gatewayHost: String): String? = nativeRelayUrlFor(gatewayHost)

    override fun noteRelayBackoff(
        relayUrl: String,
        delayMs: Long,
    ) = nativeRelayBackoffNote(relayUrl, delayMs)

    override fun relayBackoffRemainingMs(relayUrl: String): Long = nativeRelayBackoffRemainingMs(relayUrl)

    override fun signedConfigUrl(relayUrl: String): String =
        translating { Companion.signedConfigUrl(PROFILE_ANDROID, relayUrl) }

    override fun verifyKeyConfig(
        gatewayHost: String,
        envelopeJson: ByteArray,
        nowSeconds: Long,
    ): OhttpVerifiedKey =
        translating { Companion.verifyKeyConfig(PROFILE_ANDROID, gatewayHost, envelopeJson, nowSeconds) }
            .let { OhttpVerifiedKey(key = it.key, notAfter = it.notAfter, network = it.network) }

    override fun keyConfigDnsName(gatewayHost: String): String =
        Companion.keyConfigDnsName(gatewayHost)

    override fun dohQueryUrl(resolver: String, gatewayHost: String): String =
        Companion.dohQueryUrl(resolver, gatewayHost)

    override fun envelopeFromDohJson(dohBody: ByteArray, gatewayHost: String): ByteArray =
        translating { Companion.envelopeFromDohJson(dohBody, gatewayHost) }

    override fun dohResolvers(): Array<String> =
        Companion.dohResolvers()


    override fun encapsulate(
        key: ByteArray,
        request: OhttpCodecRequest,
    ): OhttpSealedRequest =
        translating(sealing = true) {
            Companion.encapsulate(
                PROFILE_ANDROID,
                key,
                request.method.toByteArray(Charsets.US_ASCII),
                request.scheme.toByteArray(Charsets.US_ASCII),
                request.authority.toByteArray(Charsets.US_ASCII),
                request.path.toByteArray(Charsets.US_ASCII),
                OhttpFields.pack(request.headers),
                request.body,
            )
        }.let { OhttpSealedRequest(body = it.body, opener = ResponseOpenerImpl(it.opener)) }

    private fun complete(
        opener: Long,
        outerStatus: Int,
        contentType: String?,
        body: ByteArray,
        attempt: Int,
    ): OhttpCompletion =
        translating { Companion.complete(PROFILE_ANDROID, opener, outerStatus, contentType, body, attempt) }
            .let(::completion)

    /**
     * Not named `openerFree`: the companion's `@JvmStatic external fun openerFree(Long)` already puts a
     * static `openerFree(J)V` on this class, and an instance method with the same JVM signature clashes.
     */
    private fun freeOpener(opener: Long) = Companion.openerFree(opener)

    private inline fun <T> translating(
        sealing: Boolean = false,
        block: () -> T
    ): T =
        try {
            block()
        } catch (e: JniOhttpException) {
            throw Companion.mapFailure(e, sealing)
        }

    private fun completion(outcome: JniOhttpOutcome): OhttpCompletion =
        when (outcome.kind) {
            OUTCOME_RESPONSE -> {
                OhttpCompletion.Response(
                    OhttpCodecResponse(
                        status = outcome.status,
                        headers = unpackResponseFields(outcome.headers),
                        trailers = unpackResponseFields(outcome.trailers),
                        body = outcome.body,
                    ),
                )
            }

            OUTCOME_REFETCH_KEY_CONFIG -> {
                OhttpCompletion.RefetchKeyConfig
            }

            OUTCOME_REFUSED -> {
                OhttpCompletion.Refused(outcome.status, outcome.detail.orEmpty())
            }

            OUTCOME_WRONG_MEDIA_TYPE -> {
                OhttpCompletion.WrongMediaType(outcome.detail.orEmpty())
            }

            else -> {
                throw OhttpException("Unexpected native OHTTP outcome ${outcome.kind}")
            }
        }

    private fun events(native: JniOhttpStreamEvents): OhttpResponseEvents =
        OhttpResponseEvents(
            head = native.headers?.let { OhttpResponseHead(native.status, unpackResponseFields(it)) },
            body = native.body,
            trailers = native.trailers?.let(::unpackResponseFields),
            end = native.end,
        )

    private fun unpackResponseFields(packed: ByteArray): List<Pair<String, String>> =
        try {
            OhttpFields.unpack(packed)
        } catch (e: IllegalArgumentException) {
            throw OhttpException("Malformed native OHTTP header list", cause = e)
        }

    companion object {
        private const val PROFILE_ANDROID = 1

        // Mirrors `OUTCOME_*` in ohttp.rs.
        private const val OUTCOME_RESPONSE = 0
        private const val OUTCOME_REFETCH_KEY_CONFIG = 1
        private const val OUTCOME_REFUSED = 2
        private const val OUTCOME_WRONG_MEDIA_TYPE = 3

        // Mirrors `KIND_*` in ohttp.rs.
        internal const val KIND_KEY_CONFIG = 1
        internal const val KIND_OPEN = 3
        internal const val KIND_NOT_A_RESPONSE = 4
        internal const val KIND_BHTTP_FORMAT = 5
        internal const val KIND_TOO_LARGE = 6

        private val instance = RustOhttpTool()

        /** Loads the native library if needed and returns the shared codec (it holds no state). */
        suspend fun new(): OhttpCodec {
            RustBackend.loadLibrary()
            return instance
        }

        /**
         * Maps a failure of the native core to the exceptions of the [OhttpCodec] contract: a rejected
         * key configuration is a [KeyConfigVerificationFailedException], an answer that does not open
         * (AEAD, chunk framing, Binary HTTP) an [OhttpInternalException], a request over the size cap
         * ([sealing]) an [OhttpRequestTooLargeException], anything else an [OhttpException].
         */
        internal fun mapFailure(
            e: JniOhttpException,
            sealing: Boolean = false
        ): OhttpException =
            when {
                e.kind == KIND_KEY_CONFIG -> KeyConfigVerificationFailedException(e.message.orEmpty(), e)
                e.kind in ANSWER_DOES_NOT_OPEN -> OhttpInternalException(e.message.orEmpty(), e)
                sealing && e.kind == KIND_TOO_LARGE -> OhttpRequestTooLargeException(e.message.orEmpty(), e)
                else -> OhttpException(e.message.orEmpty(), cause = e)
            }

        private val ANSWER_DOES_NOT_OPEN = setOf(KIND_OPEN, KIND_NOT_A_RESPONSE, KIND_BHTTP_FORMAT)

        @JvmStatic
        private external fun verifyKeyConfig(
            profile: Int,
            gatewayHost: String,
            json: ByteArray,
            nowSecs: Long,
        ): JniOhttpKey

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun encapsulate(
            profile: Int,
            key: ByteArray,
            method: ByteArray,
            scheme: ByteArray,
            authority: ByteArray,
            path: ByteArray,
            headers: ByteArray,
            body: ByteArray,
        ): JniOhttpSealed

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun complete(
            profile: Int,
            opener: Long,
            outerStatus: Int,
            contentType: String?,
            body: ByteArray,
            attempt: Int,
        ): JniOhttpOutcome

        @JvmStatic
        private external fun openerFree(opener: Long)

        @JvmStatic
        private external fun streamOpen(
            profile: Int,
            opener: Long,
            outerStatus: Int,
            contentType: String?,
            attempt: Int,
        ): Long

        @JvmStatic
        private external fun streamPush(
            stream: Long,
            bytes: ByteArray,
        ): JniOhttpStreamEvents

        @JvmStatic
        private external fun streamFinish(stream: Long): JniOhttpStreamEvents

        @JvmStatic
        private external fun streamFree(stream: Long)

        @JvmStatic
        private external fun signedConfigUrl(
            profile: Int,
            relayUrl: String,
        ): String

        /**
         * Returns the relay URL for a given gateway host, or null if the host is not a known gateway.
         * The built-in mapping can be overridden at build time via environment variables.
         */
        @JvmStatic
        private external fun nativeRelayUrlFor(gatewayHost: String): String?

        @JvmStatic
        private external fun nativeRelayBackoffNote(
            relayUrl: String,
            delayMs: Long,
        )

        @JvmStatic
        private external fun nativeRelayBackoffRemainingMs(relayUrl: String): Long

        @JvmStatic
        private external fun profileInfo(profile: Int): JniOhttpProfile

        /**
         * Returns the DNS name of the signed Key Configuration TXT record for [gatewayHost]:
         * `ohttp-keys.example` or `ohttp-keys-testnet.example`.
         */
        @JvmStatic
        private external fun keyConfigDnsName(gatewayHost: String): String

        /**
         * Returns the DoH query URL (JSON API) for [resolver] and [gatewayHost]: the URL to GET
         * to fetch the TXT record as JSON.
         */
        @JvmStatic
        private external fun dohQueryUrl(
            resolver: String,
            gatewayHost: String,
        ): String

        /**
         * Parses a DoH JSON response [dohBody] (the body of `GET dohQueryUrl(...)`) and extracts
         * the signed envelope, which can then be verified with [verifyKeyConfig].
         *
         * @throws OhttpException if the DoH response is malformed or the envelope cannot be parsed
         */
        @JvmStatic
        private external fun envelopeFromDohJson(
            dohBody: ByteArray,
            gatewayHost: String,
        ): ByteArray

        /**
         * Returns the DNS-over-HTTPS resolvers tried in order: Cloudflare, then Google.
         */
        @JvmStatic
        private external fun dohResolvers(): Array<String>

        /**
         * Public helpers for DNS fallback, used by [co.electriccoin.lightwallet.client.internal.OhttpTransport].
         */
        object Dns {
            /** The DNS name of the signed Key Configuration TXT record for [gatewayHost]. */
            fun keyConfigDnsName(gatewayHost: String): String = Companion.keyConfigDnsName(gatewayHost)

            /** The DoH query URL (JSON API) for [resolver] and [gatewayHost]. */
            fun dohQueryUrl(
                resolver: String,
                gatewayHost: String,
            ): String = Companion.dohQueryUrl(resolver, gatewayHost)

            /**
             * Parses a DoH JSON response and extracts the signed envelope.
             *
             * @throws OhttpException if the response is malformed
             */
            fun envelopeFromDohJson(
                dohBody: ByteArray,
                gatewayHost: String,
            ): ByteArray = Companion.envelopeFromDohJson(dohBody, gatewayHost)

            /** The DNS-over-HTTPS resolvers tried in order. */
            val resolvers: Array<String> by lazy { Companion.dohResolvers() }
        }
    }
}
