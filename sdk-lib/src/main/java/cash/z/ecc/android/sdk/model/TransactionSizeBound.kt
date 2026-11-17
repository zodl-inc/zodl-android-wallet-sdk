package cash.z.ecc.android.sdk.model

/**
 * The limits each transaction of a proposal must stay within, below what consensus allows.
 *
 * Consensus alone ([CONSENSUS], every cap `null`) lets a transaction be as large as a block. A
 * wallet may need less: a relay may refuse transactions above its own mempool limit, proving a
 * transaction on the device takes memory in proportion to its shielded actions
 * ([forProvingMemory]), and an external signer may accept only so many inputs or outputs.
 * [intersect] combines such bounds into one that satisfies them all.
 *
 * Pass a bound to `Synchronizer.proposeTransfer`, `proposeSendMax`, `proposeFulfillingPaymentUri`,
 * `createProposedTransactions` and `createPcztFromProposal`. A proposal that exceeds it fails with
 * [cash.z.ecc.android.sdk.exception.TransactionEncoderException.ProposalTooLargeException], whose
 * `violation` names the exceeded cap ([SizeBoundViolation]); nothing is selected or built short of
 * the bound, so a Send Max over it fails rather than sending less. Shielding and the Orchard to
 * Ironwood migration are never bounded.
 *
 * The counts are those of the transaction as it will be built, and are computed by the Rust
 * backend: shielded actions include the padding the builder adds (the ZIP 218 measure), and the
 * byte size is the backend's conservative estimate of the serialized transaction.
 *
 * @property maxBytes the largest estimated serialized size of a transaction; `null`, or a value
 * above a block, is a block.
 * @property maxShieldedActions the most Sapling spends and outputs plus Orchard and Ironwood actions.
 * @property maxSaplingActions the most Sapling spends plus outputs; `0` forbids the Sapling pool.
 * @property maxOrchardActions the most Orchard actions; `0` forbids the Orchard pool.
 * @property maxIronwoodActions the most Ironwood actions; `0` forbids the Ironwood pool.
 * @property maxTransparentInputs the most transparent inputs.
 * @property maxTransparentOutputs the most transparent outputs, change included.
 * @property maxShieldedPaymentOutputs the most shielded outputs that pay someone, as opposed to the
 * wallet's own change.
 */
