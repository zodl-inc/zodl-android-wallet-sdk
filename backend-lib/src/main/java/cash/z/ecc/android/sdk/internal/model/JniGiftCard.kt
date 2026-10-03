package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep

/**
 * A gift card read from a gift card link by the Rust backend.
 *
 * Constructed from native code: the class name and the
 * `(Int, Int, Long, Long, String?, ByteArray, String)` constructor signature are part of the JNI
 * contract with `gift_card.rs`, and are never referenced from bytecode.
 *
 * @param origin who issued the link: 0 for this SDK's own format, 1 to 3 for Vizor v1 to v3.
 * @param networkId the [network id][cash.z.ecc.android.sdk.internal.Backend.networkId] of the card.
 * @param birthdayHeight the height from which to scan for the card's funds.
 * @param amountZatoshi the amount the link states, or -1 if it states none.
 * @param description the link's description or message, if any.
 * @param seed the 64-byte BIP 39 seed of the card wallet. This is spend authority over the card.
 * @param fundingAddress the Orchard-only unified address the card is funded at.
 */
@Keep
@Suppress("LongParameterList")
class JniGiftCard(
    val origin: Int,
    val networkId: Int,
    val birthdayHeight: Long,
    val amountZatoshi: Long,
    val description: String?,
    val seed: ByteArray,
    val fundingAddress: String
) {
    // Override to prevent leaking the card's key to logs
    override fun toString() =
        "JniGiftCard(origin=$origin, networkId=$networkId, birthdayHeight=$birthdayHeight, " +
            "amountZatoshi=$amountZatoshi, seed=***)"
}
