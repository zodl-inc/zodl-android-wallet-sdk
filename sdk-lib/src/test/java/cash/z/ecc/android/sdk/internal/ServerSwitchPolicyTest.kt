package cash.z.ecc.android.sdk.internal

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The 13 vectors behind the shared iOS/Android server switch hysteresis policy (MOB-1832).
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
    fun `current unmeasured switches to best`() {
        val best = endpoint("best.example")
        assertEquals(
            best,
            switchTarget(
                ranked =
                    listOf(
                        measured(best, 500.milliseconds),
                        measured(endpoint("other.example"), 600.milliseconds)
                    )
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
                        )
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
                ServerSwitchOutcome.CurrentUnhealthy(endpoint("best.example")),
                ServerSwitchOutcome.ImprovementSufficient(endpoint("best.example"), 250.milliseconds),
                ServerSwitchOutcome.ImprovementInsufficient(15.milliseconds)
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
                outcome = ServerSwitchPolicy.decide(current = eu, ranked = ranked)
            )
        )
    }

    private fun switchTarget(ranked: List<MeasuredEndpoint>) =
        ServerSwitchPolicy.decide(current = current, ranked = ranked).endpointToSwitchTo

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
