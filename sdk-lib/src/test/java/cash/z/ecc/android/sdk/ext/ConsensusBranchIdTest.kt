package cash.z.ecc.android.sdk.ext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConsensusBranchIdTest {
    // Branch ids as defined by `zcash_protocol::consensus::BranchId`.
    @Test
    fun post_canopy_branch_ids_resolve() {
        assertEquals(ConsensusBranchId.NU5, ConsensusBranchId.fromId(0xc2d6_d0b4))
        assertEquals(ConsensusBranchId.NU6, ConsensusBranchId.fromId(0xc8e7_1055))
        assertEquals(ConsensusBranchId.NU6_1, ConsensusBranchId.fromId(0x4dec_4df0))
        assertEquals(ConsensusBranchId.NU6_2, ConsensusBranchId.fromId(0x5437_f330))
        assertEquals(ConsensusBranchId.NU6_3, ConsensusBranchId.fromId(0x37a5_165b))
        assertEquals(ConsensusBranchId.NU7, ConsensusBranchId.fromId(0x7719_0ad9))
    }

    @Test
    fun nu7_resolves_from_hex_and_name() {
        assertEquals(ConsensusBranchId.NU7, ConsensusBranchId.fromHex("77190AD9"))
        assertEquals(ConsensusBranchId.NU7, ConsensusBranchId.fromHex("0x7719_0ad9"))
        assertEquals(ConsensusBranchId.NU7, ConsensusBranchId.fromName("nu7"))
        assertEquals("NU7", ConsensusBranchId.NU7.toString())
    }

    @Test
    fun hex_ids_match_numeric_ids() {
        ConsensusBranchId.entries.filter { it != ConsensusBranchId.SPROUT }.forEach {
            assertEquals(it.id, it.hexId.toLong(16), "hexId mismatch for ${it.displayName}")
        }
    }

    @Test
    fun unknown_id_is_null() {
        assertNull(ConsensusBranchId.fromId(0x1234_5678))
    }
}
