package cash.z.ecc.android.sdk.model

/**
 * Height-aware target spacing between blocks, as defined by ZIP 208 and ZIP 218. It mirrors
 * `pow_target_spacing` and `target_time_between` in `zcash_protocol`.
 *
 * The spacing is 150 s before Blossom, 75 s from Blossom until NU7 and 25 s from NU7. Any conversion between
 * a number of blocks and a duration must go through this object, because one height range can span the NU7
 * activation height.
 *
 * Networks other than [ZcashNetwork.Mainnet] and [ZcashNetwork.Testnet] (for example the darkside network) have no
 * known Blossom or NU7 heights. They are treated as post-Blossom and pre-NU7, which is the 75 s spacing.
 */
@Suppress("MagicNumber")
object BlockSpacing {
    const val PRE_BLOSSOM_SECONDS = 150L
    const val POST_BLOSSOM_SECONDS = 75L
    const val POST_NU7_SECONDS = 25L

    /**
     * The factor by which NU7 divides the target spacing.
     */
    const val NU7_RATIO = POST_BLOSSOM_SECONDS / POST_NU7_SECONDS

    private const val MAINNET_BLOSSOM_HEIGHT = 653_600L
    private const val TESTNET_BLOSSOM_HEIGHT = 584_000L
    private const val MAINNET_NU7_HEIGHT = 3_543_405L
    private const val TESTNET_NU7_HEIGHT = 4_465_026L

    /**
     * The Blossom activation height of [network], or null if it is not a known network.
     */
    fun blossomActivationHeight(network: ZcashNetwork): BlockHeight? =
        when (network.id) {
            ZcashNetwork.ID_MAINNET -> BlockHeight(MAINNET_BLOSSOM_HEIGHT)
            ZcashNetwork.ID_TESTNET -> BlockHeight(TESTNET_BLOSSOM_HEIGHT)
            else -> null
        }

    /**
     * The NU7 activation height of [network], or null if it is not a known network.
     */
    fun nu7ActivationHeight(network: ZcashNetwork): BlockHeight? =
        when (network.id) {
            ZcashNetwork.ID_MAINNET -> BlockHeight(MAINNET_NU7_HEIGHT)
            ZcashNetwork.ID_TESTNET -> BlockHeight(TESTNET_NU7_HEIGHT)
            else -> null
        }

    /**
     * The target spacing, in seconds, between the block at [height] and its parent.
     */
    fun targetSeconds(
        network: ZcashNetwork,
        height: BlockHeight
    ): Long {
        val blossom = blossomActivationHeight(network)?.value
        val nu7 = nu7ActivationHeight(network)?.value
        return when {
            blossom != null && height.value < blossom -> PRE_BLOSSOM_SECONDS
            nu7 != null && height.value >= nu7 -> POST_NU7_SECONDS
            else -> POST_BLOSSOM_SECONDS
        }
    }

    /**
     * The target time, in seconds, from the block at [from] to the block at [to]: the sum of [targetSeconds]
     * over the heights in `(from, to]`. Zero if [to] is not above [from].
     */
    fun targetSecondsBetween(
        network: ZcashNetwork,
        from: BlockHeight,
        to: BlockHeight
    ): Long {
        if (to.value <= from.value) return 0L
        val blossom = blossomActivationHeight(network)?.value
        val nu7 = nu7ActivationHeight(network)?.value
        // Heights in (from, to] at or above `start`; zero if `start` is null.
        fun countAtOrAbove(start: Long?): Long {
            if (start == null) return 0L
            val first = maxOf(start, from.value + 1)
            return if (first > to.value) 0L else to.value - first + 1
        }

        val all = to.value - from.value
        val postBlossomOrLater = if (blossom == null) all else countAtOrAbove(blossom)
        val postNu7 = if (nu7 == null) 0L else countAtOrAbove(maxOf(nu7, blossom ?: nu7))
        return (all - postBlossomOrLater) * PRE_BLOSSOM_SECONDS +
            (postBlossomOrLater - postNu7) * POST_BLOSSOM_SECONDS +
            postNu7 * POST_NU7_SECONDS
    }
}
