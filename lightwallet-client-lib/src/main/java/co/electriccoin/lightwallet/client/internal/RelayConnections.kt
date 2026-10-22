package co.electriccoin.lightwallet.client.internal

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The relay connections every [DirectOhttpSender] shares while Tor is off: one OkHttp connection pool
 * (HTTP/2 multiplexed when the relay negotiates it, keep-alive HTTP/1.1 otherwise), so that the UI's
 * proxied requests (voting, NEAR, rates) and the Kotlin gRPC leg ride an open TLS connection instead
 * of paying a TCP + TLS handshake each - under a saturated link during sync that handshake alone
 * queued for seconds (2026-10-22 device evidence: a NEAR GET left the phone 11.6 s before it reached
 * the gateway).
 *
 * A pooled connection is never used past [MAX_CONNECTION_AGE]: the pool is rotated whole once that
 * long has passed since it was opened, so no connection it holds can be older. New exchanges then
 * open on the fresh pool; an exchange still running on the old one finishes there, and the old
 * pool's idle connections are closed at once and on every later call until none is left. Same
 * constant and meaning as slipstream's `H2_MAX_CONNECTION_AGE` (`core/src/ohttp.rs`): "connection
 * lifetime is linkable metadata the relay sees, so a long-lived wallet process moves to a fresh
 * connection now and then. In-flight requests finish on the old one." The ZIP's reference figure
 * for Tor-off mode is "a new connection at least every 10 minutes".
 *
 * [clock] is `System.nanoTime` in production and injectable for the tests that drive the rotation.
 */
internal class RelayConnections(
    private val clock: () -> Long = System::nanoTime,
    private val maxConnectionAgeNanos: Long = MAX_CONNECTION_AGE_NANOS,
) {
    private val lock = Any()
    private var generation = Generation(newClient(), clock())
    private val retired = ArrayDeque<ConnectionPool>()

    /**
     * The client for one exchange bounded by [timeoutMs]: the current generation's pool, or a fresh one
     * when the current pool has reached [maxConnectionAgeNanos]. Connecting is capped at the smaller of
     * [CONNECT_TIMEOUT_MS] and [timeoutMs]. Reads and writes time out [SOCKET_TIMEOUT_SLACK_MS] after
     * [timeoutMs]: the sender's deadline watchdog bounds the whole exchange at [timeoutMs] and must be
     * the one that fires, because it marks the exchange expired before it cancels the call, so that
     * what the cancelled call surfaces (an error, or on a closed socket a plain end of the body) is
     * reported as the timeout; a socket timeout of OkHttp's own at the same instant would surface the
     * same end of body as a truncated response instead.
     */
    fun client(timeoutMs: Int): OkHttpClient {
        val base =
            synchronized(lock) {
                val now = clock()
                if (now - generation.openedAtNanos >= maxConnectionAgeNanos) {
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

    private class Generation(
        val client: OkHttpClient,
        val openedAtNanos: Long,
    )

    companion object {
        /** The process-wide instance every [DirectOhttpSender] uses unless given another. */
        val shared: RelayConnections by lazy { RelayConnections() }

        /**
         * Longest a pooled relay connection is reused with Tor off: slipstream's `H2_MAX_CONNECTION_AGE`
         * (10 minutes, `core/src/ohttp.rs`), the ZIP's reference rotation for Tor-off mode.
         */
        val MAX_CONNECTION_AGE_NANOS: Long = TimeUnit.MINUTES.toNanos(MAX_CONNECTION_AGE_MINUTES)

        const val MAX_CONNECTION_AGE_MINUTES = 10L

        /** How long an idle connection is kept for reuse; a relay that closes idle connections sooner wins. */
        private const val KEEP_ALIVE_MINUTES = 5L

        private const val MAX_IDLE_CONNECTIONS = 5

        private const val CONNECT_TIMEOUT_MS = 15_000

        /** How much later than the sender's deadline watchdog OkHttp's own read/write timeouts fire (see [client]). */
        private const val SOCKET_TIMEOUT_SLACK_MS = 1_000L

        /**
         * Redirects are never followed (a relay answer other than 200 is reported with its status, see
         * [DirectOhttpSender]), and a failed exchange is never retried by the HTTP layer: a sealed request
         * replayed on a fresh connection would reach the gateway twice and its second copy would be refused
         * as a replay. The retry policy lives in the transport, which re-seals.
         */
        private fun newClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
    }
}
