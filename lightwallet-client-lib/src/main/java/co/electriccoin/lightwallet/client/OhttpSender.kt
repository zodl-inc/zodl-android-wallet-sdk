package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.util.Disposable

/**
 * Transport abstraction for the OHTTP relay POST leg, supporting both direct HttpURLConnection
 * and Tor-backed sending. The Tor path is used when Tor is enabled in the app and the target
 * is a gateway-mapped external API (CMC, NEAR) — the relay leg goes over Tor, hiding the
 * app's IP from the relay, and the gateway injects the secret server-side.
 *
 * Lives in the top-level `co.electriccoin.lightwallet.client` package (not `.internal`) because
 * it appears on public API surface: [co.electriccoin.lightwallet.client.OhttpHttpClient.Companion.create]
 * takes one, and [cash.z.ecc.android.sdk.Synchronizer.ohttpSenderOverTor] returns one.
 *
 * Extends [Disposable]: a Tor-backed sender wraps an isolated native `TorClient` (see
 * `cash.z.ecc.android.sdk.internal.TorOhttpSender`) that must be released once the sender is
 * replaced or no longer needed (e.g. on a Tor-preference toggle or [OhttpHttpClient] teardown) --
 * without a dispose path on the interface itself, a caller holding only an [OhttpSender]/
 * [OhttpHttpClient] reference has no way to free that native resource at all.
 */
interface OhttpSender : Disposable {
    /**
     * POSTs [body] to [url] with the given [headers], returning the response body. [timeoutMs] bounds
     * the whole exchange, not only each read.
     * @throws OhttpException on failure (network, HTTP error, timeout); a 429 carries the relay's
     * `Retry-After` as [OhttpException.retryAfterSeconds].
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
     * @throws OhttpException on failure (network, HTTP error, timeout).
     */
    suspend fun get(
        url: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int,
    ): ByteArray

    companion object {
        /**
         * The largest sealed response a sender reads: the gateway's 256 MiB backend body limit plus
         * 64 KiB of framing, encryption overhead and header fields. Mirrors
         * `GATEWAY_MAX_SEALED_RESPONSE_BYTES` of the native OHTTP core, which enforces it again on
         * the whole body before decrypting; a sender uses it to stop reading a hostile relay's
         * answer early. (`RustOhttpToolTest` checks that the two agree.)
         */
        const val MAX_SEALED_RESPONSE_BYTES: Int = 268_435_456 + 65_536

        /**
         * The largest key configuration document a sender reads (64 KiB). Mirrors `MAX_KEY_CONFIG_BYTES`
         * of the native OHTTP core, which enforces it again before parsing the envelope.
         */
        const val MAX_KEY_CONFIG_BYTES: Int = 64 * 1024

        /** A `Retry-After` value in the delay-seconds form, or null (absent, an HTTP date, malformed). */
        fun retryAfterSeconds(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }
}
