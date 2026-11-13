package com.zodl.slipstream.internal.spend

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.internal.transaction.MigrationTransactionMarks
import cash.z.ecc.android.sdk.internal.transaction.StoredRejection
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SubmitRejection
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import android.content.SharedPreferences.OnSharedPreferenceChangeListener as OnChangeListener

class SubmitPlanStoreRejectionTest {
    private val txId = FirstClassByteArray(byteArrayOf(1, 2, 3))
    private val a = LightWalletEndpoint("a.z.cash", 443, true)
    private val b = LightWalletEndpoint("b.z.cash", 443, true)
    private val tooLarge = TransactionSubmitResult.Failure(txId, false, -25, "exceeding the configured mempool maximum")
    private val mempoolFull = TransactionSubmitResult.Failure(txId, false, -26, "mempool full")
    private val invalid = TransactionSubmitResult.Failure(txId, false, -26, "bad-txns-no-source-of-funds")
    private val transportFailure = TransactionSubmitResult.Failure(txId, true, 4, "DEADLINE_EXCEEDED")

    @Test
    fun a_transaction_its_only_endpoint_refuses_for_good_is_rejected_and_survives_a_restart() {
        val preferences = InMemoryPreferences()
        val store = SubmitPlanStore(preferences)

        store.recordSubmitOutcome(txId, a, tooLarge)

        assertEquals(StoredRejection(-25, "exceeding the configured mempool maximum"), store.rejectionFor(txId))
        assertEquals(setOf(TransactionId.new(txId)), store.rejectedTransactionIds.value)
        assertEquals(setOf(TransactionId.new(txId)), SubmitPlanStore(preferences).rejectedTransactionIds.value)
        assertEquals(listOf(a), store.endpointsFor(txId))
    }

    @Test
    fun an_acceptance_by_another_endpoint_clears_the_rejection() {
        val store = SubmitPlanStore(InMemoryPreferences())

        store.recordSubmitOutcome(txId, a, tooLarge)
        store.recordSubmitOutcome(txId, b, TransactionSubmitResult.Success(txId))

        assertNull(store.rejectionFor(txId))
        assertEquals(emptySet(), store.rejectedTransactionIds.value)
    }

    @Test
    fun an_endpoint_that_already_holds_the_transaction_and_a_refusal_elsewhere_is_never_rejected() {
        val preferences = InMemoryPreferences()
        val store = SubmitPlanStore(preferences)
        val alreadyHeld = TransactionSubmitResult.Failure(txId, false, -26, "transaction already exists in mempool")

        store.recordSubmitOutcome(txId, a, alreadyHeld)
        store.recordSubmitOutcome(txId, b, invalid)

        assertNull(store.rejectionFor(txId))
        assertEquals(emptySet(), store.rejectedTransactionIds.value)
        assertEquals(TransactionSubmitResult.Success(txId), store.withVerdict(txId, invalid))
        // The acceptance is persisted: a refusal after a restart does not reject it either.
        val restarted = SubmitPlanStore(preferences)
        restarted.recordSubmitOutcome(txId, b, invalid)
        assertNull(restarted.rejectionFor(txId))
    }

    @Test
    fun one_explicit_rejection_and_one_unanswered_submission_stays_pending() {
        val store = SubmitPlanStore(InMemoryPreferences())
        store.recordSubmitStarted(txId, a)
        store.recordSubmitStarted(txId, b)
        store.recordSubmitOutcome(txId, a, tooLarge)
        assertNull(store.rejectionFor(txId))

        // b's submission threw or was cancelled, possibly after its request was sent.
        store.recordSubmitOutcome(txId, b, TransactionSubmitResult.NotAttempted(txId))

        assertNull(store.rejectionFor(txId))
        assertEquals(emptyMap(), store.rejectedTransactions.value)
    }

    @Test
    fun a_refusal_then_a_late_acceptance_is_not_rejected() {
        val store = SubmitPlanStore(InMemoryPreferences())
        store.recordSubmitStarted(txId, a)
        store.recordSubmitStarted(txId, b)
        store.recordSubmitOutcome(txId, a, tooLarge)
        assertNull(store.rejectionFor(txId))

        store.recordSubmitOutcome(txId, b, TransactionSubmitResult.Success(txId))

        assertNull(store.rejectionFor(txId))
        assertEquals(listOf(a, b), store.endpointsFor(txId))
    }

