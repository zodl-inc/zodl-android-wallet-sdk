package co.electriccoin.lightwallet.client.health

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OhttpHealthCenterTest {
    private val route = OhttpHealthRoute("ohttp-gateway.example", "https://relay.example/relay", viaTor = false)
    private var now = 1_000L
    private val center = OhttpHealthCenter(clock = { now.also { now += 10 } })

    @Test
    fun recordedObservationIsRetainedWithItsIdentity() {
        val request = center.begin(route, OhttpHealthScope.LIGHTWALLETD)
        request.record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)

        val observation = center.snapshot(route).observations.single()
        assertEquals(OhttpHealthSource.CLIENT, observation.source)
        assertEquals(OhttpHealthScope.LIGHTWALLETD, observation.scope)
        assertEquals(OhttpHealthOutcome.TransportUnavailable, observation.outcome)
        assertEquals(OhttpHealthStage.CONNECTION, observation.stage)
        assertEquals(request.requestId, observation.requestId)
        assertTrue(observation.observedAtNanos > observation.startedAtNanos)
        assertFalse(observation.historical)
    }

    @Test
    fun successAndFailureOfTheSameScopeCoexist() {
        center.begin(route, OhttpHealthScope.LIGHTWALLETD).record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)
        center
            .begin(route, OhttpHealthScope.LIGHTWALLETD)
            .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)

        val outcomes = center.snapshot(route).observations.map { it.outcome }
        assertEquals(listOf(OhttpHealthOutcome.Usable, OhttpHealthOutcome.TransportUnavailable), outcomes)
    }

    @Test
    fun aNewerObservationReplacesTheOlderOneOfItsKindOnly() {
        center.begin(route, OhttpHealthScope.LIGHTWALLETD).record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)
        val newer = center.begin(route, OhttpHealthScope.LIGHTWALLETD)
        newer.record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)

        val observations = center.snapshot(route).observations
        assertEquals(1, observations.size)
        assertEquals(newer.requestId, observations.single().requestId)
    }

    @Test
    fun anObservationStampedBeforeTheRetainedOneDoesNotReplaceIt() {
        // The clock steps backwards, so the second observation looks older than the retained one.
        var t = 9_000L
        val backwards = OhttpHealthCenter(clock = { t.also { t -= 100 } })
        val first = backwards.begin(route, OhttpHealthScope.LIGHTWALLETD)
        first.record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)
        val late = backwards.begin(route, OhttpHealthScope.LIGHTWALLETD)
        late.record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)

        assertEquals(first.requestId, backwards.snapshot(route).observations.single().requestId)
    }

    @Test
    fun diagnosticEvidenceStaysSeparateFromOrdinaryEvidence() {
        center
            .begin(route, OhttpHealthScope.LIGHTWALLETD)
            .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)
        center
            .begin(route, OhttpHealthScope.LIGHTWALLETD, purpose = OhttpHealthPurpose.DIAGNOSTIC)
            .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)

        assertEquals(2, center.snapshot(route).observations.size)
    }

    @Test
    fun routeUsableIsRecordedInTheRouteScope() {
        val request = center.begin(route, OhttpHealthScope.EXCHANGE_RATES)
        request.recordRouteUsable(OhttpHealthStage.RESPONSE_HEAD)

        val observation = center.snapshot(route).observations.single()
        assertEquals(OhttpHealthScope.ROUTE, observation.scope)
        assertEquals(OhttpHealthOutcome.Usable, observation.outcome)
    }

    @Test
    fun theFirstUpdateReplaysHistoryAndLaterOnesAreFresh() =
        runBlocking {
            center.begin(route, OhttpHealthScope.LIGHTWALLETD).record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)

            val replayed = center.updates(route).first()
            assertTrue(replayed.observations.all { it.historical })

            val fresh = async(start = CoroutineStart.UNDISPATCHED) { center.updates(route).drop(1).first() }
            yield()
            center
                .begin(route, OhttpHealthScope.LIGHTWALLETD)
                .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)
            val update = fresh.await()
            assertTrue(update.observations.any { it.outcome == OhttpHealthOutcome.TransportUnavailable })
            assertTrue(update.observations.none { it.historical })
        }

    @Test
    fun aRequestInAnOperationCarriesItsIdentity() =
        runBlocking {
            val operation = newOhttpHealthOperation()
            val request = withOhttpOperation(operation) { center.beginInContext(route, OhttpHealthScope.LIGHTWALLETD) }

            assertEquals(operation, request.operation)
            assertEquals(OhttpHealthPurpose.USER_OPERATION, request.purpose)
        }

    @Test
    fun backgroundWorkDoesNotInheritTheOperation() =
        runBlocking {
            val operation = newOhttpHealthOperation()
            val request =
                withOhttpOperation(operation) {
                    withOhttpBackground { center.beginInContext(route, OhttpHealthScope.LIGHTWALLETD) }
                }

            assertNull(request.operation)
            assertEquals(OhttpHealthPurpose.BACKGROUND, request.purpose)
        }

    @Test
    fun completionCarriesOnlyTheEvidenceOfTheFailedAttempt() =
        runBlocking {
            val operation = newOhttpHealthOperation()
            val attempt = OhttpHealthAttempt(operation)
            val completion = async(start = CoroutineStart.UNDISPATCHED) { center.completions.first() }

            withOhttpOperation(operation) {
                // A harmless pre-check outside the attempt must not decide the verdict.
                center
                    .beginInContext(route, OhttpHealthScope.LIGHTWALLETD)
                    .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)
                withOhttpAttempt(attempt) {
                    center
                        .beginInContext(route, OhttpHealthScope.LIGHTWALLETD)
                        .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.RESPONSE_HEAD)
                }
            }
            center.complete(operation, failed = true, failedAttempt = attempt)

            val completed = completion.await()
            assertTrue(completed.failed)
            assertEquals(1, completed.observations.size)
            assertEquals(OhttpHealthStage.RESPONSE_HEAD, completed.observations.single().stage)
        }

    @Test
    fun aLocalFailureCompletesWithoutEvidence() =
        runBlocking {
            val operation = newOhttpHealthOperation()
            val completion = async(start = CoroutineStart.UNDISPATCHED) { center.completions.first() }

            center.complete(operation, failed = true)

            assertTrue(completion.await().observations.isEmpty())
        }

    @Test
    fun anAttemptTokenIsAdmittedOnlyOnceAndOnlyForItsOperation() =
        runBlocking {
            val operation = newOhttpHealthOperation()
            val attempt = OhttpHealthAttempt(operation)
            val foreign = newOhttpHealthOperation()

            withOhttpOperation(foreign) {
                withOhttpAttempt(attempt) {
                    center.beginInContext(route, OhttpHealthScope.LIGHTWALLETD).record(
                        OhttpHealthOutcome.TransportUnavailable,
                        OhttpHealthStage.CONNECTION,
                    )
                }
            }
            withOhttpOperation(operation) {
                withOhttpAttempt(attempt) {
                    center.beginInContext(route, OhttpHealthScope.LIGHTWALLETD).record(
                        OhttpHealthOutcome.TransportUnavailable,
                        OhttpHealthStage.CONNECTION,
                    )
                }
                // Reusing a token that was already entered is not attributed again.
                withOhttpAttempt(attempt) {
                    center.beginInContext(route, OhttpHealthScope.LIGHTWALLETD).record(
                        OhttpHealthOutcome.TransportUnavailable,
                        OhttpHealthStage.CONNECTION,
                    )
                }
            }

            val completion = async(start = CoroutineStart.UNDISPATCHED) { center.completions.first() }
            center.complete(operation, failed = true, failedAttempt = attempt)
            assertEquals(1, completion.await().observations.size)
        }
}
