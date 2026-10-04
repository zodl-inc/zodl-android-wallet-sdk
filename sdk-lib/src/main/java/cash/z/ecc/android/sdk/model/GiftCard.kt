package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.ext.toHex
import cash.z.ecc.android.sdk.internal.GiftCardLinks
import cash.z.ecc.android.sdk.internal.jni.GiftCardLinkException
import cash.z.ecc.android.sdk.internal.jni.RustGiftCardTool
import cash.z.ecc.android.sdk.internal.model.JniGiftCard
import java.security.MessageDigest

/**
 * A gift card: a bearer link that carries the key of a small, single-purpose wallet.
 *
 * The card wallet is ZIP 32 account 0 of a BIP 39 seed carried by the link, and its funds sit
 * at that account's Orchard-only default address. Anyone holding the link (including whoever
 * issued it) can spend those funds, so a card should be redeemed into the user's own wallet
 * with [cash.z.ecc.android.sdk.GiftCardRedeemer].
 *
 * Instances are created only by [parse], which validates the link in the Rust backend. The
 * card's key is never exposed and never appears in [toString].
 *
 * @property origin who issued the link.
 * @property network the network the card is on.
 * @property birthdayHeight the height from which to scan for the card's funds.
 * @property statedAmount the amount the link states, if any. This is informational only: the
 * funds found on chain are authoritative.
 * @property description the link's description or message, if any. This is untrusted text
 * chosen by the issuer.
 */
@Suppress("LongParameterList")
class GiftCard private constructor(
    val origin: GiftCardOrigin,
    val network: ZcashNetwork,
    val birthdayHeight: BlockHeight,
    val statedAmount: Zatoshi?,
    val description: String?,
    internal val seed: GiftCardSeed,
    private val fundingAddress: String
) {
    /**
     * A stable, non-secret identifier of this card, derived by hashing its funding address.
     *
     * Two links for the same card (for example, the same card shared twice) have the same id.
     * It reveals neither the card's key nor its address.
     */
    val id: String by lazy {
        MessageDigest
            .getInstance("SHA-256")
            .digest((ID_DOMAIN + fundingAddress).toByteArray(Charsets.UTF_8))
            .copyOf(ID_BYTES)
            .toHex()
    }

    // Override to prevent leaking the card's key, or the link's free-form text, to logs
    override fun toString() =
        "GiftCard(origin=$origin, network=${network.networkName}, birthdayHeight=${birthdayHeight.value}, " +
            "statedAmount=${statedAmount?.value}, seed=***)"

    companion object {
        private const val ID_DOMAIN = "ZODL gift card id:"
        private const val ID_BYTES = 16

        /**
         * Parses and validates a gift card link.
         *
         * Supports this SDK's own links (`https://gift.zodl.com/#v=1&key=...&height=...`) and
         * the legacy JSON payment-link encoding at `/payment-links/open#vN=` (`v1=`, `v2=`,
         * `v3=` payloads) found in the wild. Parsing is pure: it touches no wallet database and
         * no network.
         *
         * @throws GiftCardException.InvalidLink if the text is not a valid gift card link. Its
         * message and [GiftCardException.InvalidLink.reason] never contain any part of the link.
         */
        suspend fun parse(link: String): GiftCard = parse(link, RustGiftCardTool.new())

        @Suppress("TooGenericExceptionCaught")
        internal fun parse(
            link: String,
            links: GiftCardLinks
        ): GiftCard {
            val jni =
                try {
                    links.parse(link)
                } catch (e: GiftCardLinkException) {
                    throw GiftCardException.InvalidLink(GiftCardLinkError.fromReason(e.reason), e)
                } catch (e: RuntimeException) {
                    throw GiftCardException.InvalidLink(GiftCardLinkError.Unknown, e)
                }
            return fromJni(jni)
        }

        internal fun fromJni(jni: JniGiftCard): GiftCard {
            val origin =
                GiftCardOrigin.entries.getOrNull(jni.origin)
                    ?: throw GiftCardException.InvalidLink(GiftCardLinkError.Unknown)
            val network =
                runCatching { ZcashNetwork.from(jni.networkId) }
                    .getOrElse { throw GiftCardException.InvalidLink(GiftCardLinkError.UnsupportedNetwork) }
            return GiftCard(
                origin = origin,
                network = network,
                birthdayHeight = BlockHeight.new(jni.birthdayHeight),
                statedAmount = jni.amountZatoshi.takeIf { it >= 0 }?.let { Zatoshi(it) },
                description = jni.description,
                seed = GiftCardSeed(jni.seed.copyOf()),
                fundingAddress = jni.fundingAddress
            )
        }
    }
}

/** The encoding a gift card link was read from. The order matches the backend's origin codes. */
enum class GiftCardOrigin {
    /** A `v=1` link in this SDK's own format (`https://gift.zodl.com/#v=1&key=...&height=...`). */
    Zodl,

    /** A `v1=` payload of the legacy JSON payment-link encoding at `/payment-links/open#vN=`. */
    LegacyV1,

    /** A `v2=` payload of the legacy JSON payment-link encoding at `/payment-links/open#vN=`. */
    LegacyV2,

    /** A `v3=` payload of the legacy JSON payment-link encoding at `/payment-links/open#vN=`. */
    LegacyV3
}

/** Why a gift card link was rejected. */
enum class GiftCardLinkError(
    internal val reason: String?
) {
    /** The text is not a gift card link of any supported kind. */
    NotAGiftLink("not_a_gift_link"),

    /** The link is a gift card link of a version this SDK does not read. */
    UnsupportedVersion("unsupported_version"),

    /** A required field is missing. */
    MissingField("missing_field"),

    /** A field appears more than once. */
    DuplicateField("duplicate_field"),

    /** A field is present but malformed or out of range. */
    InvalidField("invalid_field"),

    /** The card is for a network this SDK does not support. */
    UnsupportedNetwork("unsupported_network"),

    /** The card is for a different network than the wallet using it. */
    NetworkMismatch("network_mismatch"),

    /** The link is too long to be a gift card link. */
    TooLong("too_long"),

    /** The card's key could not be derived. */
    KeyDerivation("key_derivation"),

    /** The link was rejected for a reason this SDK version does not recognize. */
    Unknown(null);

    internal companion object {
        fun fromReason(reason: String): GiftCardLinkError = entries.firstOrNull { it.reason == reason } ?: Unknown
    }
}

/**
 * The 64-byte BIP 39 seed of a gift card wallet: spend authority over the card's funds.
 *
 * Never exposed outside the SDK, and redacted in [toString].
 */
internal class GiftCardSeed(
    private val bytes: ByteArray
) {
    init {
        require(bytes.size == SEED_BYTES) { "A gift card seed is $SEED_BYTES bytes" }
    }

    fun copyBytes(): ByteArray = bytes.copyOf()

    // Override to prevent leaking the card's key to logs
    override fun toString() = "GiftCardSeed(***)"

    private companion object {
        const val SEED_BYTES = 64
    }
}