    @Test
    fun a_refusal_and_a_transient_failure_in_either_order_stays_pending() {
        listOf(listOf(a to tooLarge, b to mempoolFull), listOf(b to mempoolFull, a to tooLarge)).forEach { answers ->
            val preferences = InMemoryPreferences()
            val store = SubmitPlanStore(preferences)
            store.recordSubmitStarted(txId, a)
            store.recordSubmitStarted(txId, b)

            store.recordSubmitOutcome(txId, answers[0].first, answers[0].second)
            assertNull(store.rejectionFor(txId))
            store.recordSubmitOutcome(txId, answers[1].first, answers[1].second)

            assertNull(store.rejectionFor(txId))
            assertEquals(emptyMap(), store.rejectedTransactions.value)
            assertEquals(emptyMap(), SubmitPlanStore(preferences).rejectedTransactions.value)
        }
    }

    @Test
    fun two_endpoints_that_both_refuse_for_good_reject_the_transaction() {
        val preferences = InMemoryPreferences()
        val store = SubmitPlanStore(preferences)
        store.recordSubmitStarted(txId, a)
        store.recordSubmitStarted(txId, b)

        store.recordSubmitOutcome(txId, a, tooLarge)
        store.recordSubmitOutcome(txId, b, tooLarge)

        assertEquals(StoredRejection(-25, "exceeding the configured mempool maximum"), store.rejectionFor(txId))
        assertEquals(mapOf(TransactionId.new(txId) to SubmitRejection.TOO_LARGE), store.rejectedTransactions.value)
        assertEquals(
            mapOf(TransactionId.new(txId) to SubmitRejection.TOO_LARGE),
            SubmitPlanStore(preferences).rejectedTransactions.value
        )
    }

    @Test
    fun a_transport_failure_beside_an_explicit_refusal_stays_pending_until_it_answers_explicitly() {
        val store = SubmitPlanStore(InMemoryPreferences())

        // a may have accepted the transaction before its response was lost.
        store.recordSubmitOutcome(txId, a, transportFailure)
        store.recordSubmitOutcome(txId, b, invalid)
        assertNull(store.rejectionFor(txId))
        assertEquals(emptyMap(), store.rejectedTransactions.value)

        store.recordSubmitOutcome(txId, a, tooLarge)
        assertEquals(StoredRejection(-25, "exceeding the configured mempool maximum"), store.rejectionFor(txId))
    }

    @Test
    fun a_transport_failure_reconciled_by_an_acceptance_is_never_rejected() {
        val store = SubmitPlanStore(InMemoryPreferences())

        store.recordSubmitOutcome(txId, a, transportFailure)
        store.recordSubmitOutcome(txId, b, invalid)
        store.recordSubmitOutcome(txId, a, TransactionSubmitResult.Success(txId))

        assertNull(store.rejectionFor(txId))
        assertEquals(emptySet(), store.rejectedTransactionIds.value)
    }

    @Test
    fun an_acceptance_survives_a_restart_and_refusals_after_it_never_reject() {
        val preferences = InMemoryPreferences()
        SubmitPlanStore(preferences).recordSubmitOutcome(txId, a, TransactionSubmitResult.Success(txId))

        val restarted = SubmitPlanStore(preferences)
        restarted.recordSubmitOutcome(txId, a, mempoolFull)
        restarted.recordSubmitOutcome(txId, b, invalid)

        assertNull(restarted.rejectionFor(txId))
        assertEquals(emptySet(), restarted.rejectedTransactionIds.value)
        restarted.retainRejections(emptySet())
        assertEquals(
            emptySet(),
            preferences.all.keys
                .filter { it.startsWith("accepted:") }
                .toSet()
        )
    }

    @Test
    fun results_carry_the_stored_verdict() {
        val store = SubmitPlanStore(InMemoryPreferences())
        store.recordSubmitStarted(txId, b)
        store.recordSubmitOutcome(txId, a, tooLarge)
        assertNull((store.withVerdict(txId, tooLarge) as TransactionSubmitResult.Failure).rejection)

        store.recordSubmitOutcome(txId, b, invalid)
        assertEquals(
            SubmitRejection.INVALID,
            (store.withVerdict(txId, invalid) as TransactionSubmitResult.Failure).rejection
        )

        store.recordSubmitOutcome(txId, b, TransactionSubmitResult.Success(txId))
        assertEquals(TransactionSubmitResult.Success(txId), store.withVerdict(txId, tooLarge))
    }

