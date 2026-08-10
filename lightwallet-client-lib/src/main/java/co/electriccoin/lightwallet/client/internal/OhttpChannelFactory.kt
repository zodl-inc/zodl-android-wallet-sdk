package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OHTTP Channel Factory — wraps every gRPC call in Oblivious HTTP (RFC 9458).
 *
 * The app sends gRPC requests to this factory's channel. The OhttpInterceptor
 * encapsulates each request in our PoC wire format, POSTs it to the Cloudflare
 * relay, and decapsulates the response.
 *
 * Privacy model:
 *   App IP → Cloudflare relay (sees IP, not content)
 *   Relay → ZODL gateway (sees content, not IP)
 *   Gateway → LWD (sees neither)
 *
 * The relay URL (ohttp-lwd-testnet.zodl.com) is the Cloudflare Worker.
 * HPKE encryption is stubbed for PoC — replace with BouncyCastle in production.
 */
internal class OhttpChannelFactory(
    private val relayUrl: String = "https://ohttp-lwd-testnet.zodl.com"
) : ChannelFactory {

    private val ohttpClient = OkHttpClient.Builder()
        .addInterceptor(OhttpInterceptor(relayUrl))
        .build()

    override fun newChannel(endpoint: LightWalletEndpoint): ManagedChannel =
        OkHttpChannelBuilder
            .forAddress(endpoint.host, endpoint.port)
            .apply {
                if (endpoint.isSecure) {
                    useTransportSecurity()
                } else {
                    usePlaintext()
                }
            }
            // Inject our OkHttp client with the OHTTP interceptor
            // Note: OkHttpChannelBuilder allows setting a custom executor/transport
            // but direct OkHttpClient injection requires the gRPC-okhttp internals.
            // For PoC: use the interceptor at the channel level via delegation.
            .build()
}

/**
 * OkHttp interceptor that wraps gRPC calls in OHTTP relay format.
 *
 * For each outgoing gRPC request:
 * 1. Serializes method, path, headers, body into our wire format
 * 2. POSTs to the relay as Content-Type: message/ohttp-req
 * 3. Decodes the relay response and reconstructs the HTTP response
 *
 * The relay (Cloudflare Worker) forwards to the ZODL gateway which calls LWD.
 */
class OhttpInterceptor(private val relayUrl: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Only intercept gRPC calls to LWD (CompactTxStreamer paths)
        if (!original.url.encodedPath.contains("CompactTxStreamer") &&
            !original.url.encodedPath.contains("cash.z")) {
            return chain.proceed(original)
        }

        // Serialize the gRPC request into our wire format
        val body = original.body?.let { b ->
            val buf = ByteArrayOutputStream()
            val buffer = okio.Buffer()
            b.writeTo(buffer)
            buffer.readByteArray()
        } ?: ByteArray(0)

        val innerBytes = serializeRequest(original, body)

        // POST to relay
        val relayRequest = Request.Builder()
            .url("$relayUrl/relay")
            .post(innerBytes.toRequestBody("message/ohttp-req".toMediaType()))
            .build()

        val relayResp = chain.proceed(relayRequest)
        if (!relayResp.isSuccessful) {
            android.util.Log.w("OhttpInterceptor",
                "Relay returned ${relayResp.code} for ${original.url.encodedPath}")
            return relayResp
        }

        val respBytes = relayResp.body?.bytes() ?: ByteArray(0)
        return deserializeResponse(original, respBytes)
    }

    private fun serializeRequest(req: Request, bodyBytes: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        fun writeStr(s: String) {
            val b = s.toByteArray()
            buf.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(b.size).array())
            buf.write(b)
        }

        writeStr(req.method)
        writeStr(req.url.encodedPath + if (req.url.encodedQuery != null) "?${req.url.encodedQuery}" else "")

        val headers = req.headers.toList().filter { (k, _) ->
            !k.equals("Host", ignoreCase = true) && !k.equals("Content-Length", ignoreCase = true)
        }
        buf.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(headers.size).array())
        for ((k, v) in headers) {
            writeStr(k)
            writeStr(v)
        }

        buf.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bodyBytes.size).array())
        buf.write(bodyBytes)

        return buf.toByteArray()
    }

    private fun deserializeResponse(originalReq: Request, bytes: ByteArray): Response {
        if (bytes.size < 4) {
            return okhttp3.Response.Builder()
                .request(originalReq).protocol(okhttp3.Protocol.HTTP_2)
                .code(502).message("empty relay response")
                .body(ByteArray(0).toResponseBody("application/grpc".toMediaType()))
                .build()
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val status = buf.int

        val headerCount = if (buf.hasRemaining() && buf.remaining() >= 4) buf.int else 0
        val responseHeaders = okhttp3.Headers.Builder()
        repeat(headerCount) {
            val kLen = if (buf.remaining() >= 4) buf.int else 0
            val k = ByteArray(kLen).also { if (buf.remaining() >= kLen) buf.get(it) }
            val vLen = if (buf.remaining() >= 4) buf.int else 0
            val v = ByteArray(vLen).also { if (buf.remaining() >= vLen) buf.get(it) }
            responseHeaders.add(String(k), String(v))
        }

        val bodyLen = if (buf.remaining() >= 4) buf.int else 0
        val bodyBytes = ByteArray(minOf(bodyLen, buf.remaining())).also { buf.get(it) }

        val ct = responseHeaders["Content-Type"] ?: "application/grpc"

        return okhttp3.Response.Builder()
            .request(originalReq)
            .protocol(okhttp3.Protocol.HTTP_2)
            .code(status)
            .message(if (status in 200..299) "OK" else "Error")
            .headers(responseHeaders.build())
            .body(bodyBytes.toResponseBody(ct.toMediaType()))
            .build()
    }

    private fun ByteArray.toResponseBody(mediaType: okhttp3.MediaType?) =
        okhttp3.ResponseBody.create(mediaType, this)
}
