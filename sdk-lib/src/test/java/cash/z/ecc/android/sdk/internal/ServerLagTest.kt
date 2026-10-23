package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerLagTest {
    private val network = ZcashNetwork.Mainnet
    private val nu7 = 3_543_405L

    @Test
    fun before_nu7_the_limit_is_288_blocks() {
        assertFalse(isServerBehind(network, nu7 - 1_000, nu7 - 1_000 + 287))
        assertTrue(isServerBehind(network, nu7 - 1_000, nu7 - 1_000 + 288))
    }

    @Test
    fun after_nu7_the_limit_is_864_blocks() {
        assertFalse(isServerBehind(network, nu7 + 1_000, nu7 + 1_000 + 863))
        assertTrue(isServerBehind(network, nu7 + 1_000, nu7 + 1_000 + 864))
    }

    @Test
    fun a_range_that_spans_nu7_counts_each_side_at_its_own_spacing() {
        // 100 blocks at 75 s (7,500 s) plus 700 blocks at 25 s (17,500 s) is more than six hours.
        assertTrue(isServerBehind(network, nu7 - 101, nu7 + 700 - 1))
        // 100 blocks at 75 s plus 500 blocks at 25 s is 20,000 s, which is under six hours (21,600 s).
        assertFalse(isServerBehind(network, nu7 - 101, nu7 + 500 - 1))
    }

    @Test
    fun a_server_ahead_of_its_estimate_is_not_behind() {
        assertFalse(isServerBehind(network, nu7 + 10, nu7))
    }
}
