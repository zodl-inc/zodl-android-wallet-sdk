package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.TransactionSubmitResult.Failure.Companion.CODE_EXPIRED
import cash.z.ecc.android.sdk.model.TransactionSubmitResult.Failure.Companion.CODE_WRONG_CONSENSUS_BRANCH
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A local refusal ([isLocalRefusal]) sends nothing, so it is never an endpoint's answer: it cannot
 * complete a rejection with another endpoint's refusal for good.
 */
class PendingSubmitPlanStoreLocalRefusalTest {
    @Test
    fun a_local_refusal_beside_a_terminal_refusal_is_not_rejected_and_stays_pending() =
        runBlocking<Unit> {
            val store = PendingSubmitPlanStore()
            store.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(a, b)))

            // The SDK refused to send to a (wrong consensus branch), so a never saw it; b refuses it for good.
            store.recordSubmitOutcome(transaction.txId, a, localRefusal(CODE_WRONG_CONSENSUS_BRANCH))
            store.recordSubmitOutcome(transaction.txId, b, invalid)
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())

            // Expired by the next block: still a local refusal, still pending.
            store.recordSubmitOutcome(transaction.txId, a, localRefusal(CODE_EXPIRED))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())
        }

    @Test
    fun a_local_refusal_is_not_an_explicit_server_answer() {
        listOf(CODE_WRONG_CONSENSUS_BRANCH, CODE_EXPIRED)
            .forEach { code -> assertEquals(false, localRefusal(code).isExplicitServerAnswer()) }
        assertEquals(true, invalid.isExplicitServerAnswer())
    }

    private companion object {
        val transaction =
            CreatedTransaction(
                txId = FirstClassByteArray(byteArrayOf(1)),
                raw = FirstClassByteArray(byteArrayOf(1, 1)),
                expiryHeight = null
            )
        val a = LightWalletEndpoint("a.z.cash", 443, true)
        val b = LightWalletEndpoint("b.z.cash", 443, true)
        val invalid = TransactionSubmitResult.Failure(transaction.txId, false, -26, "bad-txns-no-source-of-funds")

        fun localRefusal(code: Int) = TransactionSubmitResult.Failure(transaction.txId, false, code, "refused locally")
    }
}
