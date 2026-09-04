package cash.z.ecc.android.sdk.internal

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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

    /**
     * How many consecutive evaluations have to fail to measure the current server before the wallet gives
     * up on it. A single failed measurement is a congestion spike far more often than a broken server, and
     * acting on one is what makes A to B to A flapping reachable.
     */
    const val UNHEALTHY_EVALUATIONS_BEFORE_SWITCH: Int = 2

    /**
     * No switch is recommended within this window of the previous one the caller confirmed, whatever the
     * benchmark says. A switch tears the Synchronizer down and rebuilds it, so the cost of an unnecessary
     * one is high and the benefit of reacting fast to a second one is low.
     *
     * The window has to be comfortably longer than the interval at which the caller evaluates - the app
     * evaluates at most every ten minutes - or every evaluation already finds it elapsed and the cooldown
     * defers nothing.
     */
    val SWITCH_COOLDOWN: Duration = 30.minutes
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

    data class CurrentUnhealthyUnconfirmed(
        val consecutiveEvaluations: Int
    ) : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint? = null
        override val reason: String =
            "current server failed benchmarking $consecutiveEvaluations of the " +
                "${ServerSwitchThresholds.UNHEALTHY_EVALUATIONS_BEFORE_SWITCH} consecutive evaluations " +
                "needed to leave it"
    }

    data class CurrentUnhealthy(
        val switchTo: LightWalletEndpoint,
        val consecutiveEvaluations: Int
    ) : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint = switchTo
        override val reason: String =
            "current server failed benchmarking $consecutiveEvaluations consecutive times"
    }

    data class CurrentNotOffered(
        val switchTo: LightWalletEndpoint,
        val consecutiveEvaluations: Int
    ) : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint = switchTo
        override val reason: String =
            "current server is no longer offered as a candidate and failed benchmarking " +
                "$consecutiveEvaluations consecutive times"
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

    data class SwitchOnCooldown(
        val deferred: LightWalletEndpoint,
        val remaining: Duration
    ) : ServerSwitchOutcome {
        override val endpointToSwitchTo: LightWalletEndpoint? = null
        override val reason: String =
            "switch to ${deferred.describe()} deferred, ${remaining.inWholeSeconds} s left of the cooldown " +
                "after the previous switch"
    }
}

/**
 * Two endpoints denote the same server iff their host and port match. Scheme and timeouts are ignored.
 */
internal fun LightWalletEndpoint.isSameServer(other: LightWalletEndpoint): Boolean =
    host == other.host && port == other.port

/**
 * The hysteresis policy behind automatic server selection: keep the current server unless a candidate is
 * meaningfully faster, or the current server has repeatedly failed to be measured.
 *
 * The policy is pure - everything it needs about earlier evaluations is passed in, and
 * [ServerSwitchEvaluator] owns that state.
 */
