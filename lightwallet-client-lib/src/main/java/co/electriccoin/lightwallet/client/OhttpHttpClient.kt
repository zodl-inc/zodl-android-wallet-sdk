package co.electriccoin.lightwallet.client

import android.util.Log
import co.electriccoin.lightwallet.client.internal.BinaryHttp
import co.electriccoin.lightwallet.client.internal.Constants
import co.electriccoin.lightwallet.client.internal.DirectOhttpSender
import co.electriccoin.lightwallet.client.internal.OHTTP_PROXY_ALLOWED_SUFFIXES
import co.electriccoin.lightwallet.client.internal.OhttpSender
import co.electriccoin.lightwallet.client.internal.OhttpTransport
import co.electriccoin.lightwallet.client.internal.PROXY_KEY_CONFIG_URL
import co.electriccoin.lightwallet.client.internal.PROXY_RELAY_URL
import co.electriccoin.lightwallet.client.internal.isOhttpProxyAllowedHost
import co.electriccoin.lightwallet.client.internal.proxyGatewayHostFor
import co.electriccoin.lightwallet.client.internal.stripSecretsForGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

class OhttpHttpClient private constructor(
    private val sender: OhttpSender,
    /**
     * Optional client trace ID prefix. When set, every outer OHTTP POST to the relay carries
     * `X-Client-Trace-Id: <prefix>-<sessionUuid>-<seq>`. See OhttpTransport.ohttpClientTraceIdPrefix
     * for details.
     */
    private val ohttpClientTraceIdPrefix: String? = null
) {
    data class Request(
        val url: String,
        val method: String = "GET",
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0)
    )

    data class Response(
        val status: Int,
        val body: ByteArray
    )

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000

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
         * @param ohttpClientTraceIdPrefix Optional client trace ID prefix for end-to-end tracing.
         */
        fun create(
            sender: OhttpSender = DirectOhttpSender(),
            ohttpClientTraceIdPrefix: String? = null
        ): OhttpHttpClient = OhttpHttpClient(sender, ohttpClientTraceIdPrefix)
    }

    /**
     * Returns true if [url] can be routed through the OHTTP gateway: either the host has a static
     * proxy mapping (near.zodl.com, vote.zodl.com) or it matches an allowlisted
     * suffix (.valargroup.org, .valargroup.dev, chaindefuser.com).
     */
    fun canHandle(url: String): Boolean {
        val host = runCatching { URI.create(url).host }.getOrNull()
        if (host == null) {
            Log.w(Constants.LOG_TAG, "OHTTP canHandle: no host in $url")
            return false
        }
        val mapped = proxyGatewayHostFor(host) != null
        val allowlisted = isOhttpProxyAllowedHost(host)
        Log.i(
            Constants.LOG_TAG,
            "OHTTP canHandle host=$host mapped=$mapped allowlisted=$allowlisted suffixes=$OHTTP_PROXY_ALLOWED_SUFFIXES"
        )
        return mapped || allowlisted
    }

    suspend fun execute(request: Request): Response {
        val uri = URI.create(request.url)
        val originalHost = uri.host ?: error("No host in URL: ${request.url}")
        // Gateway v1.4.5+ supports direct proxying for allowlisted suffixes (the inner request's
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
        val transport =
            OhttpTransport(
                PROXY_RELAY_URL,
                PROXY_KEY_CONFIG_URL,
                sender,
                ohttpClientTraceIdPrefix = ohttpClientTraceIdPrefix
            )
        val pathQ =
            uri.rawPath
                .let { p ->
                    uri.rawQuery?.let { q -> "$p?$q" } ?: p
                }.ifEmpty { "/" }
        val bReq =
            BinaryHttp.Request(
                method = request.method,
                scheme = uri.scheme ?: "https",
                authority = innerAuthority,
                path = pathQ,
                headers = filteredHeaders,
                body = request.body
            )
        val mode = if (proxyHost != null) "mapped" else "allowlist"
        val senderType = if (sender is DirectOhttpSender) "direct" else "Tor"
        Log.i(
            Constants.LOG_TAG,
            "OHTTP proxy ${request.method} host=$originalHost authority=$innerAuthority mode=$mode sender=$senderType"
        )
        val bRes = withContext(Dispatchers.IO) { transport.roundTrip(bReq, DEFAULT_TIMEOUT_MS) }
        Log.i(
            Constants.LOG_TAG,
            "OHTTP proxy ${request.method} host=$originalHost -> http=${bRes.status} (${bRes.body.size} bytes)"
        )
        return Response(status = bRes.status, body = bRes.body)
    }
}
