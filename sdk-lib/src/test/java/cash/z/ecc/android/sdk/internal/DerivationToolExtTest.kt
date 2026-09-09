package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertContentEquals

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
}