internal object ServerSwitchPolicy {
    /**
     * @param current the endpoint the wallet is connected to right now
     * @param ranked healthy survivors of the benchmark, sorted ascending by score
     * @param isCurrentOffered whether the caller offered [current] among its candidates; the benchmark
     * measures [current] either way, so a host dropped from the bundled list is still given its chance
     * @param consecutiveUnhealthyEvaluations how many evaluations in a row, this one included, have failed
     * to measure [current]; zero whenever it was measured
     * @param sinceLastSwitch how long ago the caller last confirmed a switch through
     * [ServerSwitchEvaluator.onSwitchApplied], or null when it never did
     *
     * @return the outcome of the decision; it can never name [current] as the endpoint to switch to
     */
    fun decide(
        current: LightWalletEndpoint,
        ranked: List<MeasuredEndpoint>,
        isCurrentOffered: Boolean,
        consecutiveUnhealthyEvaluations: Int,
        sinceLastSwitch: Duration?
    ): ServerSwitchOutcome {
        val best = ranked.firstOrNull()
        val currentMeasurement = ranked.firstOrNull { it.endpoint.isSameServer(current) }
        val outcome =
            when {
                best == null -> {
                    ServerSwitchOutcome.NoResults
                }

                best.endpoint.isSameServer(current) -> {
                    ServerSwitchOutcome.AlreadyBest
                }

                currentMeasurement == null -> {
                    decideOnUnmeasuredCurrent(
                        best = best.endpoint,
                        isCurrentOffered = isCurrentOffered,
                        consecutiveUnhealthyEvaluations = consecutiveUnhealthyEvaluations
                    )
                }

                else -> {
                    compareScores(best = best, current = currentMeasurement)
                }
            }
        return applyCooldown(outcome = outcome, sinceLastSwitch = sinceLastSwitch)
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

    private fun decideOnUnmeasuredCurrent(
        best: LightWalletEndpoint,
        isCurrentOffered: Boolean,
        consecutiveUnhealthyEvaluations: Int
    ): ServerSwitchOutcome =
        when {
            consecutiveUnhealthyEvaluations < ServerSwitchThresholds.UNHEALTHY_EVALUATIONS_BEFORE_SWITCH -> {
                ServerSwitchOutcome.CurrentUnhealthyUnconfirmed(consecutiveUnhealthyEvaluations)
            }

            isCurrentOffered -> {
                ServerSwitchOutcome.CurrentUnhealthy(
                    switchTo = best,
                    consecutiveEvaluations = consecutiveUnhealthyEvaluations
                )
            }

            else -> {
                ServerSwitchOutcome.CurrentNotOffered(
                    switchTo = best,
                    consecutiveEvaluations = consecutiveUnhealthyEvaluations
                )
            }
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

    private fun applyCooldown(
        outcome: ServerSwitchOutcome,
        sinceLastSwitch: Duration?
    ): ServerSwitchOutcome {
        val switchTo = outcome.endpointToSwitchTo ?: return outcome
        return if (sinceLastSwitch != null && sinceLastSwitch < ServerSwitchThresholds.SWITCH_COOLDOWN) {
            ServerSwitchOutcome.SwitchOnCooldown(
                deferred = switchTo,
                remaining = ServerSwitchThresholds.SWITCH_COOLDOWN - sinceLastSwitch
            )
        } else {
            outcome
        }
    }
}

/**
 * The stateful half of the switch decision: how many consecutive evaluations have failed to measure the
 * current server, and how long ago a switch was last applied.
 *
 * An evaluation only counts failures. It never clears the count and never starts the cooldown, because a
 * recommendation is not a switch - the caller is free to decline one, and a wallet left on a server that
 * has already failed twice has to be able to be offered the same way out again. [onSwitchApplied] is what
 * records a switch that actually happened.
 *
 * The state has to outlive any single Synchronizer, because acting on the decision tears the Synchronizer
 * down and rebuilds it - state held by the instance that made the call would be gone before the cooldown
 * it established could ever apply. Hence [PROCESS_WIDE]. It is in-memory only; a process restart begins
 * with a clean slate, which is acceptable because a restart is not something the benchmark can cause.
 */
internal class ServerSwitchEvaluator(
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    private val mutex = Mutex()

    private var consecutiveUnhealthyEvaluations = 0

    private var lastSwitchAt: TimeMark? = null

    /**
     * Folds the state of the earlier evaluations into [ServerSwitchPolicy.decide] and records how this one
     * measured the current server. An evaluation in which nothing at all survived says nothing about the
     * current server either, so it neither counts against it nor clears its count.
     *
     * @param ranked healthy survivors of the benchmark, sorted ascending by score
     * @param isCurrentOffered whether the caller offered [current] among its candidates
     */
    suspend fun evaluate(
        current: LightWalletEndpoint,
        ranked: List<MeasuredEndpoint>,
        isCurrentOffered: Boolean
    ): ServerSwitchOutcome =
        mutex.withLock {
            val consecutive =
                when {
                    ranked.any { it.endpoint.isSameServer(current) } -> 0
                    ranked.isEmpty() -> consecutiveUnhealthyEvaluations
                    else -> consecutiveUnhealthyEvaluations + 1
                }
            consecutiveUnhealthyEvaluations = consecutive
            ServerSwitchPolicy.decide(
                current = current,
                ranked = ranked,
                isCurrentOffered = isCurrentOffered,
                consecutiveUnhealthyEvaluations = consecutive,
                sinceLastSwitch = lastSwitchAt?.elapsedNow()
            )
        }

    /**
     * Records that the wallet was actually moved to [endpoint]: the consecutive-failure count starts over
     * and the cooldown starts now.
     */
    suspend fun onSwitchApplied(endpoint: LightWalletEndpoint) =
        mutex.withLock {
            Twig.info { "Server Switch: switch to ${endpoint.describe()} applied, cooldown starts now" }
            consecutiveUnhealthyEvaluations = 0
            lastSwitchAt = timeSource.markNow()
        }

    companion object {
        val PROCESS_WIDE = ServerSwitchEvaluator()
    }
}

private fun LightWalletEndpoint.describe(): String = "$host:$port"
