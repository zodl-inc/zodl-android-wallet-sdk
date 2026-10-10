package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ownership of a transport for the whole of one ceremony.
 *
 * A transport admits one exchange at a time, and a [cash.z.ecc.android.sdk.ledger.LedgerDevice] runs
 * one call at a time, but neither protects what the device actually cares about: a pairing, a signing
 * ceremony or an app switch is a run of commands whose order the device's state depends on. One
 * command from another ceremony in the middle of that run — a second `LedgerDevice` over the same
 * transport reading the app version while the first is exporting a viewing key, say — corrupts the
 * device's ceremony and fails both callers.
 *
 * Every entry point that talks to a device therefore takes the transport's ceremony gate before its
 * first command and holds it until it is done, success or failure: `LedgerDevice`'s commands and
 * pairing, [LedgerPcztSigner]'s signing ceremony, and `LedgerZcashApp`'s app queries and switches.
 * Two ceremonies on one transport run one after the other, whichever objects started them.
 *
 * The gate belongs to the transport, not to the SDK object using it: [LedgerCeremonyGates] keeps one
 * per transport instance that has a ceremony running or queued, so two devices over one transport, or
 * a device and a signing ceremony, find the same gate. A caller cancelled while queued for it throws
 * [CancellationException] without touching the transport: it never sent anything, and the transport
 * stays with whoever holds it.
 */
internal object LedgerCeremony {
    /**
     * Runs [body] as the only ceremony on [transport], waiting for any ceremony already running or
     * queued on it to finish first.
     *
     * @throws CancellationException when the calling coroutine is cancelled before the ceremony is
     *         admitted, on entry included; nothing was sent and nothing is closed. Otherwise whatever
     *         [body] throws.
     */
    suspend fun <T> run(
        transport: LedgerApduTransport,
        body: suspend () -> T
    ): T {
        currentCoroutineContext().ensureActive()
        val gate = LedgerCeremonyGates.gateFor(transport)
        try {
            gate.acquire()
        } catch (e: CancellationException) {
            LedgerCeremonyGates.release(transport)
            throw e
        }
        val hold = LedgerCeremonyHold(transport, gate)
        try {
            return body()
        } finally {
            hold.release()
        }
    }

    /**
     * Takes [transport] for a ceremony already in progress that moves onto it partway through — a
     * pairing or an app switch whose link dropped and that reconnected — and keeps it until the
     * returned hold is released.
     *
     * A transport nothing else is using is taken at once, even by a cancelled coroutine: the ceremony
     * is then the transport's only user, and its own cancellation handling closes the transport as it
     * would any other it is on, instead of leaving a fresh connection open that nothing owns. A
     * transport another ceremony holds is waited for, and a cancellation while queued throws
     * [CancellationException] without taking it: the transport stays with its holder, and the caller
     * must not have adopted it, so that its cleanup closes nothing of the holder's.
     */
    suspend fun adopt(transport: LedgerApduTransport): LedgerCeremonyHold {
        val gate = LedgerCeremonyGates.gateFor(transport)
        if (gate.tryAcquire()) {
            return LedgerCeremonyHold(transport, gate)
        }
        try {
            gate.acquire()
        } catch (e: CancellationException) {
            LedgerCeremonyGates.release(transport)
            throw e
        }
        return LedgerCeremonyHold(transport, gate)
    }
}

/**
 * A ceremony's hold on one transport, from [LedgerCeremony.adopt]; [LedgerCeremony.run] holds its
 * transport the same way for the span of its body. Release it exactly once, when the ceremony is done
 * with the transport, success or failure.
 */
internal class LedgerCeremonyHold(
    private val transport: LedgerApduTransport,
    private val gate: LedgerCeremonyGate
) {
    /** Lets the next ceremony queued on the transport, if any, proceed. Safe from a cancelled coroutine. */
    fun release() {
        gate.release()
        LedgerCeremonyGates.release(transport)
    }
}

/**
 * One transport's ceremony gate: a [Mutex] that also counts the callers queued behind its holder, so a
 * test can wait for a caller to actually be queued instead of guessing with a delay.
 */
internal class LedgerCeremonyGate {
    private val mutex = Mutex()
    private val queued = AtomicInteger()

    /** How many callers are waiting behind the one holding the gate. */
    val queuedCount: Int get() = queued.get()

    /** Holds the gate at once when nothing holds it, cancelled coroutine or not; `false` when something does. */
    fun tryAcquire(): Boolean = mutex.tryLock()

    /**
     * Waits until nothing holds the gate, then holds it. Throws [CancellationException] without holding
     * it when the coroutine is cancelled while queued: [Mutex.lock] hands a lock granted in that same
     * instant back rather than resume a cancelled coroutine with it.
     */
    suspend fun acquire() {
        if (mutex.tryLock()) {
            return
        }
        queued.incrementAndGet()
        try {
            mutex.lock()
        } finally {
            queued.decrementAndGet()
        }
    }

    fun release() = mutex.unlock()
}

/**
 * The ceremony gate of every transport with a ceremony running or queued on it.
 *
 * Keyed by the transport instance, so every ceremony on one transport, from whatever object, waits
 * on the same [LedgerCeremonyGate]. An entry lives as long as some ceremony holds or waits for it and
 * goes away with the last one, so a transport that is released and collected leaves nothing behind.
 */
internal object LedgerCeremonyGates {
    private class Entry(
        val gate: LedgerCeremonyGate
    ) {
        var users = 0
    }

    private val gates = IdentityHashMap<LedgerApduTransport, Entry>()

    /** How many transports currently have a ceremony running or queued. Test-only visibility. */
    val transportsInUse: Int get() = synchronized(this) { gates.size }

    /**
     * How many ceremonies are queued behind the one running on [transport]; `0` when none runs.
     * Test-only visibility.
     */
    fun queuedCeremonies(transport: LedgerApduTransport): Int =
        synchronized(this) { gates[transport]?.gate?.queuedCount ?: 0 }

    /** The gate for [transport], counting the caller as one of its users until [release]. */
    fun gateFor(transport: LedgerApduTransport): LedgerCeremonyGate =
        synchronized(this) {
            val entry = gates.getOrPut(transport) { Entry(LedgerCeremonyGate()) }
            entry.users++
            entry.gate
        }

    /** Drops one user of [transport]'s gate, and the gate itself with the last user. */
    fun release(transport: LedgerApduTransport) {
        synchronized(this) {
            val entry = gates[transport] ?: return
            entry.users--
            if (entry.users == 0) {
                gates.remove(transport)
            }
        }
    }
}
