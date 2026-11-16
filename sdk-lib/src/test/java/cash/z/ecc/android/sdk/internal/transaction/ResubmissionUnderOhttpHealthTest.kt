package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.SubmitRejection
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.health.OhttpHealthCenter
import co.electriccoin.lightwallet.client.health.OhttpHealthOperationCompleted
import co.electriccoin.lightwallet.client.health.runOhttpOperation
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The submission path that resubmission runs through ([SubmitPlanExecutor] over
 * [CombinedWalletClient.submitTransaction]) marks its height read and its send as the OHTTP health
 * operation's decisive calls. These tests pin that the health bookkeeping and the rejection verdict
 * stay independent: each endpoint's answer is recorded through the same verdict logic whether or
 * not the resubmission runs inside an OHTTP health operation, and the operation still completes
 * with the business result. That a transaction stored as rejected is never resent is covered by
 * `CompactBlockProcessorTest` (SDK) and `ResubmissionTickerTest` (Slipstream).
 */
class ResubmissionUnderOhttpHealthTest {
    @Test
    fun a_terminal_refusal_on_resubmission_records_the_rejection_and_fails_the_operation() =
        runBlocking<Unit> {
            val store = PendingSubmitPlanStore()
            store.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpointA)))
            val clients = mapOf(endpointA to clientAnswering(CODE_REJECTED, TOO_LARGE_TEXT))

            val (result, completed) = resubmitInsideHealthOperation(store, clients)

            assertIs<TransactionSubmitResult.Failure>(result)
            assertEquals(SubmitRejection.TOO_LARGE, result.rejection)
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Rejected>(store.getSubmitPlan(transaction.txId))
            assertTrue(completed.failed)
        }

    @Test
    fun an_already_held_answer_on_resubmission_is_an_acceptance_and_never_a_rejection() =
        runBlocking<Unit> {
            val store = PendingSubmitPlanStore()
            store.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpointA, endpointB)))
            val clients =
                mapOf(
                    endpointA to clientAnswering(CODE_REJECTED, "txn-already-known"),
                    endpointB to clientAnswering(CODE_REJECTED, TOO_LARGE_TEXT)
                )

            val (result, completed) = resubmitInsideHealthOperation(store, clients)

            assertEquals(TransactionSubmitResult.Success(transaction.txId), result)
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpointA, endpointB))),
                store.getSubmitPlan(transaction.txId)
            )
            assertFalse(completed.failed)
        }

    @Test
    fun an_accepted_resubmission_is_unchanged_by_the_health_operation() =
        runBlocking<Unit> {
            val store = PendingSubmitPlanStore()
            store.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpointA)))
            val clients = mapOf(endpointA to clientAnswering(CODE_ACCEPTED, ""))

            val (result, completed) = resubmitInsideHealthOperation(store, clients)

            assertEquals(TransactionSubmitResult.Success(transaction.txId), result)
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertFalse(completed.failed)
        }

    @Test
    fun a_terminal_refusal_records_the_same_verdict_outside_a_health_operation() =
        runBlocking<Unit> {
            val store = PendingSubmitPlanStore()
            store.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpointA)))
            val clients = mapOf(endpointA to clientAnswering(CODE_REJECTED, TOO_LARGE_TEXT))

            val result = executor(store, clients).submit(transaction, TransactionSubmitPlan(listOf(endpointA)))

            assertIs<TransactionSubmitResult.Failure>(result)
            assertEquals(SubmitRejection.TOO_LARGE, result.rejection)
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Rejected>(store.getSubmitPlan(transaction.txId))
        }

    private suspend fun resubmitInsideHealthOperation(
        store: PendingSubmitPlanStore,
        clients: Map<LightWalletEndpoint, CombinedWalletClient>
    ): Pair<TransactionSubmitResult, OhttpHealthOperationCompleted> =
        coroutineScope {
            val center = OhttpHealthCenter()
            val completion: Deferred<OhttpHealthOperationCompleted> =
                async(start = CoroutineStart.UNDISPATCHED) { center.completions.first() }
            val plan =
                checkNotNull(store.getSubmitPlan(transaction.txId) as? PendingSubmitPlanStore.StoredSubmitPlan.Ready)
            val result =
                center.runOhttpOperation(isFailure = { it is TransactionSubmitResult.Failure }) {
                    executor(store, clients).submit(transaction, plan.submitPlan)
                }
            result to completion.await()
        }

    private fun executor(
        store: PendingSubmitPlanStore,
        clients: Map<LightWalletEndpoint, CombinedWalletClient>
    ) = SubmitPlanExecutor(
        transactionSubmitter =
            object : TransactionSubmitter {
                override suspend fun submit(
                    transaction: CreatedTransaction,
                    endpoint: LightWalletEndpoint
                ): TransactionSubmitResult =
                    checkNotNull(clients[endpoint]).submitTransaction(
                        transaction.raw,
                        transaction.txId,
                        noTor,
                        ZcashNetwork.Mainnet
                    ) { null }
            },
        pendingSubmitPlanStore = store
    )

    private fun clientAnswering(
        code: Int,
        message: String
    ): CombinedWalletClient =
        mock(CombinedWalletClient::class.java).also { client ->
            runBlocking {
                `when`(client.getLatestBlockHeight(ServiceMode.Direct))
                    .thenReturn(Response.Success(BlockHeightUnsafe(SERVER_HEIGHT)))
                `when`(client.submitTransaction(transaction.raw.byteArray, ServiceMode.Direct))
                    .thenReturn(Response.Success(SendResponseUnsafe(code = code, message = message)))
                `when`(client.fetchTransaction(transaction.txId.byteArray, ServiceMode.Direct))
                    .thenReturn(Response.Failure.Server.NotFound(RuntimeException("not found"), 5, "not found"))
            }
        }

    private companion object {
        const val SERVER_HEIGHT = 3_000_000L
        const val CODE_ACCEPTED = 0
        const val CODE_REJECTED = SubmitRejection.CODE_VERIFY_REJECTED
        const val TOO_LARGE_TEXT = "exceeding the configured mempool maximum"

        val noTor = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false)

        // Too short to carry a transaction prefix, so the local branch and expiry check never refuses it.
        val transaction =
            CreatedTransaction(
                txId = FirstClassByteArray(ByteArray(32) { it.toByte() }),
                raw = FirstClassByteArray(byteArrayOf(1, 2)),
                expiryHeight = null
            )

        val endpointA = LightWalletEndpoint("a.example", 443, true)
        val endpointB = LightWalletEndpoint("b.example", 443, true)
    }
}
