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
     * The card has nothing to redeem right now: either it holds no funds (it was never funded,
     * or it has already been redeemed), or its funds are not yet spendable (see
     * [cash.z.ecc.android.sdk.GiftCardRedeemer.Status.Pending]).
     */
    class NothingToRedeem(
        cause: Throwable? = null
    ) : GiftCardException("The gift card has no spendable funds", cause)

    /**
     * The gift card's temporary wallet did not finish syncing: it timed out (for example, the
     * server is unreachable) or stopped on an unrecoverable error, which is the [cause] if known.
     */
    class SyncFailed(
        cause: Throwable?
    ) : GiftCardException("The gift card wallet could not be synced", cause)

    /** The redeemer was used after [cash.z.ecc.android.sdk.GiftCardRedeemer.close]. */
    class Closed : GiftCardException("The gift card redeemer is closed")
}
