package co.electriccoin.lightwallet.client.internal

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The relay connections every [DirectOhttpSender] shares while Tor is off: one OkHttp connection pool
 * (HTTP/2 multiplexed when the relay negotiates it, keep-alive HTTP/1.1 otherwise), so that the UI's
 * proxied requests (voting, NEAR, rates) and the Kotlin gRPC leg ride an open TLS connection instead
 * of paying a TCP + TLS handshake each - under a saturated link during sync that handshake alone
 * queued for seconds (2026-10-22 device evidence: a NEAR GET left the phone 11.6 s before it reached
 * the gateway).
 *
 * No exchange runs on a pooled connection past [MAX_CONNECTION_AGE_MINUTES] after the pool opened: an
 * exchange starts on the current pool only when its own deadline ([client]'s `timeoutMs`, the sender's
 * watchdog) ends before that age, otherwise the pool is rotated whole first and the exchange opens on
 * the fresh one, so no connection is used, in flight or idle, past the age. The retired pool's idle
 * connections are closed at once and on every later call until none is left. Same constant and
 * meaning as slipstream's `H2_MAX_CONNECTION_AGE` (`core/src/ohttp.rs`): "connection lifetime is
 * linkable metadata the relay sees, so a long-lived wallet process moves to a fresh connection now
 * and then. In-flight requests finish on the old one." The ZIP's reference figure for Tor-off mode
 * is "a new connection at least every 10 minutes".
 *
 * OkHttp behaviour relied on here (follow-ups, the bridge headers, cancel on HTTP/1.1 and HTTP/2,
 * pool eviction) was checked against the OkHttp 5.1.0 source, the version `OKHTTP_VERSION` pins and
 * the one the Zashi app resolves (its vendored Flexa core declares `okhttp-android:5.1.0`), so the
 * unit tests run against the library production uses.
 *
 * [clock] is `System.nanoTime` in production and injectable for the tests that drive the rotation;
 * [configureClient] lets a test trust its TLS relay (the production client uses the platform's trust).
 */
internal class RelayConnections(
    private val clock: () -> Long = System::nanoTime,
    private val maxConnectionAgeNanos: Long = MAX_CONNECTION_AGE_NANOS,
    private val configureClient: OkHttpClient.Builder.() -> Unit = {},
) {
    private val lock = Any()
    private var generation = Generation(newClient(), clock())
    private val retired = ArrayDeque<ConnectionPool>()

    /**
     * The client for one exchange bounded by [timeoutMs]: the current generation's pool, or a fresh one
     * when an exchange of [timeoutMs] on the current pool could still be running at [maxConnectionAgeNanos].
     * Connecting is capped at the smaller of [CONNECT_TIMEOUT_MS] and [timeoutMs]. Reads and writes time
     * out [SOCKET_TIMEOUT_SLACK_MS] after [timeoutMs]: the sender's deadline watchdog bounds the whole
     * exchange at [timeoutMs] and must be the one that fires, because it marks the exchange expired
     * before it cancels the call, so that what the cancelled call surfaces (an error, or on a closed
     * socket a plain end of the body) is reported as the timeout; a socket timeout of OkHttp's own at
     * the same instant would surface the same end of body as a truncated response instead.
     */
    fun client(timeoutMs: Int): OkHttpClient {
        val base =
            synchronized(lock) {
                val now = clock()
                val endsAtNanos = now + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                if (endsAtNanos - generation.openedAtNanos >= maxConnectionAgeNanos) {
                    retired.addLast(generation.client.connectionPool)
                    generation = Generation(newClient(), now)
                }
                reapRetiredLocked()
                generation.client
            }
        return base
            .newBuilder()
            .connectTimeout(minOf(CONNECT_TIMEOUT_MS, timeoutMs).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong() + SOCKET_TIMEOUT_SLACK_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong() + SOCKET_TIMEOUT_SLACK_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Closes every idle connection of every retired pool, dropping a pool once it is empty. Called after
     * each exchange too, so a connection that outlived its generation while in flight is closed as soon
     * as that exchange ends rather than at the pool's own idle timeout.
     */
    fun reapRetired() = synchronized(lock) { reapRetiredLocked() }

    /** Closes every idle connection, current generation included (tests, teardown). */
    fun evictAll() =
        synchronized(lock) {
            generation.client.connectionPool.evictAll()
            reapRetiredLocked()
        }

    private fun reapRetiredLocked() {
        retired.removeAll { pool ->
            pool.evictAll()
            pool.connectionCount() == 0
        }
    }

    /**
     * Redirects are never followed (a relay answer other than 200 is reported with its status, see
     * [DirectOhttpSender]), and the HTTP layer never sends a request twice: a sealed request replayed
     * on a fresh connection would reach the gateway twice and its second copy would be refused as a
     * replay (ZIP "One call, one exchange": a library "that transparently repeats a POST body" is
     * something "a Client MUST disable or defeat"). `retryOnConnectionFailure(false)` covers the
     * failed-connection retries and the 408 follow-up; the sealed body being one-shot
     * ([SealedRequestBody]) covers every follow-up with a body, 503 + `Retry-After: 0` and 421
     * included; [OneRequestPerExchange] refuses a second network request of any exchange, GETs
     * included. The retry policy lives in the transport, which re-seals. Debug builds log each call's
     * DNS/connect/TLS/pool/request/response timings ([OhttpTiming]).
     */
    private fun newClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(OneRequestPerExchange)
            .apply { if (OhttpTiming.enabled) eventListenerFactory(OhttpTiming.listenerFactory) }
            .apply(configureClient)
            .build()

    private class Generation(
        val client: OkHttpClient,
        val openedAtNanos: Long,
    )

    /**
     * Tag of the one request an exchange sends (see [OneRequestPerExchange]); every request the sender
     * builds carries a fresh one.
     */
    class ExchangeTicket {
        val sent = AtomicBoolean(false)
    }

    /**
     * Refuses the second network request OkHttp would send under one call: a follow-up
     * (`RetryAndFollowUpInterceptor` re-sends the same request object, tag included, on a 503 with
     * `Retry-After: 0`, on a 408, on a 421 from a coalesced HTTP/2 connection) fails here with an
     * [IOException] before any byte leaves, so an exchange is one request on the wire whatever the
     * status rules of the OkHttp version in use. A request without a ticket is not the sender's and
     * passes.
     */
    private object OneRequestPerExchange : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request: Request = chain.request()
            val ticket = request.tag(ExchangeTicket::class.java)
            if (ticket != null && !ticket.sent.compareAndSet(false, true)) {
                throw IOException("One exchange, one request: the HTTP layer tried to send the request again")
            }
            return chain.proceed(request)
        }
    }

    companion object {
        /** The process-wide instance every [DirectOhttpSender] uses unless given another. */
        val shared: RelayConnections by lazy { RelayConnections() }

        /**
         * Longest a pooled relay connection is used with Tor off: slipstream's `H2_MAX_CONNECTION_AGE`
         * (10 minutes, `core/src/ohttp.rs`), the ZIP's reference rotation for Tor-off mode.
         */
        val MAX_CONNECTION_AGE_NANOS: Long = TimeUnit.MINUTES.toNanos(MAX_CONNECTION_AGE_MINUTES)

        const val MAX_CONNECTION_AGE_MINUTES = 10L

        /**
         * How long an idle connection is kept for reuse. It must stay below the relay's own idle timeout,
         * or a request would be written on a connection the relay has just closed and, with no retry by
         * the HTTP layer, fail as UNAVAILABLE. Fastly does not document the client-side idle timeout of
         * its edge, so it was measured against `ohttp-relay.example` on 2026-10-23: an
         * HTTP/1.1 connection left idle after one request was closed by the relay at 600 s, one left idle
         * after the handshake at 600 s too, and an HTTP/2 connection got GOAWAY ("idle timeout") at 610 s
         * ([MEASURED_RELAY_IDLE_TIMEOUT_SECONDS]). Five minutes is half of that, and the rotation at
         * [MAX_CONNECTION_AGE_MINUTES] retires a connection before the relay would anyway. OkHttp also
         * checks a pooled connection before reusing it for a POST (`isHealthy`, extensive check), which
         * catches a closed socket, so the keep-alive is a second guard, not the only one.
         */
        const val KEEP_ALIVE_SECONDS = 300L

        /** The relay idle timeout the 2026-10-23 measurement found (HTTP/1.1; HTTP/2 was 610 s). */
        const val MEASURED_RELAY_IDLE_TIMEOUT_SECONDS = 600L

        private const val MAX_IDLE_CONNECTIONS = 5

        private const val CONNECT_TIMEOUT_MS = 15_000

        /** How much later than the sender's deadline watchdog OkHttp's own read/write timeouts fire (see [client]). */
        private const val SOCKET_TIMEOUT_SLACK_MS = 1_000L
    }
}
