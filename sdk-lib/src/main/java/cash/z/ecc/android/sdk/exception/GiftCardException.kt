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
}
