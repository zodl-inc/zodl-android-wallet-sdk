package cash.z.ecc.android.sdk.internal.model

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A pin on one specific [TorClient]'s native Tor runtime, for handing that runtime across a JNI
 * boundary to a native subsystem that holds it beyond a single call (today: the voting round
 * driver and share-tracking driver).
 *
 * Obtainable only from [TorClient.leaseRuntime] -- the constructor is internal, so a caller cannot
 * fabricate a lease around an arbitrary pointer. While the lease is held, [TorClient.dispose]
 * defers freeing the runtime instead of freeing it out from under an in-flight native call.
 *
 * [release] goes back to the exact [TorClient] that issued the lease, whatever has happened to the
 * holder that handed it out since (a `Synchronizer` closed or rebuilt mid-vote, a disposed
 * `LazyTorClient`). It is idempotent -- a second call is a no-op, so one caller can never release
 * another caller's pin -- and it completes even when called from a cancelled coroutine.
 */
class TorRuntimeLease internal constructor(
    private val rawHandle: Long,
    private val onRelease: suspend () -> Unit
) {
    private val released = AtomicBoolean(false)

    /**
     * The raw native runtime handle this lease keeps alive.
     *
     * @throws IllegalStateException if the lease has already been [release]d -- using the handle
     * after that point is a use-after-free.
     */
    val handle: Long
        get() {
            check(!released.get()) { "TorRuntimeLease used after release" }
            return rawHandle
        }

    val isReleased: Boolean
        get() = released.get()

    suspend fun release() {
        if (released.compareAndSet(false, true)) {
            withContext(NonCancellable) { onRelease() }
        }
    }
}