data class TransactionSizeBound(
    val maxBytes: Int? = null,
    val maxShieldedActions: Int? = null,
    val maxSaplingActions: Int? = null,
    val maxOrchardActions: Int? = null,
    val maxIronwoodActions: Int? = null,
    val maxTransparentInputs: Int? = null,
    val maxTransparentOutputs: Int? = null,
    val maxShieldedPaymentOutputs: Int? = null,
) {
    init {
        require(caps().all { it == null || it >= 0 }) { "A transaction size bound cap must not be negative" }
    }

    /** The cap on [pool]'s actions, or `null` when there is none. */
    fun maxPoolActions(pool: TransactionPool): Int? =
        when (pool) {
            TransactionPool.SAPLING -> maxSaplingActions
            TransactionPool.ORCHARD -> maxOrchardActions
            TransactionPool.IRONWOOD -> maxIronwoodActions
            TransactionPool.TRANSPARENT -> null
        }

    /** The component-wise minimum of this bound and [other]: a transaction within it is within both. */
    fun intersect(other: TransactionSizeBound): TransactionSizeBound =
        TransactionSizeBound(
            maxBytes = minCap(maxBytes, other.maxBytes),
            maxShieldedActions = minCap(maxShieldedActions, other.maxShieldedActions),
            maxSaplingActions = minCap(maxSaplingActions, other.maxSaplingActions),
            maxOrchardActions = minCap(maxOrchardActions, other.maxOrchardActions),
            maxIronwoodActions = minCap(maxIronwoodActions, other.maxIronwoodActions),
            maxTransparentInputs = minCap(maxTransparentInputs, other.maxTransparentInputs),
            maxTransparentOutputs = minCap(maxTransparentOutputs, other.maxTransparentOutputs),
            maxShieldedPaymentOutputs = minCap(maxShieldedPaymentOutputs, other.maxShieldedPaymentOutputs)
        )

    /** The caps in the order the backend reads them (see [toBackendEncoding]). */
    internal fun caps(): List<Int?> =
        listOf(
            maxBytes,
            maxShieldedActions,
            maxSaplingActions,
            maxOrchardActions,
            maxIronwoodActions,
            maxTransparentInputs,
            maxTransparentOutputs,
            maxShieldedPaymentOutputs
        )

    companion object {
        /** Consensus only: a transaction as large as a block, and no cap on any count. */
        val CONSENSUS = TransactionSizeBound()

        /** The memory proving takes regardless of the transaction, measured on Android devices. */
        const val PROVING_BASELINE_BYTES: Long = 550L * 1024 * 1024

        /** The memory proving takes for each shielded action, measured on Android devices. */
        const val PROVING_BYTES_PER_ACTION: Long = 20L * 1024 * 1024

        /**
         * The most shielded actions [forProvingMemory] allows however much memory is available:
         * about what a relay's 250 kB transaction limit admits.
         */
        const val MAX_PROVING_ACTIONS: Int = 75

        /**
         * The fewest shielded actions [forProvingMemory] allows however little memory is reported:
         * a device that runs the wallet at all proves this many. An app that needs a lower cap
         * intersects one deliberately.
         */
        const val MIN_PROVING_ACTIONS: Int = 16

        /**
         * The bound on shielded actions under which proving a transaction fits in [availableBytes]
         * of memory: `(availableBytes - PROVING_BASELINE_BYTES) / PROVING_BYTES_PER_ACTION`,
         * rounded down, at most [MAX_PROVING_ACTIONS] and at least [MIN_PROVING_ACTIONS].
         *
         * This is the only sizing policy in the SDK. Which memory figure to pass (total RAM, the
         * share the OS lets the app use, a per-device profile) is the app's decision.
         */
        fun forProvingMemory(availableBytes: Long): TransactionSizeBound {
            val actions =
                ((availableBytes - PROVING_BASELINE_BYTES) / PROVING_BYTES_PER_ACTION)
                    .coerceIn(MIN_PROVING_ACTIONS.toLong(), MAX_PROVING_ACTIONS.toLong())
            return TransactionSizeBound(maxShieldedActions = actions.toInt())
        }

        private fun minCap(
            a: Int?,
            b: Int?
        ): Int? =
            when {
                a == null -> b
                b == null -> a
                else -> minOf(a, b)
            }
    }
}

/** "No cap", as the backend reads a [TransactionSizeBound]. */
private const val NO_CAP = -1L

/**
 * This bound as the Rust backend reads it: `null` for [TransactionSizeBound.CONSENSUS], otherwise
 * every cap in [TransactionSizeBound.caps] order, `-1` meaning none.
 */
internal fun TransactionSizeBound.toBackendEncoding(): LongArray? =
    if (this == TransactionSizeBound.CONSENSUS) {
        null
    } else {
        caps().map { it?.toLong() ?: NO_CAP }.toLongArray()
    }

/**
 * The cap of a [TransactionSizeBound] a transaction exceeds.
 *
 * @property dimension which cap.
 * @property pool the pool whose cap is exceeded, for [Dimension.POOL_ACTIONS]; `null` otherwise.
 * @property count the transaction's count.
 * @property limit the cap it exceeds.
 */
data class SizeBoundViolation(
    val dimension: Dimension,
    val pool: TransactionPool?,
    val count: Long,
    val limit: Long
) {
    /** The caps of a [TransactionSizeBound] other than its byte limit. */
    enum class Dimension {
        /** [TransactionSizeBound.maxShieldedActions]. */
        SHIELDED_ACTIONS,

        /** One pool's cap: [TransactionSizeBound.maxPoolActions]. */
        POOL_ACTIONS,

        /** [TransactionSizeBound.maxTransparentInputs]. */
        TRANSPARENT_INPUTS,

        /** [TransactionSizeBound.maxTransparentOutputs]. */
        TRANSPARENT_OUTPUTS,

        /** [TransactionSizeBound.maxShieldedPaymentOutputs]. */
        SHIELDED_PAYMENT_OUTPUTS
    }
}
