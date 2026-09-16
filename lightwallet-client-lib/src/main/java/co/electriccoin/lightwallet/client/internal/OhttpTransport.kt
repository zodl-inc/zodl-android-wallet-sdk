package co.electriccoin.lightwallet.client.internal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * OHTTP (RFC 9458) client transport: encapsulates a Binary HTTP request with HPKE, POSTs it to the
 * relay and decapsulates the gateway's response.
 *
 * Pure JVM (`HttpURLConnection`) so that the whole OHTTP path can be exercised from a plain unit
 * test against the real gateway before it is wired into gRPC.
 */
internal class OhttpTransport(
    private val relayUrl: String,
    private val keyConfigUrl: String,
    private val sender: OhttpSender = DirectOhttpSender(),
    private val logger: (String) -> Unit = {},
    /**
     * Optional client trace ID prefix. When set, every outer OHTTP POST to the relay carries
     * `X-Client-Trace-Id: <prefix>-<uuid>` so a tester can tag and follow requests end-to-end in
     * Grafana (relay → gateway → downstream). The prefix is sanitised to `[A-Za-z0-9._:-]` and
     * truncated so the full header value ≤ 64 chars.
     *
     * **Privacy warning:** a custom trace ID makes that wallet's requests linkable across relay
     * and gateway logs. DEBUG ONLY. Never set in release builds. Off by default.
     */
    private val ohttpClientTraceIdPrefix: String? = null
) {
    private val lock = Any()
    private var cachedKeyConfig: OhttpKeyConfig? = null

    /** Fetches (or returns the cached) gateway key configuration. */
    fun keyConfig(forceRefresh: Boolean = false): OhttpKeyConfig {
        synchronized(lock) {
            if (!forceRefresh) cachedKeyConfig?.let { return it }
        }
        val bytes = httpGet(keyConfigUrl, KEY_FETCH_TIMEOUT_MS)
        val parsed = OhttpKeyConfig.parseSupported(bytes)
        logger("OHTTP key config fetched: key_id=${parsed.keyId}")
        synchronized(lock) { cachedKeyConfig = parsed }
        return parsed
    }

    /**
     * Performs one OHTTP round trip. A gateway rejection (HTTP 400 from the relay/gateway pair) is
     * retried once after refreshing the key configuration, which handles gateway key rotation.
     */
    suspend fun roundTrip(request: BinaryHttp.Request, timeoutMs: Int): BinaryHttp.Response {
        var keyConfig = keyConfig()
        return try {
            roundTripWith(keyConfig, request, timeoutMs)
        } catch (e: OhttpException) {
            if (e.httpStatus == HttpURLConnection.HTTP_BAD_REQUEST) {
                logger("OHTTP gateway rejected request (${e.message}); refreshing key config and retrying")
                keyConfig = keyConfig(forceRefresh = true)
                roundTripWith(keyConfig, request, timeoutMs)
            } else {
                throw e
            }
        }
    }

    private suspend fun roundTripWith(
        keyConfig: OhttpKeyConfig,
        request: BinaryHttp.Request,
        timeoutMs: Int
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
                ohttpClientTraceIdPrefix?.let { prefix ->
                    val traceId = generateClientTraceId(prefix)
                    add("X-Client-Trace-Id" to traceId)
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

    // ---- HTTP ------------------------------------------------------------------------------------

    private fun httpGet(url: String, timeoutMs: Int): ByteArray {
        val connection = open(url, timeoutMs)
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", KEYS_MEDIA_TYPE)
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw OhttpException("Key config fetch failed: HTTP $status", status)
            }
            return connection.inputStream.use { it.readFully() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = timeoutMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        return connection
    }

    private fun InputStream.readFully(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val REQUEST_MEDIA_TYPE = "message/ohttp-req"
        const val RESPONSE_MEDIA_TYPE = "message/ohttp-res"
        const val KEYS_MEDIA_TYPE = "application/ohttp-keys"

        private val REQUEST_LABEL = "message/bhttp request".toByteArray(Charsets.US_ASCII)
        private val RESPONSE_LABEL = "message/bhttp response".toByteArray(Charsets.US_ASCII)

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val KEY_FETCH_TIMEOUT_MS = 15_000
        private const val READ_BUFFER_SIZE = 16 * 1024
        private const val MAX_CLIENT_TRACE_ID_LENGTH = 64

        /**
         * Generates a client trace ID from the given prefix: `<sanitized-prefix>-<uuid>`, truncated to
         * 64 chars. The prefix is sanitised to match `^[A-Za-z0-9._:-]+$` (relay/gateway requirement).
         */
        private fun generateClientTraceId(prefix: String): String {
            val sanitized = prefix.replace(Regex("[^A-Za-z0-9._:-]"), "")
            val uuid = UUID.randomUUID().toString()
            val full = "$sanitized-$uuid"
            return if (full.length <= MAX_CLIENT_TRACE_ID_LENGTH) {
                full
            } else {
                full.take(MAX_CLIENT_TRACE_ID_LENGTH)
            }
        }
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
    logger: (String) -> Unit
): List<Pair<String, String>> {
    if (authority !in SECRET_INJECTING_GATEWAY_HOSTS) return headers
    val stripped = headers.filter { (key, _) -> key !in SECRET_HEADERS }
    if (stripped.size < headers.size) {
        val removed = headers.filter { (key, _) -> key in SECRET_HEADERS }.map { it.first }
        logger(
            "OHTTP: stripped secret headers $removed for gateway-mapped host $authority " +
                "(gateway injects server-side)"
        )
    }
    return stripped
}
