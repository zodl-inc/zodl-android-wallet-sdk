package cash.z.ecc.android.sdk.model

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TransactionSizeBoundTest {
    @Test
    fun consensus_caps_nothing_and_crosses_jni_as_null() {
        assertEquals(TransactionSizeBound(), TransactionSizeBound.CONSENSUS)
        assertNull(TransactionSizeBound.CONSENSUS.toBackendEncoding())
        TransactionPool.entries.forEach { assertNull(TransactionSizeBound.CONSENSUS.maxPoolActions(it)) }
    }

    @Test
    fun the_backend_encoding_is_every_cap_in_its_fixed_order_with_minus_one_for_none() {
        val bound =
            TransactionSizeBound(
                maxBytes = 250_000,
                maxShieldedActions = 75,
                maxSaplingActions = 0,
                maxOrchardActions = 32,
                maxIronwoodActions = 31,
                maxTransparentInputs = 10,
                maxTransparentOutputs = null,
                maxShieldedPaymentOutputs = 4
            )

        assertContentEquals(longArrayOf(250_000, 75, 0, 32, 31, 10, -1, 4), bound.toBackendEncoding())
    }

    @Test
    fun pool_caps_are_read_per_pool_and_transparent_has_none() {
        val bound = TransactionSizeBound(maxSaplingActions = 1, maxOrchardActions = 2, maxIronwoodActions = 3)

        assertEquals(1, bound.maxPoolActions(TransactionPool.SAPLING))
        assertEquals(2, bound.maxPoolActions(TransactionPool.ORCHARD))
        assertEquals(3, bound.maxPoolActions(TransactionPool.IRONWOOD))
        assertNull(bound.maxPoolActions(TransactionPool.TRANSPARENT))
    }

    @Test
    fun intersect_takes_the_stricter_of_each_cap() {
        val relay = TransactionSizeBound(maxBytes = 250_000, maxOrchardActions = 40)
        val signer = TransactionSizeBound(maxShieldedActions = 48, maxOrchardActions = 10, maxTransparentInputs = 10)

        val both = relay.intersect(signer)

        assertEquals(both, signer.intersect(relay))
        assertEquals(
            TransactionSizeBound(
                maxBytes = 250_000,
                maxShieldedActions = 48,
                maxOrchardActions = 10,
                maxTransparentInputs = 10
            ),
            both
        )
        assertEquals(relay, relay.intersect(TransactionSizeBound.CONSENSUS))
    }

    @Test
    fun a_negative_cap_is_refused() {
        assertFailsWith<IllegalArgumentException> { TransactionSizeBound(maxShieldedActions = -1) }
        assertFailsWith<IllegalArgumentException> { TransactionSizeBound(maxBytes = -1) }
    }

    @Test
    fun proving_memory_allows_an_action_per_20_mb_above_the_baseline() {
        val mb = 1024L * 1024

        assertEquals(
            TransactionSizeBound(maxShieldedActions = 40),
            TransactionSizeBound.forProvingMemory(550 * mb + 40 * 20 * mb + 19 * mb)
        )
    }

    @Test
    fun proving_memory_is_capped_and_never_below_the_floor() {
        assertEquals(
            TransactionSizeBound.MAX_PROVING_ACTIONS,
            TransactionSizeBound.forProvingMemory(Long.MAX_VALUE).maxShieldedActions
        )
        val floor = TransactionSizeBound.MIN_PROVING_ACTIONS
        assertEquals(floor, TransactionSizeBound.forProvingMemory(0).maxShieldedActions)
        assertEquals(
            TransactionSizeBound.MIN_PROVING_ACTIONS,
            TransactionSizeBound.forProvingMemory(TransactionSizeBound.PROVING_BASELINE_BYTES).maxShieldedActions
        )
        // An app may still intersect a lower cap deliberately.
        assertEquals(
            8,
            TransactionSizeBound
                .forProvingMemory(0)
                .intersect(TransactionSizeBound(maxShieldedActions = 8))
                .maxShieldedActions
        )
    }
}
