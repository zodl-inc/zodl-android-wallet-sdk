package co.electriccoin.lightwallet.client

import android.util.Log
import co.electriccoin.lightwallet.client.internal.Constants
import co.electriccoin.lightwallet.client.internal.DirectOhttpSender
import co.electriccoin.lightwallet.client.internal.OhttpResponseSink
import co.electriccoin.lightwallet.client.internal.OhttpTransport
import co.electriccoin.lightwallet.client.internal.PROXY_GATEWAY_HOST
import co.electriccoin.lightwallet.client.internal.PROXY_GATEWAY_HOST_TESTNET
import co.electriccoin.lightwallet.client.internal.isOhttpProxyAllowedHost
import co.electriccoin.lightwallet.client.internal.proxyGatewayHostFor
import co.electriccoin.lightwallet.client.internal.stripSecretsForGateway
import co.electriccoin.lightwallet.client.util.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

class OhttpHttpClient private constructor(
    codec: OhttpCodec,
    private val sender: OhttpSender,
    /**
     * Optional client trace ID prefix. When set, every outer OHTTP POST to the relay carries
     * `X-Client-Trace-Id: <prefix>-<sessionUuid>-<seq>`. See OhttpTransport.ohttpClientTraceIdPrefix
     * for details.
     */
    private val ohttpClientTraceIdPrefix: String? = null,
    gatewayHost: String = PROXY_GATEWAY_HOST,
) : Disposable {
    // F-07 fix: Reuse OhttpTransport instance to share key cache across requests
    private val transport =
        OhttpTransport(
            relayUrl =
                codec.relayUrlFor(gatewayHost)
                    ?: error("No OHTTP relay configured for gateway host $gatewayHost"),
            gatewayHost = gatewayHost,
            codec = codec,
            sender = sender,
            ohttpClientTraceIdPrefix = ohttpClientTraceIdPrefix,
        )

    data class Request(
        val url: String,
        val method: String = "GET",
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
    )

    data class Response(
        val status: Int,
        val body: ByteArray,
        val headers: List<Pair<String, String>> = emptyList(),
    )

    /**
     * Receives the inner response of [executeStreaming] as it is opened: [onHead] once, [onData] for
     * the body bytes of each chunk as soon as it is decrypted, [onEnd] once the response is complete
     * and authenticated. Called on the reading thread; a listener that throws stops the read.
     */
    interface ResponseListener {
        fun onHead(
            status: Int,
            headers: List<Pair<String, String>>,
        )

        fun onData(bytes: ByteArray)

        fun onEnd()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000

        /**
         * Creates an OHTTP client for external API requests (NEAR, CMC, voting).
         *
         * Privacy routing:
         * - [sender] = DirectOhttpSender() (default, Tor OFF): direct OHTTP via HttpURLConnection. The relay sees
         *   the app's clearnet IP; the gateway (which injects secrets) does not.
         * - [sender] = TorOhttpSender (Tor ON): OHTTP relay leg goes over an isolated Tor stream. The
         *   relay sees only a Tor exit; the gateway still injects secrets server-side. The app MUST
         *   NOT send secrets in either mode (they are stripped with a warning if present).
         *
         * The returned client takes ownership of [sender]: [dispose] disposes it, so do not share one
         * sender between two clients.
         *
         * @param codec the native OHTTP protocol implementation; in an app that uses the SDK, get it from
         *   `cash.z.ecc.android.sdk.util.OhttpCodecFactory`.
         * @param ohttpClientTraceIdPrefix Optional client trace ID prefix for end-to-end tracing.
         * @param testnet seal to the testnet gateway (through its relay) instead of the mainnet one: the
         *   gateway must match the wallet's network, as the SDK's own voting route does.
         */
        fun create(
            codec: OhttpCodec,
            sender: OhttpSender = DirectOhttpSender(),
            ohttpClientTraceIdPrefix: String? = null,
            testnet: Boolean = false,
        ): OhttpHttpClient =
            OhttpHttpClient(
                codec,
                sender,
                ohttpClientTraceIdPrefix,
                if (testnet) PROXY_GATEWAY_HOST_TESTNET else PROXY_GATEWAY_HOST,
            )
    }

    /**
     * Returns true if [url] can be routed through the OHTTP gateway: either the host has a static
     * proxy mapping (near.zodl.com, vote.zodl.com) or it is one of the hosts the gateway proxies
     * (exact match, the gateway's BACKENDS).
     */
    fun canHandle(url: String): Boolean {
        val host = runCatching { URI.create(url).host }.getOrNull()
        if (host == null) {
            Log.w(Constants.LOG_TAG, "OHTTP canHandle: no host in $url")
            return false
        }
        val mapped = proxyGatewayHostFor(host) != null
        val allowlisted = isOhttpProxyAllowedHost(host)
        Log.d(
            Constants.LOG_TAG,
            "OHTTP canHandle host=$host mapped=$mapped allowlisted=$allowlisted",
        )
        return mapped || allowlisted
    }

    /** Sends [request] through the gateway and returns the whole inner response, within 30 s. */
    suspend fun execute(request: Request): Response = execute(request, DEFAULT_TIMEOUT_MS)

    /** Sends [request] through the gateway and returns the whole inner response, within [timeoutMs]. */
    suspend fun execute(
        request: Request,
        timeoutMs: Int,
    ): Response {
        val bReq = codecRequest(request)
        val bRes = withContext(Dispatchers.IO) { transport.roundTrip(bReq, timeoutMs) }
        Log.d(
            Constants.LOG_TAG,
            "OHTTP proxy ${request.method} host=${bReq.authority} -> http=${bRes.status} (${bRes.body.size} bytes)",
        )
        return Response(status = bRes.status, body = bRes.body, headers = bRes.headers)
    }

    /**
     * Sends [request] like [execute], but hands the inner response to [listener] as its chunks arrive
     * (e.g. a `text/event-stream`), within [timeoutMs] for the whole exchange. Returns once the response
     * is complete; a failure after the head (truncation, a chunk that does not open, the deadline) is
     * thrown after whatever [listener] already got. Cancelling the caller stops a blocked read.
     */
    suspend fun executeStreaming(
        request: Request,
        timeoutMs: Int,
        listener: ResponseListener,
    ) {
        val bReq = codecRequest(request)
        withContext(Dispatchers.IO) {
            transport.stream(
                bReq,
                timeoutMs,
                object : OhttpResponseSink {
                    override fun onHead(
                        status: Int,
                        headers: List<Pair<String, String>>,
                    ) = listener.onHead(status, headers)

                    override fun onData(bytes: ByteArray) = listener.onData(bytes)

                    override fun onTrailers(trailers: List<Pair<String, String>>) = Unit

                    override fun onEnd() = listener.onEnd()
                },
            )
        }
    }

    private fun codecRequest(request: Request): OhttpCodecRequest {
        val uri = URI.create(request.url)
        val originalHost = uri.host ?: error("No host in URL: ${request.url}")
        // The gateway proxies its allowlisted hosts directly (the inner request's
        // :authority is NOT rewritten). Static proxy mappings (near.zodl.com, vote.zodl.com)
        // still get rewritten for backward compatibility.
        val proxyHost = proxyGatewayHostFor(originalHost)
        val innerAuthority =
            when {
                proxyHost != null -> proxyHost
                isOhttpProxyAllowedHost(originalHost) -> originalHost
                else -> error("No OHTTP proxy for: $originalHost")
            }
        // Strip secrets for gateway-mapped hosts (cmc.zodl.com, near.zodl.com) — the gateway
        // injects them server-side. The app should not send these at all; log a warning if present.
        val filteredHeaders =
            stripSecretsForGateway(innerAuthority, request.headers) { msg ->
                Log.w(Constants.LOG_TAG, msg)
            }
        val pathQ =
            uri.rawPath
                .let { p ->
                    uri.rawQuery?.let { q -> "$p?$q" } ?: p
                }.ifEmpty { "/" }
        val bReq =
            OhttpCodecRequest(
                method = request.method,
                scheme = uri.scheme ?: "https",
                authority = innerAuthority,
                path = pathQ,
                headers = filteredHeaders,
                body = request.body,
            )
        val mode = if (proxyHost != null) "mapped" else "allowlist"
        val senderType = if (sender is DirectOhttpSender) "direct" else "Tor"
        Log.d(
            Constants.LOG_TAG,
            "OHTTP proxy ${request.method} host=$originalHost authority=$innerAuthority mode=$mode sender=$senderType",
        )
        return bReq
    }

    /**
     * Releases the underlying [sender] (e.g. a Tor-backed sender's isolated native `TorClient`).
     * Callers that cache an [OhttpHttpClient] and rebuild it on a Tor-preference change (see
     * `HttpClientProviderImpl.createOhttp` in the app) must dispose the outgoing instance before
     * dropping their reference, or the previous sender's native resources leak.
     */
    override suspend fun dispose() = sender.dispose()
}
