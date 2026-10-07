package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionOverview
import com.zodl.slipstream.internal.SlipstreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/**
 * Owns `allTransactions` (R18) and backs `getTransactions(accountUuid)` (R23).
 *
 * Deliberately deviates from `SDK_ADAPTER_PLAN.md` section 2.1's literal shape - a
 * `MutableStateFlow<List<TransactionOverview>>`, re-set ONLY on [SlipstreamEngine.requeryTicks]
 * and shared hot across every collector. What's actually implemented is a COLD
 * `combine(requeryTicks.onStart { emit(Unit) }.mapLatest { queryVisibleRows }, networkHeight)`
 * chain on [Dispatchers.Default]: each collector re-runs its own query on the section 3.2/5.4
 * re-query rule rather than observing one shared re-set value, and re-maps those rows (no SQL)
 * whenever the chain tip moves - full-list replace, never diffed, is still true, just
 * per-collector rather than per-controller. Applies the section 3.1 visibility filter via
 * [VisibleTransactionRows.queryVisibleRows] (the filter itself
 * lives in `host_read.rs`'s `list_transactions_sql`, moved from the Kotlin `VisibleTransactionsQuery`
 * this reader used to build).
 */
internal class TransactionsController(
    private val reader: VisibleTransactionRows,
    private val engine: SlipstreamEngine,
    private val typesafeBackend: TypesafeBackend,
) {
    val allTransactions: Flow<List<TransactionOverview>> = visibleTransactions(accountUuid = null)

    /**
     * R23: same machinery as R18 plus the `account_uuid = ?` filter; the interface has no
     * per-account flow.
     */
    fun forAccount(accountUuid: AccountUuid): Flow<List<TransactionOverview>> = visibleTransactions(accountUuid)

    /**
     * Rows are re-read only on [SlipstreamEngine.requeryTicks] (the section 5.4 rule, which keys on
     * `txSetVersion` and never fires for a bare chain-tip advance), but every row's
     * [cash.z.ecc.android.sdk.model.TransactionState] depends on the chain tip too: a received
     * transaction becomes Confirmed purely because new blocks arrive, with no change to the
     * transaction set. So the last-read rows are re-mapped against the current tip on every
     * [SlipstreamEngine.networkHeight] change - the twin of the legacy
     * `SdkSynchronizer.getTransactions()` combining its rows with `processor.networkHeight` -
     * without re-running the SQL.
     *
     * `networkHeight` is a StateFlow, so it is already distinct-until-changed: an unchanged tip
     * re-published on every 2 s poll tick does not re-map anything.
     *
     * Each collection keeps its own [ScannedHeightCache], so the scanned-height read over JNI
     * happens only when [latestHeight] needs it rather than on every tip change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun visibleTransactions(accountUuid: AccountUuid?): Flow<List<TransactionOverview>> =
        flow {
            val cache = ScannedHeightCache()
            val rows =
                engine.requeryTicks
                    .onStart { emit(Unit) }
                    .mapLatest { reader.queryVisibleRows(isRecovering(), accountUuid) }
            emitAll(
                combine(rows, engine.networkHeight) { latestRows, networkHeight ->
                    val latestHeight = latestHeight(networkHeight, cache)
                    latestRows.map { row -> TransactionOverviewCursor.fromRow(row, latestHeight) }
                }
            )
        }.flowOn(Dispatchers.Default)

    private fun isRecovering(): Boolean = engine.lastSnapshot.value?.isRecovering ?: false

    /**
     * MOB-1664: [SlipstreamEngine.lastSnapshot] is per-engine-instance state that starts back at
     * `null` every time the synchronizer is rebuilt (e.g. an automatic server switch tears down
     * and reconstructs the whole engine via `WalletCoordinator`'s `flatMapLatest`) - unlike the
     * legacy `SdkSynchronizer.getTransactions()`, which always had `backend.getMaxScannedHeight()`
     * as a DB-backed fallback for exactly this gap. That fallback was lost when this path was
     * ported; restoring it here (rather than trying to seed the new engine instance from the old
     * one's last-known height) keeps every account's confirmation math tied to its own DB file,
     * so it can't leak state across a network/account switch and stays reorg-safe (a reorged-out
     * tx's `minedHeight` is cleared in the DB, so `computeTransactionState` still won't report it
     * Confirmed even once `latestHeight` resolves again). See [resolveLatestHeight] for why the
     * scanned height is folded in even when a live tip is known.
     *
     * The scanned height is read from the backend on the first computation of each collection,
     * and after that only when the live snapshot tip is unknown or below the height last resolved
     * for this collection (see [needsScannedHeight]); otherwise the value read last is reused, as
     * a live tip at or above everything resolved so far already covers anything the wallet has
     * scanned.
     */
    private suspend fun latestHeight(
        networkHeight: BlockHeight?,
        cache: ScannedHeightCache
    ): BlockHeight? {
        val snapshotChainTip = engine.lastSnapshot.value?.chainTip
        if (needsScannedHeight(resolveLiveChainTip(snapshotChainTip), cache.lastResolved)) {
            cache.maxScannedHeight = typesafeBackend.getMaxScannedHeight()
        }
        return resolveLatestHeight(
            snapshotChainTip = snapshotChainTip,
            networkHeight = networkHeight,
            maxScannedHeight = cache.maxScannedHeight
        ).also { cache.lastResolved = it }
    }

    /** What one collection of [visibleTransactions] remembers between tip changes. */
    private class ScannedHeightCache {
        var maxScannedHeight: BlockHeight? = null
        var lastResolved: BlockHeight? = null
    }
}

