package co.electriccoin.lightwallet.client.internal

import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only timing of the Tor-off OHTTP path, log tag [TAG], one line per event with its offset in ms
 * from the start of the call or phase it belongs to. Never a body, never a URL beyond the host. Off in
 * release builds ([OhttpDebugFlags.logTiming]): nothing is installed and nothing is logged.
 *
 * Two clocks, correlated by the client trace id when the transport has one (`X-Client-Trace-Id`):
 * - [phases], the transport's own: what happens before the sender is called (key configuration wait,
 *   relay pause check, encapsulation over JNI) and when the sender returns, under one label per call;
 * - [listenerFactory], an OkHttp [EventListener] per call: DNS, connect, TLS, connection acquired
 *   (new or reused from the pool, and how long ago that connection was first seen), request headers and
 *   body sent, response headers and body received, call end or failure.
 *
 * Why (2026-10-23 device test, 10 Mbit/s Wi-Fi during a restore): the first voting request of a flow
 * reached the gateway ~6.4 s after the app logged it, later ones in 80-450 ms; these lines show whether
 * the time goes before the HTTP layer or inside it.
 */
internal object OhttpTiming {
    const val TAG = "OhttpTiming"

    val enabled: Boolean
        get() = OhttpDebugFlags.logTiming

    private val callSequence = AtomicLong(0)

    /** When each pooled connection was first seen by any call: a stand-in for its age. */
    private val firstSeenNanos: MutableMap<Connection, Long> =
        Collections.synchronizedMap(WeakHashMap<Connection, Long>())

    /** The transport's clock for one call, or null when timing is off. */
    fun phases(label: String): Phases? = if (enabled) Phases("$label#${callSequence.incrementAndGet()}") else null

    /** Installed on the OkHttp client when [enabled] (see `RelayConnections`). */
    val listenerFactory: EventListener.Factory = EventListener.Factory { call -> CallTiming(call.request()) }

    /** Marks of one call's phases: offset from the first mark, and the time since the previous one. */
    class Phases internal constructor(
        val label: String,
    ) {
        private val startNanos = System.nanoTime()
        private var lastNanos = startNanos

        fun mark(phase: String) {
            val now = System.nanoTime()
            Log.d(TAG, "$label +${millis(now - startNanos)} ms $phase (+${millis(now - lastNanos)} ms)")
            lastNanos = now
        }
    }

    @Suppress("TooManyFunctions")
    private class CallTiming(
        request: Request,
    ) : EventListener() {
        private val label = labelOf(request)
        private val startNanos = System.nanoTime()
        private var connectedHere = false

        private fun at(event: String) {
            Log.d(TAG, "$label +${millis(System.nanoTime() - startNanos)} ms $event")
        }

        override fun callStart(call: Call) = at("callStart")

        override fun dnsStart(
            call: Call,
            domainName: String,
        ) = at("dnsStart")

        override fun dnsEnd(
            call: Call,
            domainName: String,
            inetAddressList: List<InetAddress>,
        ) = at("dnsEnd ${inetAddressList.size} addresses")

        override fun connectStart(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
        ) {
            connectedHere = true
            at("connectStart")
        }

        override fun secureConnectStart(call: Call) = at("secureConnectStart")

        override fun secureConnectEnd(
            call: Call,
            handshake: Handshake?,
        ) = at("secureConnectEnd ${handshake?.tlsVersion}")

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) = at("connectEnd $protocol")

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) = at("connectFailed ${ioe.javaClass.simpleName}")

        override fun connectionAcquired(
            call: Call,
            connection: Connection,
        ) {
            val now = System.nanoTime()
            val firstSeen = synchronized(firstSeenNanos) { firstSeenNanos.getOrPut(connection) { now } }
            val origin = if (connectedHere) "new" else "reused"
            at("connectionAcquired $origin ${connection.protocol()} first seen ${millis(now - firstSeen)} ms ago")
        }

        override fun connectionReleased(
            call: Call,
            connection: Connection,
        ) = at("connectionReleased")

        override fun requestHeadersStart(call: Call) = at("requestHeadersStart")

        override fun requestHeadersEnd(
            call: Call,
            request: Request,
        ) = at("requestHeadersEnd")

        override fun requestBodyEnd(
            call: Call,
            byteCount: Long,
        ) = at("requestBodyEnd $byteCount bytes")

        override fun responseHeadersStart(call: Call) = at("responseHeadersStart")

        override fun responseHeadersEnd(
            call: Call,
            response: Response,
        ) = at("responseHeadersEnd HTTP ${response.code}")

        override fun responseBodyEnd(
            call: Call,
            byteCount: Long,
        ) = at("responseBodyEnd $byteCount bytes")

        override fun callEnd(call: Call) = at("callEnd")

        override fun callFailed(
            call: Call,
            ioe: IOException,
        ) = at("callFailed ${ioe.javaClass.simpleName}: ${ioe.message}")

        override fun canceled(call: Call) = at("canceled")
    }

    /** The trace id the transport put on the request, else a sequence number; the method and the host. */
    private fun labelOf(request: Request): String {
        val id = request.header(OhttpTransport.TRACE_HEADER) ?: "call#${callSequence.incrementAndGet()}"
        return "[$id] ${request.method} ${request.url.host}"
    }

    private fun millis(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos)
}
