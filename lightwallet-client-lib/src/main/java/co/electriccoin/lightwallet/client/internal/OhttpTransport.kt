package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
import java.net.HttpURLConnection

/**
 * OHTTP (RFC 9458) client transport: encapsulates a Binary HTTP request with HPKE, POSTs it to the
 * relay and decapsulates the gateway's response.
 *
 * Pure JVM (`HttpURLConnection`) so that the whole OHTTP path can be exercised from a plain unit
 * test against the real gateway before it is wired into gRPC.
 */
@Suppress("LongParameterList") // Network config + test/debug overrides
internal class OhttpTransport(
    private val relayUrl: String,
    private val network: String,
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
     * Optional pre-cached key configuration for testing. When set, skips the initial HTTP GET.
     * Production code should leave this null.
     */
    initialKeyConfig: OhttpKeyConfig? = null,
    /**
     * Optional pre-cached key configuration expiry timestamp (seconds). Only used when
     * [initialKeyConfig] is set. Production code should leave this null.
     */
    initialExpirySeconds: Long? = null,
    /**
     * Optional Ed25519 public key that replaces the embedded ZODL signing key when verifying the
     * signed key configuration, so tests can sign envelopes with a fresh key. Production code
     * must leave this null.
     */
    private val trustedSigningKey: ByteArray? = null,
) {
    private val lock = Any()
    private var cachedKeyConfig: OhttpKeyConfig? = initialKeyConfig
    private var cachedExpiry: Long? = initialExpirySeconds
    private val clientTrace: OhttpClientTrace? =
        if (OhttpDebugFlags.allowTracePrefix) {
            ohttpClientTraceIdPrefix?.let { OhttpClientTrace(it) }
        } else {
            null
        }

    /** Fetches (or returns the cached) gateway key configuration. */
    suspend fun keyConfig(forceRefresh: Boolean = false): OhttpKeyConfig {
        val nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
        synchronized(lock) {
            if (!forceRefresh) {
                val cached = cachedKeyConfig
                val expiry = cachedExpiry
                // A pre-seeded config without an expiry (test-only) never expires.
                if (cached != null && (expiry == null || nowSeconds < expiry)) {
                    return cached
                }
            }
        }

        // Derive signed config URL from relay URL
        val signedUrl = deriveSignedConfigUrl(relayUrl)
        val headers = listOf("Accept" to "application/json")
        val jsonBytes = sender.get(signedUrl, headers, KEY_FETCH_TIMEOUT_MS)
        val json = jsonBytes.toString(Charsets.UTF_8)

        // Verify and parse
        val (parsed, expiry) =
            OhttpSignedConfigVerifier.verifyAndParse(json, network, trustedPublicKey = trustedSigningKey)
        logger("OHTTP signed key config verified: key_id=${parsed.keyId}, network=$network, valid_until=$expiry")

        synchronized(lock) {
            cachedKeyConfig = parsed
            cachedExpiry = expiry
        }
        return parsed
    }

    /**
     * Performs one OHTTP round trip. A gateway rejection (HTTP 400 from the relay/gateway pair) is
     * retried once after refreshing the key configuration, which handles gateway key rotation.
     */
    suspend fun roundTrip(
        request: BinaryHttp.Request,
        timeoutMs: Int,
    ): BinaryHttp.Response {
        var keyConfig = keyConfig()
        return try {
            roundTripWith(keyConfig, request, timeoutMs)
        } catch (e: OhttpException) {
            when {
                e.httpStatus == HttpURLConnection.HTTP_BAD_REQUEST -> {
                    logger("OHTTP gateway rejected request (${e.message}); refreshing key config and retrying")
                    keyConfig = keyConfig(forceRefresh = true)
                    roundTripWith(keyConfig, request, timeoutMs)
                }

                e.httpStatus == HTTP_UNPROCESSABLE_ENTITY && (
                    e.message?.contains("ohttp-key", ignoreCase = true) == true ||
                        e.message?.contains("HPKE", ignoreCase = true) == true
                ) -> {
                    // 422 with problem type ohttp-key: invalidate cache and refetch
                    logger("OHTTP gateway 422 ohttp-key error (${e.message}); invalidating cache and retrying")
                    keyConfig = keyConfig(forceRefresh = true)
                    roundTripWith(keyConfig, request, timeoutMs)
                }

                else -> {
                    throw e
                }
            }
        }
    }

    private suspend fun roundTripWith(
        keyConfig: OhttpKeyConfig,
        request: BinaryHttp.Request,
        timeoutMs: Int,
    ): BinaryHttp.Response {
        // RFC 9458 §4.3 — Encapsulation of Requests
        val header = keyConfig.header()
        val info = REQUEST_LABEL + byteArrayOf(0x00) + header
        val context = Hpke.setupBaseS(keyConfig.publicKey, info)
        val plaintext = BinaryHttp.encodeRequest(request)
        val ciphertext = context.seal(ByteArray(0), plaintext)
        val encapsulatedRequest = header + context.enc + ciphertext

        val relayHeaders =
            buildList {
                add("Content-Type" to REQUEST_MEDIA_TYPE)
                add("Accept" to RESPONSE_MEDIA_TYPE)
                add("Accept-Encoding" to "identity")
                clientTrace?.let { trace ->
                    add("X-Client-Trace-Id" to trace.next())
                }
            }
        val encapsulatedResponse = sender.post(relayUrl, encapsulatedRequest, relayHeaders, timeoutMs)

        // RFC 9458 §4.4 — Encapsulation of Responses (client side: decapsulate)
        val nonceLength = maxOf(Hpke.N_N, Hpke.N_K)
        if (encapsulatedResponse.size < nonceLength + Hpke.N_T) {
            throw OhttpException("OHTTP response too short (${encapsulatedResponse.size} bytes)")
        }
        val responseNonce = encapsulatedResponse.copyOfRange(0, nonceLength)
        val responseCiphertext = encapsulatedResponse.copyOfRange(nonceLength, encapsulatedResponse.size)

        val secret = context.export(RESPONSE_LABEL, Hpke.N_K)
        val salt = context.enc + responseNonce
        val prk = Hpke.hkdfExtract(salt, secret)
        val aeadKey = Hpke.hkdfExpand(prk, "key".toByteArray(Charsets.US_ASCII), Hpke.N_K)
        val aeadNonce = Hpke.hkdfExpand(prk, "nonce".toByteArray(Charsets.US_ASCII), Hpke.N_N)

        val decrypted =
            try {
                Hpke.aesGcmOpen(aeadKey, aeadNonce, ByteArray(0), responseCiphertext)
            } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
                throw OhttpException("OHTTP response authentication failed", cause = e)
            }
        return BinaryHttp.decodeResponse(decrypted)
    }

    private fun deriveSignedConfigUrl(relayUrl: String): String {
        val base = relayUrl.trimEnd('/')
        return if (base.endsWith("/relay")) {
            base.removeSuffix("/relay") + "/ohttp-configs/signed"
        } else {
            // Fallback: just append /ohttp-configs/signed
            "$base/ohttp-configs/signed"
        }
    }

    companion object {
        const val REQUEST_MEDIA_TYPE = "message/ohttp-req"
        const val RESPONSE_MEDIA_TYPE = "message/ohttp-res"
        private const val MILLIS_PER_SECOND = 1000L
        private const val HTTP_UNPROCESSABLE_ENTITY = 422

        private val REQUEST_LABEL = "message/bhttp request".toByteArray(Charsets.US_ASCII)
        private val RESPONSE_LABEL = "message/bhttp response".toByteArray(Charsets.US_ASCII)

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
