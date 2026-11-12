package cash.z.ecc.android.sdk.exception

import cash.z.ecc.android.sdk.GiftCardRedeemer
import cash.z.ecc.android.sdk.model.GiftCardLinkError
import cash.z.ecc.android.sdk.model.TransactionId

/**
 * Failures specific to gift cards. Messages never contain any part of a gift card link, which
 * carries a spending key.
 */
sealed class GiftCardException(
    message: String,
    cause: Throwable? = null
) : SdkException(message, cause) {
    /** The text is not a valid gift card link. */
    class InvalidLink(
        val reason: GiftCardLinkError,
        cause: Throwable? = null
    ) : GiftCardException("Invalid gift card link: $reason", cause)

    /** The card is for a different network than the redeemer was created for. */
    class NetworkMismatch : GiftCardException("The gift card is for a different network")

    /**
     * The card has nothing to redeem right now: it holds no funds (it was never funded, or it
     * has already been redeemed), what it holds does not exceed the ZIP 317 fee a redemption
     * would pay, or its funds are not yet spendable (see
     * [cash.z.ecc.android.sdk.GiftCardRedeemer.Status.Pending]).
     */
    class NothingToRedeem(
        cause: Throwable? = null
    ) : GiftCardException("The gift card has no spendable funds", cause)

    /**
     * The gift card's temporary wallet could not be created or did not finish syncing: creating
     * it failed (for example, no bundled checkpoint covers the card's birthday, or its database
     * could not be opened), it timed out (for example, the server is unreachable), or it stopped
     * on an unrecoverable error. The underlying failure is the [cause], if known.
     */
    class SyncFailed(
        cause: Throwable?
    ) : GiftCardException("The gift card wallet could not be synced", cause)

    /**
     * [cash.z.ecc.android.sdk.GiftCardRedeemer.redeem] was called before a
     * [cash.z.ecc.android.sdk.GiftCardRedeemer.check] on the same redeemer completed. Without it
     * the card's funds are unknown, so nothing is attempted.
     */
    class NotChecked : GiftCardException("Check the gift card before redeeming it")

    /**
     * Another [cash.z.ecc.android.sdk.GiftCardRedeemer] in this process is using the same
     * temporary wallet (the same card, or the same alias). Close that redeemer first.
     */
    class InUse : GiftCardException("Another redeemer is using this gift card's wallet")

    /**
     * [cash.z.ecc.android.sdk.GiftCardRedeemer.redeem] ended without anything to report: the
     * proposal created no transaction, so nothing was sent. Close the redeemer and start over with
     * a new one to retry. A transaction that was created but not accepted by the server is reported
     * in a [cash.z.ecc.android.sdk.GiftCardRedeemer.Redemption] as a failed result instead.
     */
    class RedemptionIncomplete : GiftCardException("The gift card redemption created no transaction")

    /**
     * The card holds more notes than one transaction can spend within the size bound passed to
     * [cash.z.ecc.android.sdk.GiftCardRedeemer.redeem], and redeeming it in several rounds is not
     * available. Nothing more was sent: [sentTxIds] are the transactions of the earlier rounds of a
     * redemption in several rounds, which stand (empty when it failed in its first round). The
     * [cause] is the [TransactionEncoderException.ProposalTooLargeException] naming the exceeded
     * limit.
     */
    class TooManyNotes(
        cause: Throwable? = null,
        val sentTxIds: List<TransactionId> = emptyList()
    ) : GiftCardException("The gift card holds too many notes to redeem in one transaction", cause)

    /**
     * Round [round] of a redemption in several rounds was not accepted by the server; [redemption]
     * holds its results. The rounds before it, whose transactions are [sentTxIds], were sent and
     * stand. The round's notes stay locked until its transactions expire; redeem the card again then
     * to send the rest.
     */
    class RoundFailed(
        val round: Int,
        val redemption: GiftCardRedeemer.Redemption,
        val sentTxIds: List<TransactionId> = emptyList()
    ) : GiftCardException("Round $round of the gift card redemption was not accepted")

    /**
     * The transactions [txIds] of round [round] of a redemption in several rounds were not mined in
     * time, or expired. The rounds before it stand; redeem the card again to send the rest.
     */
    class RoundExpired(
        val round: Int,
        val txIds: List<TransactionId>
    ) : GiftCardException("Round $round of the gift card redemption was not mined in time")

    /**
     * The redeemer was used after [cash.z.ecc.android.sdk.GiftCardRedeemer.close] was called, or
     * after the card's key was wiped with [cash.z.ecc.android.sdk.model.GiftCard.wipe].
     */
    class Closed : GiftCardException("The gift card redeemer is closed")
}
