package cash.z.ecc.android.sdk.internal

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The vectors behind the shared iOS/Android server switch hysteresis policy (MOB-1832), including the
 * failure-path gates: a current server has to fail benchmarking in
 * [ServerSwitchThresholds.UNHEALTHY_EVALUATIONS_BEFORE_SWITCH] consecutive evaluations before the wallet
 * leaves it, and no switch is recommended inside [ServerSwitchThresholds.SWITCH_COOLDOWN] of the previous
 * one. [ServerSwitchEvaluatorTest] covers the state those two gates read.
 */
class ServerSwitchPolicyTest {
    private val current = endpoint("current.example")

    @Test
    fun `empty results stay`() {
        assertNull(switchTarget(ranked = emptyList()))
    }

    @Test
    fun `best is current stays`() {
        assertNull(
            switchTarget(
                ranked =
                    listOf(
                        measured(current, 100.milliseconds),
                        measured(endpoint("other.example"), 300.milliseconds)
                    )
            )
        )
    }

    @Test
    fun `current unmeasured in enough consecutive evaluations switches to best`() {
        val best = endpoint("best.example")
        assertEquals(
            best,
            switchTarget(
                ranked =
                    listOf(
                        measured(best, 500.milliseconds),
                        measured(endpoint("other.example"), 600.milliseconds)
                    ),
                consecutiveUnhealthyEvaluations = ServerSwitchThresholds.UNHEALTHY_EVALUATIONS_BEFORE_SWITCH
            )
        )
    }

    @Test
    fun `current unmeasured just once stays`() {
        assertEquals(
            ServerSwitchOutcome.CurrentUnhealthyUnconfirmed(1),
            decide(
                ranked =
                    listOf(
                        measured(endpoint("best.example"), 500.milliseconds),
                        measured(endpoint("other.example"), 600.milliseconds)
                    ),
                consecutiveUnhealthyEvaluations = 1
            )
        )
    }

    @Test
    fun `current unmeasured and no longer offered switches with its own outcome`() {
        val best = endpoint("best.example")
        assertEquals(
            ServerSwitchOutcome.CurrentNotOffered(
                switchTo = best,
                consecutiveEvaluations = ServerSwitchThresholds.UNHEALTHY_EVALUATIONS_BEFORE_SWITCH
            ),
            decide(
                ranked = listOf(measured(best, 500.milliseconds)),
                isCurrentOffered = false,
                consecutiveUnhealthyEvaluations = ServerSwitchThresholds.UNHEALTHY_EVALUATIONS_BEFORE_SWITCH
            )
        )
    }

    @Test
    fun `the unhealthy outcome carries the live consecutive count`() {
        val best = endpoint("best.example")
        val outcome =
            decide(
                ranked = listOf(measured(best, 500.milliseconds)),
                consecutiveUnhealthyEvaluations = 5
            )

        assertEquals(ServerSwitchOutcome.CurrentUnhealthy(switchTo = best, consecutiveEvaluations = 5), outcome)
        assertTrue(outcome.reason.contains("5 consecutive times"), "Unexpected reason ${outcome.reason}")
    }

    @Test
    fun `a switch inside the cooldown window is deferred`() {
        val best = endpoint("best.example")
        assertEquals(
            ServerSwitchOutcome.SwitchOnCooldown(deferred = best, remaining = 1.minutes),
            decide(
                ranked =
                    listOf(
                        measured(best, 150.milliseconds),
                        measured(current, 400.milliseconds)
                    ),
                sinceLastSwitch = ServerSwitchThresholds.SWITCH_COOLDOWN - 1.minutes
            )
        )
    }

    @Test
    fun `a switch once the cooldown window elapsed proceeds`() {
        val best = endpoint("best.example")
        assertEquals(
            best,
            switchTarget(
                ranked =
                    listOf(
                        measured(best, 150.milliseconds),
                        measured(current, 400.milliseconds)
                    ),
                sinceLastSwitch = ServerSwitchThresholds.SWITCH_COOLDOWN
            )
        )
    }

    @Test
    fun `the cooldown does not turn a stay into a switch`() {
        assertNull(
            switchTarget(
                ranked =
                    listOf(
                        measured(endpoint("best.example"), 100.milliseconds),
                        measured(current, 105.milliseconds)
                    ),
                sinceLastSwitch = Duration.ZERO
            )
        )
    }

    @Test
    fun `marginal improvement stays`() {
        assertNull(
            switchTarget(
                ranked =
                    listOf(
                        measured(endpoint("best.example"), 100.milliseconds),
                        measured(current, 105.milliseconds)
                    )
            )
        )
    }

    @Test
    fun `absolute gate passes but relative gate fails so stays`() {
        assertNull(
            switchTarget(
                ranked =
                    listOf(
                        measured(endpoint("best.example"), 1750.milliseconds),
                        measured(current, 2000.milliseconds)
                    )
            )
        )
    }

    @Test
    fun `relative gate passes but absolute gate fails so stays`() {
        assertNull(
            switchTarget(
                ranked =
                    listOf(
                        measured(endpoint("best.example"), 20.milliseconds),
                        measured(current, 190.milliseconds)
                    )
            )
        )
    }

