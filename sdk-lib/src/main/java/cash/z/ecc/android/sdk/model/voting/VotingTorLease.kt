package cash.z.ecc.android.sdk.model.voting

import cash.z.ecc.android.sdk.internal.model.TorRuntimeLease

/**
 * A lease on a [cash.z.ecc.android.sdk.Synchronizer]'s shared Tor runtime, for routing the voting
 * API's network traffic over Tor. Obtain one from
 * [cash.z.ecc.android.sdk.Synchronizer.acquireVotingTorLease]; pass `null` to the voting API
 * instead when Tor is disabled.
 *
 * The lease keeps the underlying runtime alive -- a `Synchronizer` closed or rebuilt while it is
 * held (endpoint switch, Tor toggle, wallet reset) defers freeing the runtime until the lease is
 * released. Callers MUST [TorRuntimeLease.release] it once done, and not before every voting
 * session or call it was passed to has finished: a round or share-tracking session keeps using the
 * runtime for its whole lifetime, so release only after that session's `close()`.
 *
 * [TorRuntimeLease.release] is idempotent, completes even from a cancelled coroutine, and always
 * releases against the Tor client that issued the lease, never whichever client the synchronizer
 * holds now.
 */
typealias VotingTorLease = TorRuntimeLease
