package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.BinaryHttp
import co.electriccoin.lightwallet.client.internal.PROXY_KEY_CONFIG_URL
import co.electriccoin.lightwallet.client.internal.PROXY_RELAY_URL
import co.electriccoin.lightwallet.client.internal.OhttpTransport
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
    data class Response(val status: Int, val body: ByteArray)

    fun canHandle(url: String): Boolean {
        val host = runCatching { URI.create(url).host }.getOrNull() ?: return false
        return proxyGatewayHostFor(host) != null
    }

    suspend fun execute(request: Request): Response {
        val uri = URI.create(request.url)
        val originalHost = uri.host ?: throw IllegalArgumentException("No host in URL: ${request.url}")
        val proxyHost = proxyGatewayHostFor(originalHost)
            ?: throw IllegalArgumentException("No OHTTP proxy for: $originalHost")
        // The proxied authority (near.zodl.com, vote.zodl.com, ...) is NOT a gateway host: the
        // encapsulated request still travels relay -> ohttp-gateway.example, which routes by the
        // inner `:authority`. Using relayUrlFor(proxyHost) here threw IllegalArgumentException
        // before any network I/O, so every Tor-OFF NEAR/voting call failed.
        val transport = OhttpTransport(PROXY_RELAY_URL, PROXY_KEY_CONFIG_URL)
        val pathQ = uri.rawPath.let { p ->
            uri.rawQuery?.let { q -> "$p?$q" } ?: p
        }.ifEmpty { "/" }
        val bReq = BinaryHttp.Request(
            method = request.method,
            scheme = uri.scheme ?: "https",
            authority = proxyHost,
            path = pathQ,
            headers = request.headers,
            body = request.body
        )
        val bRes = withContext(Dispatchers.IO) { transport.roundTrip(bReq, 30_000) }
        return Response(status = bRes.status, body = bRes.body)
    }
}
