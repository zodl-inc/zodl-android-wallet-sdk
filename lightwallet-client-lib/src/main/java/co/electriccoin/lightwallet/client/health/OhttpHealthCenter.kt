package co.electriccoin.lightwallet.client.health

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow

/**
 * Records the outcome of every Kotlin-client OHTTP request ([OhttpHealthSource.CLIENT]) and keeps the
 * bounded retained evidence per route: the Kotlin counterpart of iOS `OHTTPHealthCenter`.
 *
 * Evidence never changes the caller's result. Recording must never block or throw.
 */
class OhttpHealthCenter(
    private val clock: () -> Long = OhttpHealthClock::nowNanos,
) {
    private data class Slot(
        val scope: OhttpHealthScope,
        val category: Int,
        val diagnostic: Boolean,
    )

    private val lock = Any()
    private var nextRequestId = 0L
    private var nextVersion = 0L

    // Access-ordered and bounded like the engine's route registry (32 routes).
    private val routes =
        object : LinkedHashMap<OhttpHealthRoute, MutableStateFlow<OhttpHealthSnapshot>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<OhttpHealthRoute, MutableStateFlow<OhttpHealthSnapshot>>?) =
                size > MAX_ROUTES
        }
    private val retained = HashMap<OhttpHealthRoute, LinkedHashMap<Slot, OhttpHealthObservation>>()

    private val completionFlow =
        MutableSharedFlow<OhttpHealthOperationCompleted>(
            extraBufferCapacity = COMPLETION_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** One entry per [complete] call. The newest [COMPLETION_BUFFER] are kept: a slow subscriber never stalls a send. */
    val completions: SharedFlow<OhttpHealthOperationCompleted> get() = completionFlow

    /**
     * Starts the ticket of one logical request. The operation and attempt are read from the coroutine
     * context by [beginInContext]; pass them explicitly from non-suspending code.
     */
    internal fun begin(
        route: OhttpHealthRoute,
        scope: OhttpHealthScope,
        purpose: OhttpHealthPurpose = OhttpHealthPurpose.BACKGROUND,
        operation: OhttpHealthOperation? = null,
        attempt: OhttpHealthAttempt? = null,
    ): OhttpHealthRequest {
        val id =
            synchronized(lock) {
                nextRequestId += 1
                nextRequestId
            }
        val effectivePurpose =
            if (purpose == OhttpHealthPurpose.BACKGROUND && operation != null) {
                OhttpHealthPurpose.USER_OPERATION
            } else {
                purpose
            }
        return OhttpHealthRequest(this, route, scope, effectivePurpose, operation, attempt, id, clock())
    }

    /** [begin] with the operation and attempt of the calling coroutine. */
    internal suspend fun beginInContext(
        route: OhttpHealthRoute,
        scope: OhttpHealthScope,
        purpose: OhttpHealthPurpose = OhttpHealthPurpose.BACKGROUND,
    ): OhttpHealthRequest {
        val context = currentOhttpHealthContext()
        val effective =
            if (purpose == OhttpHealthPurpose.BACKGROUND && context?.purpose != null) context.purpose else purpose
        return begin(route, scope, effective, context?.operation, context?.attempt)
    }

    internal fun record(
        request: OhttpHealthRequest,
        scope: OhttpHealthScope,
        outcome: OhttpHealthOutcome,
        stage: OhttpHealthStage,
    ) {
        val observed = clock()
        val observation: OhttpHealthObservation
        synchronized(lock) {
            nextVersion += 1
            observation =
                OhttpHealthObservation(
                    source = OhttpHealthSource.CLIENT,
                    route = request.route,
                    scope = scope,
                    outcome = outcome,
                    stage = stage,
                    version = nextVersion,
                    requestId = request.requestId,
                    startedAtNanos = request.startedAtNanos,
                    observedAtNanos = observed,
                    operation = request.operation,
                    purpose = request.purpose,
                )
            val slots = retained.getOrPut(request.route) { LinkedHashMap() }
            val slot = Slot(scope, outcome.category, request.purpose == OhttpHealthPurpose.DIAGNOSTIC)
            val existing = slots[slot]
            if (existing == null || observation.supersedes(existing)) slots[slot] = observation
            publish(request.route, slots)
        }
        request.attempt?.record(observation)
    }

    private fun publish(
        route: OhttpHealthRoute,
        slots: Map<Slot, OhttpHealthObservation>,
    ) {
        val snapshot =
            OhttpHealthSnapshot(
                version = slots.values.maxOfOrNull { it.version } ?: 0L,
                observations = slots.values.sortedBy { it.version },
            )
        val flow = routes.getOrPut(route) { MutableStateFlow(snapshot) }
        flow.value = snapshot
        // A route evicted from the registry also loses its retained evidence.
        retained.keys.retainAll(routes.keys)
    }

    /** The current retained evidence of [route] (empty when none was recorded). */
    fun snapshot(route: OhttpHealthRoute): OhttpHealthSnapshot =
        synchronized(lock) { routes[route]?.value } ?: OhttpHealthSnapshot(0L, emptyList())

    /**
     * Coalesced updates for [route]. The first emission replays the retained history, flagged
     * `historical`; everything after it is fresh.
     */
    fun updates(route: OhttpHealthRoute): Flow<OhttpHealthSnapshot> =
        flow {
            val state =
                synchronized(lock) {
                    routes.getOrPut(route) { MutableStateFlow(OhttpHealthSnapshot(0L, emptyList())) }
                }
            // Everything retained when the collection began is history until a newer observation replaces it, on
            // every emission (as the engine does), not only on the first.
            val replayCeiling = state.value.version
            state.collect { snapshot -> emit(snapshot.markingHistoryUpTo(replayCeiling)) }
        }

    /**
     * Publishes the business result of [operation]. [failedAttempt] selects the evidence of the one
     * network attempt that decided the failure; without it (a local failure) the evidence is empty.
     * Call it after the business result is published and transaction guards are released.
     */
    internal suspend fun complete(
        operation: OhttpHealthOperation,
        failed: Boolean,
        failedAttempt: OhttpHealthAttempt? = null,
    ) {
        val observations =
            if (failed && failedAttempt != null && failedAttempt.operation == operation) {
                failedAttempt.terminalObservations()
            } else {
                emptyList()
            }
        completionFlow.tryEmit(OhttpHealthOperationCompleted(operation, failed, observations))
    }

    companion object {
        private const val MAX_ROUTES = 32
        private const val COMPLETION_BUFFER = 64

        /** The center used by the client transport. */
        val shared: OhttpHealthCenter by lazy { OhttpHealthCenter() }
    }
}

