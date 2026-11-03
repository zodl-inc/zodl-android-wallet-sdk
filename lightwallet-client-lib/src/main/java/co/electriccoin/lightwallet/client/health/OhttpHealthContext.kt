package co.electriccoin.lightwallet.client.health

import kotlinx.coroutines.NonCancellable
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
    /** Set for diagnostic work (a probe): its evidence stays apart from ordinary evidence. */
    val purpose: OhttpHealthPurpose? = null,
    /** Present inside [runOhttpOperation]: collects which call decided the operation's result. */
    val handle: OhttpOperationHandle? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OhttpHealthContextElement>
}

/** Creates the identity of one explicit user operation. */
fun newOhttpHealthOperation(): OhttpHealthOperation =
    OhttpHealthOperation(id = UUID.randomUUID().toString(), startedAtNanos = OhttpHealthClock.nowNanos())

/**
 * Runs [block] as part of [operation]; requests started inside carry its identity. It does not collect which
 * call decided the operation: [withOhttpDecisiveCall] is a no-op inside it. Use [runOhttpOperation] for an
 * operation that reports its result.
 */
suspend fun <T> withOhttpOperation(
    operation: OhttpHealthOperation,
    block: suspend () -> T,
): T = withContext(OhttpHealthContextElement(operation, null)) { block() }

/**
 * Runs [block] as a diagnostic (a probe of the route): requests started inside are recorded as
 * [OhttpHealthPurpose.DIAGNOSTIC], which the engine and the policy keep apart from ordinary traffic,
 * and carry no user operation.
 */
suspend fun <T> withOhttpDiagnostic(block: suspend () -> T): T =
    withContext(OhttpHealthContextElement(null, null, OhttpHealthPurpose.DIAGNOSTIC)) { block() }

/**
 * What [runOhttpOperation] and [withOhttpDecisiveCall] share: the operation, and the call (attempt) whose
 * final failure decides its result.
 */
class OhttpOperationHandle internal constructor(
    val operation: OhttpHealthOperation,
) {
    /** The latest decisive call started inside the operation: a later call supersedes an earlier one. */
    @Volatile
    var decisiveAttempt: OhttpHealthAttempt? = null
        internal set
}

/**
 * Runs [block] as one explicit user operation and reports its result to this center when it ends: the
 * evidence of the decisive call it made (see [withOhttpDecisiveCall]) is attached when [isFailure] says the
 * result failed, or [block] threw. A failure with no decisive call (a local one) carries no evidence.
 * Call it at the one place that knows the business result, after guards are released.
 */
suspend fun <T> OhttpHealthCenter.runOhttpOperation(
    isFailure: (T) -> Boolean = { false },
    block: suspend () -> T,
): T {
    val handle = OhttpOperationHandle(newOhttpHealthOperation())
    var failed = true
    try {
        val result = withContext(OhttpHealthContextElement(handle.operation, null, null, handle)) { block() }
        failed = isFailure(result)
        return result
    } finally {
        withContext(NonCancellable) { complete(handle.operation, failed, handle.decisiveAttempt) }
    }
}

/**
 * Marks [block] as the call whose failure decides the current operation's result (the send of a transaction,
 * the one request of a quote). Outside [runOhttpOperation] it just runs [block]. A later decisive call
 * replaces an earlier one, so a successful pre-check never lets itself be mistaken for the verdict.
 */
suspend fun <T> withOhttpDecisiveCall(block: suspend () -> T): T {
    val handle = currentOhttpHealthContext()?.handle ?: return block()
    val attempt = OhttpHealthAttempt(handle.operation)
    handle.decisiveAttempt = attempt
    return withOhttpAttempt(attempt, block)
}

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
    val context = currentOhttpHealthContext()
    val current = context?.operation
    val admitted = current != null && current == attempt.operation && attempt.enter()
    return withContext(
        OhttpHealthContextElement(current, if (admitted) attempt else null, context?.purpose, context?.handle)
    ) { block() }
}

/**
 * Carries the caller's [OhttpHealthContextElement] over the gRPC hop: the channel runs every call on
 * its own executor, where the coroutine context of the caller is gone.
 */
internal val OHTTP_HEALTH_CALL_OPTION: io.grpc.CallOptions.Key<OhttpHealthContextElement> =
    io.grpc.CallOptions.Key
        .create("ohttp-health-context")

/** The calling coroutine's health context, to attach to a gRPC stub with [io.grpc.stub.AbstractStub.withOption]. */
internal suspend fun currentOhttpHealthContext(): OhttpHealthContextElement? =
    kotlin.coroutines.coroutineContext[OhttpHealthContextElement]

suspend fun currentOhttpOperation(): OhttpHealthOperation? =
    kotlin.coroutines.coroutineContext[OhttpHealthContextElement]?.operation

internal suspend fun currentOhttpAttempt(): OhttpHealthAttempt? =
    kotlin.coroutines.coroutineContext[OhttpHealthContextElement]?.attempt

/**
 * Collects the terminal evidence of the requests started inside one [withOhttpAttempt] scope:
 * the latest observation per request. Entered at most once.
 */
class OhttpHealthAttempt(
    val operation: OhttpHealthOperation,
) {
    private val lock = Any()
    private var entered = false
    private val terminal = LinkedHashMap<Long, OhttpHealthObservation>()

    internal fun enter(): Boolean =
        synchronized(lock) {
            if (entered) {
                false
            } else {
                entered = true
                true
            }
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
