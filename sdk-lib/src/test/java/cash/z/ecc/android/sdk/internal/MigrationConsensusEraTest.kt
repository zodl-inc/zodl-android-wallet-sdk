package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.BlockSpacing
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationConsensusEraTest {
    private val nu7 = BlockSpacing.nu7ActivationHeight(ZcashNetwork.Mainnet)!!.value

    private fun rawTx(
        branchId: Long,
        header: Long = 0x8000_0005L,
    ): ByteArray {
        fun le(value: Long) = ByteArray(4) { i -> ((value shr (8 * i)) and 0xFF).toByte() }
        return le(header) + le(0x26A7_270AL) + le(branchId) + ByteArray(16)
    }

    @Test
    fun parses_branch_id_little_endian() {
        assertEquals(ConsensusBranchId.NU7.id, rawTransactionBranchId(rawTx(ConsensusBranchId.NU7.id)))
        assertEquals(ConsensusBranchId.NU6_3.id, rawTransactionBranchId(rawTx(ConsensusBranchId.NU6_3.id)))
    }

    @Test
    fun unreadable_transactions_have_no_branch_id() {
        assertNull(rawTransactionBranchId(ByteArray(11)))
        assertNull(rawTransactionBranchId(rawTx(ConsensusBranchId.NU7.id, header = 0x0000_0004L)))
    }

    @Test
    fun pre_nu7_transaction_is_stale_from_the_activation_height() {
        val tx = rawTx(ConsensusBranchId.NU6_3.id)
        assertFalse(isBuiltForOtherConsensusEra(tx, ZcashNetwork.Mainnet, BlockHeight.new(nu7 - 1)))
        assertTrue(isBuiltForOtherConsensusEra(tx, ZcashNetwork.Mainnet, BlockHeight.new(nu7)))
        assertTrue(isBuiltForOtherConsensusEra(tx, ZcashNetwork.Mainnet, BlockHeight.new(nu7 + 1_000)))
    }

    @Test
    fun nu7_transaction_is_fine_from_the_activation_height_only() {
        val tx = rawTx(ConsensusBranchId.NU7.id)
        assertTrue(isBuiltForOtherConsensusEra(tx, ZcashNetwork.Mainnet, BlockHeight.new(nu7 - 1)))
        assertFalse(isBuiltForOtherConsensusEra(tx, ZcashNetwork.Mainnet, BlockHeight.new(nu7)))
    }

    @Test
    fun unreadable_transaction_never_blocks() {
        assertFalse(isBuiltForOtherConsensusEra(ByteArray(3), ZcashNetwork.Mainnet, BlockHeight.new(nu7 + 1)))
    }
}