private fun OhttpHealthSnapshot.markingHistoryUpTo(ceiling: Long) =
    copy(observations = observations.map { if (it.version <= ceiling) it.copy(historical = true) else it })

/**
 * Whether [this] replaces [existing] of the same kind. A late response of an older request never replaces newer
 * evidence of its own kind. A non-diagnostic ROUTE/Usable is ordered like the engine and iOS do, by
 * (start, observed, version): the request that started later proves the route more recently, and recovery is
 * judged by the start of the success.
 */
private fun OhttpHealthObservation.supersedes(existing: OhttpHealthObservation): Boolean =
    if (purpose != OhttpHealthPurpose.DIAGNOSTIC &&
        scope == OhttpHealthScope.ROUTE &&
        outcome == OhttpHealthOutcome.Usable
    ) {
        compareValuesBy(this, existing, { it.startedAtNanos }, { it.observedAtNanos }, { it.version }) > 0
    } else {
        existing.observedAtNanos <= observedAtNanos
    }

/** The ticket of one logical request: its start identity and recipients never change on completion. */
internal class OhttpHealthRequest internal constructor(
    private val center: OhttpHealthCenter,
    val route: OhttpHealthRoute,
    val scope: OhttpHealthScope,
    val purpose: OhttpHealthPurpose,
    val operation: OhttpHealthOperation?,
    internal val attempt: OhttpHealthAttempt?,
    val requestId: Long,
    val startedAtNanos: Long,
) {
    /** Records the request's outcome in its own scope. */
    internal fun record(
        outcome: OhttpHealthOutcome,
        stage: OhttpHealthStage,
    ) = center.record(this, scope, outcome, stage)

    /**
     * Records that the route itself answered (an outer response arrived) as [OhttpHealthScope.ROUTE]
     * evidence, whatever the inner result was.
     */
    internal fun recordRouteUsable(stage: OhttpHealthStage) =
        center.record(this, OhttpHealthScope.ROUTE, OhttpHealthOutcome.Usable, stage)
}
