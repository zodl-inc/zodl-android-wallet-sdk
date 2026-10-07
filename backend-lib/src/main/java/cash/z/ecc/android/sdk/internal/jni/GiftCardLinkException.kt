package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep

/**
 * Thrown across the JNI boundary when a gift card link is rejected.
 *
 * [reason] is one of the fixed categories reported by `gift_card.rs` (`not_a_gift_link`,
 * `unsupported_version`, `missing_field`, `duplicate_field`, `invalid_field`,
 * `unsupported_network`, `network_mismatch`, `too_long`, `key_derivation`). Neither it nor the
 * message ever contains any part of the link, which carries a spending key.
 *
 * Constructed from native code, which is why it must be kept: the class name and the
 * `(String)` constructor signature are part of the JNI contract with `gift_card.rs`.
 */
@Keep
class GiftCardLinkException(
    val reason: String
) : RuntimeException("invalid gift card link: $reason")
