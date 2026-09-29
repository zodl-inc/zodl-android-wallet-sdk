package co.electriccoin.lightwallet.client

/**
 * Transport abstraction for the OHTTP relay POST leg, supporting both direct HttpURLConnection
 * and Tor-backed sending. The Tor path is used when Tor is enabled in the app and the target
 * is a gateway-mapped external API (CMC, NEAR) — the relay leg goes over Tor, hiding the
 * app's IP from the relay, and the gateway injects the secret server-side.
 *
 * Lives in the top-level `co.electriccoin.lightwallet.client` package (not `.internal`) because
 * it appears on public API surface: [co.electriccoin.lightwallet.client.OhttpHttpClient.Companion.create]
 * takes one, and [cash.z.ecc.android.sdk.Synchronizer.ohttpSenderOverTor] returns one.
 */
interface OhttpSender {
    /**
     * POSTs [body] to [url] with the given [headers] and [timeoutMs], returning the response body.
     * @throws co.electriccoin.lightwallet.client.internal.OhttpException on failure (network, HTTP error, timeout).
     */
    suspend fun post(
        url: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray

    /**
     * GETs [url] with the given [headers] and [timeoutMs], returning the response body.
     * Used for fetching the gateway key configuration. With Tor enabled, this ensures the key
     * config fetch also goes over Tor, preventing the client IP from being exposed to the gateway.
     * @throws co.electriccoin.lightwallet.client.internal.OhttpException on failure (network, HTTP error, timeout).
     */
    suspend fun get(
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray
}
