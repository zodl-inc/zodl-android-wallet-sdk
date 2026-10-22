package cash.z.ecc.android.sdk.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockSpacingTest {
    private val nu7 = BlockSpacing.nu7ActivationHeight(ZcashNetwork.Mainnet)!!.value
    private val blossom = BlockSpacing.blossomActivationHeight(ZcashNetwork.Mainnet)!!.value

    private fun h(value: Long) = BlockHeight(value)

    @Test
    fun activation_heights_match_zcash_protocol() {
        assertEquals(3_543_405L, nu7)
        assertEquals(653_600L, blossom)
        assertEquals(4_465_026L, BlockSpacing.nu7ActivationHeight(ZcashNetwork.Testnet)!!.value)
        assertEquals(584_000L, BlockSpacing.blossomActivationHeight(ZcashNetwork.Testnet)!!.value)
    }

    @Test
    fun spacing_changes_exactly_at_the_activation_heights() {
        val net = ZcashNetwork.Mainnet
        assertEquals(150L, BlockSpacing.targetSeconds(net, h(blossom - 1)))
        assertEquals(75L, BlockSpacing.targetSeconds(net, h(blossom)))
        assertEquals(75L, BlockSpacing.targetSeconds(net, h(nu7 - 1)))
        assertEquals(25L, BlockSpacing.targetSeconds(net, h(nu7)))
    }

    @Test
    fun between_is_zero_when_to_is_not_above_from() {
        assertEquals(0L, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, h(nu7), h(nu7)))
        assertEquals(0L, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, h(nu7 + 5), h(nu7)))
    }

    @Test
    fun between_before_nu7_is_75_seconds_per_block() {
        assertEquals(75L * 1_000, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, h(nu7 - 2_000), h(nu7 - 1_000)))
    }

    @Test
    fun between_after_nu7_is_25_seconds_per_block() {
        assertEquals(25L * 1_000, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, h(nu7 + 100), h(nu7 + 1_100)))
    }

    @Test
    fun between_splits_a_range_that_spans_nu7_activation() {
        // (nu7-10, nu7+10] holds 9 heights below NU7 (nu7-9 .. nu7-1) and 11 at or above it (nu7 .. nu7+10).
        val from = h(nu7 - 10)
        val to = h(nu7 + 10)
        assertEquals(9L * 75 + 11L * 25, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, from, to))
    }

    @Test
    fun between_starting_at_the_activation_block_counts_only_the_new_spacing_after_it() {
        // (nu7, nu7+3] -> 3 blocks, all at or above NU7.
        assertEquals(3L * 25, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, h(nu7), h(nu7 + 3)))
        // (nu7-1, nu7+1] -> heights nu7 and nu7+1, both at or above NU7.
        assertEquals(2L * 25, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, h(nu7 - 1), h(nu7 + 1)))
    }

    @Test
    fun between_splits_a_range_that_spans_blossom_activation() {
        val from = h(blossom - 3)
        val to = h(blossom + 2)
        // heights blossom-2, blossom-1 are pre-Blossom; blossom, blossom+1, blossom+2 are post.
        assertEquals(2L * 150 + 3L * 75, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, from, to))
    }

    @Test
    fun between_equals_the_sum_of_per_height_spacing() {
        val from = nu7 - 7
        val to = nu7 + 9
        val expected = ((from + 1)..to).sumOf { BlockSpacing.targetSeconds(ZcashNetwork.Mainnet, h(it)) }
        assertEquals(expected, BlockSpacing.targetSecondsBetween(ZcashNetwork.Mainnet, h(from), h(to)))
    }

    @Test
    fun testnet_uses_its_own_nu7_height() {
        val t = BlockSpacing.nu7ActivationHeight(ZcashNetwork.Testnet)!!.value
        assertEquals(75L, BlockSpacing.targetSeconds(ZcashNetwork.Testnet, h(t - 1)))
        assertEquals(25L, BlockSpacing.targetSeconds(ZcashNetwork.Testnet, h(t)))
    }

    @Test
    fun unknown_network_keeps_the_75_second_spacing() {
        val custom = ZcashNetwork(42, "darkside", BlockHeight(1), BlockHeight(2))
        assertNull(BlockSpacing.nu7ActivationHeight(custom))
        assertEquals(75L, BlockSpacing.targetSeconds(custom, h(10)))
        assertEquals(75L * 1_000_000, BlockSpacing.targetSecondsBetween(custom, h(0), h(1_000_000)))
    }
}