/**
 * Whether [TransactionsController] must read the wallet DB's max scanned height again: on the
 * first computation of a collection ([lastResolved] `null`), when the live snapshot tip is
 * unknown ([liveChainTip] `null`, see [resolveLiveChainTip]), or when it is below [lastResolved],
 * the height the previous computation settled on (a stale tip, which the scanned height may have
 * to lift). The first computation always reads it: a freshly rebuilt engine can report a known
 * but stale-low tip before it has caught up, and only the scanned height lifts it then.
 */
internal fun needsScannedHeight(
    liveChainTip: BlockHeight?,
    lastResolved: BlockHeight?
): Boolean = lastResolved == null || liveChainTip == null || liveChainTip < lastResolved

/**
 * The confirmation-math height for [TransactionOverviewCursor.fromRow]: the highest of the live
 * snapshot tip (see [resolveLiveChainTip]), the engine's last-published network height, and the
 * wallet DB's max scanned height. The scanned height can never legitimately exceed the chain
 * tip, so taking the max only matters when the tip reading is stale (e.g. a freshly rebuilt
 * engine whose tip has not caught up yet) - in which case it stops a transaction the wallet has
 * already scanned well past from being under-counted back to Pending. `null` only when none of
 * the three is known.
 */
internal fun resolveLatestHeight(
    snapshotChainTip: Long?,
    networkHeight: BlockHeight?,
    maxScannedHeight: BlockHeight?
): BlockHeight? = listOfNotNull(resolveLiveChainTip(snapshotChainTip), networkHeight, maxScannedHeight).maxOrNull()

/**
 * MOB-1664: the live-height half of [TransactionsController.latestHeight]'s decision, pulled out
 * as a pure function so the "is this snapshot's chainTip trustworthy" check is directly testable
 * without a live [SlipstreamEngine]/native handle. Returns `null` for a fresh engine instance
 * (chainTip absent) or a degraded snapshot (chainTip <= 0), signalling the caller to fall back to
 * the DB-backed scanned height instead of treating the raw reading as ground truth.
 */
internal fun resolveLiveChainTip(chainTip: Long?): BlockHeight? = chainTip?.takeIf { it > 0 }?.let(BlockHeight::new)
