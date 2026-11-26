package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.model.ConfirmationsPolicy
import cash.z.ecc.android.sdk.internal.model.DbTransactionOverview
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository

/**
 * High level transaction information, suitable for mapping to a display of transaction history.
 *
 * Note that both sent and received transactions will have a positive net value.  Consumers of this class must check
 * [isSentTransaction] if displaying negative values is desired.
 *
 * Pending transactions are identified by a null [minedHeight].  Pending transactions are considered expired if the
 * last synced block exceeds the [expiryHeight].
 */
data class TransactionOverview(
    val txId: TransactionId,
    val minedHeight: BlockHeight?,
    val expiryHeight: BlockHeight?,
    val index: Long?,
    val raw: FirstClassByteArray?,
    val isSentTransaction: Boolean,
    val netValue: Zatoshi,
    val totalSpent: Zatoshi,
    val totalReceived: Zatoshi,
    val feePaid: Zatoshi?,
    val isChange: Boolean,
    val receivedNoteCount: Int,
    val sentNoteCount: Int,
    val memoCount: Int,
    val blockTimeEpochSeconds: Long?,
    val transactionState: TransactionState,
    val isShielding: Boolean,
    /** Number of the account's own notes this transaction spent. */
    val spentNoteCount: Int,
    /**
     * The value that crossed shielded pools when this transaction is a
     * wallet-internal transfer between them, such as an Orchard to Ironwood
     * migration; `null` when it is not such a transfer.
     *
     * For such a transaction [netValue] is just the fee, so this is the amount
     * to present to a user rather than the balance delta.
     */
    val poolCrossingValue: Zatoshi?,
    /**
     * Whether this transaction is considered trusted, meaning its outputs are
     * spendable after the trusted confirmation count rather than the untrusted
     * one.
     */
    val isTrusted: Boolean,
    /**
     * How this transaction classifies against ZIP 318, the Orchard to Ironwood pool migration.
     *
     * [Zip318Kind.NOT_CLASSIFIED] means the wallet has not looked, not that the transaction is
     * not a migration, so it warrants no label at all.
     */
    val zip318Kind: Zip318Kind
) {
    override fun toString() = "TransactionOverview"

    companion object {
        internal fun new(
            dbTransactionOverview: DbTransactionOverview,
            latestBlockHeight: BlockHeight?
        ): TransactionOverview =
            TransactionOverview(
                txId = TransactionId(dbTransactionOverview.rawId),
                minedHeight = dbTransactionOverview.minedHeight,
                expiryHeight = dbTransactionOverview.expiryHeight,
                index = dbTransactionOverview.index,
                raw = dbTransactionOverview.raw,
                isSentTransaction = dbTransactionOverview.isSentTransaction,
                netValue = dbTransactionOverview.netValue,
                feePaid = dbTransactionOverview.feePaid,
                isChange = dbTransactionOverview.isChange,
                receivedNoteCount = dbTransactionOverview.receivedNoteCount,
                sentNoteCount = dbTransactionOverview.sentNoteCount,
                memoCount = dbTransactionOverview.memoCount,
                blockTimeEpochSeconds = dbTransactionOverview.blockTimeEpochSeconds,
                transactionState =
                    TransactionState.new(
                        latestBlockHeight = latestBlockHeight,
                        minedHeight = dbTransactionOverview.minedHeight,
                        expiryHeight = dbTransactionOverview.expiryHeight,
                        isExpiredUnmined = dbTransactionOverview.isExpiredUnmined,
                        requiredConfirmations =
                            ConfirmationsPolicy.requiredConfirmations(
                                isSentTransaction = dbTransactionOverview.isSentTransaction,
                                isTrusted = dbTransactionOverview.isTrusted
                            )
                    ),
                isShielding = dbTransactionOverview.isShielding,
                totalSpent = dbTransactionOverview.totalSpent,
                totalReceived = dbTransactionOverview.totalReceived,
                spentNoteCount = dbTransactionOverview.spentNoteCount,
                poolCrossingValue = dbTransactionOverview.poolCrossingValue,
                isTrusted = dbTransactionOverview.isTrusted,
                zip318Kind = dbTransactionOverview.zip318Kind
            )
    }

    private fun isExpired() = expiryHeight != null && TransactionState.Expired == transactionState

    internal suspend fun checkAndFillInTime(storage: DerivedDataRepository): TransactionOverview =
        if (isExpired() && blockTimeEpochSeconds == null) {
            Twig.debug { "Expired transaction ${txId.txIdString()} - going to find its time" }
            storage.findBlockByHeight(expiryHeight!!)?.run {
                Twig.debug { "Expired transaction ${txId.txIdString()} - time found: $blockTimeEpochSeconds" }
                this@TransactionOverview.copy(blockTimeEpochSeconds = blockTimeEpochSeconds)
            } ?: this
        } else {
            this
        }
}

enum class TransactionState {
    Confirmed,
    Pending,
    Expired;

    companion object {
        /**
         * A mined transaction is [Confirmed] once it has [requiredConfirmations] confirmations;
         * callers take that from [ConfirmationsPolicy.requiredConfirmations]: the trusted count
         * for a received transaction the wallet trusts, the untrusted count otherwise.
         *
         * An unmined transaction is [Expired] exactly when its expiry height, the last height at
         * which it can be mined, is known and non-zero (0 disables expiry) and [scannedHeight] has
         * reached it; otherwise it is [Pending]. A chain tip past expiry alone does not prove the
         * transaction was never mined, because the wallet may not have scanned the block that
         * mined it yet. [scannedHeight] defaults to [latestBlockHeight] for callers whose height
         * already reflects what the wallet has scanned; `null` keeps an unmined transaction
         * [Pending].
         *
         * [isExpiredUnmined] is never proof of expiry on its own. The database computes it against
         * the highest scanned block, which can sit above an unscanned gap: the scanner covers the
         * chain-tip range before older ranges, so the flag can turn true while the block that
         * mined the transaction is still waiting to be scanned. A true flag therefore yields
         * [Expired] only when [scannedHeight] has reached the expiry height, which the rule above
         * already decides; with an unknown expiry height or an unknown [scannedHeight] the
         * transaction stays [Pending], and a mined transaction ignores the flag. On the legacy
         * path, where [scannedHeight] is the chain tip and the tip is at or above the highest
         * scanned block, a true flag still means [Expired].
         *
         * A mined transaction counts its confirmations from [latestBlockHeight], one for a
         * transaction mined in the latest block; with an unknown [latestBlockHeight] it is
         * [Pending], because the status will change as the wallet syncs.
         */
        @Suppress("LongParameterList", "UnusedParameter")
        internal fun new(
            latestBlockHeight: BlockHeight?,
            minedHeight: BlockHeight?,
            expiryHeight: BlockHeight?,
            isExpiredUnmined: Boolean?,
            requiredConfirmations: Int,
            scannedHeight: BlockHeight? = latestBlockHeight
        ): TransactionState {
            if (minedHeight == null) {
                val isExpired =
                    expiryHeight != null &&
                        expiryHeight.value != 0L &&
                        scannedHeight != null &&
                        expiryHeight <= scannedHeight
                return if (isExpired) Expired else Pending
            }

            return latestBlockHeight?.let { chainTip ->
                if ((chainTip + 1 - minedHeight) >= requiredConfirmations) {
                    Confirmed
                } else {
                    Pending
                }
            } ?: Pending
        }
    }
}
