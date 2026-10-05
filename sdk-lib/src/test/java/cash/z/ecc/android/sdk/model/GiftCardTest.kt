package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.ext.toHex
import cash.z.ecc.android.sdk.internal.GiftCardLinks
import cash.z.ecc.android.sdk.internal.jni.GiftCardLinkException
import cash.z.ecc.android.sdk.internal.model.JniGiftCard
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Kotlin half of gift card parsing. The parsing itself is Rust's (covered by
 * `liberated_payment.rs` and `gift_card.rs` tests); these pin the mapping of its result and
 * errors, and that nothing secret leaks through `toString` or exception messages.
 */
class GiftCardTest {
    private val seed = ByteArray(64) { (it * 7 + 3).toByte() }

    private fun jniCard(
        origin: Int = 0,
        networkId: Int = ZcashNetwork.ID_MAINNET,
        amount: Long = 1_000_000,
        description: String? = "Hi from Zcash Summit!",
        fundingAddress: String = "u1fundingaddress"
    ) = JniGiftCard(origin, networkId, 3_483_141, amount, description, seed.copyOf(), fundingAddress)

    private fun links(result: () -> JniGiftCard) =
        object : GiftCardLinks {
            override fun parse(link: String): JniGiftCard = result()
        }

    @Test
    fun mapsTheParsedCard() {
        val card = GiftCard.parse("link", links { jniCard() })
        assertEquals(GiftCardOrigin.Zodl, card.origin)
        assertEquals(ZcashNetwork.Mainnet, card.network)
        assertEquals(BlockHeight.new(3_483_141), card.birthdayHeight)
        assertEquals(Zatoshi(1_000_000), card.statedAmount)
        assertEquals("Hi from Zcash Summit!", card.description)
        assertContentEquals(seed, card.seed.copyBytes())
    }

    @Test
    fun mapsAbsentOptionalFields() {
        val card = GiftCard.parse("link", links { jniCard(origin = 3, networkId = 0, amount = -1, description = null) })
        assertEquals(GiftCardOrigin.LegacyV3, card.origin)
        assertEquals(ZcashNetwork.Testnet, card.network)
        assertNull(card.statedAmount)
        assertNull(card.description)
    }

    @Test
    fun originCodesMatchTheBackend() {
        // The backend's `origin_code` reports these integers; the enum order must match it.
        assertEquals(GiftCardOrigin.Zodl, GiftCard.parse("link", links { jniCard(origin = 0) }).origin)
        assertEquals(GiftCardOrigin.LegacyV1, GiftCard.parse("link", links { jniCard(origin = 1) }).origin)
        assertEquals(GiftCardOrigin.LegacyV2, GiftCard.parse("link", links { jniCard(origin = 2) }).origin)
        assertEquals(GiftCardOrigin.LegacyV3, GiftCard.parse("link", links { jniCard(origin = 3) }).origin)
    }

    @Test
    fun toStringRedactsTheSeedAndDescription() {
        val card = GiftCard.parse("link", links { jniCard() })
        val text = card.toString() + card.seed.toString()
        assertFalse(text.contains(seed.toHex()))
        assertFalse(text.contains("Summit"))
        assertFalse(text.contains("fundingaddress"))
        assertFalse(card.id.contains("fundingaddress"))
    }

    @Test
    fun idIsStableAndSeparatesCards() {
        val a = GiftCard.parse("link", links { jniCard() })
        val b = GiftCard.parse("link", links { jniCard(description = null) })
        val c = GiftCard.parse("link", links { jniCard(fundingAddress = "u1other") })
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
        assertEquals(32, a.id.length)
    }

    @Test
    fun mapsRejectionReasons() {
        GiftCardLinkError.entries.filter { it != GiftCardLinkError.Unknown }.forEach { error ->
            val cause = GiftCardLinkException(error.reason!!)
            val thrown =
                assertFailsWith<GiftCardException.InvalidLink> {
                    GiftCard.parse("secret link", links { throw cause })
                }
            assertEquals(error, thrown.reason)
            assertSame(cause, thrown.cause)
            assertFalse(thrown.message.orEmpty().contains("secret link"))
        }
    }

    @Test
    fun mapsUnrecognizedFailuresToUnknown() {
        val thrown =
            assertFailsWith<GiftCardException.InvalidLink> {
                GiftCard.parse("secret link", links { throw GiftCardLinkException("something_new") })
            }
        assertEquals(GiftCardLinkError.Unknown, thrown.reason)

        val generic =
            assertFailsWith<GiftCardException.InvalidLink> {
                GiftCard.parse("secret link", links { throw IllegalStateException("boom") })
            }
        assertEquals(GiftCardLinkError.Unknown, generic.reason)
    }

    @Test
    fun rejectsUnknownOriginOrNetwork() {
        assertFailsWith<GiftCardException.InvalidLink> { GiftCard.parse("link", links { jniCard(origin = 9) }) }
        assertFailsWith<GiftCardException.InvalidLink> { GiftCard.parse("link", links { jniCard(networkId = 7) }) }
    }

    @Test
    fun wipesTheBackendsSeedCopyOnceTheCardHoldsItsOwn() {
        val jni = jniCard()
        val card = GiftCard.parse("link", links { jni })
        assertTrue(jni.seed.all { it == 0.toByte() })
        assertContentEquals(seed, card.seed.copyBytes())
    }

    @Test
    fun wipesTheBackendsSeedCopyWhenTheCardIsRejected() {
        val jni = jniCard(origin = 9)
        assertFailsWith<GiftCardException.InvalidLink> { GiftCard.parse("link", links { jni }) }
        assertTrue(jni.seed.all { it == 0.toByte() })
    }

    @Test
    fun exposesTheBackendsSanitizedDescriptionAsIs() {
        // Sanitizing is the backend's job (see `gift_card.rs`); the card must not alter or drop
        // what it reports, including a trailing space left where a line break was.
        val sanitized = "Giftmoc.liame "
        assertEquals(sanitized, GiftCard.parse("link", links { jniCard(description = sanitized) }).description)
        assertNull(GiftCard.parse("link", links { jniCard(description = null) }).description)
    }

    @Test
    fun jniCardToStringRedactsTheSeed() {
        assertFalse(jniCard().toString().contains(seed.toHex()))
        assertFalse(jniCard().toString().contains("fundingaddress"))
    }
}
