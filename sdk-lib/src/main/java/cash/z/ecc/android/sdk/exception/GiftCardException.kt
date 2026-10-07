package cash.z.ecc.android.sdk.exception

import cash.z.ecc.android.sdk.model.GiftCardLinkError

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
     * proposal created no transaction, or no transaction was submitted. Nothing was sent when no
     * transaction was created; close the redeemer and start over with a new one to retry.
     */
    class RedemptionIncomplete : GiftCardException("The gift card redemption created or submitted no transaction")

    /** The redeemer was used after [cash.z.ecc.android.sdk.GiftCardRedeemer.close]. */
    class Closed : GiftCardException("The gift card redeemer is closed")
}