    @Test
    fun `both gates pass so switches to best`() {
        val best = endpoint("best.example")
        assertEquals(
            best,
            switchTarget(
                ranked =
                    listOf(
                        measured(best, 150.milliseconds),
                        measured(current, 400.milliseconds)
                    )
            )
        )
    }

    @Test
    fun `exact threshold boundary switches to best`() {
        val best = endpoint("best.example")
        assertEquals(
            best,
            switchTarget(
                ranked =
                    listOf(
                        measured(best, 600.milliseconds),
                        measured(current, 800.milliseconds)
                    )
            )
        )
    }

    @Test
    fun `equal scores with other server first stays`() {
        assertNull(
            switchTarget(
                ranked =
                    listOf(
                        measured(endpoint("best.example"), 300.milliseconds),
                        measured(current, 300.milliseconds)
                    )
            )
        )
    }

    @Test
    fun `same host on another port is a different server`() {
        val otherPort = endpoint("same.example", port = 9067)
        assertEquals(
            otherPort,
            ServerSwitchPolicy
                .decide(
                    current = endpoint("same.example"),
                    ranked =
                        listOf(
                            measured(otherPort, 100.milliseconds),
                            measured(endpoint("same.example"), 900.milliseconds)
                        ),
                    isCurrentOffered = true,
                    consecutiveUnhealthyEvaluations = 0,
                    sinceLastSwitch = null
                ).endpointToSwitchTo
        )
    }

    @Test
    fun `current in the middle of the list switches to best`() {
        val best = endpoint("best.example")
        assertEquals(
            best,
            switchTarget(
                ranked =
                    listOf(
                        measured(best, 100.milliseconds),
                        measured(endpoint("second.example"), 200.milliseconds),
                        measured(current, 500.milliseconds)
                    )
            )
        )
    }

    @Test
    fun `only the current server survived so stays`() {
        assertNull(switchTarget(ranked = listOf(measured(current, 400.milliseconds))))
    }

    @Test
    fun `every outcome carries a reason`() {
        val outcomes =
            listOf(
                ServerSwitchOutcome.NoResults,
                ServerSwitchOutcome.AlreadyBest,
                ServerSwitchOutcome.CurrentUnhealthyUnconfirmed(1),
                ServerSwitchOutcome.CurrentUnhealthy(endpoint("best.example"), 2),
                ServerSwitchOutcome.CurrentNotOffered(endpoint("best.example"), 2),
                ServerSwitchOutcome.ImprovementSufficient(endpoint("best.example"), 250.milliseconds),
                ServerSwitchOutcome.ImprovementInsufficient(15.milliseconds),
                ServerSwitchOutcome.SwitchOnCooldown(endpoint("best.example"), 3.minutes)
            )

        outcomes.forEach { assertTrue(it.reason.isNotBlank(), "Missing reason for $it") }
    }

    @Test
    fun `describe renders the decision line`() {
        val eu = endpoint("eu.zec.rocks")
        val na = endpoint("na.zec.rocks")
        val ranked = listOf(measured(na, 180.milliseconds), measured(eu, 195.milliseconds))

        assertEquals(
            "Server Switch: current=eu.zec.rocks:443 ranked=[na.zec.rocks:443=180ms, eu.zec.rocks:443=195ms] " +
                "-> stay (improvement 15 ms below threshold)",
            ServerSwitchPolicy.describe(
                current = eu,
                ranked = ranked,
                outcome =
                    ServerSwitchPolicy.decide(
                        current = eu,
                        ranked = ranked,
                        isCurrentOffered = true,
                        consecutiveUnhealthyEvaluations = 0,
                        sinceLastSwitch = null
                    )
            )
        )
    }

    private fun switchTarget(
        ranked: List<MeasuredEndpoint>,
        isCurrentOffered: Boolean = true,
        consecutiveUnhealthyEvaluations: Int = ServerSwitchThresholds.UNHEALTHY_EVALUATIONS_BEFORE_SWITCH,
        sinceLastSwitch: Duration? = null
    ) = decide(
        ranked = ranked,
        isCurrentOffered = isCurrentOffered,
        consecutiveUnhealthyEvaluations = consecutiveUnhealthyEvaluations,
        sinceLastSwitch = sinceLastSwitch
    ).endpointToSwitchTo

    private fun decide(
        ranked: List<MeasuredEndpoint>,
        isCurrentOffered: Boolean = true,
        consecutiveUnhealthyEvaluations: Int = ServerSwitchThresholds.UNHEALTHY_EVALUATIONS_BEFORE_SWITCH,
        sinceLastSwitch: Duration? = null
    ) = ServerSwitchPolicy.decide(
        current = current,
        ranked = ranked,
        isCurrentOffered = isCurrentOffered,
        consecutiveUnhealthyEvaluations = consecutiveUnhealthyEvaluations,
        sinceLastSwitch = sinceLastSwitch
    )

    private fun measured(
        endpoint: LightWalletEndpoint,
        score: Duration
    ) = MeasuredEndpoint(endpoint = endpoint, score = score)

    private fun endpoint(
        host: String,
        port: Int = 443
    ) = LightWalletEndpoint(
        host = host,
        port = port,
        isSecure = true
    )
}
