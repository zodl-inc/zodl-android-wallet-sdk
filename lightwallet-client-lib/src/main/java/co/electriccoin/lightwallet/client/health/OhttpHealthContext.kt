package co.electriccoin.lightwallet.client.health

import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carries the user operation (and, inside [withOhttpAttempt], the attempt that decides its result)
 * through coroutine contexts: the Kotlin counterpart of iOS `OHTTPHealthContext` task-locals.
 *
 * Automatic retries, timers and follow-up work must not inherit a user's action identity: wrap them
 * in [withOhttpBackground].
 */
internal class OhttpHealthContextElement(
    val operation: OhttpHealthOperation?,
    val attempt: OhttpHealthAttempt?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OhttpHealthContextElement>
}

/** Creates the identity of one explicit user operation. */
fun newOhttpHealthOperation(): OhttpHealthOperation =
    OhttpHealthOperation(id = UUID.randomUUID().toString(), startedAtNanos = OhttpHealthClock.nowNanos())

/** Runs [block] as part of [operation]; requests started inside carry its identity. */
suspend fun <T> withOhttpOperation(
    operation: OhttpHealthOperation,
    block: suspend () -> T,
): T = withContext(OhttpHealthContextElement(operation, null)) { block() }

/** Runs [block] without any inherited operation or attempt. */
suspend fun <T> withOhttpBackground(block: suspend () -> T): T =
    withContext(OhttpHealthContextElement(null, null)) { block() }

/**
 * Scopes only the network call whose final failure may determine the operation's result. Foreign
 * operations and attempt tokens that were already used never get attributed.
 */
suspend fun <T> withOhttpAttempt(
    attempt: OhttpHealthAttempt,
    block: suspend () -> T,
): T {
    val current = currentOhttpOperation()
    val admitted = current != null && current == attempt.operation && attempt.enter()
    return try {
        withContext(OhttpHealthContextElement(current, if (admitted) attempt else null)) { block() }
    } finally {
        if (admitted) attempt.leave()
    }
}

/**
 * Carries the caller's [OhttpHealthContextElement] over the gRPC hop: the channel runs every call on
 * its own executor, where the coroutine context of the caller is gone.
 */
internal val OHTTP_HEALTH_CALL_OPTION: io.grpc.CallOptions.Key<OhttpHealthContextElement> =
    io.grpc.CallOptions.Key.create("ohttp-health-context")

/** The calling coroutine's health context, to attach to a gRPC stub with [io.grpc.stub.AbstractStub.withOption]. */
internal suspend fun currentOhttpHealthContext(): OhttpHealthContextElement? =
    kotlin.coroutines.coroutineContext[OhttpHealthContextElement]

suspend fun currentOhttpOperation(): OhttpHealthOperation? = kotlin.coroutines.coroutineContext[OhttpHealthContextElement]?.operation

internal suspend fun currentOhttpAttempt(): OhttpHealthAttempt? = kotlin.coroutines.coroutineContext[OhttpHealthContextElement]?.attempt

/**
 * Collects the terminal evidence of the requests started inside one [withOhttpAttempt] scope:
 * the latest observation per request. Entered at most once.
 */
class OhttpHealthAttempt(
    val operation: OhttpHealthOperation,
) {
    private val lock = Any()
    private var entered = false
    private var active = 0
    private val terminal = LinkedHashMap<Long, OhttpHealthObservation>()

    internal fun enter(): Boolean =
        synchronized(lock) {
            if (entered) {
                false
            } else {
                entered = true
                active++
                true
            }
        }

    internal fun leave() {
        synchronized(lock) { active-- }
    }

    internal fun record(observation: OhttpHealthObservation) {
        synchronized(lock) {
            val existing = terminal[observation.requestId]
            if (existing == null || existing.version <= observation.version) {
                terminal[observation.requestId] = observation
            }
        }
    }

    internal fun terminalObservations(): List<OhttpHealthObservation> =
        synchronized(lock) { terminal.values.sortedBy { it.version } }
}
