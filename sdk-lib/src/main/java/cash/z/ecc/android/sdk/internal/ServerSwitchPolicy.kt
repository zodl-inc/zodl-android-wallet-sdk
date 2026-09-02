package cash.z.ecc.android.sdk.internal

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A benchmarked endpoint. [score] is comparable only within a single benchmark run; lower is better.
 */
internal data class MeasuredEndpoint(
    val endpoint: LightWalletEndpoint,
    val score: Duration
)

internal object ServerSwitchThresholds {
    val MIN_ABSOLUTE_IMPROVEMENT: Duration = 200.milliseconds

    const val MIN_RELATIVE_IMPROVEMENT: Double = 0.25
}

internal sealed interface ServerSwitchOutcome {
    val endpointToSwitchTo: LightWalletEndpoint?

    val reason: String

    data object NoResults : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint? = null
        override val reason: String = "no candidate survived benchmarking"
    }

    data object AlreadyBest : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint? = null
        override val reason: String = "current server is already the fastest"
    }

    data class CurrentUnhealthy(
        val switchTo: LightWalletEndpoint
    ) : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint = switchTo
        override val reason: String = "current server failed benchmarking"
    }

    data class ImprovementSufficient(
        val switchTo: LightWalletEndpoint,
        val improvement: Duration
    ) : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint = switchTo
        override val reason: String = "improvement ${improvement.inWholeMilliseconds} ms meets thresholds"
    }

    data class ImprovementInsufficient(
        val improvement: Duration
    ) : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint? = null
        override val reason: String = "improvement ${improvement.inWholeMilliseconds} ms below threshold"
    }
}

/**
 * Two endpoints denote the same server iff their host and port match. Scheme and timeouts are ignored.
 */
internal fun LightWalletEndpoint.isSameServer(other: LightWalletEndpoint): Boolean =
    host == other.host && port == other.port

/**
 * The hysteresis policy behind automatic server selection: keep the current server unless a candidate is
 * meaningfully faster, or the current server is unhealthy.
 */
internal object ServerSwitchPolicy {
    /**
     * @param current the endpoint the wallet is connected to right now
     * @param ranked healthy survivors of the benchmark, sorted ascending by score
     *
     * @return the outcome of the decision; it can never name [current] as the endpoint to switch to
     */
    fun decide(
        current: LightWalletEndpoint,
        ranked: List<MeasuredEndpoint>
    ): ServerSwitchOutcome {
        val best = ranked.firstOrNull()
        val currentMeasurement = ranked.firstOrNull { it.endpoint.isSameServer(current) }
        return when {
            best == null -> ServerSwitchOutcome.NoResults
            best.endpoint.isSameServer(current) -> ServerSwitchOutcome.AlreadyBest
            currentMeasurement == null -> ServerSwitchOutcome.CurrentUnhealthy(best.endpoint)
            else -> compareScores(best = best, current = currentMeasurement)
        }
    }

    fun describe(
        current: LightWalletEndpoint,
        ranked: List<MeasuredEndpoint>,
        outcome: ServerSwitchOutcome
    ): String {
        val scores = ranked.joinToString { "${it.endpoint.describe()}=${it.score.inWholeMilliseconds}ms" }
        val decision = outcome.endpointToSwitchTo?.let { "switch to ${it.describe()}" } ?: "stay"
        return "Server Switch: current=${current.describe()} ranked=[$scores] -> $decision (${outcome.reason})"
    }

    private fun compareScores(
        best: MeasuredEndpoint,
        current: MeasuredEndpoint
    ): ServerSwitchOutcome {
        val improvement = current.score - best.score
        val meetsAbsolute = improvement >= ServerSwitchThresholds.MIN_ABSOLUTE_IMPROVEMENT
        val meetsRelative = improvement >= current.score * ServerSwitchThresholds.MIN_RELATIVE_IMPROVEMENT
        return if (meetsAbsolute && meetsRelative) {
            ServerSwitchOutcome.ImprovementSufficient(switchTo = best.endpoint, improvement = improvement)
        } else {
            ServerSwitchOutcome.ImprovementInsufficient(improvement = improvement)
        }
    }

    private fun LightWalletEndpoint.describe(): String = "$host:$port"
}
