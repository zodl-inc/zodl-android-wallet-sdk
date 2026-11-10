@file:Suppress("MaxLineLength")

package co.electriccoin.lightwallet.client.health

import co.electriccoin.lightwallet.client.internal.ExampleGatewaysRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OhttpHealthCenterTest {
    @get:Rule
    val exampleGateways = ExampleGatewaysRule()

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

        assertEquals(
            first.requestId,
            backwards
                .snapshot(route)
                .observations
                .single()
                .requestId
        )
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
            // The new failure is live; what was retained when the collection began stays history on every emission.
            val failure = update.observations.single { it.outcome == OhttpHealthOutcome.TransportUnavailable }
            assertFalse(failure.historical)
            assertTrue(update.observations.filter { it.outcome == OhttpHealthOutcome.Usable }.all { it.historical })
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

    @Test
    fun aDiagnosticRequestIsKeptApartAndCarriesNoOperation() =
        runBlocking {
            val operation = newOhttpHealthOperation()
            val request =
                withOhttpOperation(operation) {
                    withOhttpDiagnostic { center.beginInContext(route, OhttpHealthScope.LIGHTWALLETD) }
                }

            assertEquals(OhttpHealthPurpose.DIAGNOSTIC, request.purpose)
            assertNull(request.operation)
        }

    @Test
    fun anOperationCompletesWithTheEvidenceOfItsLatestDecisiveCall() =
        runBlocking {
            val completion = async(start = CoroutineStart.UNDISPATCHED) { center.completions.first() }

            val failed =
                center.runOhttpOperation(isFailure = { it == "failed" }) {
                    // A pre-check that succeeded is superseded by the send that failed.
                    withOhttpDecisiveCall {
                        center
                            .beginInContext(route, OhttpHealthScope.LIGHTWALLETD)
                            .record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)
                    }
                    withOhttpDecisiveCall {
                        center
                            .beginInContext(route, OhttpHealthScope.LIGHTWALLETD)
                            .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)
                    }
                    "failed"
                }

            assertEquals("failed", failed)
            val completed = completion.await()
            assertTrue(completed.failed)
            assertEquals(1, completed.observations.size)
            assertEquals(OhttpHealthOutcome.TransportUnavailable, completed.observations.single().outcome)
        }

    @Test
    fun aSuccessfulOperationCarriesNoEvidence() =
        runBlocking {
            val completion = async(start = CoroutineStart.UNDISPATCHED) { center.completions.first() }

            center.runOhttpOperation<String> {
                withOhttpDecisiveCall {
                    center
                        .beginInContext(route, OhttpHealthScope.LIGHTWALLETD)
                        .record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)
                }
                "ok"
            }

            val completed = completion.await()
            assertTrue(!completed.failed)
            assertTrue(completed.observations.isEmpty())
        }

    @Test
    fun anOperationThatThrowsCompletesAsFailedAndRethrows() =
        runBlocking {
            val completion = async(start = CoroutineStart.UNDISPATCHED) { center.completions.first() }

            val error =
                runCatching {
                    center.runOhttpOperation<Unit> { throw IllegalStateException("boom") }
                }.exceptionOrNull()

            assertEquals("boom", error?.message)
            assertTrue(completion.await().failed)
        }

    @Test
    fun aDecisiveCallOutsideAnOperationJustRuns() =
        runBlocking {
            val request = withOhttpDecisiveCall { center.beginInContext(route, OhttpHealthScope.LIGHTWALLETD) }
            assertNull(request.operation)
        }

    @Test
    fun replayedHistoryStaysHistoricalOnLaterEmissionsUntilANewerObservationReplacesIt() =
        runBlocking {
            center
                .begin(route, OhttpHealthScope.LIGHTWALLETD)
                .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)
            val updates = mutableListOf<OhttpHealthSnapshot>()
            val collector = async(start = CoroutineStart.UNDISPATCHED) { center.updates(route).take(3).toList(updates) }

            // An unrelated new observation re-delivers the replayed failure: it is still history.
            center.begin(route, OhttpHealthScope.EXCHANGE_RATES).record(OhttpHealthOutcome.Usable, OhttpHealthStage.COMPLETE)
            yield()
            // A newer failure of the same kind replaces the replayed one, and is live.
            center
                .begin(route, OhttpHealthScope.LIGHTWALLETD)
                .record(OhttpHealthOutcome.TransportUnavailable, OhttpHealthStage.CONNECTION)
            collector.await()

            val afterUnrelated = updates[1].observations.single { it.scope == OhttpHealthScope.LIGHTWALLETD }
            assertTrue(afterUnrelated.historical)
            val afterReplacement = updates[2].observations.single { it.scope == OhttpHealthScope.LIGHTWALLETD }
            assertFalse(afterReplacement.historical)
        }

    @Test
    fun aRouteUsableThatStartedLaterSupersedesOneObservedLater() {
        // A starts before B but is observed after it; recovery is judged by the start of the success, so B wins.
        var t = 0L
        val ordered = OhttpHealthCenter(clock = { t })
        t = 5
        val a = ordered.begin(route, OhttpHealthScope.LIGHTWALLETD)
        t = 6
        val b = ordered.begin(route, OhttpHealthScope.LIGHTWALLETD)
        t = 8
        b.recordRouteUsable(OhttpHealthStage.RESPONSE_HEAD)
        t = 10
        a.recordRouteUsable(OhttpHealthStage.RESPONSE_HEAD)

        val kept = ordered.snapshot(route).observations.single { it.scope == OhttpHealthScope.ROUTE }
        assertEquals(b.requestId, kept.requestId)
    }
}
