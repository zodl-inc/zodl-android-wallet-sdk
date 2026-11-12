package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.jni.RustPcztTransactionId
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * The Orchard to Ironwood migration transactions of one wallet ([namespace], as
 * [PendingSubmitPlanStore.namespaceFor] names it), which are never reported as rejected: they are
 * resubmitted until they expire, as they always have been.
 *
 * Each mark is keyed by transaction id and persisted, so it survives serializing and reparsing a
 * [Proposal] or [Pczt] and a restart. Every migration entry point records it:
 * - a migration proposer marks its [Proposal] ([markProposal], keyed by a digest of its bytes);
 * - creating transactions from a marked proposal marks their ids ([markTransactions]);
 * - creating a PCZT from a marked proposal, or a migration PCZT, marks the id of the transaction it
 *   describes, which signing and proving never change ([markPczt]);
 * - the migration engine marks each transaction id it broadcasts.
 *
 * Every component of a wallet (the synchronizer's plan store, the migration SDK, Slipstream) uses
 * its own instance over the same preferences: the marks are read from the preferences on every
 * lookup, never cached, so a mark one of them records is seen by all. Without [preferenceProvider]
 * (tests) they are kept in memory.
 *
 * Marks are bounded. A transaction's mark is erased with its submit plan, when the plan store prunes
 * the plan (the transaction was mined or expired: [forgetTransactionKeys]). A mark nothing else
 * erases, that of a proposal ([markProposal]) or of a transaction that never reached a plan store
 * (such as the transaction of an abandoned PCZT), is erased with the wallet's namespace
 * ([eraseNamespace]) and once it is older than [MAX_MARK_AGE_SECONDS] ([pruneStale]). Each mark
 * records when it was last made, and making it again (the migration engine marks a transaction on
 * every broadcast) renews it.
 */
@Suppress("TooManyFunctions")
internal class MigrationTransactionMarks(
    private val preferenceProvider: PreferenceProvider?,
    namespace: String,
    private val pcztTransactionId: suspend (ByteArray) -> ByteArray = RustPcztTransactionId::of,
    /** The current time, in seconds since the epoch: when a mark is made, and what [pruneStale] ages marks by. */
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND }
) {
    private val namespacePrefix = namespace.takeIf { it.isNotBlank() }?.let { "$it:" }.orEmpty()
    private val inMemory = mutableMapOf<PreferenceKey, Map<String, Long>>()

    /** Marks [proposal] as a migration: the transactions created from it are marked. */
    suspend fun markProposal(proposal: Proposal) {
        add(EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATION_PROPOSALS.key, listOf(proposal.toKey()))
    }

    /** Whether [proposal], or a proposal of the same bytes, was marked with [markProposal]. */
    suspend fun isMigrationProposal(proposal: Proposal): Boolean =
        proposal.toKey() in read(EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATION_PROPOSALS.key)

    /** Marks the transactions [txIds] as migrations. */
    suspend fun markTransactions(txIds: List<FirstClassByteArray>) {
        add(EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATIONS.key, txIds.map { it.toKey() })
    }

    /** Marks the transaction [pczt] describes as a migration, by its id. */
    suspend fun markPczt(pczt: Pczt) {
        markTransactions(listOf(FirstClassByteArray(pcztTransactionId(pczt.toByteArray()))))
    }

    /**
     * Marks the transaction [pczt] describes, by its id, when [proposal], the proposal it was created from, is a
     * migration ([markProposal]).
     */
    suspend fun markPcztOf(
        proposal: Proposal,
        pczt: Pczt
    ) {
        if (isMigrationProposal(proposal)) markPczt(pczt)
    }

    /** Whether the transaction [txId] is marked as a migration. */
    suspend fun isMigrationTransaction(txId: FirstClassByteArray): Boolean =
        txId.toKey() in read(EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATIONS.key)

    /** Forgets the marks of the transactions stored under [keys] (keys as [PendingSubmitPlanStore] keeps them). */
    suspend fun forgetTransactionKeys(keys: Collection<String>) {
        val owned = keys.filter { it.startsWith(namespacePrefix) }
        if (owned.isEmpty()) return
        update(EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATIONS.key) { it - owned.toSet() }
    }

    /** Forgets the marks of the transactions [txIds], such as ones whose submit plan was pruned. */
    suspend fun forgetTransactions(txIds: Collection<FirstClassByteArray>) {
        forgetTransactionKeys(txIds.map { it.toKey() })
    }

    /**
     * The key a plan store keeping its plans under this namespace stores [txId] under, as
     * [forgetTransactionKeys] and [pruneStale] take it.
     */
    fun transactionKey(txId: FirstClassByteArray): String = txId.toKey()

    /**
     * Forgets this namespace's marks older than [MAX_MARK_AGE_SECONDS] that nothing else erases: every
     * proposal mark, and the mark of every transaction not among [plannedTransactionKeys] (the
     * transactions that have a submit plan, whose marks are erased with it).
     */
    suspend fun pruneStale(plannedTransactionKeys: Set<String>) {
        val cutoff = nowEpochSeconds() - MAX_MARK_AGE_SECONDS

        fun Map<String, Long>.withoutStale(isPlanned: (String) -> Boolean) =
            filter { (key, markedAt) -> !key.startsWith(namespacePrefix) || markedAt >= cutoff || isPlanned(key) }
        update(EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATIONS.key) { marks ->
            marks.withoutStale { it in plannedTransactionKeys }
        }
        update(EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATION_PROPOSALS.key) { marks -> marks.withoutStale { false } }
    }

    private fun Proposal.toKey() =
        namespacePrefix +
            MessageDigest
                .getInstance(DIGEST_ALGORITHM)
                .digest(toByteArray())
                .joinToString("") { "%02x".format(it) }

    private fun FirstClassByteArray.toKey() = namespacePrefix + byteArray.toHexReversed()

    private suspend fun add(
        key: PreferenceKey,
        entries: List<String>
    ) {
        if (entries.isNotEmpty()) {
            val markedAt = nowEpochSeconds()
            update(key) { marks -> marks + entries.associateWith { markedAt } }
        }
    }

    private suspend fun read(key: PreferenceKey): Set<String> =
        if (preferenceProvider == null) {
            mutex.withLock { inMemory[key].orEmpty().keys }
        } else {
            preferenceProvider.readMarks(key, nowEpochSeconds()).keys
        }

    private suspend fun update(
        key: PreferenceKey,
        change: (Map<String, Long>) -> Map<String, Long>
    ) {
        mutex.withLock {
            if (preferenceProvider == null) {
                inMemory[key] = change(inMemory[key].orEmpty())
            } else {
                val stored = preferenceProvider.readMarks(key, nowEpochSeconds())
                val updated = change(stored)
                if (updated != stored) {
                    preferenceProvider.putString(key, PendingSubmitPlanCodec.encodeMarks(updated))
                }
            }
        }
    }

    companion object {
        private const val DIGEST_ALGORITHM = "SHA-256"
        private const val MILLIS_PER_SECOND = 1000L

        /**
         * How long a mark nothing else erases is kept: 30 days. A migration transaction stays
         * pending only until its expiry height, and while it is resubmitted it has a submit plan,
         * which keeps its mark; a PCZT or proposal unused for this long has long expired.
         */
        internal const val MAX_MARK_AGE_SECONDS = 30L * 24 * 60 * 60

        /** Serializes the read-modify-write cycles on the marks of every wallet in the process. */
        private val mutex = Mutex()

        /** The marks stored under [key], each with when it was made ([unknownTime] when that was not recorded). */
        private suspend fun PreferenceProvider.readMarks(
            key: PreferenceKey,
            unknownTime: Long
        ): Map<String, Long> =
            getString(key)
                .orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let { PendingSubmitPlanCodec.decodeMarks(it, unknownTime) }
                .orEmpty()

        /** Removes every mark of [namespace], leaving all other namespaces untouched. */
        internal suspend fun eraseNamespace(
            preferenceProvider: PreferenceProvider,
            namespace: String
        ) {
            require(namespace.isNotBlank()) { "Refusing to erase every namespace" }
            val prefix = "$namespace:"
            mutex.withLock {
                listOf(
                    EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATIONS.key,
                    EncryptedPreferenceKeys.PENDING_SUBMIT_MIGRATION_PROPOSALS.key
                ).forEach { key ->
                    val stored = preferenceProvider.readMarks(key, unknownTime = 0L)
                    val kept = stored.filterKeys { !it.startsWith(prefix) }
                    if (kept != stored) {
                        preferenceProvider.putString(key, PendingSubmitPlanCodec.encodeMarks(kept))
                    }
                }
            }
        }
    }
}
