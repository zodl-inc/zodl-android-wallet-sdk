package cash.z.ecc.android.sdk

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.GiftCardLinkError
import cash.z.ecc.android.sdk.model.GiftCardOrigin
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [GiftCard.parse] through the Rust backend: the link rules a card's holder depends on, end to
 * end across the JNI boundary.
 */
class GiftCardParseTest {
    private val key = "zgift1gfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpyysjzgfpqfg2fuw"

    // A never-funded test card: 32 bytes of 0x42 as its secret.
    private val link = "https://gift.zodl.com/#v=1&key=$key&height=3100000"

    @Test
    @SmallTest
    fun readsAV1Link() =
        runTest {
            val card = GiftCard.parse(link)
            assertEquals(GiftCardOrigin.Zodl, card.origin)
            assertEquals(ZcashNetwork.Mainnet, card.network)
            assertEquals(BlockHeight.new(3_100_000), card.birthdayHeight)
            assertNull(card.statedAmount)
            assertNull(card.description)
        }

    @Test
    @SmallTest
    fun readsAnUpperCaseKeyAsTheSameCard() =
        runTest {
            val lower = GiftCard.parse(link)
            val upper = GiftCard.parse(link.replace(key, key.uppercase()))
            assertEquals(lower.id, upper.id)
            assertContentEquals(lower.seed.copyBytes(), upper.seed.copyBytes())
        }

    @Test
    @SmallTest
    fun aMalformedDescriptionDoesNotRejectTheCard() =
        runTest {
            listOf("100%", "%ZZ", "a".repeat(MAX_DESCRIPTION_BYTES + 1)).forEach { desc ->
                val card = GiftCard.parse("$link&desc=$desc")
                assertNull(card.description, desc)
                assertEquals(GiftCard.parse(link).id, card.id)
            }
        }

    @Test
    @SmallTest
    fun descriptionsAreSanitized() =
        runTest {
            // U+202E (right-to-left override), a line feed and BEL.
            val card = GiftCard.parse("$link&desc=Gift%E2%80%AEmoc.liame%0A%07")
            assertEquals("Giftmoc.liame ", card.description)
        }

    @Test
    @SmallTest
    fun rejectsABirthdayBelowNu5() =
        runTest {
            listOf("1", "419199", "1687103").forEach { height ->
                val thrown =
                    assertFailsWith<GiftCardException.InvalidLink> {
                        GiftCard.parse(link.replace("height=3100000", "height=$height"))
                    }
                assertEquals(GiftCardLinkError.InvalidField, thrown.reason)
            }
        }

    private companion object {
        const val MAX_DESCRIPTION_BYTES = 512
    }
}
