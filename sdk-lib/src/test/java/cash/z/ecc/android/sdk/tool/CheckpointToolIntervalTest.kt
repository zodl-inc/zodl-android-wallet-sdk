package cash.z.ecc.android.sdk.tool

import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckpointToolIntervalTest {
    @Test
    fun interval_before_nu7_is_the_measured_average() {
        assertEquals(52.33f, CheckpointTool.avgIntervalHoursAt(ZcashNetwork.Mainnet, 3_543_404L, 52.33f))
    }

    @Test
    fun interval_from_nu7_is_a_third_of_the_measured_average() {
        assertEquals(52.33f / 3, CheckpointTool.avgIntervalHoursAt(ZcashNetwork.Mainnet, 3_543_405L, 52.33f))
    }

    @Test
    fun testnet_uses_its_own_nu7_height() {
        assertEquals(134.93f, CheckpointTool.avgIntervalHoursAt(ZcashNetwork.Testnet, 4_465_025L, 134.93f))
        assertEquals(134.93f / 3, CheckpointTool.avgIntervalHoursAt(ZcashNetwork.Testnet, 4_465_026L, 134.93f))
    }
}
