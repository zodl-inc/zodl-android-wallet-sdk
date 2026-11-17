package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.internal.storage.preference.PreferenceHolder
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.internal.transaction.MigrationTransactionMarks
import cash.z.ecc.android.sdk.internal.transaction.OutboundTransactionManager
import cash.z.ecc.android.sdk.internal.transaction.PendingSubmitPlanStore
import cash.z.ecc.android.sdk.internal.transaction.SdkBroadcaster
import cash.z.ecc.android.sdk.internal.transaction.TransactionSubmitter
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * A migration proposed by the migration SDK is never reported as rejected, along the whole path an app takes: propose
 * (`proposeImmediateSendMax`), create its PCZT, add proofs, create the transaction from the PCZT and submit it, with
 * the proposal and the PCZTs serialized and reparsed in between and the synchronizer restarted before a later
 * submission. Every endpoint refuses it for good; an ordinary transaction refused the same way is rejected.
 */
class MigrationNeverRejectedEndToEndTest {
    private val preferences = FakePreferenceProvider()
    private val namespace = PendingSubmitPlanStore.namespaceFor(ZcashNetwork.Testnet.id, ALIAS)

    @Test
    fun a_migration_is_never_rejected_through_pczt_reparse_and_restart() =
        runBlocking<Unit> {
            val migrationSdk = migrationSdk()
            val proposal = migrationSdk.proposeImmediateMigration()

            // The app carries the proposal and every PCZT as bytes: nothing transient survives.
            val reparsedProposal = Proposal.fromByteArray(proposal.toByteArray())
            val pczt = Pczt(byteArrayOf(1, 2, 3))
            store().migrationMarks.markPcztOf(reparsedProposal, Pczt(pczt.toByteArray().copyOf()))
            val provenPczt = Pczt(byteArrayOf(4, 5, 6))
            val signedPczt = Pczt(byteArrayOf(7, 8, 9))

            val store = store()
            val broadcaster = broadcaster(store, MIGRATION_TXID)
            val transaction =
                broadcaster
                    .createTransactionFromPczt(Pczt(provenPczt.toByteArray()), Pczt(signedPczt.toByteArray()))
                    .single()

            assertEquals(
                TransactionSubmitResult.Failure(transaction.txId, false, -26, INVALID),
                broadcaster.submit(transaction, A)
            )
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store.getSubmitPlan(transaction.txId))

            val restarted = store()
            broadcaster(restarted, MIGRATION_TXID).submit(transaction, B)
            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(restarted.getSubmitPlan(transaction.txId))
            assertEquals(emptySet(), restarted.rejectedTransactionIds.first())
        }

    @Test
    fun an_ordinary_transaction_refused_the_same_way_is_rejected() =
        runBlocking<Unit> {
            val proposal = Proposal.fromByteArray(byteArrayOf(0x10, 0x03, 0x18, 0x01))
            val store = store()
            store.migrationMarks.markPcztOf(proposal, Pczt(byteArrayOf(1, 2, 3)))
            val broadcaster = broadcaster(store, ORDINARY_TXID)
            val transaction = broadcaster.createTransactionFromPczt(Pczt(byteArrayOf(4)), Pczt(byteArrayOf(7))).single()

            broadcaster.submit(transaction, A)

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Rejected>(store.getSubmitPlan(transaction.txId))
        }

    @Test
    fun a_migration_created_directly_from_a_reparsed_proposal_is_never_rejected() =
        runBlocking<Unit> {
            val proposal = Proposal.fromByteArray(migrationSdk().proposeImmediateMigration().toByteArray())
            val store = store()
            val broadcaster = broadcaster(store, MIGRATION_TXID)

            val transaction = broadcaster.createProposedTransactions(proposal, mock(UnifiedSpendingKeyStub)).single()
            broadcaster.submit(transaction, A)

            assertIs<PendingSubmitPlanStore.StoredSubmitPlan.Ready>(store().getSubmitPlan(transaction.txId))
        }

    @Test
    fun a_migration_mark_that_cannot_be_recorded_fails_the_call_instead_of_being_lost() =
        runBlocking<Unit> {
            val failingWrites =
                object : PreferenceProvider by preferences {
                    override suspend fun putString(
                        key: PreferenceKey,
                        value: String?
                    ) = throw IOException("the preferences cannot be written")
                }

            assertFailsWith<IOException> { migrationSdk(failingWrites).proposeImmediateMigration() }
        }

    private fun store() =
        PendingSubmitPlanStore(
            preferences,
            namespace,
            MigrationTransactionMarks(preferences, namespace, pcztTransactionId = { MIGRATION_TXID.byteArray })
        )

    private fun broadcaster(
        store: PendingSubmitPlanStore,
        txId: FirstClassByteArray
    ) = SdkBroadcaster(transactionManager(txId), RefusingSubmitter, store)

    private fun migrationSdk(preferenceProvider: PreferenceProvider = preferences) =
        OrchardMigrationSdkImpl(
            context = fakeAndroidContext(),
            network = ZcashNetwork.Testnet,
            alias = ALIAS,
            account = AccountUuid.new(ByteArray(16) { it.toByte() }),
            migrationBackend =
                proxy<TypesafeMigrationBackend> { name ->
                    // A minimal Proposal protobuf: fee rule ZIP 317, no steps.
                    if (name == "proposeImmediateSendMax") byteArrayOf(0x10, 0x03) else error("Unused: $name")
                },
            defaultSubmitEndpoint = A,
            preferenceProviderHolder = FakePreferenceHolder(preferenceProvider)
        )

    private fun transactionManager(txId: FirstClassByteArray) =
        proxy<OutboundTransactionManager> { name ->
            val encoded = EncodedTransaction(txId, FirstClassByteArray(byteArrayOf(1)), BlockHeight.new(100))
            when (name) {
                "extractAndStoreTxFromPczt" -> encoded
                "createProposedTransactions" -> listOf(encoded)
                else -> error("Unused: $name")
            }
        }

    private object RefusingSubmitter : TransactionSubmitter {
        override suspend fun submit(
            transaction: CreatedTransaction,
            endpoint: LightWalletEndpoint
        ) = TransactionSubmitResult.Failure(transaction.txId, false, -26, INVALID)
    }

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

    private class FakePreferenceHolder(
        private val provider: PreferenceProvider
    ) : PreferenceHolder() {
        override suspend fun create() = provider

        override suspend fun clear() = provider.clearPreferences()
    }

    private companion object {
        const val ALIAS = "MigrationNeverRejectedEndToEndTest"
        const val INVALID = "16: bad-txns-no-source-of-funds"
        val A = LightWalletEndpoint("a.z.cash", 443, true)
        val B = LightWalletEndpoint("b.z.cash", 443, true)
        val MIGRATION_TXID = FirstClassByteArray(ByteArray(32) { 1 })
        val ORDINARY_TXID = FirstClassByteArray(ByteArray(32) { 2 })
        val UnifiedSpendingKeyStub = cash.z.ecc.android.sdk.model.UnifiedSpendingKey::class.java

        /** An implementation of [T] answering each call, suspending or not, with [answer] of its method's name. */
        inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                answer(method.name)
            } as T

        fun fakeAndroidContext(): Context {
            val tempDir =
                kotlin.io.path
                    .createTempDirectory("MigrationNeverRejectedEndToEndTest")
                    .toFile()
            val context = mock(Context::class.java)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
            `when`(context.getDatabasePath(anyString())).thenReturn(File(tempDir, "databases/unused.db"))
            return context
        }
    }
}
