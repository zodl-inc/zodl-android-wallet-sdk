package com.zodl.slipstream.internal

import com.zodl.slipstream.SlipstreamSynchronizer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [engineMemoryHint]: the main wallet's engine plans with the whole device, a helper wallet's with its share of it,
 * never more, and an unknown device size stays unknown.
 */
class EngineMemoryTest {
    @Test
    fun theMainWalletKeepsTheWholeDevice() {
        assertEquals(EIGHT_GIB, engineMemoryHint(EIGHT_GIB, SlipstreamSynchronizer.FULL_ENGINE_MEMORY))
        assertEquals(TWO_GIB, engineMemoryHint(TWO_GIB, SlipstreamSynchronizer.FULL_ENGINE_MEMORY))
    }

    @Test
    fun aHelperWalletGetsItsShareOfTheDevice() {
        assertEquals(FOUR_GIB, engineMemoryHint(EIGHT_GIB, HALF))
        assertEquals(TWO_GIB, engineMemoryHint(FOUR_GIB, HALF))
    }

    @Test
    fun aHelperWalletNeverGetsMoreThanTheMainWallet() {
        listOf(1L, TWO_GIB, FOUR_GIB, EIGHT_GIB).forEach { device ->
            listOf(0.01f, 0.25f, HALF, 0.99f, 1f).forEach { fraction ->
                val hint = engineMemoryHint(device, fraction)
                assertTrue(hint in 1..engineMemoryHint(device, SlipstreamSynchronizer.FULL_ENGINE_MEMORY))
            }
        }
    }

    @Test
    fun aHelperWalletOnASmallDeviceStaysOnTheSmallBudget() {
        assertTrue(engineMemoryHint(TWO_GIB, HALF) < SMALL_DEVICE_THRESHOLD_BYTES)
        assertTrue(engineMemoryHint(FOUR_GIB, HALF) < SMALL_DEVICE_THRESHOLD_BYTES)
    }

    @Test
    fun anUnknownDeviceSizeStaysUnknown() {
        assertEquals(0L, engineMemoryHint(0L, HALF))
        assertEquals(0L, engineMemoryHint(-1L, SlipstreamSynchronizer.FULL_ENGINE_MEMORY))
    }

    @Test
    fun aFractionOutsideZeroToOneIsRejected() {
        listOf(0f, -0.5f, 1.01f, Float.NaN).forEach { fraction ->
            assertFailsWith<IllegalArgumentException> { engineMemoryHint(EIGHT_GIB, fraction) }
        }
    }

    private companion object {
        const val HALF = 0.5f
        const val TWO_GIB = 2L shl 30
        const val FOUR_GIB = 4L shl 30
        const val EIGHT_GIB = 8L shl 30

        /** The engine's `EngineConfig::SMALL_DEVICE_THRESHOLD_BYTES`. */
        const val SMALL_DEVICE_THRESHOLD_BYTES = 3L shl 30
    }
}
