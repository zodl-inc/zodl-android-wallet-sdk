package cash.z.ecc.android.sdk.internal

import org.junit.Test
import kotlin.test.assertEquals

class ChainTipEstimatorTest {
    @Test
    fun `estimateTip floors elapsed over 75s`() {
        // 74*5 = 370 seconds elapsed; 370/75 = 4 (floor); 1000 + 4 = 1004
        assertEquals(1004, estimateTip(1000, 0, 74 * 5))
    }

    @Test
    fun `estimateTip clamps negative elapsed`() {
        // nowEpoch < scannedBlockTime: negative elapsed clamped to 0
        assertEquals(1000, estimateTip(1000, 500, 400))
    }
}

class ChainTipEstimatorNu7Test {
    private val network = cash.z.ecc.android.sdk.model.ZcashNetwork.Mainnet
    private val nu7 = 3_543_405L

    @Test
    fun `without a network the window is measured as before and the fallback is 75s`() {
        assertEquals(75L, secondsPerBlockFromSamples(longArrayOf()))
        assertEquals(75L, secondsPerBlockFromSamples(longArrayOf(1_000L, 5_000L)))
        // 100 blocks over 7_400 s => 74 s/block, even across a hypothetical activation height.
        assertEquals(74L, secondsPerBlockFromSamples(longArrayOf(1_100L, 7_400L, 1_000L, 0L)))
    }

    @Test
    fun `before NU7 the fallback is 75s and a measured window is kept`() {
        assertEquals(75L, secondsPerBlockFromSamples(longArrayOf(nu7 - 1_000), network))
        assertEquals(74L, secondsPerBlockFromSamples(longArrayOf(nu7 - 900, 7_400L, nu7 - 1_000, 0L), network))
    }

    @Test
    fun `from NU7 the fallback is the 25s target`() {
        assertEquals(25L, secondsPerBlockFromSamples(longArrayOf(nu7 + 10), network))
    }

    @Test
    fun `a window that spans NU7 activation is not measured and uses the target at the latest block`() {
        // 100 blocks, 50 before and 50 after activation, over 4_950 s => a meaningless 49 s average.
        val samples = longArrayOf(nu7 + 50, 4_950L, nu7 - 50, 0L)
        assertEquals(25L, secondsPerBlockFromSamples(samples, network))
    }

    @Test
    fun `a window entirely after NU7 activation is measured again`() {
        // 100 blocks over 2_400 s => 24 s/block, fully after activation.
        assertEquals(24L, secondsPerBlockFromSamples(longArrayOf(nu7 + 200, 2_400L, nu7 + 100, 0L), network))
    }

    @Test
    fun `a window that ends exactly before activation is measured`() {
        assertEquals(74L, secondsPerBlockFromSamples(longArrayOf(nu7 - 1, 7_400L, nu7 - 101, 0L), network))
    }

    @Test
    fun `estimateTip stays at one rate while the elapsed time ends before NU7`() {
        // 1,000 blocks before NU7 at 75 s: 10 blocks take 750 s.
        assertEquals(nu7 - 1_000 + 10, estimateTip(nu7 - 1_000, 0L, 750L, 75L, network))
    }

    @Test
    fun `estimateTip converts the time past NU7 at 25 s`() {
        // 10 blocks (nu7 - 11 .. nu7 - 1) take 750 s, then 100 s is 4 blocks at 25 s.
        assertEquals(nu7 - 1 + 4, estimateTip(nu7 - 11, 0L, 850L, 75L, network))
    }

    @Test
    fun `estimateTip from the last block before NU7 converts the time at 25 s`() {
        // The block after nu7 - 1 is the first 25 s block, whatever rate the sample at nu7 - 1 measured.
        assertEquals(nu7 - 1 + 4, estimateTip(nu7 - 1, 0L, 100L, 75L, network))
        assertEquals(nu7 - 1, estimateTip(nu7 - 1, 0L, 24L, 75L, network))
    }

    @Test
    fun `estimateTip after NU7 uses the given rate`() {
        assertEquals(nu7 + 10 + 4, estimateTip(nu7 + 10, 0L, 100L, 25L, network))
    }

    @Test
    fun `estimateTip without a network ignores NU7`() {
        assertEquals(nu7 - 11 + 11, estimateTip(nu7 - 11, 0L, 850L, 75L))
    }

    @Test
    fun `the rate at the estimated tip is the 25s target once the estimated tip is past NU7`() {
        // Last scanned block nu7 - 11 at t = 0, window not measurable (one sample pair), 850 s elapsed.
        val samples = longArrayOf(nu7 - 11, 0L)
        assertEquals(25L, secondsPerBlockAtEstimatedTip(samples, network, 850L))
    }

    @Test
    fun `the rate at the estimated tip stays at the scanned rate while the tip is before NU7`() {
        val samples = longArrayOf(nu7 - 11, 0L)
        assertEquals(75L, secondsPerBlockAtEstimatedTip(samples, network, 600L))
    }
}
