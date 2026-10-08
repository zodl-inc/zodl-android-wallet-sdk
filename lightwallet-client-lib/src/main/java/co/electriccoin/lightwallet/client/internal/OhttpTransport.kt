package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpCodec
import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpCompletion
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpProfileInfo
import co.electriccoin.lightwallet.client.OhttpSender

/**
 * OHTTP (RFC 9458) client transport: seals a request to the gateway's key, POSTs it to the relay
 * and opens the gateway's response.
 *
 * The protocol itself (signed key configuration, Binary HTTP, HPKE, outer-status classification,
 * size caps) is done by the shared native core behind [codec]; this class keeps the I/O, the key
 * cache, the retry loop and the trace id.
 *
 * Pure JVM apart from [codec] (`HttpURLConnection` through the default [sender]) so that the whole
 * OHTTP path can be exercised from a plain unit test with a fake [codec].
 */
@Suppress("LongParameterList") // Network config + test/debug overrides
internal class OhttpTransport(
    private val relayUrl: String,
    /** The gateway's host, which the codec maps to a network and so to the signing key it trusts. */
    private val gatewayHost: String,
    private val codec: OhttpCodec,
    private val sender: OhttpSender = DirectOhttpSender(),
    private val logger: (String) -> Unit = {},
    /**
     * Optional client trace ID prefix. When set, every outer OHTTP POST to the relay carries
     * `X-Client-Trace-Id: <prefix>-<sessionUuid>-<seq>` so a tester can tag and follow requests
     * end-to-end in Grafana (relay → gateway → downstream). The prefix is sanitised to
     * `[A-Za-z0-9._:-]` with first char alphanumeric, and truncated so the full header value ≤ 64 chars.
     *
     * **Privacy warning:** a custom trace ID makes that wallet's requests linkable across relay
     * and gateway logs. DEBUG ONLY. Never set in release builds. Off by default.
     */
    ohttpClientTraceIdPrefix: String? = null,
    /**
     * Optional pre-cached gateway key (an [co.electriccoin.lightwallet.client.OhttpVerifiedKey.key])
     * for testing. When set, skips the initial HTTP GET. Production code should leave this null.
     */
    initialKey: ByteArray? = null,
    /**
     * Optional pre-cached key expiry timestamp (seconds). Only used when [initialKey] is set.
     * Production code should leave this null.
     */
    initialExpirySeconds: Long? = null,
) {
    private val lock = Any()
    private var cachedKey: ByteArray? = initialKey
    private var cachedExpiry: Long? = initialExpirySeconds
    private val clientTrace: OhttpClientTrace? =
        if (OhttpDebugFlags.allowTracePrefix) {
            ohttpClientTraceIdPrefix?.let { OhttpClientTrace(it) }
        } else {
            null
        }
    private val profile: OhttpProfileInfo by lazy { codec.profileInfo() }
    private val signedConfigUrl: String by lazy { codec.signedConfigUrl(relayUrl) }

    /**
     * Fetches (or returns the cached) gateway key. A key is used until the `not_after` of its signed
     * configuration (less the codec's refresh margin); a forced refresh discards the cached key
     * before the fetch, when the codec's cache rules say so.
     */
    suspend fun keyConfig(forceRefresh: Boolean = false): ByteArray {
        val nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
        synchronized(lock) {
            if (forceRefresh && profile.dropBeforeRefetch) {
                cachedKey = null
                cachedExpiry = null
            }
            val cached = cachedKey
            val expiry = cachedExpiry
            // A pre-seeded key without an expiry (test-only) never expires.
            val fresh = expiry == null || expiry - nowSeconds > profile.cacheRefreshMarginSecs
            if (!forceRefresh && cached != null && fresh) {
                return cached
            }
        }

        val headers = profile.keyFetchHeaders + traceHeaders(profile.traceOnKeyFetch)
        val json = sender.get(signedConfigUrl, headers, KEY_FETCH_TIMEOUT_MS)

        val verified = codec.verifyKeyConfig(gatewayHost, json, nowSeconds)
        logger("OHTTP signed key config verified: network=${verified.network}, valid_until=${verified.notAfter}")

        synchronized(lock) {
            cachedKey = verified.key
            cachedExpiry = verified.notAfter
        }
        return verified.key
    }

    /**
     * Performs one OHTTP round trip. When the relay or gateway rejects the sealed request as
     * unreadable (outer HTTP 400 or 422, which handles gateway key rotation), the key configuration
     * is refetched and the request is **sealed afresh** and sent once more: the gateway answers a
     * repeated nonce with 409, so a ciphertext is never posted twice.
     */
    suspend fun roundTrip(
        request: OhttpCodecRequest,
        timeoutMs: Int,
    ): OhttpCodecResponse {
        exchange(keyConfig(), request, timeoutMs, FIRST_ATTEMPT)?.let { return it }
        logger("OHTTP gateway rejected the request; refreshing the key config and retrying")
        return exchange(keyConfig(forceRefresh = true), request, timeoutMs, RETRY_ATTEMPT)
            ?: throw OhttpException("OHTTP gateway rejected the request again after a key config refresh")
    }

    /**
     * One sealing and one post. Returns the response, or null if the gateway asked for a new key
     * configuration. The opener is spent by [OhttpResponseOpener.open] or freed here, whichever
     * way this ends (an exception or a cancelled coroutine included).
     */
    private suspend fun exchange(
        key: ByteArray,
        request: OhttpCodecRequest,
        timeoutMs: Int,
        attempt: Int,
    ): OhttpCodecResponse? {
        val sealed = codec.encapsulate(key, request)
        return sealed.opener.use { opener ->
            val answer =
                try {
                    sender.post(relayUrl, sealed.body, profile.postHeaders + traceHeaders(true), timeoutMs)
                } catch (e: OhttpException) {
                    // The senders only return the body of a 200, and throw the relay's other
                    // statuses: let the opener decide whether this one asks for a new key.
                    val status = e.httpStatus ?: throw e
                    return@use refusal(opener.open(status, null, ByteArray(0), attempt), e)
                }
            interpret(opener.open(HTTP_OK, null, answer, attempt))
        }
    }

    private fun refusal(
        completion: OhttpCompletion,
        refused: OhttpException,
    ): OhttpCodecResponse? =
        when (completion) {
            OhttpCompletion.RefetchKeyConfig -> null
            else -> throw refused
        }

    private fun interpret(completion: OhttpCompletion): OhttpCodecResponse? =
        when (completion) {
            is OhttpCompletion.Response -> {
                completion.response
            }

            OhttpCompletion.RefetchKeyConfig -> {
                null
            }

            is OhttpCompletion.Refused -> {
                throw OhttpException(
                    "Relay/gateway returned HTTP ${completion.status}: ${completion.detail}",
                    completion.status,
                )
            }

            is OhttpCompletion.WrongMediaType -> {
                throw OhttpException("Relay answered ${completion.contentType}, not $RESPONSE_MEDIA_TYPE")
            }
        }

    /** The trace id header, if a trace prefix is set and [wanted]. */
    private fun traceHeaders(wanted: Boolean): List<Pair<String, String>> =
        clientTrace?.takeIf { wanted }?.let { listOf(TRACE_HEADER to it.next()) } ?: emptyList()

    companion object {
        const val RESPONSE_MEDIA_TYPE = "message/ohttp-chunked-res"
        const val TRACE_HEADER = "X-Client-Trace-Id"
        private const val MILLIS_PER_SECOND = 1000L
        private const val HTTP_OK = 200
        private const val FIRST_ATTEMPT = 0
        private const val RETRY_ATTEMPT = 1

        private const val KEY_FETCH_TIMEOUT_MS = 15_000
    }
}

