@file:Suppress("MagicNumber")

package co.electriccoin.lightwallet.client.health

import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpInternalException
import co.electriccoin.lightwallet.client.OhttpRequestTooLargeException
import co.electriccoin.lightwallet.client.internal.OhttpEndpoints
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Classifies Kotlin-client OHTTP results into [OhttpHealthOutcome]s, mirroring the engine's
 * classification and iOS `OHTTPHealthTraffic`. Only an unreachable route or an outer relay/gateway
 * 5xx is [OhttpHealthOutcome.TransportUnavailable]; rejections, rate limits and configuration
 * problems are never outage evidence.
 */
object OhttpHealthTraffic {
    /** The pause applied to a 429 that carries no `Retry-After`. */
    const val DEFAULT_RATE_LIMIT_PAUSE_SECONDS = 30L

    /** Which scope a request to [authority] belongs to (a gateway host is lightwalletd traffic). */
    fun scope(authority: String): OhttpHealthScope =
        when (authority.lowercase()) {
            "cmc.zodl.com" -> {
                OhttpHealthScope.EXCHANGE_RATES
            }

            "near.zodl.com" -> {
                OhttpHealthScope.SWAPS
            }

            "flexa.zodl.com" -> {
                OhttpHealthScope.FLEXA
            }

            "vote.zodl.com", "pir.zodl.com" -> {
                OhttpHealthScope.VOTING
            }

            else -> {
                if (OhttpEndpoints.isOhttpGateway(authority.lowercase())) {
                    OhttpHealthScope.LIGHTWALLETD
                } else {
                    OhttpHealthScope.OTHER
                }
            }
        }

    /** The outcome of a failed request. [nowNanos] stamps a rate-limit pause. */
    fun outcome(
        error: Throwable,
        nowNanos: Long = OhttpHealthClock.nowNanos(),
    ): OhttpHealthOutcome =
        when (error) {
            is CancellationException -> OhttpHealthOutcome.Cancelled

            is OhttpRequestTooLargeException, is OhttpInternalException -> OhttpHealthOutcome.ResponseRejected

            is KeyConfigVerificationFailedException -> OhttpHealthOutcome.ConfigurationFailure

            is OhttpException -> ohttpExceptionOutcome(error, nowNanos)

            is SocketTimeoutException, is ConnectException, is NoRouteToHostException,
            is UnknownHostException, is SSLException, is SocketException,
            -> OhttpHealthOutcome.TransportUnavailable

            // Any other I/O failure while talking to the relay is the route not delivering.
            is IOException -> OhttpHealthOutcome.TransportUnavailable

            else -> OhttpHealthOutcome.Inconclusive
        }

    /**
     * An [OhttpException] with a status is the relay's/gateway's answer. Without one it is either a
     * size/protocol refusal or a transport failure that a sender wrapped (its cause says which).
     */
    private fun ohttpExceptionOutcome(
        error: OhttpException,
        nowNanos: Long,
    ): OhttpHealthOutcome {
        error.httpStatus?.let { return outerStatusOutcome(it, error.retryAfterSeconds, nowNanos) }
        return when (error.cause) {
            null -> OhttpHealthOutcome.Inconclusive
            is OutOfMemoryError -> OhttpHealthOutcome.ResponseRejected
            else -> OhttpHealthOutcome.TransportUnavailable
        }
    }

    /**
     * The outcome of an outer (relay/gateway) HTTP status, as the engine's `classify_outer`: a policy refusal
     * (401, 403, 407, 451), a configuration problem (400, 404, 405, 406, 409, 415, 422, 426, 501, 505), a
     * response that is too large (413), a 5xx the route does not deliver, anything else a rejected response.
     */
    fun outerStatusOutcome(
        status: Int,
        retryAfterSeconds: Long? = null,
        nowNanos: Long = OhttpHealthClock.nowNanos(),
    ): OhttpHealthOutcome =
        when (status) {
            in 200..299 -> OhttpHealthOutcome.Usable
            429 -> rateLimited(retryAfterSeconds, nowNanos)
            401, 403, 407, 451 -> OhttpHealthOutcome.PolicyRejected
            400, 404, 405, 406, 409, 415, 422, 426, 501, 505 -> OhttpHealthOutcome.ConfigurationFailure
            413 -> OhttpHealthOutcome.ResponseRejected
            in 500..599 -> OhttpHealthOutcome.TransportUnavailable
            else -> OhttpHealthOutcome.ResponseRejected
        }

    /**
     * The outcome of an inner (backend) HTTP status carried inside a delivered OHTTP response, as the engine's
     * `inner_health`. A backend 5xx is the backend's trouble, not the route's.
     */
    fun innerStatusOutcome(
        status: Int,
        retryAfterSeconds: Long? = null,
        nowNanos: Long = OhttpHealthClock.nowNanos(),
    ): OhttpHealthOutcome =
        when (status) {
            in 200..299 -> OhttpHealthOutcome.Usable
            401, 403, 407, 451 -> OhttpHealthOutcome.PolicyRejected
            429 -> rateLimited(retryAfterSeconds, nowNanos)
            426, 501, 505 -> OhttpHealthOutcome.ConfigurationFailure
            in 500..599 -> OhttpHealthOutcome.BackendUnavailable
            in 400..499 -> OhttpHealthOutcome.ResponseRejected
            else -> OhttpHealthOutcome.Inconclusive
        }

    /** The outcome of a non-OK gRPC status inside an otherwise usable response. */
    fun grpcOutcome(grpcStatus: Int): OhttpHealthOutcome =
        when (grpcStatus) {
            0 -> OhttpHealthOutcome.Usable
            1 -> OhttpHealthOutcome.Cancelled
            4, 14 -> OhttpHealthOutcome.BackendUnavailable
            7, 16 -> OhttpHealthOutcome.PolicyRejected
            12 -> OhttpHealthOutcome.ConfigurationFailure
            else -> OhttpHealthOutcome.ResponseRejected
        }

    private fun rateLimited(
        retryAfterSeconds: Long?,
        nowNanos: Long,
    ): OhttpHealthOutcome {
        val seconds = retryAfterSeconds?.takeIf { it > 0 } ?: DEFAULT_RATE_LIMIT_PAUSE_SECONDS
        return OhttpHealthOutcome.RateLimited(nowNanos + seconds * NANOS_PER_SECOND)
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
}
