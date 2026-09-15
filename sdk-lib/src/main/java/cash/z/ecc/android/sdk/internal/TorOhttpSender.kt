package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.JniHttpHeader
import cash.z.ecc.android.sdk.internal.model.TorClient
import co.electriccoin.lightwallet.client.internal.OhttpSender
import co.electriccoin.lightwallet.client.internal.OhttpTransport
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
            throw OhttpTransport.OhttpException(
                "Relay/gateway returned HTTP ${response.status} (Tor path)",
                response.status
            )
        }
        return response.body
    }
}
