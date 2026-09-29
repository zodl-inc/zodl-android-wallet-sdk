package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.JniHttpHeader
import cash.z.ecc.android.sdk.internal.model.TorClient
import co.electriccoin.lightwallet.client.OhttpSender
import co.electriccoin.lightwallet.client.internal.OhttpException
import java.net.HttpURLConnection

/**
 * Tor-backed OHTTP sender: routes the relay POST through an isolated Tor circuit. Used when
 * Tor is ON and the request targets a gateway-mapped external API (CMC, NEAR), ensuring the
 * relay never sees the app's clearnet IP. The gateway injects the API secret server-side.
 */
class TorOhttpSender(
    private val torClient: TorClient
) : OhttpSender {
    override suspend fun post(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int
    ): ByteArray {
        val jniHeaders = headers.map { (key, value) -> JniHttpHeader(key, value) }
        // Retry limit 0: OHTTP already has its own retry-on-400 logic (key rotation); we don't
        // want the Tor client retrying on top of that (would multiply total attempts).
        val response = torClient.httpPost(url, jniHeaders, body, retryLimit = 0)
        if (response.status != HttpURLConnection.HTTP_OK) {
            throw OhttpException(
                "Relay/gateway returned HTTP ${response.status} (Tor path)",
                response.status
            )
        }
        return response.body.requireWithinResponseSizeLimit()
    }

    override suspend fun get(
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int
    ): ByteArray {
        val jniHeaders = headers.map { (key, value) -> JniHttpHeader(key, value) }
        // Retry limit 0: consistent with post() — no additional retries at the Tor layer.
        val response = torClient.httpGet(url, jniHeaders, retryLimit = 0)
        if (response.status != HttpURLConnection.HTTP_OK) {
            throw OhttpException(
                "GET request failed: HTTP ${response.status} (Tor path)",
                response.status
            )
        }
        return response.body.requireWithinResponseSizeLimit()
    }

    /**
     * `torClient.httpPost`/`httpGet` cross the JNI boundary as an already-fully-materialized
     * [ByteArray] -- unlike [co.electriccoin.lightwallet.client.internal.DirectOhttpSender], there
     * is no streaming read to bound here, so this can only reject an oversized response after the
     * fact, not prevent the Rust side from buffering it. Kept for parity: a malicious/compromised
     * relay or gateway should not get a bigger blast radius just because Tor is on.
     */
    private fun ByteArray.requireWithinResponseSizeLimit(): ByteArray {
        if (size > MAX_RESPONSE_SIZE_BYTES) {
            throw OhttpException("Response exceeded the $MAX_RESPONSE_SIZE_BYTES byte limit (Tor path)")
        }
        return this
    }

    private companion object {
        // Matches DirectOhttpSender.MAX_RESPONSE_SIZE_BYTES.
        const val MAX_RESPONSE_SIZE_BYTES = 64 * 1024 * 1024
    }
}
