package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.ext.fromHexReversed
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SubmitRejection
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class PendingSubmitPlanStoreTest {
    @Test
    fun created_transactions_wait_for_submit_plan() =
        runBlocking {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()

            store.createAndMarkAwaitingSubmitPlan {
                listOf(transaction)
            }

            assertEquals(PendingSubmitPlanStore.StoredSubmitPlan.AwaitingPlan, store.getSubmitPlan(transaction.txId))
            assertNull(store.getSubmitPlan(createdTransaction(2).txId))
        }

    @Test
    fun persists_submit_plans() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val endpoint = endpoint("submit.z.cash")
            val firstStore = PendingSubmitPlanStore(preferenceProvider)

            firstStore.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpoint)))

            val secondStore = PendingSubmitPlanStore(preferenceProvider)
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                secondStore.getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun ignores_submit_plans_with_unsupported_version() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            preferenceProvider.putString(
                EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key,
                "version=2\n${transaction.txId.byteArray.toHexReversed()}=submit.z.cash,443,true"
            )

            val store = PendingSubmitPlanStore(preferenceProvider)

            assertNull(store.getSubmitPlan(transaction.txId))
        }

    @Test
    fun submitted_endpoints_are_added_to_existing_plan() =
        runBlocking {
            val transaction = createdTransaction(1)
            val firstEndpoint = endpoint("a.z.cash")
            val secondEndpoint = endpoint("b.z.cash")
            val store = PendingSubmitPlanStore()

            store.createAndMarkAwaitingSubmitPlan {
                listOf(transaction)
            }
            store.addSubmitEndpoint(transaction, firstEndpoint)
            store.addSubmitEndpoint(transaction, secondEndpoint)

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(
                    TransactionSubmitPlan(listOf(firstEndpoint, secondEndpoint))
                ),
                store.getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun prunes_plans_that_are_no_longer_resubmission_candidates() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val retainedTransaction = createdTransaction(1)
            val prunedTransaction = createdTransaction(2)
            val firstStore = PendingSubmitPlanStore(preferenceProvider)

            firstStore.storeSubmitPlan(retainedTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))
            firstStore.storeSubmitPlan(prunedTransaction, TransactionSubmitPlan(listOf(endpoint("b.z.cash"))))
            firstStore.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { listOf(retainedTransaction.txId) },
                transactionId = { it }
            )

            val secondStore = PendingSubmitPlanStore(preferenceProvider)
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("a.z.cash")))),
                secondStore.getSubmitPlan(retainedTransaction.txId)
            )
            assertNull(secondStore.getSubmitPlan(prunedTransaction.txId))
        }

    @Test
    fun default_retain_missing_prunes_missing_plan() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val prunedTransaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider)
            store.storeSubmitPlan(prunedTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))

            store.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it }
            )

            assertNull(store.getSubmitPlan(prunedTransaction.txId))
        }

    @Test
    fun retain_missing_returning_true_retains_plan_for_missing_transaction() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val retainedTransaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider)
            store.storeSubmitPlan(retainedTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))

            store.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it },
                retainMissing = { true }
            )

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("a.z.cash")))),
                store.getSubmitPlan(retainedTransaction.txId)
            )
        }

    @Test
    fun retain_missing_callback_receives_original_txid_bytes_after_rehydration() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val txId = FirstClassByteArray(byteArrayOf(0x01, 0x02, 0x03, 0xAB.toByte(), 0x00))
            val transaction =
                CreatedTransaction(
                    txId = txId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = null
                )
            val firstStore = PendingSubmitPlanStore(preferenceProvider)
            firstStore.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))

            val secondStore = PendingSubmitPlanStore(preferenceProvider)
            var receivedTxId: FirstClassByteArray? = null
            secondStore.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it },
                retainMissing = { candidateTxId ->
                    receivedTxId = candidateTxId
                    true
                }
            )

            assertContentEquals(txId.byteArray, receivedTxId?.byteArray)
        }

    @Test
    fun namespaced_store_only_consults_retain_missing_for_its_own_keys() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val ownTransaction = createdTransaction(1)
            val otherNamespaceTransaction = createdTransaction(2)
            val ownStore = PendingSubmitPlanStore(preferenceProvider, namespace = "own")
            val otherStore = PendingSubmitPlanStore(preferenceProvider, namespace = "other")

            ownStore.storeSubmitPlan(ownTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))
            otherStore.storeSubmitPlan(otherNamespaceTransaction, TransactionSubmitPlan(listOf(endpoint("b.z.cash"))))

            val consultedTxIds = mutableListOf<FirstClassByteArray>()
            val reloadedOwnStore = PendingSubmitPlanStore(preferenceProvider, namespace = "own")
            reloadedOwnStore.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it },
                retainMissing = { txId ->
                    consultedTxIds += txId
                    true
                }
            )

            assertEquals(listOf(ownTransaction.txId), consultedTxIds)

            val otherStoreReloaded = PendingSubmitPlanStore(preferenceProvider, namespace = "other")
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("b.z.cash")))),
                otherStoreReloaded.getSubmitPlan(otherNamespaceTransaction.txId)
            )
        }

    /**
     * Both stores load the (empty) shared preference before either writes to it, as two synchronizers running side
     * by side do.
     */
    @Test
    fun concurrently_loaded_namespaced_stores_keep_each_others_plans() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val mainTransaction = createdTransaction(1)
            val giftTransaction = createdTransaction(2)
            val mainStore = PendingSubmitPlanStore(preferenceProvider, namespace = "1_zcashdefault")
            val giftStore = PendingSubmitPlanStore(preferenceProvider, namespace = "1_giftcard_ab")

            assertNull(mainStore.getSubmitPlan(mainTransaction.txId))
            assertNull(giftStore.getSubmitPlan(giftTransaction.txId))

            mainStore.storeSubmitPlan(mainTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))
            giftStore.storeSubmitPlan(giftTransaction, TransactionSubmitPlan(listOf(endpoint("b.z.cash"))))
            giftStore.addSubmitEndpoint(giftTransaction, endpoint("c.z.cash"))

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("a.z.cash")))),
                PendingSubmitPlanStore(preferenceProvider, namespace = "1_zcashdefault")
                    .getSubmitPlan(mainTransaction.txId)
            )
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(
                    TransactionSubmitPlan(listOf(endpoint("b.z.cash"), endpoint("c.z.cash")))
                ),
                PendingSubmitPlanStore(preferenceProvider, namespace = "1_giftcard_ab")
                    .getSubmitPlan(giftTransaction.txId)
            )
        }

    /** A namespace that merely starts with the erased one is a different namespace. */
    @Test
    fun erase_namespace_removes_only_that_namespace() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val mainTransaction = createdTransaction(1)
            val giftTransaction = createdTransaction(2)
            PendingSubmitPlanStore(preferenceProvider, namespace = "1_zcashdefault")
                .storeSubmitPlan(mainTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))
            PendingSubmitPlanStore(preferenceProvider, namespace = "1_gift")
                .storeSubmitPlan(giftTransaction, TransactionSubmitPlan(listOf(endpoint("b.z.cash"))))
            PendingSubmitPlanStore(preferenceProvider, namespace = "1_gift2")
                .storeSubmitPlan(giftTransaction, TransactionSubmitPlan(listOf(endpoint("c.z.cash"))))

            PendingSubmitPlanStore.eraseNamespace(preferenceProvider, "1_gift")

            assertNull(
                PendingSubmitPlanStore(preferenceProvider, namespace = "1_gift").getSubmitPlan(giftTransaction.txId)
            )
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("a.z.cash")))),
                PendingSubmitPlanStore(preferenceProvider, namespace = "1_zcashdefault")
                    .getSubmitPlan(mainTransaction.txId)
            )
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("c.z.cash")))),
                PendingSubmitPlanStore(preferenceProvider, namespace = "1_gift2").getSubmitPlan(giftTransaction.txId)
            )
        }

    @Test
    fun to_hex_reversed_and_from_hex_reversed_round_trip() {
        val bytes = Random(42).nextBytes(37)

        assertContentEquals(bytes, bytes.toHexReversed().fromHexReversed())
    }

    @Test
    fun a_transaction_its_only_endpoint_refuses_for_good_is_rejected() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()

            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Rejected(-25, TOO_LARGE_TEXT),
                store.getSubmitPlan(transaction.txId)
            )
            assertEquals(setOf(TransactionId.new(transaction.txId)), store.rejectedTransactionIds.first())
        }

    @Test
    fun one_explicit_refusal_stays_pending_while_a_planned_endpoint_has_not_answered() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            store.storeSubmitPlan(
                transaction,
                TransactionSubmitPlan(listOf(endpoint("a.z.cash"), endpoint("b.z.cash")))
            )

            store.recordSubmitOutcome(transaction.txId, endpoint("a.z.cash"), tooLarge(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())

            store.recordSubmitOutcome(transaction.txId, endpoint("b.z.cash"), tooLarge(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Rejected>(store.getSubmitPlan(transaction.txId))
        }

    @Test
    fun a_refusal_while_another_submission_is_in_flight_stays_pending_and_a_late_acceptance_wins() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider)
            store.createAndMarkAwaitingSubmitPlan { listOf(transaction) }
            store.recordSubmitStarted(transaction.txId, endpoint("a.z.cash"))
            store.recordSubmitStarted(transaction.txId, endpoint("b.z.cash"))

            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(
                PendingSubmitPlanStore(preferenceProvider).getSubmitPlan(transaction.txId)
            )
            assertEquals(emptySet(), store.rejectedTransactionIds.first())

            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"), accepted(transaction))

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(
                    TransactionSubmitPlan(listOf(endpoint("a.z.cash"), endpoint("b.z.cash")))
                ),
                store.getSubmitPlan(transaction.txId)
            )
            assertEquals(emptyMap(), store.rejectedTransactions.first())
        }

    @Test
    fun a_refusal_then_a_late_transient_answer_stays_pending() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider)
            store.recordSubmitStarted(transaction.txId, endpoint("a.z.cash"))
            store.recordSubmitStarted(transaction.txId, endpoint("b.z.cash"))
            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))

            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"), mempoolFull(transaction))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(
                PendingSubmitPlanStore(preferenceProvider).getSubmitPlan(transaction.txId)
            )
            assertEquals(emptyMap(), store.rejectedTransactions.first())
        }

    @Test
    fun a_transient_answer_then_a_refusal_stays_pending_and_a_later_acceptance_still_wins() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            store.recordSubmitStarted(transaction.txId, endpoint("a.z.cash"))
            store.recordSubmitStarted(transaction.txId, endpoint("b.z.cash"))
            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), mempoolFull(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))

            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"), tooLarge(transaction))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())

            store.addSubmitEndpoint(
                transaction,
                endpoint("a.z.cash"),
                TransactionSubmitResult.Success(transaction.txId)
            )

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())
        }

    @Test
    fun two_endpoints_that_both_refuse_for_good_reject_the_transaction() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            store.recordSubmitStarted(transaction.txId, endpoint("a.z.cash"))
            store.recordSubmitStarted(transaction.txId, endpoint("b.z.cash"))
            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))

            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"), tooLarge(transaction))

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Rejected(-25, TOO_LARGE_TEXT),
                store.getSubmitPlan(transaction.txId)
            )
            assertEquals(
                mapOf(TransactionId.new(transaction.txId) to SubmitRejection.TOO_LARGE),
                store.rejectedTransactions.first()
            )
        }

    @Test
    fun only_transient_answers_stay_pending() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()

            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), mempoolFull(transaction))
            store.addSubmitEndpoint(
                transaction,
                endpoint("b.z.cash"),
                TransactionSubmitResult.Failure(transaction.txId, true, 14, "unavailable")
            )

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())
        }

    @Test
    fun a_migration_with_a_refusal_and_an_unanswered_endpoint_is_never_rejected() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            store.createAndMarkAwaitingSubmitPlan(isMigration = true) { listOf(transaction) }
            store.recordSubmitStarted(transaction.txId, endpoint("a.z.cash"))
            store.recordSubmitStarted(transaction.txId, endpoint("b.z.cash"))

            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))
            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))

            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"), tooLarge(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptySet(), store.rejectedTransactionIds.first())
        }

    @Test
    fun one_rejection_and_one_acceptance_is_not_rejected() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()

            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))
            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"), accepted(transaction))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptySet(), store.rejectedTransactionIds.first())
        }

    @Test
    fun an_endpoint_that_already_holds_the_transaction_and_a_refusal_elsewhere_is_never_rejected() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider)
            store.storeSubmitPlan(
                transaction,
                TransactionSubmitPlan(listOf(endpoint("a.z.cash"), endpoint("b.z.cash")))
            )

            store.recordSubmitOutcome(transaction.txId, endpoint("a.z.cash"), alreadyHeld(transaction))
            store.recordSubmitOutcome(transaction.txId, endpoint("b.z.cash"), invalid(transaction))

            // Every endpoint answered explicitly, but a holds the transaction: it is accepted, not rejected,
            // and stays resubmittable.
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())
            assertEquals(
                TransactionSubmitResult.Success(transaction.txId),
                store.withVerdict(transaction.txId, invalid(transaction))
            )
            // The acceptance is persisted: a refusal after a restart does not reject it either.
            val restarted = PendingSubmitPlanStore(preferenceProvider)
            restarted.recordSubmitOutcome(transaction.txId, endpoint("b.z.cash"), invalid(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(restarted.getSubmitPlan(transaction.txId))
        }

    @Test
    fun a_migration_mark_is_erased_with_its_plan_and_an_unplanned_one_once_old() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            var now = 1_000_000L
            val marks =
                MigrationTransactionMarks(preferenceProvider, "1_main", { ByteArray(0) }, nowEpochSeconds = { now })
            val store = PendingSubmitPlanStore(preferenceProvider, "1_main", marks)
            val planned = createdTransaction(1)
            val abandoned = FirstClassByteArray(byteArrayOf(2))
            store.createAndMarkAwaitingSubmitPlan(isMigration = true) { listOf(planned) }
            marks.markTransactions(listOf(abandoned))

            // Still a candidate and young: both kept.
            store.loadTransactionsAndRetainSubmitPlans({ listOf(planned) }, { it.txId })
            assertTrue(marks.isMigrationTransaction(planned.txId))
            assertTrue(marks.isMigrationTransaction(abandoned))

            // Old: the unplanned mark goes, the planned one stays while its plan does.
            now += MigrationTransactionMarks.MAX_MARK_AGE_SECONDS + 1
            store.loadTransactionsAndRetainSubmitPlans({ listOf(planned) }, { it.txId })
            assertTrue(marks.isMigrationTransaction(planned.txId))
            assertFalse(marks.isMigrationTransaction(abandoned))

            // Mined or expired: its plan is pruned, and its mark with it.
            store.loadTransactionsAndRetainSubmitPlans({ emptyList<CreatedTransaction>() }, { it.txId })
            assertFalse(marks.isMigrationTransaction(planned.txId))
        }

    @Test
    fun a_later_acceptance_clears_a_rejected_verdict() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Rejected>(store.getSubmitPlan(transaction.txId))

            store.recordSubmitOutcome(transaction.txId, endpoint("a.z.cash"), accepted(transaction))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptySet(), store.rejectedTransactionIds.first())
        }

    @Test
    fun one_explicit_rejection_and_one_unanswered_submission_stays_pending() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            store.recordSubmitStarted(transaction.txId, endpoint("a.z.cash"))
            store.recordSubmitStarted(transaction.txId, endpoint("b.z.cash"))
            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))

            // The other endpoint's submission threw, was cancelled or timed out, possibly after its
            // request was sent: it may still accept the transaction.
            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptySet(), store.rejectedTransactionIds.first())
        }

    @Test
    fun no_explicit_rejection_is_never_rejected() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()

            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"))
            store.addSubmitEndpoint(
                transaction,
                endpoint("b.z.cash"),
                TransactionSubmitResult.Failure(transaction.txId, true, 14, "unavailable")
            )

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
        }

    @Test
    fun a_transport_failure_beside_an_explicit_refusal_stays_pending_until_it_answers_explicitly() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            store.recordSubmitStarted(transaction.txId, endpoint("a.z.cash"))
            store.recordSubmitStarted(transaction.txId, endpoint("b.z.cash"))

            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))
            // b may have accepted the transaction before its response was lost.
            store.addSubmitEndpoint(transaction, endpoint("b.z.cash"), transportFailure(transaction))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())

            // A resubmission reaches b, which now refuses it explicitly: every endpoint answered.
            store.recordSubmitOutcome(transaction.txId, endpoint("b.z.cash"), invalid(transaction))
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Rejected(-26, INVALID_TEXT),
                store.getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun an_acceptance_whose_response_is_lost_and_a_refusal_elsewhere_is_never_rejected() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            store.storeSubmitPlan(
                transaction,
                TransactionSubmitPlan(listOf(endpoint("a.z.cash"), endpoint("b.z.cash")))
            )

            // a accepted, but its response was lost; b refuses it explicitly.
            store.recordSubmitOutcome(transaction.txId, endpoint("a.z.cash"), transportFailure(transaction))
            store.recordSubmitOutcome(transaction.txId, endpoint("b.z.cash"), invalid(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))

            // The resubmission reconciles it: a answers that it has the transaction.
            store.recordSubmitOutcome(transaction.txId, endpoint("a.z.cash"), accepted(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), store.rejectedTransactions.first())
        }

    @Test
    fun an_acceptance_survives_a_restart_and_refusals_after_it_never_reject() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            PendingSubmitPlanStore(preferenceProvider).apply {
                storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"), endpoint("b.z.cash"))))
                recordSubmitOutcome(transaction.txId, endpoint("a.z.cash"), accepted(transaction))
            }

            // After a restart a answers transiently (it already has the transaction) and b refuses it.
            val restarted = PendingSubmitPlanStore(preferenceProvider)
            restarted.recordSubmitOutcome(transaction.txId, endpoint("a.z.cash"), mempoolFull(transaction))
            restarted.recordSubmitOutcome(transaction.txId, endpoint("b.z.cash"), invalid(transaction))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(restarted.getSubmitPlan(transaction.txId))
            assertEquals(emptyMap(), restarted.rejectedTransactions.first())
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(
                PendingSubmitPlanStore(preferenceProvider).getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun acceptances_are_pruned_with_their_plan_and_erased_with_their_namespace() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider, "1_main")
            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), accepted(transaction))
            assertTrue(
                preferenceProvider
                    .getString(EncryptedPreferenceKeys.PENDING_SUBMIT_ACCEPTANCES.key)
                    .orEmpty()
                    .contains("1_main:")
            )

            store.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it }
            )
            assertFalse(
                preferenceProvider
                    .getString(EncryptedPreferenceKeys.PENDING_SUBMIT_ACCEPTANCES.key)
                    .orEmpty()
                    .contains("1_main:")
            )

            PendingSubmitPlanStore(preferenceProvider, "1_main")
                .addSubmitEndpoint(transaction, endpoint("a.z.cash"), accepted(transaction))
            PendingSubmitPlanStore.eraseNamespace(preferenceProvider, "1_main")
            assertFalse(
                preferenceProvider
                    .getString(EncryptedPreferenceKeys.PENDING_SUBMIT_ACCEPTANCES.key)
                    .orEmpty()
                    .contains("1_main:")
            )
        }

    @Test
    fun the_executor_keeps_a_transaction_pending_when_an_endpoint_fails_in_transport() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            val plan = TransactionSubmitPlan(listOf(endpoint("a.z.cash"), endpoint("b.z.cash")))
            store.storeSubmitPlan(transaction, plan)
            val submitter =
                object : TransactionSubmitter {
                    override suspend fun submit(
                        transaction: CreatedTransaction,
                        endpoint: LightWalletEndpoint
                    ) = if (endpoint.host == "a.z.cash") transportFailure(transaction) else invalid(transaction)
                }

            SubmitPlanExecutor(submitter, store).submit(transaction, plan)

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))
        }

    @Test
    fun a_rejection_survives_a_restart_and_a_new_endpoint_refusing_too_keeps_it() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            PendingSubmitPlanStore(preferenceProvider)
                .addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))

            val restarted = PendingSubmitPlanStore(preferenceProvider)
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Rejected(-25, TOO_LARGE_TEXT),
                restarted.getSubmitPlan(transaction.txId)
            )
            assertEquals(setOf(TransactionId.new(transaction.txId)), restarted.rejectedTransactionIds.first())

            restarted.addSubmitEndpoint(transaction, endpoint("b.z.cash"), tooLarge(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Rejected>(restarted.getSubmitPlan(transaction.txId))

            restarted.addSubmitEndpoint(transaction, endpoint("c.z.cash"), accepted(transaction))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(restarted.getSubmitPlan(transaction.txId))
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(
                PendingSubmitPlanStore(preferenceProvider).getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun a_rejection_is_pruned_with_its_plan() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider)
            store.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))

            store.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it }
            )

            assertNull(store.getSubmitPlan(transaction.txId))
            assertEquals(emptySet(), store.rejectedTransactionIds.first())
            assertNull(PendingSubmitPlanStore(preferenceProvider).getSubmitPlan(transaction.txId))
        }

    @Test
    fun rejections_are_kept_per_namespace_and_erased_with_it() =
        runBlocking<Unit> {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val mainnet = PendingSubmitPlanStore(preferenceProvider, "1_main")
            val testnet = PendingSubmitPlanStore(preferenceProvider, "0_main")
            mainnet.addSubmitEndpoint(transaction, endpoint("a.z.cash"), tooLarge(transaction))
            testnet.addSubmitEndpoint(transaction, endpoint("a.z.cash"), accepted(transaction))

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Rejected>(
                PendingSubmitPlanStore(preferenceProvider, "1_main").getSubmitPlan(transaction.txId)
            )
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(
                PendingSubmitPlanStore(preferenceProvider, "0_main").getSubmitPlan(transaction.txId)
            )

            PendingSubmitPlanStore.eraseNamespace(preferenceProvider, "1_main")
            assertNull(PendingSubmitPlanStore(preferenceProvider, "1_main").getSubmitPlan(transaction.txId))
        }

    @Test
    fun the_executor_records_every_endpoint_it_tries() =
        runBlocking<Unit> {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()
            val plan = TransactionSubmitPlan(listOf(endpoint("a.z.cash"), endpoint("b.z.cash")))
            store.storeSubmitPlan(transaction, plan)
            val submitter =
                object : TransactionSubmitter {
                    override suspend fun submit(
                        transaction: CreatedTransaction,
                        endpoint: LightWalletEndpoint
                    ) = tooLarge(transaction)
                }

            SubmitPlanExecutor(submitter, store).submit(transaction, plan)

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Rejected>(store.getSubmitPlan(transaction.txId))
        }

    @Test
    fun rejections_round_trip_through_their_codec() {
        val rejections =
            mapOf(
                "ns:01" to StoredRejection(-25, "a=b, c|d\nnew line"),
                "ns:02" to StoredRejection(-26, "")
            )

        val encoded = PendingSubmitPlanCodec.encodeRejections(rejections)
        assertEquals(rejections, PendingSubmitPlanCodec.decodeRejections(encoded))
        assertEquals(emptyMap(), PendingSubmitPlanCodec.decodeRejections("version=2\nns:01=-25,x"))
        assertEquals(emptyMap(), PendingSubmitPlanCodec.decodeRejections("version=1\nns:01=notacode,x\nns:02=-25"))
    }

    private class FakePreferenceProvider : PreferenceProvider {
        private val values = mutableMapOf<String, String?>()

        override suspend fun hasKey(key: PreferenceKey): Boolean = values.containsKey(key.key)

        override suspend fun putString(
            key: PreferenceKey,
            value: String?
        ) {
            values[key.key] = value
        }

        override suspend fun getString(key: PreferenceKey): String? = values[key.key]

        override fun observe(key: PreferenceKey): Flow<Unit> = emptyFlow()

        override suspend fun clearPreferences(): Boolean {
            values.clear()
            return true
        }
    }

    companion object {
        private fun createdTransaction(index: Int) =
            CreatedTransaction(
                txId = FirstClassByteArray(byteArrayOf(index.toByte())),
                raw = FirstClassByteArray(byteArrayOf(index.toByte(), index.toByte())),
                expiryHeight = null
            )

        private fun endpoint(host: String) = LightWalletEndpoint(host, 443, true)

        private const val TOO_LARGE_TEXT = "exceeding the configured mempool maximum"

        private fun tooLarge(transaction: CreatedTransaction) =
            TransactionSubmitResult.Failure(transaction.txId, false, -25, TOO_LARGE_TEXT)

        private fun accepted(transaction: CreatedTransaction) = TransactionSubmitResult.Success(transaction.txId)

        private fun mempoolFull(transaction: CreatedTransaction) =
            TransactionSubmitResult.Failure(transaction.txId, false, -26, "mempool full")

        private fun alreadyHeld(transaction: CreatedTransaction) =
            TransactionSubmitResult.Failure(transaction.txId, false, -26, "transaction already exists in mempool")

        private const val INVALID_TEXT = "bad-txns-no-source-of-funds"

        private fun invalid(transaction: CreatedTransaction) =
            TransactionSubmitResult.Failure(transaction.txId, false, -26, INVALID_TEXT)

        private fun transportFailure(transaction: CreatedTransaction) =
            TransactionSubmitResult.Failure(transaction.txId, true, 4, "DEADLINE_EXCEEDED")
    }
}
