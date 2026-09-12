package co.electriccoin.lightwallet.client

import android.util.Log
import co.electriccoin.lightwallet.client.internal.BinaryHttp
import co.electriccoin.lightwallet.client.internal.Constants
import co.electriccoin.lightwallet.client.internal.OhttpTransport
import co.electriccoin.lightwallet.client.internal.PROXY_KEY_CONFIG_URL
import co.electriccoin.lightwallet.client.internal.PROXY_RELAY_URL
import co.electriccoin.lightwallet.client.internal.isOhttpProxyAllowedHost
import co.electriccoin.lightwallet.client.internal.proxyGatewayHostFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

class OhttpHttpClient {
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
    }

    /**
     * Returns true if [url] can be routed through the OHTTP gateway: either the host has a static
     * proxy mapping (near.zodl.com, vote.zodl.com, params.zodl.com) or it matches an allowlisted
     * suffix (.valargroup.org, .valargroup.dev, chaindefuser.com).
     */
    fun canHandle(url: String): Boolean {
        val host = runCatching { URI.create(url).host }.getOrNull() ?: return false
        return proxyGatewayHostFor(host) != null || isOhttpProxyAllowedHost(host)
    }

    suspend fun execute(request: Request): Response {
        val uri = URI.create(request.url)
        val originalHost = uri.host ?: error("No host in URL: ${request.url}")
        // Gateway v1.4.5+ supports direct proxying for allowlisted suffixes (the inner request's
        // :authority is NOT rewritten). Static proxy mappings (near.zodl.com, vote.zodl.com,
        // params.zodl.com) still get rewritten for backward compatibility.
        val proxyHost = proxyGatewayHostFor(originalHost)
        val innerAuthority =
            when {
                proxyHost != null -> proxyHost
                isOhttpProxyAllowedHost(originalHost) -> originalHost
                else -> error("No OHTTP proxy for: $originalHost")
            }
        val transport = OhttpTransport(PROXY_RELAY_URL, PROXY_KEY_CONFIG_URL)
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
                headers = request.headers,
                body = request.body
            )
        val mode = if (proxyHost != null) "mapped" else "allowlist"
        Log.i(
            Constants.LOG_TAG,
            "OHTTP proxy ${request.method} host=$originalHost authority=$innerAuthority mode=$mode"
        )
        val bRes = withContext(Dispatchers.IO) { transport.roundTrip(bReq, DEFAULT_TIMEOUT_MS) }
        Log.i(
            Constants.LOG_TAG,
            "OHTTP proxy ${request.method} host=$originalHost -> http=${bRes.status} (${bRes.body.size} bytes)"
        )
        return Response(status = bRes.status, body = bRes.body)
    }
}
