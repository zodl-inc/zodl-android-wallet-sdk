package cash.z.ecc.android.sdk.model.voting

import cash.z.ecc.android.sdk.internal.model.TorRuntimeLease

/**
 * A lease on a [cash.z.ecc.android.sdk.Synchronizer]'s shared Tor runtime, for routing the voting
 * API's network traffic over Tor. Obtain one from
 * [cash.z.ecc.android.sdk.Synchronizer.acquireVotingTorLease]; pass `null` to the voting API
 * instead when Tor is disabled.
 *
 * Opaque: the raw runtime pointer never leaves the SDK. The lease keeps the underlying runtime
 * alive -- a `Synchronizer` closed or rebuilt while it is held (endpoint switch, Tor toggle, wallet
 * reset) defers freeing the runtime until the lease is released. Callers MUST [release] it once
 * done, and not before every voting session or call it was passed to has finished: a round or
 * share-tracking session keeps using the runtime for its whole lifetime, so release only after
 * that session's `close()`.
 *
 * The constructor is only reachable with a [TorRuntimeLease], which only
 * `TorClient.leaseRuntime()` issues -- SDK consumers obtain leases through the synchronizer.
 */
class VotingTorLease(
    private val lease: TorRuntimeLease
) {
    /**
     * The raw runtime handle, for the SDK's own JNI calls only.
     *
     * @throws IllegalStateException once [release]d -- using it after that is a use-after-free.
     */
    internal val handle: Long
        get() = lease.handle

    val isReleased: Boolean
        get() = lease.isReleased

    /**
     * Releases the lease against the Tor client that issued it, whatever has happened to the
     * synchronizer since. Idempotent (a second call is a no-op, so one holder can never release
     * another's pin) and completes even when called from a cancelled coroutine.
     */
    suspend fun release() = lease.release()
}
