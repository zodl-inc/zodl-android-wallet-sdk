@file:Suppress("ReturnCount")

package com.zodl.slipstream.internal.spend

import android.content.SharedPreferences
import androidx.core.content.edit
import cash.z.ecc.android.sdk.ext.fromHex
import cash.z.ecc.android.sdk.ext.toHex
import cash.z.ecc.android.sdk.internal.transaction.MigrationTransactionMarks
import cash.z.ecc.android.sdk.internal.transaction.StoredRejection
import cash.z.ecc.android.sdk.internal.transaction.isAcceptance
import cash.z.ecc.android.sdk.internal.transaction.submitRejectionVerdict
import cash.z.ecc.android.sdk.internal.transaction.toSubmitRejection
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SubmitRejection
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** `host|port|isSecure` <-> [LightWalletEndpoint] - a plain, dependency-free codec for [SubmitPlanStore]'s values. */
private fun LightWalletEndpoint.encode(): String = "$host|$port|$isSecure"

private fun decodeEndpoint(entry: String): LightWalletEndpoint? {
    val parts = entry.split("|")
    if (parts.size != ENDPOINT_ENTRY_FIELD_COUNT) return null
    val port = parts[1].toIntOrNull() ?: return null
    return LightWalletEndpoint(host = parts[0], port = port, isSecure = parts[2].toBoolean())
}

/**
 * The name of the `SharedPreferences` file a [SubmitPlanStore] persists to for the wallet at
 * [networkId] and [alias]: written by the synchronizer that owns the wallet, deleted with the wallet
 * by `SlipstreamSynchronizer.erase`.
 */
internal fun submitPlanPreferencesName(
    networkId: Int,
    alias: String
): String = "com.zodl.slipstream.submit_plan_${networkId}_$alias"

private const val ENDPOINT_ENTRY_FIELD_COUNT = 3
private const val ENDPOINT_DELIMITER = ";"

/** The key prefix of a stored rejection; never a transaction id's hex, which has no `:`. */
private const val REJECTED_PREFIX = "rejected:"

/** The key prefix marking a migration transaction (see [SubmitPlanStore.markMigration]). */
private const val MIGRATION_PREFIX = "migration:"

/** The key prefix of the endpoints that accepted a transaction (see [SubmitPlanStore.recordSubmitOutcome]). */
private const val ACCEPTED_PREFIX = "accepted:"

/** Every key prefix of a fact kept about a transaction besides its endpoints. */
private val TRANSACTION_FACT_PREFIXES = listOf(REJECTED_PREFIX, MIGRATION_PREFIX, ACCEPTED_PREFIX)
private const val REJECTION_DELIMITER = "|"
private const val REJECTION_FIELD_COUNT = 2

private fun StoredRejection.encode(): String =
    "$code$REJECTION_DELIMITER${URLEncoder.encode(reason, StandardCharsets.UTF_8.name())}"

private fun decodeRejection(entry: String): StoredRejection? {
    val parts = entry.split(REJECTION_DELIMITER, limit = REJECTION_FIELD_COUNT)
    val code = parts.firstOrNull()?.toIntOrNull() ?: return null
    if (parts.size != REJECTION_FIELD_COUNT) return null
    return StoredRejection(code, URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()))
}

/**
 * The adapter's own submit-plan store for R29 (`broadcaster`) - `SdkBroadcaster`'s own
 * `PendingSubmitPlanStore` is `internal` to `sdk-lib`'s own broadcaster package and paired tightly
 * to `OutboundTransactionManager`/`TransactionSubmitter`, so this is a from-scratch, minimal
 * re-implementation of exactly the two facts `KOTLIN_ROSETTA.md` section 3.5 asks for: "create
 * marks awaiting-submission; submit records endpoints for retry" - namespaced `<networkId>_<alias>`
 * like theirs, backed by the caller's own `SharedPreferences` file (never the upstream SDK's own
 * preference files).
 */
