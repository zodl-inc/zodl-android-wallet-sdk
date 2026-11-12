package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionSizeBound
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi

/**
 * The selection a [GiftCardRedeemer.redeem] of several rounds needs: a proposal sending the most of
 * the card wallet's spendable notes that fits within a [TransactionSizeBound], and what it leaves for
 * the next round.
 *
 * The backend cannot select that yet: its send-max either spends every spendable note or fails. Until
 * librustzcash ships that selection (zcash/librustzcash#3131), [AVAILABLE] is `null` and a card over the
 * bound fails with [cash.z.ecc.android.sdk.exception.GiftCardException.TooManyNotes], having sent nothing:
 * redemption in several rounds is gated off. Once it ships, [AVAILABLE] becomes the implementation that
 * calls it, and the redeemer's rounds need no other change.
 */
internal fun interface SendWhatFits {
    suspend fun propose(
        cardWallet: Synchronizer,
        account: Account,
        toAddress: RecipientAddress,
        memo: MemoContent?,
        sizeBound: TransactionSizeBound
    ): BoundedSendMax

    companion object {
        /** The implementation the redeemers created through the public API use: none yet. */
        val AVAILABLE: SendWhatFits? = null
    }
}

/**
 * One round's proposal, and what the card holds for the rounds after it ([GiftCardRedeemer.Remainder.NONE]
 * when the proposal sends everything).
 */
internal data class BoundedSendMax(
    val proposal: Proposal,
    val remainder: GiftCardRedeemer.Remainder
)

/** The redemption of every round, in order, as one. */
internal fun List<GiftCardRedeemer.Redemption>.combined(): GiftCardRedeemer.Redemption =
    singleOrNull() ?: GiftCardRedeemer.Redemption(
        fee = Zatoshi(sumOf { it.fee.value }),
        results = flatMap { it.results },
        recordedInDestination = all { it.recordedInDestination },
        amount =
            mapNotNull { it.amount }
                .takeIf { it.isNotEmpty() }
                ?.let { amounts -> Zatoshi(amounts.sumOf { it.value }) },
        rounds = size
    )

/**
 * Whether the card wallet's transactions of a round are settled: `true` once all of [wanted] are mined, `false` as
 * soon as one has expired unmined, `null` while neither.
 */
internal fun roundOutcome(
    transactions: List<TransactionOverview>,
    wanted: Set<TransactionId>
): Boolean? {
    val ours = transactions.filter { it.txId in wanted }
    return when {
        ours.any { it.transactionState == TransactionState.Expired } -> false
        ours.map { it.txId }.toSet() == wanted && ours.all { it.minedHeight != null } -> true
        else -> null
    }
}
