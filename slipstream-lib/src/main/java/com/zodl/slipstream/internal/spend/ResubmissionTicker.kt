@file:Suppress("ReturnCount")

package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlin.coroutines.cancellation.CancellationException

/** One row the resubmission scan considers. */
internal data class ResubmissionCandidate(
    val txId: ByteArray,
    val raw: ByteArray
)

/**
 * Pure twin of the resubmission scan's `WHERE` clause (`host_read.rs`'s
 * `listResubmissionCandidates` SQL, ported verbatim from the upstream
 * `SELECTION_TRX_RESUBMISSION` selection) - unmined, unexpired (relative to [chainTip]), and a
 * send (negative account balance delta). Kept as its own function so the eligibility rule is
 * unit-testable without a SQLite connection (`SDK_ADAPTER_PLAN.md` T8: "the eligibility
 * predicate is pure -> JUnit-5 it"), independently of the actual query, which applies the
 * identical condition in SQL.
 */
internal fun isEligibleForResubmission(
    minedHeight: Long?,
    expiryHeight: Long,
    chainTip: Long,
    accountBalanceDelta: Long
): Boolean = minedHeight == null && expiryHeight > chainTip && accountBalanceDelta < 0

/**
 * `KOTLIN_ROSETTA.md` section 3.7: upstream resubmits unmined-within-expiry sends from inside the
 * processor's sync loop, which this adapter never constructs. Re-homes the *tick*: every
 * [RESUBMIT_EVERY] poll ticks while `SYNCED`, resubmit every eligible candidate and poke once.
 * iOS slipstream ships no background resubmission today - Android intentionally exceeds it because
 * the product depends on it (DECISIONS.md D6).
 */
internal class ResubmissionTicker(
    private val findCandidates: suspend (chainTip: Long) -> List<ResubmissionCandidate>,
    private val resubmit: suspend (ResubmissionCandidate) -> Unit,
    private val notifyTxChange: suspend () -> Unit,
    /**
     * Whether a candidate is rejected (every endpoint answered explicitly, one refused it for good,
     * none accepted it), so it is
     * not resubmitted. Production:
     * [SubmitPlanStore.rejectionFor].
     */
    private val isRejected: (ResubmissionCandidate) -> Boolean = { false },
    /**
     * Called with every candidate, so that the rejections of transactions that are no longer
     * candidates (mined or expired) are forgotten, with their shared migration marks. Production:
     * [SubmitPlanStore.retainTransactions].
     */
    private val retainRejections: suspend (List<ResubmissionCandidate>) -> Unit = {},
    /**
     * Called right before the candidates are read, so that [retainRejections] forgets only what was
     * known before the scan: a transaction created while it runs is not a candidate yet, and keeps
     * its marks. Production: [SubmitPlanStore.beginRetentionScan].
     */
    private val beforeScan: () -> Unit = {}
) {
    private var ticksSinceLastRun = 0

    suspend fun onTick(
        isSynced: Boolean,
        chainTip: Long
    ) {
        if (!isSynced) {
            ticksSinceLastRun = 0
            return
        }
        ticksSinceLastRun++
        if (ticksSinceLastRun < RESUBMIT_EVERY) return
        ticksSinceLastRun = 0

        beforeScan()
        val candidates = findCandidates(chainTip)
        retainRejections(candidates)
        val resubmittable = candidates.filterNot(isRejected)
        if (resubmittable.isEmpty()) return
        resubmittable.forEach { resubmit(it) }
        notifyTxChange()
    }

    companion object {
        /** ~5 minutes at the 2 s tick cadence (`SlipstreamEngine.POLL_INTERVAL_MS`). */
        const val RESUBMIT_EVERY = 150
    }
}

/**
 * Resubmits [txId] to every endpoint of its submit plan in turn (just [defaultEndpoint] when it has
 * none), until one accepts it, recording each endpoint's answer in [planStore] as `sdk-lib`'s
 * `SubmitPlanExecutor` does: a transaction submitted to several endpoints can only become rejected
 * once every one of them answered explicitly, so each must be asked again. A submission that throws
 * is recorded as unanswered and the next endpoint is tried; a cancellation is rethrown.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun resubmitToPlanEndpoints(
    planStore: SubmitPlanStore,
    txId: FirstClassByteArray,
    defaultEndpoint: LightWalletEndpoint,
    submit: suspend (LightWalletEndpoint) -> TransactionSubmitResult
) {
    for (endpoint in planStore.endpointsFor(txId).ifEmpty { listOf(defaultEndpoint) }) {
        planStore.recordSubmitStarted(txId, endpoint)
        val result =
            try {
                submit(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn(e) { "Resubmission to one endpoint failed; it stays unanswered" }
                TransactionSubmitResult.NotAttempted(txId)
            }
        planStore.recordAnswer(txId, endpoint, result)
        if (result is TransactionSubmitResult.Success) break
    }
}
