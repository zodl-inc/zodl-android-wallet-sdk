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
 * [OhttpSealedRequest.opener] is single use: call its [OhttpResponseOpener.open] or
 * [OhttpResponseOpener.openStream] once, or [OhttpResponseOpener.close] it.
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
     * Records that the relay at [relayUrl] asked for [delayMs] before the next request (an outer
     * 429's `Retry-After`) where the native transports see it too. No-op by default.
     */
    fun noteRelayBackoff(
        relayUrl: String,
        delayMs: Long,
    ) = Unit

    /** What is left, in ms, of a pause the relay at [relayUrl] asked any native transport for; 0 if none. */
    fun relayBackoffRemainingMs(relayUrl: String): Long = 0
}

/**
 * True when the relay this build uses for [gatewayHost] is the default, independently operated one
 * (`relays.rs` of `zodl-ohttp-client`). A build-time override (`ZODL_OHTTP_RELAY_URL_*`, e.g. the relay
 * simulator, which the gateway operator runs) is false: such a build should present its connection as
 * not private, since one operator then sees both the client's address and the request.
 */
fun OhttpCodec.usesIndependentRelay(gatewayHost: String): Boolean =
    relayUrlFor(gatewayHost)?.let { it in INDEPENDENT_RELAY_URLS } == true

private val INDEPENDENT_RELAY_URLS =
    setOf(
        "https://ohttp-relay.example/",
        "https://ohttp-relay-testnet.example/",
    )

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
 * Opens the relay's answer to an OHTTP sealed request. Single use: [open] or [openStream] consumes
 * the opener, [close] frees it if not consumed. [close] is idempotent and thread-safe; a second
 * [open] or [openStream] throws [IllegalStateException].
 *
 * The response secret never enters the JVM; implementations wrap an opaque native handle.
 */
interface OhttpResponseOpener : java.io.Closeable {
    /**
     * Classifies the relay's answer (its outer [outerStatus], [contentType] and [body]) and, if it
     * is a sealed response, opens it. [attempt] is 0 for the first post of a request and 1 for the
     * post after an [OhttpCompletion.RefetchKeyConfig].
     *
     * The opener is consumed whatever happens, an exception included: never call [close] afterwards.
     *
     * @throws OhttpException if the answer does not open or is not a response
     * @throws IllegalStateException if the opener is already spent or freed
     */
    suspend fun open(
        outerStatus: Int,
        contentType: String?,
        body: ByteArray,
        attempt: Int,
    ): OhttpCompletion

    /**
     * Starts opening the sealed answer of an outer [outerStatus] (a 200) as it is read: feed the body
     * to [OhttpResponseDecoder.push] as it arrives. The opener is consumed whatever happens.
     *
     * @throws OhttpException if the outer response is not a sealed answer
     * @throws IllegalStateException if the opener is already spent or freed
     */
    fun openStream(
        outerStatus: Int,
        contentType: String?,
        attempt: Int,
    ): OhttpResponseDecoder

    /** Frees the opener if [open] or [openStream] has not consumed it. Idempotent. */
    override fun close()
}

/**
 * Opens a sealed answer chunk by chunk, as [OhttpResponseOpener.openStream] started it: every chunk
 * is decrypted as soon as it is complete and its Binary HTTP parsed as far as it goes. Calls are
 * serialised; [close] (idempotent) frees the native state and must follow the last call.
 */
interface OhttpResponseDecoder : java.io.Closeable {
    /**
     * More of the sealed body, counted against the size cap before it is buffered.
     *
     * @throws OhttpInternalException if a chunk does not open or the message is malformed
     * @throws OhttpException if the answer passes the size cap
     */
    fun push(bytes: ByteArray): OhttpResponseEvents

    /**
     * The sealed body has ended: opens the final chunk and checks the message is complete
     * ([OhttpResponseEvents.end] is then true).
     *
     * @throws OhttpException if the body ended before the final chunk (truncated)
     * @throws OhttpInternalException if the final chunk does not open or the message is incomplete
     */
    fun finish(): OhttpResponseEvents

    override fun close()
}

/**
 * What one [OhttpResponseDecoder.push] or [OhttpResponseDecoder.finish] completed, in this order: the
 * [head] (once per response), body bytes, the [trailers] (empty when the response has none), the [end].
 */
class OhttpResponseEvents(
    val head: OhttpResponseHead?,
    val body: ByteArray,
    val trailers: List<Pair<String, String>>?,
    val end: Boolean,
)

/** The inner status and header section of a streamed response. */
class OhttpResponseHead(
    val status: Int,
    val headers: List<Pair<String, String>>,
)

/**
 * A sealed request: [body] is what is posted to the relay; [opener] is what opens its answer.
 * Single use.
 */
class OhttpSealedRequest(
    val body: ByteArray,
    val opener: OhttpResponseOpener,
)

/** What the relay's answer came to: [OhttpResponseOpener.open]. */
sealed interface OhttpCompletion {
    /** The sealed answer opened: the backend's response. */
    class Response(
        val response: OhttpCodecResponse,
    ) : OhttpCompletion

    /**
     * The gateway does not know the key configuration: fetch it again, seal the request again and
     * post once more. Never returned for [OhttpResponseOpener.open] with an `attempt` of 1.
     */
    data object RefetchKeyConfig : OhttpCompletion

    /** The relay or the gateway refused the request; [detail] is a bounded prefix of the body. */
    class Refused(
        val status: Int,
        val detail: String,
    ) : OhttpCompletion

    /** A success status not labelled `message/ohttp-chunked-res`. */
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