@Suppress("TooManyFunctions")
internal class SubmitPlanStore(
    private val preferences: SharedPreferences,
    /**
     * The wallet's migration transactions, shared through the SDK's preferences with the migration SDK and the
     * spend service: a transaction marked there is never rejected. In memory by default (tests).
     */
    val migrationMarks: MigrationTransactionMarks = MigrationTransactionMarks(null, "")
) {
    /**
     * What each endpoint last answered for each transaction in this process; see [recordSubmitOutcome].
     * [TransactionSubmitResult.NotAttempted] while a submission has not answered ([recordSubmitStarted]).
     */
    private val outcomes = mutableMapOf<String, MutableMap<LightWalletEndpoint, TransactionSubmitResult>>()

    private val rejections = MutableStateFlow(loadRejections())

    /** The transactions [retainRejections] may forget: those with facts when [beginRetentionScan] ran. */
    private var retentionScope: Set<String>? = null

    private val rejected = MutableStateFlow(rejections.value.keys)

    /**
     * The transactions every endpoint explicitly answered, none accepted and every refusal was for good,
     * with that refusal (`Synchronizer.rejectedTransactions`).
     */
    val rejectedTransactions: StateFlow<Map<TransactionId, SubmitRejection>> = rejections.asStateFlow()

    /** The ids of [rejectedTransactions] (`Synchronizer.rejectedTransactionIds`). */
    val rejectedTransactionIds: StateFlow<Set<TransactionId>> = rejected.asStateFlow()

    /**
     * Marks [txId] as an Orchard to Ironwood migration transaction: it is never rejected, so it is
     * resubmitted until it expires, as it always has been.
     */
    @Synchronized
    fun markMigration(txId: FirstClassByteArray) {
        preferences.edit { putString(MIGRATION_PREFIX + txId.byteArray.toHex(), "") }
    }

    /**
     * Records that [txId] is being submitted to [endpoint]: until that submission answers
     * ([recordSubmitOutcome] with an answer) the endpoint may still accept the transaction, so it
     * cannot be rejected. Call it before the request is sent. Not persisted.
     */
    @Synchronized
    fun recordSubmitStarted(
        txId: FirstClassByteArray,
        endpoint: LightWalletEndpoint
    ) {
        outcomes.getOrPut(txId.byteArray.toHex()) { mutableMapOf() }[endpoint] =
            TransactionSubmitResult.NotAttempted(txId)
    }

    /**
     * Records [result], [endpoint]'s answer when [txId] was submitted to it, adding [endpoint] to the
     * transaction's endpoints, and stores or clears the transaction's rejection by the same rule as
     * `sdk-lib`'s `PendingSubmitPlanStore` (see `submitRejectionVerdict`): rejected only once every
     * endpoint gave an explicit server answer, none accepted and every one refused for good.
     * [TransactionSubmitResult.NotAttempted] records a submission that returned no answer (it threw
     * or was cancelled, possibly after the request was sent), and a transport failure
     * ([TransactionSubmitResult.Failure.grpcError]: timeout, gRPC or I/O error) is inconclusive too:
     * either keeps the transaction pending, so it is resubmitted. A migration ([markMigration]) is
     * never rejected. An acceptance (a success, or an answer that the server already holds the transaction) is
     * persisted with the endpoint that gave it: a transaction an
     * endpoint accepted, in this process or an earlier one, is never rejected.
     */
    @Synchronized
    fun recordSubmitOutcome(
        txId: FirstClassByteArray,
        endpoint: LightWalletEndpoint,
        result: TransactionSubmitResult,
        isMigrationOrigin: Boolean = false
    ) {
        recordSubmitEndpoint(txId, endpoint)
        val key = txId.byteArray.toHex()
        val answers = outcomes.getOrPut(key) { mutableMapOf() }
        answers[endpoint] = result
        if (isMigrationOrigin && !preferences.contains(MIGRATION_PREFIX + key)) markMigration(txId)
        val isMigration = isMigrationOrigin || preferences.contains(MIGRATION_PREFIX + key)
        val accepted = acceptedBy(key)
        val nowAccepted =
            if (result.isAcceptance()) (accepted + endpoint).distinct() else accepted
        val verdict =
            if (isMigration) {
                null
            } else {
                submitRejectionVerdict(
                    endpointsFor(txId),
                    answers,
                    result,
                    rejectionFor(txId),
                    acceptedEarlier = nowAccepted.isNotEmpty()
                )
            }
        preferences.edit {
            if (nowAccepted != accepted) {
                putString(ACCEPTED_PREFIX + key, nowAccepted.joinToString(ENDPOINT_DELIMITER) { it.encode() })
            }
            if (verdict == null) remove(REJECTED_PREFIX + key) else putString(REJECTED_PREFIX + key, verdict.encode())
        }
        publishRejections()
    }

    /**
     * [recordSubmitOutcome] for a transaction that is a migration when [migrationMarks] marks it: what every
     * production caller records an answer with.
     */
    suspend fun recordAnswer(
        txId: FirstClassByteArray,
        endpoint: LightWalletEndpoint,
        result: TransactionSubmitResult
    ) {
        recordSubmitOutcome(txId, endpoint, result, isMigrationOrigin = migrationMarks.isMigrationTransaction(txId))
    }

    /** The endpoints that accepted the transaction stored under [key], in any process. */
    private fun acceptedBy(key: String): List<LightWalletEndpoint> =
        preferences
            .getString(ACCEPTED_PREFIX + key, null)
            .orEmpty()
            .takeIf { it.isNotBlank() }
            ?.split(ENDPOINT_DELIMITER)
            ?.mapNotNull(::decodeEndpoint)
            .orEmpty()

    /**
     * [result], an answer recorded for [txId], as the SDK judges the transaction now (as `sdk-lib`'s
     * `PendingSubmitPlanStore.withVerdict`): a [TransactionSubmitResult.Success] once any endpoint
     * accepted it, a [TransactionSubmitResult.Failure] whose `rejection` is the stored verdict.
     */
    @Synchronized
    fun withVerdict(
        txId: FirstClassByteArray,
        result: TransactionSubmitResult
    ): TransactionSubmitResult =
        when {
            result !is TransactionSubmitResult.Failure -> result
            acceptedBy(txId.byteArray.toHex()).isNotEmpty() -> TransactionSubmitResult.Success(result.txId)
            else -> result.copy(rejection = rejectionFor(txId)?.toSubmitRejection())
        }

    /** The stored rejection of [txId], or `null` when it is not rejected. */
    fun rejectionFor(txId: FirstClassByteArray): StoredRejection? =
        preferences.getString(REJECTED_PREFIX + txId.byteArray.toHex(), null)?.let(::decodeRejection)

    /**
     * [retainRejections], then forgets the shared [migrationMarks] of the transactions it forgot, and
     * the shared marks nothing else erases once they are old ([MigrationTransactionMarks.pruneStale]):
     * a transaction with a submit plan here keeps its mark until it is forgotten.
     */
    suspend fun retainTransactions(retained: Set<FirstClassByteArray>) {
        val forgotten = retainRejections(retained)
        if (forgotten.isNotEmpty()) migrationMarks.forgetTransactions(forgotten)
        migrationMarks.pruneStale(plannedTransactions().mapTo(mutableSetOf(), migrationMarks::transactionKey))
    }

    /** The transactions with a submit plan, or awaiting one, in this store. */
    @Synchronized
    private fun plannedTransactions(): List<FirstClassByteArray> =
        preferences.all.keys
            .filter { key -> TRANSACTION_FACT_PREFIXES.none { key.startsWith(it) } }
            .mapNotNull { key -> runCatching { FirstClassByteArray(key.fromHex()) }.getOrNull() }

    /**
     * Forgets the rejection, acceptances and migration mark of every transaction not in [retained],
     * such as one past its expiry, and returns the transactions it forgot. The shared [migrationMarks]
     * are left to [retainTransactions].
     */
    @Synchronized
    fun retainRejections(retained: Set<FirstClassByteArray>): Set<FirstClassByteArray> {
        val keep = retained.mapTo(mutableSetOf()) { it.byteArray.toHex() }
        val scope = retentionScope
        retentionScope = null
        val stale =
            preferences.all.keys.filter { key ->
                val prefix = TRANSACTION_FACT_PREFIXES.firstOrNull { key.startsWith(it) }
                prefix != null &&
                    key.removePrefix(prefix).let { transaction ->
                        transaction !in keep && (scope == null || transaction in scope)
                    }
            }
        if (stale.isNotEmpty()) {
            preferences.edit { stale.forEach(::remove) }
            stale.forEach { outcomes.remove(it.substringAfter(":")) }
            publishRejections()
        }
        return stale
            .map { it.substringAfter(":") }
            .distinct()
            .mapNotNullTo(mutableSetOf()) { runCatching { FirstClassByteArray(it.fromHex()) }.getOrNull() }
    }

    /**
     * Starts a scan of the resubmission candidates: the next [retainRejections] forgets only the
     * transactions that had facts now, so that one created while the candidates are read, which they
     * do not include yet, keeps its marks.
     */
    @Synchronized
    fun beginRetentionScan() {
        retentionScope =
            preferences.all.keys
                .mapNotNull { key ->
                    TRANSACTION_FACT_PREFIXES.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) }
                }.toSet()
    }

    private fun publishRejections() {
        rejections.value = loadRejections()
        rejected.value = rejections.value.keys
    }

    private fun loadRejections(): Map<TransactionId, SubmitRejection> =
        preferences.all.entries
            .filter { it.key.startsWith(REJECTED_PREFIX) }
            .mapNotNull { (key, value) ->
                val transactionId =
                    runCatching { TransactionId.new(key.removePrefix(REJECTED_PREFIX).fromHex()) }.getOrNull()
                val rejection = (value as? String)?.let(::decodeRejection)?.toSubmitRejection()
                if (transactionId == null || rejection == null) null else transactionId to rejection
            }.toMap()

    fun markAwaitingSubmission(txId: FirstClassByteArray) {
        val key = txId.byteArray.toHex()
        if (!preferences.contains(key)) {
            preferences.edit { putString(key, "") }
        }
    }

    fun recordSubmitEndpoint(
        txId: FirstClassByteArray,
        endpoint: LightWalletEndpoint
    ) {
        val key = txId.byteArray.toHex()
        val updated = (endpointsFor(txId) + endpoint).distinct()
        preferences.edit { putString(key, updated.joinToString(ENDPOINT_DELIMITER) { it.encode() }) }
    }

    fun endpointsFor(txId: FirstClassByteArray): List<LightWalletEndpoint> {
        val stored = preferences.getString(txId.byteArray.toHex(), null).orEmpty()
        if (stored.isBlank()) return emptyList()
        return stored.split(ENDPOINT_DELIMITER).mapNotNull(::decodeEndpoint)
    }
}
