package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.JniOhttpException
import cash.z.ecc.android.sdk.internal.model.JniOhttpKey
import cash.z.ecc.android.sdk.internal.model.JniOhttpOutcome
import cash.z.ecc.android.sdk.internal.model.JniOhttpProfile
import cash.z.ecc.android.sdk.internal.model.JniOhttpSealed
import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpCodec
import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpCompletion
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpInternalException
import co.electriccoin.lightwallet.client.OhttpProfileInfo
import co.electriccoin.lightwallet.client.OhttpResponseOpener
import co.electriccoin.lightwallet.client.OhttpSealedRequest
import co.electriccoin.lightwallet.client.OhttpVerifiedKey
import java.util.concurrent.atomic.AtomicLong

/**
 * [OhttpCodec] over the shared `zodl-ohttp-client` core, Android profile (`ohttp.rs`).
 *
 * The response opener (the secret that opens the gateway's answer) never enters the JVM: `encapsulate`
 * returns an [OhttpResponseOpener] wrapping an opaque native handle. The opener swaps the handle for 0
 * before using it, so each one is used once.
 */
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

        override fun close() {
            val abandoned = handle.getAndSet(0)
            if (abandoned != 0L) this@RustOhttpTool.freeOpener(abandoned)
        }
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

    override fun signedConfigUrl(relayUrl: String): String =
        translating { Companion.signedConfigUrl(PROFILE_ANDROID, relayUrl) }

    override fun verifyKeyConfig(
        gatewayHost: String,
        envelopeJson: ByteArray,
        nowSeconds: Long,
    ): OhttpVerifiedKey =
        translating { Companion.verifyKeyConfig(PROFILE_ANDROID, gatewayHost, envelopeJson, nowSeconds) }
            .let { OhttpVerifiedKey(key = it.key, notAfter = it.notAfter, network = it.network) }

    override fun encapsulate(
        key: ByteArray,
        request: OhttpCodecRequest,
    ): OhttpSealedRequest =
        translating {
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

    private inline fun <T> translating(block: () -> T): T =
        try {
            block()
        } catch (e: JniOhttpException) {
            throw Companion.mapFailure(e)
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
        internal const val KIND_OPEN = 2
        internal const val KIND_BHTTP_FORMAT = 3

        private val instance = RustOhttpTool()

        /** Loads the native library if needed and returns the shared codec (it holds no state). */
        suspend fun new(): OhttpCodec {
            RustBackend.loadLibrary()
            return instance
        }

        /**
         * Maps a failure of the native core to the exceptions of the [OhttpCodec] contract: a rejected
         * key configuration is a [KeyConfigVerificationFailedException], AEAD/format failures are
         * [OhttpInternalException], anything else an [OhttpException].
         */
        internal fun mapFailure(e: JniOhttpException): Exception =
            when (e.kind) {
                KIND_KEY_CONFIG -> KeyConfigVerificationFailedException(e.message.orEmpty(), e)
                KIND_OPEN, KIND_BHTTP_FORMAT -> OhttpInternalException(e.message.orEmpty(), e)
                else -> OhttpException(e.message.orEmpty(), cause = e)
            }

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
        private external fun profileInfo(profile: Int): JniOhttpProfile
    }
}
