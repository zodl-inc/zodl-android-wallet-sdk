package co.electriccoin.lightwallet.client.internal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

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
    private val logger: (String) -> Unit = {}
) {
    private val lock = Any()
    private var cachedKeyConfig: OhttpKeyConfig? = null

    class OhttpException(
        message: String,
        val httpStatus: Int? = null,
        cause: Throwable? = null
    ) : IOException(message, cause)

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
    fun roundTrip(request: BinaryHttp.Request, timeoutMs: Int): BinaryHttp.Response {
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

    private fun roundTripWith(
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

        val encapsulatedResponse = httpPost(relayUrl, encapsulatedRequest, timeoutMs)

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

    private fun httpPost(url: String, body: ByteArray, timeoutMs: Int): ByteArray {
        val connection = open(url, timeoutMs)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", REQUEST_MEDIA_TYPE)
            connection.setRequestProperty("Accept", RESPONSE_MEDIA_TYPE)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val detail =
                    connection.errorStream
                        ?.use { it.readFully() }
                        ?.toString(Charsets.UTF_8)
                        ?.take(200)
                throw OhttpException("Relay/gateway returned HTTP $status${detail?.let { ": $it" } ?: ""}", status)
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
        val buffer = ByteArray(16 * 1024)
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
    }
}
