package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.JniGiftCard

/** Internal bridge for Rust-backed gift card link parsing. */
interface GiftCardLinks {
    /**
     * Parses a gift card link. Pure: touches no wallet database and no network.
     *
     * @throws cash.z.ecc.android.sdk.internal.jni.GiftCardLinkException if the link is rejected
     */
    fun parse(link: String): JniGiftCard
}
