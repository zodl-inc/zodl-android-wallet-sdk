package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.TransactionSubmitResult

/**
 * Submits a transaction to the endpoints of its plan in turn, until one does not fail. Each
 * endpoint's answer is recorded in [pendingSubmitPlanStore], when there is one, so that a
 * transaction becomes [PendingSubmitPlanStore.StoredSubmitPlan.Rejected] only once every endpoint
 * gave an explicit server answer, none accepted and one refused it for good. A transport failure
 * (timeout, gRPC or I/O error) or a submission that throws is inconclusive: it is recorded as such,
 * keeps the transaction pending and the next endpoint is tried.
 */
internal class SubmitPlanExecutor(
    private val transactionSubmitter: TransactionSubmitter,
    private val pendingSubmitPlanStore: PendingSubmitPlanStore? = null
) {
    suspend fun submit(
        transaction: CreatedTransaction,
        submitPlan: TransactionSubmitPlan
    ): TransactionSubmitResult {
        var result: TransactionSubmitResult = TransactionSubmitResult.NotAttempted(transaction.txId)
        for (endpoint in submitPlan.endpoints.distinct()) {
            pendingSubmitPlanStore?.recordSubmitStarted(transaction.txId, endpoint)
            result = transactionSubmitter.submit(transaction, endpoint)
            pendingSubmitPlanStore?.let { store ->
                store.recordSubmitOutcome(transaction.txId, endpoint, result)
                result = store.withVerdict(transaction.txId, result)
            }
            if (result !is TransactionSubmitResult.Failure) {
                break
            }
        }
        return result
    }
}
