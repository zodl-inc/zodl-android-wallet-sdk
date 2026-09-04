package cash.z.ecc.android.sdk.internal

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The state the failure-path hysteresis reads (MOB-1832): the consecutive count of evaluations that could
 * not measure the current server, and the cooldown after a switch the caller confirmed. The gates
 * themselves are pinned in [ServerSwitchPolicyTest]; this covers what happens across a series of
 * evaluations, which is where the A to B to A round trip lives.
 *
 * Evaluating and confirming are deliberately separate: an evaluation only counts failures, and
 * [ServerSwitchEvaluator.onSwitchApplied] is what clears the count and starts the cooldown.
 */
class ServerSwitchEvaluatorTest {
    private val a = endpoint("a.example")
    private val b = endpoint("b.example")

    @Test
    fun `one failed measurement of the current server does not switch`() =
        runBlocking {
            val evaluator = ServerSwitchEvaluator(TestTimeSource())

            assertNull(evaluator.switchTarget(current = a, ranked = listOf(measured(b, 100.milliseconds))))
        }

    @Test
    fun `two consecutive failed measurements of the current server switch`() =
        runBlocking {
            val evaluator = ServerSwitchEvaluator(TestTimeSource())
            val ranked = listOf(measured(b, 100.milliseconds))

            evaluator.switchTarget(current = a, ranked = ranked)

            assertEquals(b, evaluator.switchTarget(current = a, ranked = ranked))
        }

    @Test
    fun `a healthy measurement in between resets the consecutive count`() =
        runBlocking {
            val evaluator = ServerSwitchEvaluator(TestTimeSource())
            val unhealthy = listOf(measured(b, 100.milliseconds))
            val healthy = listOf(measured(b, 100.milliseconds), measured(a, 120.milliseconds))

            evaluator.switchTarget(current = a, ranked = unhealthy)
            evaluator.switchTarget(current = a, ranked = healthy)

            assertNull(evaluator.switchTarget(current = a, ranked = unhealthy))
        }

    @Test
    fun `an evaluation that measured nothing neither counts nor resets`() =
        runBlocking {
            val evaluator = ServerSwitchEvaluator(TestTimeSource())
            val unhealthy = listOf(measured(b, 100.milliseconds))

            evaluator.switchTarget(current = a, ranked = unhealthy)
            evaluator.switchTarget(current = a, ranked = emptyList())

            assertEquals(b, evaluator.switchTarget(current = a, ranked = unhealthy))
        }

    @Test
    fun `a recommendation the caller declined is offered again by the next evaluation`() =
        runBlocking {
            val evaluator = ServerSwitchEvaluator(TestTimeSource())
            val unhealthy = listOf(measured(b, 100.milliseconds))

            evaluator.switchTarget(current = a, ranked = unhealthy)
            assertEquals(b, evaluator.switchTarget(current = a, ranked = unhealthy))

            assertEquals(b, evaluator.switchTarget(current = a, ranked = unhealthy))
        }

    @Test
    fun `the reversed evaluation right after a confirmed switch cannot make the return trip`() =
        runBlocking {
            val timeSource = TestTimeSource()
            val evaluator = ServerSwitchEvaluator(timeSource)

            assertEquals(
                b,
                evaluator.switchTarget(
                    current = a,
                    ranked = listOf(measured(b, 150.milliseconds), measured(a, 400.milliseconds))
                )
            )
            evaluator.onSwitchApplied(b)

            timeSource += 30.seconds

            assertNull(
                evaluator.switchTarget(
                    current = b,
                    ranked = listOf(measured(a, 150.milliseconds), measured(b, 400.milliseconds))
                )
            )
        }

    @Test
    fun `an unhealthy current server cannot make the return trip either`() =
        runBlocking {
            val timeSource = TestTimeSource()
            val evaluator = ServerSwitchEvaluator(timeSource)
            val aIsUnhealthy = listOf(measured(b, 100.milliseconds))
            val bIsUnhealthy = listOf(measured(a, 100.milliseconds))

            evaluator.switchTarget(current = a, ranked = aIsUnhealthy)
            assertEquals(b, evaluator.switchTarget(current = a, ranked = aIsUnhealthy))
            evaluator.onSwitchApplied(b)

            timeSource += 30.seconds

            evaluator.switchTarget(current = b, ranked = bIsUnhealthy)
            assertNull(evaluator.switchTarget(current = b, ranked = bIsUnhealthy))
        }

    @Test
    fun `a switch is recommended again once the cooldown elapsed`() =
        runBlocking {
            val timeSource = TestTimeSource()
            val evaluator = ServerSwitchEvaluator(timeSource)

            evaluator.switchTarget(
                current = a,
                ranked = listOf(measured(b, 150.milliseconds), measured(a, 400.milliseconds))
            )
            evaluator.onSwitchApplied(b)

            timeSource += ServerSwitchThresholds.SWITCH_COOLDOWN

            assertEquals(
                a,
                evaluator.switchTarget(
                    current = b,
                    ranked = listOf(measured(a, 150.milliseconds), measured(b, 400.milliseconds))
                )
            )
        }

    /**
     * The app evaluates at most every ten minutes, so the cooldown only defers anything if it outlasts
     * several of those evaluations - which is what makes it a second gate rather than a restatement of the
     * app's own rate limit.
     */
    @Test
    fun `evaluations every ten minutes cannot switch twice inside the cooldown`() =
        runBlocking {
            val timeSource = TestTimeSource()
            val evaluator = ServerSwitchEvaluator(timeSource)
            val bIsFaster = listOf(measured(b, 150.milliseconds), measured(a, 400.milliseconds))
            val aIsFaster = listOf(measured(a, 150.milliseconds), measured(b, 400.milliseconds))

            assertEquals(b, evaluator.switchTarget(current = a, ranked = bIsFaster))
            evaluator.onSwitchApplied(b)

            repeat(2) {
                timeSource += 10.minutes
                assertNull(evaluator.switchTarget(current = b, ranked = aIsFaster))
            }

            timeSource += 10.minutes

            assertEquals(a, evaluator.switchTarget(current = b, ranked = aIsFaster))
        }

    @Test
    fun `confirming a switch clears the consecutive failure count`() =
        runBlocking {
            val evaluator = ServerSwitchEvaluator(TestTimeSource())
            val aIsUnhealthy = listOf(measured(b, 100.milliseconds))

            evaluator.switchTarget(current = a, ranked = aIsUnhealthy)
            assertEquals(b, evaluator.switchTarget(current = a, ranked = aIsUnhealthy))
            evaluator.onSwitchApplied(b)

            assertNull(evaluator.switchTarget(current = a, ranked = aIsUnhealthy))
        }

    private suspend fun ServerSwitchEvaluator.switchTarget(
        current: LightWalletEndpoint,
        ranked: List<MeasuredEndpoint>
    ) = evaluate(current = current, ranked = ranked, isCurrentOffered = true).endpointToSwitchTo

    private fun measured(
        endpoint: LightWalletEndpoint,
        score: Duration
    ) = MeasuredEndpoint(endpoint = endpoint, score = score)

    private fun endpoint(host: String) =
        LightWalletEndpoint(
            host = host,
            port = 443,
            isSecure = true
        )
}