    @Test
    fun a_resubmission_asks_every_endpoint_of_the_plan_so_a_restart_can_still_complete_the_verdict() =
        runBlocking<Unit> {
            val preferences = InMemoryPreferences()
            SubmitPlanStore(preferences).apply {
                recordSubmitOutcome(txId, a, transportFailure)
                recordSubmitOutcome(txId, b, transportFailure)
            }

            val restarted = SubmitPlanStore(preferences)
            val asked = mutableListOf<LightWalletEndpoint>()
            resubmitToPlanEndpoints(restarted, txId, a) { endpoint ->
                asked += endpoint
                if (endpoint == a) tooLarge else invalid
            }

            assertEquals(listOf(a, b), asked)
            assertEquals(StoredRejection(-26, "bad-txns-no-source-of-funds"), restarted.rejectionFor(txId))
        }

    @Test
    fun a_resubmission_stops_at_an_acceptance_and_survives_a_throwing_endpoint() =
        runBlocking<Unit> {
            val store = SubmitPlanStore(InMemoryPreferences())
            store.recordSubmitEndpoint(txId, a)
            store.recordSubmitEndpoint(txId, b)
            val asked = mutableListOf<LightWalletEndpoint>()

            resubmitToPlanEndpoints(store, txId, a) { endpoint ->
                asked += endpoint
                if (endpoint == a) throw java.io.IOException("reset") else TransactionSubmitResult.Success(txId)
            }

            assertEquals(listOf(a, b), asked)
            assertNull(store.rejectionFor(txId))
        }

    @Test
    fun a_scan_never_forgets_the_marks_of_a_transaction_created_while_it_ran() {
        val preferences = InMemoryPreferences()
        val store = SubmitPlanStore(preferences)
        val older = FirstClassByteArray(byteArrayOf(9))
        store.recordSubmitOutcome(older, a, tooLarge.copy(txId = older))

        store.beginRetentionScan()
        // Created and refused after the candidates were read: not a candidate yet.
        store.markMigration(txId)
        store.recordSubmitOutcome(txId, a, tooLarge)
        store.retainRejections(emptySet())

        assertNull(store.rejectionFor(older))
        assertEquals(
            setOf("migration:010203"),
            preferences.all.keys
                .filter { it.startsWith("migration:") }
                .toSet()
        )
    }

    @Test
    fun a_local_refusal_beside_a_terminal_refusal_is_not_rejected_and_stays_pending() {
        val store = SubmitPlanStore(InMemoryPreferences())
        val wrongBranch =
            TransactionSubmitResult.Failure(
                txId,
                false,
                TransactionSubmitResult.Failure.CODE_WRONG_CONSENSUS_BRANCH,
                "built for another consensus branch"
            )
        val expired =
            TransactionSubmitResult.Failure(txId, false, TransactionSubmitResult.Failure.CODE_EXPIRED, "expired")
        store.recordSubmitStarted(txId, a)
        store.recordSubmitStarted(txId, b)

        // The SDK refused to send to a, so a never saw the transaction; b refuses it for good.
        store.recordSubmitOutcome(txId, a, wrongBranch)
        store.recordSubmitOutcome(txId, b, invalid)
        assertNull(store.rejectionFor(txId))
        assertEquals(emptySet(), store.rejectedTransactionIds.value)

        store.recordSubmitOutcome(txId, a, expired)
        assertNull(store.rejectionFor(txId))
        assertEquals(emptySet(), store.rejectedTransactionIds.value)
    }

    @Test
    fun only_transient_failures_stay_pending() {
        val store = SubmitPlanStore(InMemoryPreferences())

        store.recordSubmitOutcome(txId, a, mempoolFull)
        store.recordSubmitOutcome(txId, b, TransactionSubmitResult.Failure(txId, true, 14, "unavailable"))

        assertNull(store.rejectionFor(txId))
    }

    @Test
    fun a_migration_refused_while_another_endpoint_has_not_answered_is_never_rejected() {
        val store = SubmitPlanStore(InMemoryPreferences())
        store.markMigration(txId)
        store.recordSubmitStarted(txId, b)

        store.recordSubmitOutcome(txId, a, tooLarge)
        store.recordSubmitOutcome(txId, b, tooLarge)

        assertNull(store.rejectionFor(txId))
        assertEquals(emptyMap(), store.rejectedTransactions.value)
    }

    @Test
    fun a_transaction_the_shared_migration_marks_hold_is_never_rejected() =
        runBlocking<Unit> {
            val store = SubmitPlanStore(InMemoryPreferences())
            // Marked by the migration SDK when it broadcast it, or when its PCZT was created.
            store.migrationMarks.markTransactions(listOf(txId))

            store.recordAnswer(txId, a, invalid)

            assertNull(store.rejectionFor(txId))
            assertEquals(emptySet(), store.rejectedTransactionIds.value)
        }

