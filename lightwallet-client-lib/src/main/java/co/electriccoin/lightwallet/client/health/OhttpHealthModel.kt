@file:Suppress("MagicNumber")

package co.electriccoin.lightwallet.client.health

/**
 * A configuration identity, never a per-request URL or a backend destination.
 *
 * The types in this file are typed OHTTP request-health evidence, shared in shape with the Slipstream engine's
 * `transport_health` observations (slipstream-internal MOB-2300) and with the iOS SDK, so the app policy can
 * treat engine and client-side evidence uniformly. Times are [OhttpHealthClock] nanoseconds: never compare them
 * with wall-clock time.
 */
data class OhttpHealthRoute(
    val gatewayHost: String,
    val relayUrl: String,
    val viaTor: Boolean,
)

/** Which stack produced the observation. */
enum class OhttpHealthSource { NATIVE, CLIENT }

enum class OhttpHealthScope { ROUTE, LIGHTWALLETD, EXCHANGE_RATES, SWAPS, VOTING, FLEXA, OTHER }

enum class OhttpHealthStage { CONNECTION, KEY_CONFIGURATION, RESPONSE_HEAD, RESPONSE_BODY, COMPLETE }

enum class OhttpHealthPurpose { BACKGROUND, USER_OPERATION, DIAGNOSTIC }

/**
 * Only [TransportUnavailable] describes an unreachable private route. A backend rejection and a
 * rate-limit pause must never become relay-outage evidence.
 */
sealed interface OhttpHealthOutcome {
    /** Stable category index, shared with the engine (usable = 0 ... inconclusive = 8). */
    val category: Int

    data object Usable : OhttpHealthOutcome {
        override val category = 0
    }

    data object TransportUnavailable : OhttpHealthOutcome {
        override val category = 1
    }

    data object BackendUnavailable : OhttpHealthOutcome {
        override val category = 2
    }

    /** [retryNotBeforeNanos] is in [OhttpHealthClock] nanoseconds. */
    data class RateLimited(
        val retryNotBeforeNanos: Long,
    ) : OhttpHealthOutcome {
        override val category = 3
    }

    data object PolicyRejected : OhttpHealthOutcome {
        override val category = 4
    }

    data object ConfigurationFailure : OhttpHealthOutcome {
        override val category = 5
    }

    data object ResponseRejected : OhttpHealthOutcome {
        override val category = 6
    }

    data object Cancelled : OhttpHealthOutcome {
        override val category = 7
    }

    data object Inconclusive : OhttpHealthOutcome {
        override val category = 8
    }
}

/**
 * Immutable identity shared by the child work of one explicit user operation. Local correlation
 * data only: it is never serialized into any request.
 */
data class OhttpHealthOperation(
    val id: String,
    val startedAtNanos: Long,
)

data class OhttpHealthObservation(
    val source: OhttpHealthSource,
    val route: OhttpHealthRoute,
    val scope: OhttpHealthScope,
    val outcome: OhttpHealthOutcome,
    val stage: OhttpHealthStage,
    /** Monotonic within one [source]; not comparable between sources. */
    val version: Long,
    val requestId: Long,
    val startedAtNanos: Long,
    val observedAtNanos: Long,
    val operation: OhttpHealthOperation? = null,
    val purpose: OhttpHealthPurpose = OhttpHealthPurpose.BACKGROUND,
    /** Replayed history is never fresh evidence. */
    val historical: Boolean = false,
)

/**
 * The latest observation per (scope, outcome category, diagnostic-or-ordinary): a bounded
 * retained set, not an event log. Success and failure coexist so a coalesced wake cannot erase
 * their ordering; consumers compare [OhttpHealthObservation.observedAtNanos].
 */
data class OhttpHealthSnapshot(
    val version: Long,
    val observations: List<OhttpHealthObservation>,
)

/** Emitted once per explicitly completed user operation. */
data class OhttpHealthOperationCompleted(
    val operation: OhttpHealthOperation,
    val failed: Boolean,
    /** Evidence of the explicitly failed network attempt only; empty for a local failure. */
    val observations: List<OhttpHealthObservation>,
)
