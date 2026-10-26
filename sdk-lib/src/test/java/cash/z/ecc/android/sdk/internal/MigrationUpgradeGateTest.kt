package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.BlockSpacing
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MigrationUpgradeGateTest {
    private val activation = BlockSpacing.nu7ActivationHeight(ZcashNetwork.Mainnet)!!
    private val nu7 = activation.value

    private fun phase(
        next: Long,
        bypass: Boolean = false,
        network: ZcashNetwork = ZcashNetwork.Mainnet
    ) = migrationGatePhase(network, BlockHeight.new(next), bypass)

    private fun rawTx(branchId: Long): ByteArray {
        fun le(value: Long) = ByteArray(4) { i -> ((value shr (8 * i)) and 0xFF).toByte() }
        return le(0x8000_0005L) + le(0x26A7_270AL) + le(branchId) + ByteArray(16)
    }

    @Test
    fun pauses_below_the_cutoff_window() {
        assertEquals(MigrationGatePhase.PausedUntil(activation), phase(nu7 - NETWORK_UPGRADE_CUTOFF_BLOCKS - 1))
    }

    @Test
    fun enters_the_cutoff_window_48_blocks_before_activation() {
        assertEquals(MigrationGatePhase.Cutoff(activation), phase(nu7 - NETWORK_UPGRADE_CUTOFF_BLOCKS))
        assertEquals(MigrationGatePhase.Cutoff(activation), phase(nu7 - 1))
    }

    @Test
    fun opens_at_activation() {
        assertEquals(MigrationGatePhase.Open, phase(nu7))
        assertEquals(MigrationGatePhase.Open, phase(nu7 + 1_000_000))
    }

    @Test
    fun bypass_opens_the_gate() {
        assertEquals(MigrationGatePhase.Open, phase(nu7 - 1_000, bypass = true))
        assertEquals(MigrationGatePhase.Open, phase(nu7 - 1, bypass = true))
    }

    @Test
    fun uses_the_networks_own_activation_height() {
        val testnetNu7 = BlockSpacing.nu7ActivationHeight(ZcashNetwork.Testnet)!!
        assertEquals(MigrationGatePhase.Open, phase(testnetNu7.value, network = ZcashNetwork.Testnet))
        assertEquals(
            MigrationGatePhase.Cutoff(testnetNu7),
            phase(testnetNu7.value - 1, network = ZcashNetwork.Testnet)
        )
    }

    @Test
    fun accepts_the_next_heights_branch_unexpired() {
        val tx = rawTx(ConsensusBranchId.NU6_3.id)
        assertNull(checkSubmittable(tx, nu7 + 100, ZcashNetwork.Mainnet, BlockHeight.new(nu7 - 2)))
    }

    @Test
    fun refuses_a_branch_other_than_the_next_heights() {
        assertEquals(
            SubmissionRefusal.WRONG_BRANCH,
            checkSubmittable(rawTx(ConsensusBranchId.NU6_3.id), nu7 + 100, ZcashNetwork.Mainnet, BlockHeight.new(nu7))
        )
        assertEquals(
            SubmissionRefusal.WRONG_BRANCH,
            checkSubmittable(
                rawTx(ConsensusBranchId.NU7.id),
                nu7 + 100,
                ZcashNetwork.Mainnet,
                BlockHeight.new(nu7 - 1)
            )
        )
    }

    @Test
    fun refuses_an_expired_transaction_and_ignores_a_zero_expiry() {
        val tx = rawTx(ConsensusBranchId.NU6_3.id)
        val next = BlockHeight.new(nu7 - 100)
        assertEquals(
            SubmissionRefusal.EXPIRED,
            checkSubmittable(tx, next.value - 1, ZcashNetwork.Mainnet, next)
        )
        assertNull(checkSubmittable(tx, next.value, ZcashNetwork.Mainnet, next))
        assertNull(checkSubmittable(tx, 0L, ZcashNetwork.Mainnet, next))
        assertNull(checkSubmittable(tx, null, ZcashNetwork.Mainnet, next))
    }
}
