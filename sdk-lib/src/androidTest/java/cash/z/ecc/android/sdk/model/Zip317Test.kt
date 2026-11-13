package cash.z.ecc.android.sdk.model

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.GiftCardRedeemer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * [Zip317] through the Rust backend: the constants come from librustzcash across the JNI boundary.
 */
class Zip317Test {
    @Test
    @SmallTest
    fun marginalFeeIsFiveThousandZatoshi() =
        runTest {
            assertEquals(Zatoshi(5_000), Zip317.marginalFee())
        }

    @Test
    @SmallTest
    fun marginalFeeIsTheSameOnEveryCall() =
        runTest {
            assertEquals(Zip317.marginalFee(), Zip317.marginalFee())
        }

    /** The gift card pre-filter restates `zip317::MINIMUM_FEE`, the marginal fee times two grace actions. */
    @Test
    @SmallTest
    fun giftCardMinimumFeeIsTwoMarginalFees() =
        runTest {
            assertEquals(Zatoshi(2 * Zip317.marginalFee().value), GiftCardRedeemer.MINIMUM_FEE)
        }
}