/**
 * Gateway-mapped hosts where the gateway injects secrets server-side. The app must NOT send
 * secrets (X-CMC_PRO_API_KEY, Authorization) in OHTTP requests to these hosts.
 */
private val SECRET_INJECTING_GATEWAY_HOSTS = setOf("cmc.zodl.com", "near.zodl.com")

/**
 * Headers that must be stripped from OHTTP requests to [SECRET_INJECTING_GATEWAY_HOSTS].
 */
private val SECRET_HEADERS = setOf("X-CMC_PRO_API_KEY", "Authorization")

/**
 * Strips secret-bearing headers from [headers] if [authority] is a gateway-mapped host that
 * injects secrets server-side. Returns the filtered header list and logs a warning if any
 * secret header was present (the app should not send these at all).
 */
internal fun stripSecretsForGateway(
    authority: String,
    headers: List<Pair<String, String>>,
    logger: (String) -> Unit,
): List<Pair<String, String>> {
    if (authority.lowercase() !in SECRET_INJECTING_GATEWAY_HOSTS) return headers
    val isSecretHeader = { key: String -> SECRET_HEADERS.any { it.equals(key, ignoreCase = true) } }
    val stripped = headers.filterNot { (key, _) -> isSecretHeader(key) }
    if (stripped.size < headers.size) {
        val removed = headers.filter { (key, _) -> isSecretHeader(key) }.map { it.first }
        logger(
            "OHTTP: stripped secret headers $removed for gateway-mapped host $authority " +
                "(gateway injects server-side)",
        )
    }
    return stripped
}
