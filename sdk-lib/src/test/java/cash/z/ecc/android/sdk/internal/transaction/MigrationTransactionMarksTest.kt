package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationTransactionMarksTest {
    private val preferences = FakePreferenceProvider()

    @Test
    fun a_mark_one_component_records_is_seen_by_another_over_the_same_preferences() =
        runBlocking {
            marks("1_main").markProposal(proposal())
            marks("1_main").markTransactions(listOf(TXID))

            assertTrue(marks("1_main").isMigrationProposal(Proposal.fromByteArray(proposal().toByteArray())))
            assertTrue(marks("1_main").isMigrationTransaction(TXID))
            assertFalse(marks("0_main").isMigrationTransaction(TXID))
            assertFalse(marks("0_main").isMigrationProposal(proposal()))
        }

    @Test
    fun a_pczt_is_marked_by_its_transaction_id_only_when_its_proposal_is_a_migration() =
        runBlocking {
            marks("1_main").markPcztOf(proposal(), Pczt(byteArrayOf(1)))
            assertFalse(marks("1_main").isMigrationTransaction(TXID))

            marks("1_main").markProposal(proposal())
            marks("1_main").markPcztOf(proposal(), Pczt(byteArrayOf(1)))
            assertTrue(marks("1_main").isMigrationTransaction(TXID))
        }

    @Test
    fun marks_are_forgotten_and_erased_per_namespace() =
        runBlocking {
            marks("1_main").markTransactions(listOf(TXID))
            marks("0_main").markTransactions(listOf(TXID))
            marks("0_main").markProposal(proposal())

            marks("1_main").forgetTransactionKeys(listOf("1_main:" + TXID_KEY))
            assertFalse(marks("1_main").isMigrationTransaction(TXID))
            assertTrue(marks("0_main").isMigrationTransaction(TXID))

            MigrationTransactionMarks.eraseNamespace(preferences, "0_main")
            assertFalse(marks("0_main").isMigrationTransaction(TXID))
            assertFalse(marks("0_main").isMigrationProposal(proposal()))
        }

    @Test
    fun marks_without_preferences_are_kept_in_memory() =
        runBlocking {
            val marks = MigrationTransactionMarks(null, "", pcztTransactionId = { TXID.byteArray })
            marks.markTransactions(listOf(TXID))
            assertTrue(marks.isMigrationTransaction(TXID))
        }

    @Test
    fun marks_nothing_else_erases_are_pruned_once_older_than_the_maximum_age() =
        runBlocking {
            var now = 1_000_000L

            fun marks(namespace: String) =
                MigrationTransactionMarks(preferences, namespace, { TXID.byteArray }, nowEpochSeconds = { now })
            val planned = FirstClassByteArray(ByteArray(32) { 7 })
            marks("1_main").markProposal(proposal())
            marks("1_main").markTransactions(listOf(TXID, planned))
            marks("0_main").markTransactions(listOf(TXID))
            val plannedKeys = setOf(marks("1_main").transactionKey(planned))

            // Up to the maximum age nothing is pruned.
            now += MigrationTransactionMarks.MAX_MARK_AGE_SECONDS
            marks("1_main").pruneStale(plannedKeys)
            assertTrue(marks("1_main").isMigrationProposal(proposal()))
            assertTrue(marks("1_main").isMigrationTransaction(TXID))

            // Past it, the proposal and the unplanned transaction (an abandoned PCZT's) go; the planned transaction
            // keeps its mark until its plan is pruned, and other namespaces are untouched.
            now += 1
            marks("1_main").pruneStale(plannedKeys)
            assertFalse(marks("1_main").isMigrationProposal(proposal()))
            assertFalse(marks("1_main").isMigrationTransaction(TXID))
            assertTrue(marks("1_main").isMigrationTransaction(planned))
            assertTrue(marks("0_main").isMigrationTransaction(TXID))

            marks("1_main").forgetTransactions(listOf(planned))
            assertFalse(marks("1_main").isMigrationTransaction(planned))
        }

    @Test
    fun marking_again_renews_a_mark() =
        runBlocking {
            var now = 1_000_000L
            val marks = MigrationTransactionMarks(preferences, "1_main", { TXID.byteArray }, nowEpochSeconds = { now })
            marks.markTransactions(listOf(TXID))
            now += MigrationTransactionMarks.MAX_MARK_AGE_SECONDS
            // The migration engine marks a transaction again on every broadcast.
            marks.markTransactions(listOf(TXID))
            now += 1

            marks.pruneStale(emptySet())

            assertTrue(marks.isMigrationTransaction(TXID))
        }

    private fun marks(namespace: String) =
        MigrationTransactionMarks(preferences, namespace, pcztTransactionId = { TXID.byteArray })

    private fun proposal() = Proposal.fromByteArray(byteArrayOf(0x10, 0x03))

    private class FakePreferenceProvider : PreferenceProvider {
        private val values = mutableMapOf<String, String?>()

        override suspend fun hasKey(key: PreferenceKey) = values.containsKey(key.key)

        override suspend fun putString(
            key: PreferenceKey,
            value: String?
        ) {
            values[key.key] = value
        }

        override suspend fun getString(key: PreferenceKey) = values[key.key]

        override fun observe(key: PreferenceKey): Flow<Unit> = flowOf(Unit)

        override suspend fun clearPreferences(): Boolean {
            values.clear()
            return true
        }
    }

    private companion object {
        val TXID = FirstClassByteArray(ByteArray(32) { it.toByte() })
        val TXID_KEY = (31 downTo 0).joinToString("") { "%02x".format(it) }
    }
}
