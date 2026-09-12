package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.BinaryHttp
import co.electriccoin.lightwallet.client.internal.OhttpEndpoints
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
        val transport = OhttpTransport(
            OhttpEndpoints.relayUrlFor(proxyHost),
            OhttpEndpoints.keyConfigUrlFor(proxyHost)
        )
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
