package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpCodec
import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCodecResponse
import co.electriccoin.lightwallet.client.OhttpCompletion
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpProfileInfo
import co.electriccoin.lightwallet.client.OhttpSender
import java.net.SocketTimeoutException

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
    suspend fun keyConfig(
        forceRefresh: Boolean = false,
        timeoutMs: Int = KEY_FETCH_TIMEOUT_MS,
    ): ByteArray {
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

        val headers = profile.keyFetchHeaders + USER_AGENT_HEADER + traceHeaders(profile.traceOnKeyFetch)
        RelayBackoff.check(relayUrl)
        val json =
            try {
                sender.get(signedConfigUrl, headers, minOf(timeoutMs, KEY_FETCH_TIMEOUT_MS))
            } catch (e: OhttpException) {
                RelayBackoff.noteIfRateLimited(relayUrl, e)
                throw e
            }

        val verified = codec.verifyKeyConfig(gatewayHost, json, nowSeconds)
        logger("OHTTP signed key config verified: network=${verified.network}, valid_until=${verified.notAfter}")

        synchronized(lock) {
            cachedKey = verified.key
            cachedExpiry = verified.notAfter
        }
        return verified.key
    }

    /**
     * Performs one OHTTP call: [roundTripOnce], and once more (sealed afresh) when the gateway
     * refused the request as stale, an inner 400 with `ohttp-freshness: rejected` ("Request
     * freshness" in the ZIP). That response carried the gateway clock, which the codec has
     * already learned, so the second attempt is stamped right even on a device whose clock is off.
     * Never more than one such retry. One call makes at most four relay POSTs (one freshness
     * retry × one key-refresh retry each); [timeoutMs] bounds the whole call, every post and key
     * fetch getting what is left of it.
     */
    suspend fun roundTrip(
        request: OhttpCodecRequest,
        timeoutMs: Int,
    ): OhttpCodecResponse {
        val deadline = CallDeadline(timeoutMs)
        val response = roundTripOnce(request, deadline)
        if (!response.isFreshnessRefusal()) return response
        logger("OHTTP gateway refused the request as stale; sending it again")
        return roundTripOnce(request, deadline)
    }

    /**
     * Performs one OHTTP round trip. When the relay or gateway rejects the sealed request as
     * unreadable (outer HTTP 400 or 422, which handles gateway key rotation), the key configuration
     * is refetched and the request is **sealed afresh** and sent once more: the gateway answers a
     * repeated nonce with 409, so a ciphertext is never posted twice. One call makes at most two
     * relay POSTs (one key-refresh retry), within [deadline].
     */
    private suspend fun roundTripOnce(
        request: OhttpCodecRequest,
        deadline: CallDeadline,
    ): OhttpCodecResponse {
        exchange(keyConfig(timeoutMs = deadline.remainingMs()), request, deadline.remainingMs(), FIRST_ATTEMPT)
            ?.let { return it }
        logger("OHTTP gateway rejected the request; refreshing the key config and retrying")
        val key = keyConfig(forceRefresh = true, timeoutMs = deadline.remainingMs())
        return exchange(key, request, deadline.remainingMs(), RETRY_ATTEMPT)
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
        RelayBackoff.check(relayUrl)
        val sealed = codec.encapsulate(key, request)
        return sealed.opener.use { opener ->
            val answer =
                try {
                    sender.post(
                        relayUrl,
                        sealed.body,
                        profile.postHeaders + USER_AGENT_HEADER + traceHeaders(true),
                        timeoutMs,
                    )
                } catch (e: OhttpException) {
                    RelayBackoff.noteIfRateLimited(relayUrl, e)
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

        /** Fixed, so the relay never sees the platform's default (device model, OS build). */
        private val USER_AGENT_HEADER = listOf("User-Agent" to "ZODL-OHTTP/1.0")
    }
}

/** What is left of one call's time; a call whose time is up fails as timed out. */
private class CallDeadline(
    timeoutMs: Int,
) {
    private val endNanos = System.nanoTime() + timeoutMs * NANOS_PER_MILLI

    fun remainingMs(): Int {
        val left = (endNanos - System.nanoTime()) / NANOS_PER_MILLI
        if (left <= 0) throw SocketTimeoutException("OHTTP call deadline exceeded")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Relays that answered 429 with a `Retry-After`: nothing is sent through such a relay until that delay,
 * capped at [MAX_RETRY_AFTER_SECONDS], is over; a call in the meantime fails as the 429 did (UNAVAILABLE).
 * Shared by every transport of the process, since they share the relay's view of this client's address.
 */
internal object RelayBackoff {
    const val MAX_RETRY_AFTER_SECONDS = 120L
    private const val MILLIS_PER_SECOND = 1000L
    private val until = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun noteIfRateLimited(
        relayUrl: String,
        e: OhttpException,
    ) {
        if (e.httpStatus == HTTP_TOO_MANY_REQUESTS) note(relayUrl, e.retryAfterSeconds)
    }

    fun note(
        relayUrl: String,
        retryAfterSeconds: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val seconds = retryAfterSeconds?.coerceAtMost(MAX_RETRY_AFTER_SECONDS) ?: return
        until[relayUrl] = nowMillis + seconds * MILLIS_PER_SECOND
    }

    fun check(
        relayUrl: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val end = until[relayUrl] ?: return
        val left = end - nowMillis
        if (left <= 0) {
            until.remove(relayUrl, end)
            return
        }
        val seconds = (left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
        throw OhttpException(
            "The relay asked this client to wait (HTTP 429); $seconds s left",
            HTTP_TOO_MANY_REQUESTS,
            retryAfterSeconds = seconds,
        )
    }
}

/**
 * Whether this inner response is the gateway's freshness refusal: status 400 with
 * `ohttp-freshness: rejected` in the header section.
 */
internal fun OhttpCodecResponse.isFreshnessRefusal(): Boolean =
    status == HTTP_BAD_REQUEST &&
        headers.any { (name, value) ->
            name.equals(FRESHNESS_HEADER, ignoreCase = true) &&
                value.trim().equals(FRESHNESS_REJECTED, ignoreCase = true)
        }

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val FRESHNESS_HEADER = "ohttp-freshness"
private const val FRESHNESS_REJECTED = "rejected"

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
