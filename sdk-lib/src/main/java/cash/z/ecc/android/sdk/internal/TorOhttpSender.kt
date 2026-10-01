package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.JniHttpHeader
import cash.z.ecc.android.sdk.internal.model.JniHttpResponseBytes
import cash.z.ecc.android.sdk.internal.model.TorClient
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
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
                "Relay/gateway returned HTTP ${response.status} (Tor path)${response.errorDetail()}",
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
                "GET request failed: HTTP ${response.status} (Tor path)${response.errorDetail()}",
                response.status
            )
        }
        return response.body.requireWithinResponseSizeLimit()
    }

    /**
     * Releases the isolated [TorClient] this sender wraps (see [cash.z.ecc.android.sdk.Synchronizer.getTorClient]
     * -- each call creates a fresh isolated native Tor handle). The caller of
     * [cash.z.ecc.android.sdk.Synchronizer.ohttpSenderOverTor] owns this sender and must dispose it
     * once it is replaced (e.g. the Tor preference toggles) or no longer needed, or the underlying
     * native Tor runtime handle leaks for the rest of the process lifetime.
     */
    override suspend fun dispose() = torClient.dispose()

    /**
     * Renders the JNI response body as `": <detail>"` for use in an [OhttpException] message, or
     * "" if empty. The body carries the gateway's problem-details JSON (e.g. type "ohttp-key"). Mirrors [co.electriccoin.lightwallet.client.internal.DirectOhttpSender]'s
     * error-stream handling so callers (e.g. [co.electriccoin.lightwallet.client.internal.OhttpTransport]'s
     * 422 ohttp-key retry branch, which matches on message text) see the same gateway error detail
     * regardless of whether Tor is on.
     */
    private fun JniHttpResponseBytes.errorDetail(): String =
        body.toString(Charsets.UTF_8).take(ERROR_MESSAGE_MAX_LENGTH).let { if (it.isEmpty()) "" else ": $it" }

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

        const val ERROR_MESSAGE_MAX_LENGTH = 200
    }
}
