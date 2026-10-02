package co.electriccoin.lightwallet.client

/**
 * The protocol half of an Oblivious HTTP (RFC 9458) exchange, as implemented by the shared
 * `zodl-ohttp-client` core (Rust) for the Android profile: key selection, the signed key
 * configuration envelope, Binary HTTP, HPKE, the classification of the relay's answer and the size
 * caps. The Kotlin transport keeps the I/O, the key cache, the retry loop and the relay tables.
 *
 * This module does not depend on the native library; `backend-lib` provides the implementation
 * (`RustOhttpTool`) and the SDK wires it (`cash.z.ecc.android.sdk.util.OhttpCodecFactory`).
 *
 * **One sealing per attempt.** The gateway answers a repeated nonce with 409, so [encapsulate] must
 * be called again for every attempt, after a transport error and after a key refresh alike. The
 * `opener` of an [OhttpSealedRequest] is single use: hand it to exactly one of [complete] or
 * [free].
 *
 * Failures are [KeyConfigVerificationFailedException] (the signed key configuration was rejected)
 * or [OhttpException] (anything else). Their messages never contain request or response content.
 */
interface OhttpCodec {
    /** The preset's headers, size caps and key cache rules. Constant for the lifetime of the codec. */
    fun profileInfo(): OhttpProfileInfo

    /**
     * Returns the relay URL for a given gateway host, or null if the host is not a known gateway.
     * The built-in mapping can be overridden at build time via environment variables.
     */
    fun relayUrlFor(gatewayHost: String): String?

    /** The URL of the signed key configuration of the relay at [relayUrl]. */
    fun signedConfigUrl(relayUrl: String): String

    /**
     * Checks the signed envelope [envelopeJson] fetched for the gateway [gatewayHost] at
     * [nowSeconds] (Unix time) and returns the gateway key in it.
     *
     * @throws KeyConfigVerificationFailedException if the envelope or the key document is rejected
     */
    fun verifyKeyConfig(
        gatewayHost: String,
        envelopeJson: ByteArray,
        nowSeconds: Long,
    ): OhttpVerifiedKey

    /**
     * Seals [request] to [key] (an [OhttpVerifiedKey.key]).
     *
     * @throws OhttpException if the request cannot be encoded or sealed, or is larger than the cap
     */
    fun encapsulate(
        key: ByteArray,
        request: OhttpCodecRequest,
    ): OhttpSealedRequest

    /**
     * Classifies the relay's answer (its outer [outerStatus], [contentType] and [body]) and, if it
     * is a sealed response, opens it with [opener]. [attempt] is 0 for the first post of a request
     * and 1 for the post after an [OhttpCompletion.RefetchKeyConfig].
     *
     * [opener] is spent whatever happens, an exception included: never call [free] for it
     * afterwards.
     *
     * @throws OhttpException if the answer does not open or is not a response
     */
    fun complete(
        opener: Long,
        outerStatus: Int,
        contentType: String?,
        body: ByteArray,
        attempt: Int,
    ): OhttpCompletion

    /** Frees an [opener] whose [complete] will never be called (the post failed). */
    fun free(opener: Long)
}

/** The request of an [OhttpCodec.encapsulate]: what the gateway forwards to its backend. */
class OhttpCodecRequest(
    val method: String,
    val scheme: String,
    val authority: String,
    val path: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
)

/** The backend's response, opened. */
class OhttpCodecResponse(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val trailers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    /** Case-insensitive lookup across headers then trailers. */
    fun field(name: String): String? =
        (headers.asSequence() + trailers.asSequence())
            .firstOrNull { it.first.equals(name, ignoreCase = true) }
            ?.second
}

/**
 * The gateway key of a verified signed key configuration.
 *
 * @property key the 33 public bytes to pass to [OhttpCodec.encapsulate]
 * @property notAfter Unix time (seconds) up to which the key may be used
 * @property network `mainnet` or `testnet`
 */
class OhttpVerifiedKey(
    val key: ByteArray,
    val notAfter: Long,
    val network: String,
)

/**
 * A sealed request: [body] is what is posted to the relay; [opener] is the native handle of what
 * opens its answer (the secret never enters the JVM). Single use.
 */
class OhttpSealedRequest(
    val body: ByteArray,
    val opener: Long,
)

/** What the relay's answer came to: [OhttpCodec.complete]. */
sealed interface OhttpCompletion {
    /** The sealed answer opened: the backend's response. */
    class Response(
        val response: OhttpCodecResponse,
    ) : OhttpCompletion

    /**
     * The gateway does not know the key configuration: fetch it again, seal the request again and
     * post once more. Never returned for [OhttpCodec.complete] with an `attempt` of 1.
     */
    data object RefetchKeyConfig : OhttpCompletion

    /** The relay or the gateway refused the request; [detail] is a bounded prefix of the body. */
    class Refused(
        val status: Int,
        val detail: String,
    ) : OhttpCompletion

    /** A success status not labelled `message/ohttp-res`. */
    class WrongMediaType(
        val contentType: String,
    ) : OhttpCompletion
}

/**
 * The preset of an [OhttpCodec].
 *
 * @property postHeaders the headers of the post to the relay, without `X-Client-Trace-Id`
 * @property keyFetchHeaders the headers of the key configuration fetch, without `X-Client-Trace-Id`
 * @property maxSealedResponseBytes the largest sealed response accepted, -1 for none
 * @property maxKeyConfigBytes the largest key configuration document accepted, -1 for none
 * @property cacheRefreshMarginSecs how many seconds before `notAfter` a key is no longer used
 * @property dropBeforeRefetch whether the cached key is discarded before the fetch that replaces it
 * @property traceOnKeyFetch whether the key fetch carries `X-Client-Trace-Id` too
 * @property bodyWindow how many bytes of a refusal body [OhttpCompletion.Refused] quotes, -1 for all
 */
@Suppress("LongParameterList")
class OhttpProfileInfo(
    val postHeaders: List<Pair<String, String>>,
    val keyFetchHeaders: List<Pair<String, String>>,
    val maxSealedResponseBytes: Long,
    val maxKeyConfigBytes: Long,
    val cacheRefreshMarginSecs: Long,
    val dropBeforeRefetch: Boolean,
    val traceOnKeyFetch: Boolean,
    val bodyWindow: Int,
)
