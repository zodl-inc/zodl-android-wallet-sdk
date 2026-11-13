@file:Suppress("LongParameterList")

package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.ext.clearContents
import cash.z.ecc.android.sdk.internal.transaction.submitTransaction
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.OvkPolicy
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSizeBound
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.toBackendEncoding
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.internal.db.SlipstreamTransactionReader
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * R29 (`broadcaster`): a full local implementation of the public [Broadcaster] interface - the
 * upstream `SdkBroadcaster` is `internal`, and the interface's default `UnavailableBroadcaster`
 * throws on every call, which is not shippable (Zodl Android calls all three methods,
 * `ProposalDataSource.kt:240,248,322`). Own [SubmitPlanStore] (`KOTLIN_ROSETTA.md` section 3.5).
 *
 * **NOTE (section 3.5 / R29):** unlike the Swift twin's `broadcaster.submit` (which has no poke),
 * this implementation DOES poke [SlipstreamEngine.notifyTxChange] on every `submit` - a gap the
 * Swift adapter has that this one deliberately does not copy, since the engine is the only
 * `allTransactions` signal on Android (there is no separate event stream to fall back on).
 *
 * The same poke fires store-first at the end of both create methods (MOB-1584): a stored
 * transaction must become visible in `allTransactions` on the next engine tick, not only after
 * its network broadcast round-trip resolves - under a degraded network the submit-time-only poke
 * left a created transaction invisible for the whole multi-endpoint submit window (up to its 30 s
 * global timeout), which testers reported as the Activity list showing a send minutes late.
 */
internal class SlipstreamBroadcaster(
    private val backend: Backend,
    private val walletClientFactory: WalletClientFactory,
    private val sdkFlags: SdkFlags,
    private val network: ZcashNetwork,
    private val walletTip: suspend () -> Long?,
    private val engine: SlipstreamEngine,
    private val planStore: SubmitPlanStore,
    private val saplingParamsDir: File,
    private val transactionReader: SlipstreamTransactionReader,
    /** Non-null when Tor is enabled; see [SaplingParams.ensureDownloaded]. */
    private val saplingParamsTorHttpClientProvider: (suspend () -> HttpClient)? = null,
    /**
     * Production: `{ SaplingParams.ensureDownloaded(saplingParamsDir, saplingParamsTorHttpClientProvider) }`.
     * Injected so tests never trigger a real download.
     */
    private val ensureSaplingParams: suspend () -> Unit =
        { SaplingParams.ensureDownloaded(saplingParamsDir, saplingParamsTorHttpClientProvider) }
) : Broadcaster {
    /**
     * Fetches the Sapling parameters first only when [proposal] needs Sapling proofs (see
     * [ensureSaplingParamsFor]): a gift card claim or any other Orchard-only send never downloads
     * them.
     */
    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        ovkPolicy: OvkPolicy,
        sizeBound: TransactionSizeBound
    ): List<CreatedTransaction> {
        ensureSaplingParamsFor(backend, proposal, ensureSaplingParams)
        val uskBytes = usk.copyBytes()
        val txIds =
            try {
                backend.createProposedTransactions(
                    proposal.toUnsafe(),
                    uskBytes,
                    discardOvk = ovkPolicy == OvkPolicy.Discard,
                    sizeBound = sizeBound.toBackendEncoding()
                )
            } finally {
                uskBytes.clearContents()
            }
        val isMigration = planStore.migrationMarks.isMigrationProposal(proposal)
        val created =
            txIds.map { txId -> storeAsAwaitingSubmission(FirstClassByteArray(txId), isMigration) }
        engine.notifyTxChange()
        return created
    }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): List<CreatedTransaction> {
        val txId = backend.extractAndStoreTxFromPczt(pcztWithProofs.toByteArray(), pcztWithSignatures.toByteArray())
        // A migration PCZT's transaction was marked by id when the PCZT was created (see MigrationTransactionMarks).
        val created = listOf(storeAsAwaitingSubmission(FirstClassByteArray(txId), isMigration = false))
        engine.notifyTxChange()
        return created
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult {
        val client = walletClientFactory.create(endpoint)
        // Until this submission answers, another endpoint's refusal cannot reject the transaction.
        planStore.recordSubmitStarted(transaction.txId, endpoint)
        try {
            val result =
                try {
                    client.submitTransaction(transaction.raw, transaction.txId, sdkFlags, network, walletTip)
                } catch (e: Throwable) {
                    // A submission that returned nothing, though its request may have been sent, does not accept the
                    // transaction and keeps it from being rejected.
                    withContext(NonCancellable) {
                        planStore.recordAnswer(
                            transaction.txId,
                            endpoint,
                            TransactionSubmitResult.NotAttempted(transaction.txId)
                        )
                    }
                    throw e
                }
            planStore.recordAnswer(transaction.txId, endpoint, result)
            engine.notifyTxChange()
            // The SDK's verdict, not this endpoint's raw answer.
            return planStore.withVerdict(transaction.txId, result)
        } finally {
            withContext(NonCancellable) { client.dispose() }
        }
    }

    private suspend fun storeAsAwaitingSubmission(
        txId: FirstClassByteArray,
        isMigration: Boolean
    ): CreatedTransaction {
        planStore.markAwaitingSubmission(txId)
        if (isMigration) {
            planStore.migrationMarks.markTransactions(listOf(txId))
            planStore.markMigration(txId)
        }
        return transactionReader.readCreatedTransaction(txId)
    }
}
