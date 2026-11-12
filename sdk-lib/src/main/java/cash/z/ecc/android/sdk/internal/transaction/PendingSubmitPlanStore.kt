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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions")
internal class PendingSubmitPlanStore(
    private val preferenceProvider: PreferenceProvider? = null,
    private val namespace: String = DEFAULT_NAMESPACE,
    /** The wallet's migration transactions: never rejected. Shared, through the preferences, with the migration SDK. */
    val migrationMarks: MigrationTransactionMarks = MigrationTransactionMarks(preferenceProvider, namespace)
) {
    private val mutex = Mutex()
    private val namespacePrefix = namespace.takeIf { it.isNotBlank() }?.let { "$it:" }.orEmpty()
    private val plansByTransactionId = mutableMapOf<String, List<LightWalletEndpoint>>()
    private val rejectionsByTransactionId = mutableMapOf<String, StoredRejection>()

    /**
     * The endpoints that accepted each transaction, in any process: a transaction with one is never
     * rejected, so a verdict is never drawn from the partial answers left after a restart. Persisted.
     */
    private val acceptancesByTransactionId = mutableMapOf<String, List<LightWalletEndpoint>>()

    /**
     * What each endpoint answered the last time a transaction was submitted to it in this process,
     * [TransactionSubmitResult.NotAttempted] while that submission has not answered (see
     * [recordSubmitStarted]). Not persisted: only the verdict drawn from it
     * ([rejectionsByTransactionId]) and the acceptances ([acceptancesByTransactionId]) are.
     */
    private val outcomesByTransactionId =
        mutableMapOf<String, MutableMap<LightWalletEndpoint, TransactionSubmitResult>>()
    private val rejectedKeys = MutableStateFlow<Map<String, StoredRejection>>(emptyMap())
    private var loadedFromPreferences = false

    /**
     * Runs transaction creation under this store lock so resubmission cannot read a
     * stored tx before it is marked as waiting for a submit plan from the caller.
     */
    suspend fun createAndMarkAwaitingSubmitPlan(
        isMigration: Boolean = false,
        createTransactions: suspend () -> List<CreatedTransaction>
    ): List<CreatedTransaction> =
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            createTransactions().also { transactions ->
                if (isMigration) migrationMarks.markTransactions(transactions.map { it.txId })
                var changed = false
                transactions.map { it.txId.toStableKey() }.forEach { transactionId ->
                    if (!plansByTransactionId.containsKey(transactionId)) {
                        plansByTransactionId[transactionId] = emptyList()
                        changed = true
                    }
                }
                if (changed) {
                    saveToPreferences()
                }
            }
        }

    suspend fun storeSubmitPlan(
        transaction: CreatedTransaction,
        submitPlan: TransactionSubmitPlan
    ) {
        storeSubmitPlan(transaction.txId, submitPlan.endpoints)
    }

    /**
     * Records that [txId] is being submitted to [endpoint]: until that submission answers
     * ([recordSubmitOutcome], [addSubmitEndpoint]) the endpoint may still accept the transaction, so
     * the transaction cannot become [StoredSubmitPlan.Rejected]. Call it before the request is sent,
     * so that the endpoints of a concurrent submission are all known before any of them answers.
     * The submit plan is not changed. Not persisted.
     */
    suspend fun recordSubmitStarted(
        txId: FirstClassByteArray,
        endpoint: LightWalletEndpoint
    ) {
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            outcomesByTransactionId.getOrPut(txId.toStableKey()) { mutableMapOf() }[endpoint] =
                TransactionSubmitResult.NotAttempted(txId)
        }
    }

    /**
     * Adds [endpoint] to the transaction's submit plan, and records what it answered ([result]; see
     * [recordSubmitOutcome]). A submission that returned nothing (it threw, was cancelled or timed
     * out, possibly after the request was sent) is recorded as unanswered, and one that failed in
     * transport is inconclusive: neither accepts the transaction, and both keep it from being
     * rejected until the endpoint gives an explicit answer to a later submission.
     */
    suspend fun addSubmitEndpoint(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint,
        result: TransactionSubmitResult? = null
    ) {
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            val transactionId = transaction.txId.toStableKey()
            val updatedEndpoints =
                (plansByTransactionId[transactionId].orEmpty() + endpoint)
                    .distinct()
            var changed = false
            if (plansByTransactionId[transactionId] != updatedEndpoints) {
                plansByTransactionId[transactionId] = updatedEndpoints
                changed = true
            }
            val outcome = result ?: TransactionSubmitResult.NotAttempted(transaction.txId)
            val isMigration = migrationMarks.isMigrationTransaction(transaction.txId)
            changed = recordOutcomeLocked(transactionId, endpoint, outcome, isMigration) || changed
            if (changed) {
                saveToPreferences()
            }
        }
    }

    /**
     * Records what [endpoint], one of the endpoints of the transaction's submit plan, answered when
     * [txId] was submitted to it, and whether that leaves the transaction
     * [StoredSubmitPlan.Rejected].
     *
     * A transaction is rejected only once every endpoint it was submitted to, or is planned for,
     * has given an explicit server answer, none accepted it and every one refused it explicitly
     * and for good; a transient refusal from any endpoint keeps it pending. An endpoint that has not
     * answered, or whose submission failed in transport (timeout, gRPC or I/O error: the server may
     * have accepted it), is inconclusive and keeps the transaction pending, so it is resubmitted
     * (see [submitRejectionVerdict]). Any acceptance, including a late one, clears it. A migration
     * transaction is never rejected.
     */
    suspend fun recordSubmitOutcome(
        txId: FirstClassByteArray,
        endpoint: LightWalletEndpoint,
        result: TransactionSubmitResult
    ) {
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            val isMigration = migrationMarks.isMigrationTransaction(txId)
            if (recordOutcomeLocked(txId.toStableKey(), endpoint, result, isMigration)) {
                saveToPreferences()
            }
        }
    }

    /**
     * [result], an answer recorded for [txId], as the SDK judges the transaction now: a
     * [TransactionSubmitResult.Success] once any endpoint accepted it (in this process or an earlier
     * one); a [TransactionSubmitResult.Failure] whose [TransactionSubmitResult.Failure.rejection] is
     * the stored verdict, non-null only when the transaction is [StoredSubmitPlan.Rejected];
     * anything else unchanged.
     */
    suspend fun withVerdict(
        txId: FirstClassByteArray,
        result: TransactionSubmitResult
    ): TransactionSubmitResult =
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            val transactionId = txId.toStableKey()
            when {
                result !is TransactionSubmitResult.Failure -> {
                    result
                }

                !acceptancesByTransactionId[transactionId].isNullOrEmpty() -> {
                    TransactionSubmitResult.Success(result.txId)
                }

                else -> {
                    result.copy(rejection = rejectionsByTransactionId[transactionId]?.toSubmitRejection())
                }
            }
        }

    /**
     * This store's transactions that are [StoredSubmitPlan.Rejected], with the explicit refusal for
     * good recorded for each, as they change.
     */
    val rejectedTransactions: Flow<Map<TransactionId, SubmitRejection>> =
        flow {
            mutex.withLock { loadFromPreferencesIfNeeded() }
            emitAll(
                rejectedKeys.map { rejections ->
                    rejections.entries
                        .mapNotNull { (key, rejection) ->
                            val transactionId =
                                runCatching {
                                    TransactionId.new(key.removePrefix(namespacePrefix).fromHexReversed())
                                }.getOrNull()
                            val submitRejection = rejection.toSubmitRejection()
                            if (transactionId == null || submitRejection == null) {
                                null
                            } else {
                                transactionId to submitRejection
                            }
                        }.toMap()
                }
            )
        }.distinctUntilChanged()

    /** The ids of this store's transactions that are [StoredSubmitPlan.Rejected], as they change. */
    val rejectedTransactionIds: Flow<Set<TransactionId>> =
        rejectedTransactions.map { it.keys }.distinctUntilChanged()

    suspend fun getSubmitPlan(txId: FirstClassByteArray): StoredSubmitPlan? =
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            val transactionId = txId.toStableKey()
            val rejection = rejectionsByTransactionId[transactionId]
            when (val endpoints = plansByTransactionId[transactionId]) {
                null -> {
                    null
                }

                emptyList<LightWalletEndpoint>() -> {
                    StoredSubmitPlan.AwaitingPlan
                }

                else -> {
                    if (rejection != null) {
                        StoredSubmitPlan.Rejected(rejection.code, rejection.reason)
                    } else {
                        StoredSubmitPlan.Ready(TransactionSubmitPlan(endpoints))
                    }
                }
            }
        }

    /**
     * Records [result] for [endpoint] and redraws the transaction's verdict (see
     * [recordSubmitOutcome]). Returns whether the stored verdict changed. Must hold [mutex].
     */
    private fun recordOutcomeLocked(
        transactionId: String,
        endpoint: LightWalletEndpoint,
        result: TransactionSubmitResult,
        isMigration: Boolean
    ): Boolean {
        val outcomes = outcomesByTransactionId.getOrPut(transactionId) { mutableMapOf() }
        outcomes[endpoint] = result
        var acceptanceChanged = false
        if (result.isAcceptance()) {
            val accepted = acceptancesByTransactionId[transactionId].orEmpty()
            if (endpoint !in accepted) {
                acceptancesByTransactionId[transactionId] = accepted + endpoint
                acceptanceChanged = true
            }
        }
        val verdict =
            if (isMigration) {
                null
            } else {
                submitRejectionVerdict(
                    planEndpoints = plansByTransactionId[transactionId].orEmpty(),
                    outcomes = outcomes,
                    latest = result,
                    previous = rejectionsByTransactionId[transactionId],
                    acceptedEarlier = !acceptancesByTransactionId[transactionId].isNullOrEmpty()
                )
            }
        val changed = rejectionsByTransactionId[transactionId] != verdict
        if (verdict == null) {
            rejectionsByTransactionId.remove(transactionId)
        } else {
            rejectionsByTransactionId[transactionId] = verdict
        }
        publishRejections()
        return changed || acceptanceChanged
    }

    private fun publishRejections() {
        rejectedKeys.value = rejectionsByTransactionId.filterKeys { ownsKey(it) }
    }

    /**
     * @param retainMissing Consulted for a stored plan whose transaction id was not among the loaded
     * [transactionId]s. Returning `true` keeps the plan; the default `false` preserves the previous
     * prune-everything-missing behavior. Runs under this store's mutex and MUST NOT call back into
     * [PendingSubmitPlanStore] (deadlock).
     */
    suspend fun <T> loadTransactionsAndRetainSubmitPlans(
        loadTransactions: suspend () -> List<T>,
        transactionId: (T) -> FirstClassByteArray,
        retainMissing: suspend (FirstClassByteArray) -> Boolean = { false }
    ): List<T> =
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            loadTransactions().also { transactions ->
                retainLoadedPlansFor(transactions.map(transactionId), retainMissing)
            }
        }

    private suspend fun storeSubmitPlan(
        txId: FirstClassByteArray,
        endpoints: List<LightWalletEndpoint>
    ) {
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            val transactionId = txId.toStableKey()
            val normalizedEndpoints = endpoints.distinct()
            if (plansByTransactionId[transactionId] != normalizedEndpoints) {
                plansByTransactionId[transactionId] = normalizedEndpoints
                saveToPreferences()
            }
        }
    }

    private suspend fun loadFromPreferencesIfNeeded() {
        if (loadedFromPreferences) {
            return
        }

        val storedPlans =
            preferenceProvider
                ?.getString(EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key)
                .orEmpty()

        if (storedPlans.isNotBlank()) {
            plansByTransactionId.putAll(PendingSubmitPlanCodec.decode(storedPlans))
        }
        val storedRejections =
            preferenceProvider
                ?.getString(EncryptedPreferenceKeys.PENDING_SUBMIT_REJECTIONS.key)
                .orEmpty()
        if (storedRejections.isNotBlank()) {
            rejectionsByTransactionId.putAll(PendingSubmitPlanCodec.decodeRejections(storedRejections))
        }
        val storedAcceptances =
            preferenceProvider
                ?.getString(EncryptedPreferenceKeys.PENDING_SUBMIT_ACCEPTANCES.key)
                .orEmpty()
        if (storedAcceptances.isNotBlank()) {
            acceptancesByTransactionId.putAll(PendingSubmitPlanCodec.decode(storedAcceptances))
        }
        publishRejections()
        loadedFromPreferences = true
    }

    /**
     * Writes this store's plans back to the shared preference.
     *
     * Every synchronizer in the process (one per network and alias) keeps its plans in the same
     * preference, each under its own [namespace]. Writing the whole in-memory map would replace
     * the other namespaces with whatever this store happened to load, losing any plan another
     * synchronizer stored since, so only this store's own namespace is replaced and the rest is
     * re-read and kept as it currently is. A store with a blank namespace owns every entry.
     */
    private suspend fun saveToPreferences() {
        val provider = preferenceProvider ?: return
        sharedPreferenceMutex.withLock {
            provider.replaceOwnNamespace(
                EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key,
                plansByTransactionId,
                PendingSubmitPlanCodec::decode,
                PendingSubmitPlanCodec::encode
            )
            provider.replaceOwnNamespace(
                EncryptedPreferenceKeys.PENDING_SUBMIT_REJECTIONS.key,
                rejectionsByTransactionId,
                PendingSubmitPlanCodec::decodeRejections,
                PendingSubmitPlanCodec::encodeRejections,
                writeWhenUnchanged = false
            )
            provider.replaceOwnNamespace(
                EncryptedPreferenceKeys.PENDING_SUBMIT_ACCEPTANCES.key,
                acceptancesByTransactionId,
                PendingSubmitPlanCodec::decode,
                PendingSubmitPlanCodec::encode,
                writeWhenUnchanged = false
            )
        }
    }

    /**
     * Replaces this store's own namespace of the map stored under [key] with [own], keeping every
     * other namespace as it currently is stored. Must hold [sharedPreferenceMutex].
     */
    private suspend fun <V> PreferenceProvider.replaceOwnNamespace(
        key: PreferenceKey,
        own: Map<String, V>,
        decode: (String) -> Map<String, V>,
        encode: (Map<String, V>) -> String,
        writeWhenUnchanged: Boolean = true
    ) {
        val stored =
            getString(key)
                .orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let(decode)
                .orEmpty()
        val merged = stored.filterKeys { !ownsKey(it) } + own.filterKeys { ownsKey(it) }
        if (writeWhenUnchanged || merged != stored) {
            putString(key, encode(merged))
        }
    }

    private fun ownsKey(transactionId: String) = namespacePrefix.isBlank() || transactionId.startsWith(namespacePrefix)

    private suspend fun retainLoadedPlansFor(
        txIds: List<FirstClassByteArray>,
        retainMissing: suspend (FirstClassByteArray) -> Boolean
    ) {
        val retainedTransactionIds = txIds.map { it.toStableKey() }.toSet()
        val keysToRemove = mutableListOf<String>()
        val storedKeys =
            plansByTransactionId.keys + rejectionsByTransactionId.keys + acceptancesByTransactionId.keys
        for (transactionId in storedKeys) {
            val isNamespaced = namespacePrefix.isBlank() || transactionId.startsWith(namespacePrefix)
            if (!isNamespaced || transactionId in retainedTransactionIds) {
                continue
            }
            val shouldRetain =
                runCatching {
                    FirstClassByteArray(transactionId.removePrefix(namespacePrefix).fromHexReversed())
                }.fold(
                    onSuccess = { txId -> retainMissing(txId) },
                    onFailure = { true }
                )
            if (!shouldRetain) {
                keysToRemove.add(transactionId)
            }
        }
        if (keysToRemove.isNotEmpty()) {
            plansByTransactionId.keys.removeAll(keysToRemove)
            rejectionsByTransactionId.keys.removeAll(keysToRemove)
            migrationMarks.forgetTransactionKeys(keysToRemove)
            acceptancesByTransactionId.keys.removeAll(keysToRemove)
            outcomesByTransactionId.keys.removeAll(keysToRemove)
            publishRejections()
            saveToPreferences()
        }
        // The marks nothing else erases (proposals, and transactions that never got a plan, such as an abandoned
        // PCZT's) are forgotten once they are old; a planned transaction keeps its mark until its plan is pruned.
        migrationMarks.pruneStale(plansByTransactionId.keys.toSet())
    }

    private fun FirstClassByteArray.toStableKey() = namespacePrefix + byteArray.toHexReversed()

    sealed interface StoredSubmitPlan {
        data object AwaitingPlan : StoredSubmitPlan

        data class Ready(
            val submitPlan: TransactionSubmitPlan
        ) : StoredSubmitPlan

        /**
         * Every endpoint answered explicitly, one refused the transaction for good, and none accepted it: it
         * is not resubmitted, and is pruned at its expiry like any other plan. [code] and [reason]
         * are the recorded refusal (see [submitRejectionVerdict]), as the server reported it.
         */
        data class Rejected(
            val code: Int,
            val reason: String
        ) : StoredSubmitPlan
    }

    companion object {
        private const val DEFAULT_NAMESPACE = ""

        /** Serializes read-merge-write cycles on the shared preference across all stores. */
        private val sharedPreferenceMutex = Mutex()

        /** The namespace a synchronizer for [networkId] and [alias] keeps its plans under. */
        internal fun namespaceFor(
            networkId: Int,
            alias: String
        ) = "${networkId}_$alias"

        /**
         * Removes every plan stored under [namespace], leaving all other namespaces untouched.
         * The synchronizer that owns [namespace] must be closed.
         */
        internal suspend fun eraseNamespace(
            preferenceProvider: PreferenceProvider,
            namespace: String
        ) {
            require(namespace.isNotBlank()) { "Refusing to erase every namespace" }
            MigrationTransactionMarks.eraseNamespace(preferenceProvider, namespace)
            val prefix = "$namespace:"
            sharedPreferenceMutex.withLock {
                preferenceProvider.eraseKeysWithPrefix(
                    EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key,
                    prefix,
                    PendingSubmitPlanCodec::decode,
                    PendingSubmitPlanCodec::encode
                )
                preferenceProvider.eraseKeysWithPrefix(
                    EncryptedPreferenceKeys.PENDING_SUBMIT_REJECTIONS.key,
                    prefix,
                    PendingSubmitPlanCodec::decodeRejections,
                    PendingSubmitPlanCodec::encodeRejections
                )
                preferenceProvider.eraseKeysWithPrefix(
                    EncryptedPreferenceKeys.PENDING_SUBMIT_ACCEPTANCES.key,
                    prefix,
                    PendingSubmitPlanCodec::decode,
                    PendingSubmitPlanCodec::encode
                )
            }
        }

        private suspend fun <V> PreferenceProvider.eraseKeysWithPrefix(
            key: PreferenceKey,
            prefix: String,
            decode: (String) -> Map<String, V>,
            encode: (Map<String, V>) -> String
        ) {
            val stored = getString(key).orEmpty().takeIf { it.isNotBlank() }?.let(decode) ?: return
            val kept = stored.filterKeys { !it.startsWith(prefix) }
            if (kept.size != stored.size) {
                putString(key, encode(kept))
            }
        }
    }
}
