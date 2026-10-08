package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.ext.fromHexReversed
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions")
internal class PendingSubmitPlanStore(
    private val preferenceProvider: PreferenceProvider? = null,
    private val namespace: String = DEFAULT_NAMESPACE
) {
    private val mutex = Mutex()
    private val namespacePrefix = namespace.takeIf { it.isNotBlank() }?.let { "$it:" }.orEmpty()
    private val plansByTransactionId = mutableMapOf<String, List<LightWalletEndpoint>>()
    private var loadedFromPreferences = false

    /**
     * Runs transaction creation under this store lock so resubmission cannot read a
     * stored tx before it is marked as waiting for a submit plan from the caller.
     */
    suspend fun createAndMarkAwaitingSubmitPlan(
        createTransactions: suspend () -> List<CreatedTransaction>
    ): List<CreatedTransaction> =
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            createTransactions().also { transactions ->
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

    suspend fun addSubmitEndpoint(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ) {
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            val transactionId = transaction.txId.toStableKey()
            val updatedEndpoints =
                (plansByTransactionId[transactionId].orEmpty() + endpoint)
                    .distinct()
            if (plansByTransactionId[transactionId] != updatedEndpoints) {
                plansByTransactionId[transactionId] = updatedEndpoints
                saveToPreferences()
            }
        }
    }

    suspend fun getSubmitPlan(txId: FirstClassByteArray): StoredSubmitPlan? =
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            when (val endpoints = plansByTransactionId[txId.toStableKey()]) {
                null -> null
                emptyList<LightWalletEndpoint>() -> StoredSubmitPlan.AwaitingPlan
                else -> StoredSubmitPlan.Ready(TransactionSubmitPlan(endpoints))
            }
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
            val stored =
                provider
                    .getString(EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key)
                    .orEmpty()
                    .takeIf { it.isNotBlank() }
                    ?.let(PendingSubmitPlanCodec::decode)
                    .orEmpty()
            val merged = stored.filterKeys { !ownsKey(it) } + plansByTransactionId.filterKeys { ownsKey(it) }
            provider.putString(
                EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key,
                PendingSubmitPlanCodec.encode(merged)
            )
        }
    }

    private fun ownsKey(transactionId: String) = namespacePrefix.isBlank() || transactionId.startsWith(namespacePrefix)

    private suspend fun retainLoadedPlansFor(
        txIds: List<FirstClassByteArray>,
        retainMissing: suspend (FirstClassByteArray) -> Boolean
    ) {
        val retainedTransactionIds = txIds.map { it.toStableKey() }.toSet()
        val keysToRemove = mutableListOf<String>()
        for (transactionId in plansByTransactionId.keys) {
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
            saveToPreferences()
        }
    }

    private fun FirstClassByteArray.toStableKey() = namespacePrefix + byteArray.toHexReversed()

    sealed interface StoredSubmitPlan {
        data object AwaitingPlan : StoredSubmitPlan

        data class Ready(
            val submitPlan: TransactionSubmitPlan
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
            val prefix = "$namespace:"
            sharedPreferenceMutex.withLock {
                val stored =
                    preferenceProvider
                        .getString(EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key)
                        .orEmpty()
                        .takeIf { it.isNotBlank() }
                        ?.let(PendingSubmitPlanCodec::decode)
                        ?: return
                val kept = stored.filterKeys { !it.startsWith(prefix) }
                if (kept.size != stored.size) {
                    preferenceProvider.putString(
                        EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key,
                        PendingSubmitPlanCodec.encode(kept)
                    )
                }
            }
        }
    }
}
