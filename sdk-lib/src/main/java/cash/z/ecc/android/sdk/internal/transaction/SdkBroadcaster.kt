package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.OvkPolicy
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSizeBound
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class SdkBroadcaster(
    private val txManager: OutboundTransactionManager,
    private val transactionSubmitter: TransactionSubmitter,
    private val pendingSubmitPlanStore: PendingSubmitPlanStore
) : Broadcaster {
    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        ovkPolicy: OvkPolicy,
        sizeBound: TransactionSizeBound
    ): List<CreatedTransaction> =
        pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan(isMigration = isMigrationProposal(proposal)) {
            txManager
                .createProposedTransactions(proposal, usk, ovkPolicy, sizeBound)
                .map { it.toCreatedTransaction() }
        }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): List<CreatedTransaction> =
        pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan {
            listOf(
                txManager
                    .extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures)
                    .toCreatedTransaction()
            )
        }

    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult = recordingEndpointAfterSubmit(transaction, endpoint)

    // A migration's transactions are marked by id when they are created, so that they are never rejected. A PCZT's
    // transaction was marked by id when the PCZT was created from a migration proposal (see
    // MigrationTransactionMarks), so creating a transaction from a PCZT marks nothing more.
    private suspend fun isMigrationProposal(proposal: Proposal) =
        pendingSubmitPlanStore.migrationMarks.isMigrationProposal(proposal)

    // Legacy Synchronizer APIs route through the same plan-store machinery as the public
    // Broadcaster so the sync-loop's resubmit step skips in-flight submits.
    internal suspend fun createAndSubmitProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        endpoint: LightWalletEndpoint,
        ovkPolicy: OvkPolicy = OvkPolicy.Sender,
        sizeBound: TransactionSizeBound = TransactionSizeBound.CONSENSUS
    ): Flow<TransactionSubmitResult> =
        pendingSubmitPlanStore
            .createAndMarkAwaitingSubmitPlan(isMigration = isMigrationProposal(proposal)) {
                txManager
                    .createProposedTransactions(proposal, usk, ovkPolicy, sizeBound)
                    .map { it.toCreatedTransaction() }
            }.createSubmitResultFlow { transaction ->
                recordingEndpointAfterSubmit(transaction, endpoint)
            }

    internal suspend fun createAndSubmitTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt,
        endpoint: LightWalletEndpoint
    ): Flow<TransactionSubmitResult> =
        pendingSubmitPlanStore
            .createAndMarkAwaitingSubmitPlan {
                listOf(
                    txManager
                        .extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures)
                        .toCreatedTransaction()
                )
            }.asFlow()
            .map { transaction ->
                recordingEndpointAfterSubmit(transaction, endpoint)
            }

    // Record the endpoint after submit returns so the in-flight window stays at AwaitingPlan
    // and the sync loop's resubmit step doesn't race it. finally + NonCancellable fires on
    // every exit (Success/Failure/throw) — AwaitingPlan strands the tx (resubmit skips that
    // state), Ready is the state the resubmit loop retries through SubmitPlanExecutor. The
    // endpoint's answer is recorded with it, so a transaction an endpoint refused for good, once every endpoint
    // answered and none accepted, becomes Rejected, which the resubmit loop skips. The submission is recorded as
    // started first, so that while it has not answered (or if it never does: it threw or was cancelled, perhaps
    // after the request was sent) another endpoint's refusal cannot reject a transaction this one may accept.
    // The result returned carries the SDK's verdict, not the raw answer: Success once any endpoint accepted the
    // transaction, and a Failure's rejection only when the store recorded it as Rejected.
    private suspend fun recordingEndpointAfterSubmit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult {
        var result: TransactionSubmitResult? = null
        pendingSubmitPlanStore.recordSubmitStarted(transaction.txId, endpoint)
        try {
            result = transactionSubmitter.submit(transaction, endpoint)
        } finally {
            withContext(NonCancellable) {
                pendingSubmitPlanStore.addSubmitEndpoint(transaction, endpoint, result)
            }
        }
        return pendingSubmitPlanStore.withVerdict(transaction.txId, checkNotNull(result))
    }
}

internal interface TransactionSubmitter {
    suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult
}

internal class EndpointTransactionSubmitter(
    private val walletClientFactory: WalletClientFactory,
    private val sdkFlags: SdkFlags,
    private val network: ZcashNetwork,
    private val walletTip: suspend () -> Long?
) : TransactionSubmitter {
    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult {
        val walletClient = walletClientFactory.create(endpoint)
        try {
            return walletClient.submitTransaction(transaction.raw, transaction.txId, sdkFlags, network, walletTip)
        } finally {
            withContext(NonCancellable) {
                walletClient.dispose()
            }
        }
    }
}

private fun List<CreatedTransaction>.createSubmitResultFlow(
    submit: suspend (CreatedTransaction) -> TransactionSubmitResult
): Flow<TransactionSubmitResult> {
    var anySubmissionFailed = false
    return asFlow()
        .map { transaction ->
            if (anySubmissionFailed) {
                // Skip both submit and addSubmitEndpoint — later legs of dependent proposals
                // (shield → spend) stay AwaitingPlan and are skipped by the resubmit loop.
                TransactionSubmitResult.NotAttempted(transaction.txId)
            } else {
                val submission = submit(transaction)
                when (submission) {
                    is TransactionSubmitResult.Success -> {
                        // Expected state
                    }

                    is TransactionSubmitResult.Failure,
                    is TransactionSubmitResult.NotAttempted -> {
                        anySubmissionFailed = true
                    }
                }
                submission
            }
        }
}