    @Test
    fun a_migration_is_never_rejected_and_its_mark_is_forgotten_with_it() {
        val preferences = InMemoryPreferences()
        val store = SubmitPlanStore(preferences)
        store.markMigration(txId)

        store.recordSubmitOutcome(txId, a, tooLarge)

        assertNull(store.rejectionFor(txId))
        assertEquals(emptySet(), store.rejectedTransactionIds.value)
        store.retainRejections(emptySet())
        assertEquals(
            emptySet(),
            preferences.all.keys
                .filter { it.startsWith("migration:") }
                .toSet()
        )
    }

    @Test
    fun retention_erases_the_shared_mark_of_a_forgotten_transaction_and_old_unplanned_marks() =
        runBlocking<Unit> {
            var now = 1_000_000L
            val marks = MigrationTransactionMarks(null, "1_main", { ByteArray(0) }, nowEpochSeconds = { now })
            val store = SubmitPlanStore(InMemoryPreferences(), marks)
            val other = FirstClassByteArray(byteArrayOf(9))
            val abandoned = FirstClassByteArray(byteArrayOf(7))
            marks.markTransactions(listOf(txId, other, abandoned))
            store.recordAnswer(txId, a, invalid)
            store.recordAnswer(other, a, invalid.copy(txId = other))

            // txId is no longer a candidate (mined or expired): its shared mark goes with its facts.
            store.beginRetentionScan()
            store.retainTransactions(setOf(other))
            assertFalse(marks.isMigrationTransaction(txId))
            assertTrue(marks.isMigrationTransaction(other))
            assertTrue(marks.isMigrationTransaction(abandoned))

            // An old mark of a transaction with no plan here (an abandoned PCZT's) goes; a planned one stays.
            now += MigrationTransactionMarks.MAX_MARK_AGE_SECONDS + 1
            store.beginRetentionScan()
            store.retainTransactions(setOf(other))
            assertFalse(marks.isMigrationTransaction(abandoned))
            assertTrue(marks.isMigrationTransaction(other))
        }

    @Test
    fun a_transient_failure_is_not_a_rejection() {
        val store = SubmitPlanStore(InMemoryPreferences())

        store.recordSubmitOutcome(txId, a, TransactionSubmitResult.Failure(txId, true, 14, "unavailable"))

        assertNull(store.rejectionFor(txId))
    }

    @Test
    fun rejections_of_transactions_no_longer_candidates_are_forgotten() {
        val store = SubmitPlanStore(InMemoryPreferences())
        val other = FirstClassByteArray(byteArrayOf(9))
        store.recordSubmitOutcome(txId, a, tooLarge)
        store.recordSubmitOutcome(other, a, tooLarge.copy(txId = other))

        store.retainRejections(setOf(other))

        assertNull(store.rejectionFor(txId))
        assertEquals(setOf(TransactionId.new(other)), store.rejectedTransactionIds.value)
        assertEquals(listOf(a), store.endpointsFor(txId))
    }

    /** A `SharedPreferences` of strings held in memory, as [SubmitPlanStore] uses it. */
    internal class InMemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(
            key: String,
            defValue: String?
        ): String? = values[key] as String? ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = error("Unused")

        override fun getInt(
            key: String,
            defValue: Int
        ): Int = error("Unused")

        override fun getLong(
            key: String,
            defValue: Long
        ): Long = error("Unused")

        override fun getFloat(
            key: String,
            defValue: Float
        ): Float = error("Unused")

        override fun getBoolean(
            key: String,
            defValue: Boolean
        ): Boolean = error("Unused")

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: OnChangeListener) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: OnChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, String?>()

            override fun putString(
                key: String,
                value: String?
            ) = apply { changes[key] = value }

            override fun remove(key: String) = apply { changes[key] = null }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = error("Unused")

            override fun putInt(
                key: String,
                value: Int
            ): SharedPreferences.Editor = error("Unused")

            override fun putLong(
                key: String,
                value: Long
            ): SharedPreferences.Editor = error("Unused")

            override fun putFloat(
                key: String,
                value: Float
            ): SharedPreferences.Editor = error("Unused")

            override fun putBoolean(
                key: String,
                value: Boolean
            ): SharedPreferences.Editor = error("Unused")

            override fun clear(): SharedPreferences.Editor = error("Unused")

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                changes.clear()
            }
        }
    }
}
