package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_ORCHARD
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_P2PKH
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_P2SH
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_SAPLING
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_UNKNOWN
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_DESCRIPTION_INVALID
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKeyComponents
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DerivationToolExtTest {
    @Test
    fun deriveUnifiedSpendingKey_wipes_the_transient_jni_copy() {
        val seed = ByteArray(32) { it.toByte() }
        val network = ZcashNetwork.Testnet
        val accountIndex = Zip32AccountIndex.new(0)
        val expectedKeyBytes = ByteArray(64) { (it + 1).toByte() }
        val jniReturnedBytes = expectedKeyBytes.copyOf()

        val derivation = mock(Derivation::class.java)
        `when`(derivation.deriveUnifiedSpendingKey(seed, network.id, accountIndex.index))
            .thenReturn(jniReturnedBytes)

        val usk = derivation.deriveUnifiedSpendingKey(seed, network, accountIndex)

        assertContentEquals(
            expectedKeyBytes,
            usk.copyBytes(),
            "the derived key must be preserved in the returned UnifiedSpendingKey"
        )
        assertContentEquals(
            ByteArray(64),
            jniReturnedBytes,
            "the transient JNI-returned array must be wiped after use"
        )
    }

    private fun describe(bits: Int): UnifiedFullViewingKeyComponents? {
        val ufvk = UnifiedFullViewingKey(UFVK_PLACEHOLDER)
        val network = ZcashNetwork.Mainnet
        val derivation = mock(Derivation::class.java)
        `when`(derivation.describeUnifiedFullViewingKey(ufvk.encoding, network.id)).thenReturn(bits)
        return derivation.describeUnifiedFullViewingKeyTypesafe(ufvk, network)
    }

    @Test
    fun describeUnifiedFullViewingKey_decodes_a_full_key() {
        assertEquals(
            UnifiedFullViewingKeyComponents(
                hasP2pkh = true,
                hasP2sh = false,
                hasSapling = true,
                hasOrchard = true,
                hasUnknownItems = false
            ),
            describe(JNI_UFVK_COMPONENT_P2PKH or JNI_UFVK_COMPONENT_SAPLING or JNI_UFVK_COMPONENT_ORCHARD)
        )
    }

    @Test
    fun describeUnifiedFullViewingKey_decodes_each_bit_on_its_own() {
        val none =
            UnifiedFullViewingKeyComponents(
                hasP2pkh = false,
                hasP2sh = false,
                hasSapling = false,
                hasOrchard = false,
                hasUnknownItems = false
            )
        assertEquals(none, describe(0))
        assertEquals(none.copy(hasP2pkh = true), describe(JNI_UFVK_COMPONENT_P2PKH))
        assertEquals(none.copy(hasP2sh = true), describe(JNI_UFVK_COMPONENT_P2SH))
        assertEquals(none.copy(hasSapling = true), describe(JNI_UFVK_COMPONENT_SAPLING))
        assertEquals(none.copy(hasOrchard = true), describe(JNI_UFVK_COMPONENT_ORCHARD))
        assertEquals(none.copy(hasUnknownItems = true), describe(JNI_UFVK_COMPONENT_UNKNOWN))
    }

    @Test
    fun describeUnifiedFullViewingKey_reports_p2sh_as_transparent_but_not_p2pkh() {
        val components = describe(JNI_UFVK_COMPONENT_P2SH or JNI_UFVK_COMPONENT_ORCHARD)!!
        assertTrue(components.hasTransparent)
        assertFalse(components.hasP2pkh)
        assertTrue(components.hasP2sh)
    }

    @Test
    fun describeUnifiedFullViewingKey_reports_no_transparent_without_either_item() {
        assertFalse(describe(JNI_UFVK_COMPONENT_SAPLING or JNI_UFVK_COMPONENT_ORCHARD)!!.hasTransparent)
        assertTrue(describe(JNI_UFVK_COMPONENT_P2PKH)!!.hasTransparent)
    }

    @Test
    fun describeUnifiedFullViewingKey_maps_invalid_to_null() {
        assertNull(describe(JNI_UFVK_DESCRIPTION_INVALID))
    }

    @Test
    fun unifiedFullViewingKey_toString_does_not_print_the_encoding() {
        val ufvk = UnifiedFullViewingKey(UFVK_PLACEHOLDER)
        assertFalse(ufvk.toString().contains(UFVK_PLACEHOLDER))
        assertFalse("$ufvk".contains(UFVK_PLACEHOLDER))
        assertEquals("UnifiedFullViewingKey(encoding=***)", ufvk.toString())
        assertEquals(UnifiedFullViewingKey(UFVK_PLACEHOLDER), ufvk)
        assertEquals(UnifiedFullViewingKey(UFVK_PLACEHOLDER).hashCode(), ufvk.hashCode())
    }

    companion object {
        /**
         * Not a real key: the tests mock the JNI layer, so only the identity of the string matters.
         */
        private const val UFVK_PLACEHOLDER = "uview1placeholderencodingthatisnotarealkey"
    }
}
