package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.bip39.Mnemonics
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RustDerivationToolTest {
    companion object {
        private const val SEED_PHRASE =
            "kitchen renew wide common vague fold vacuum tilt amazing pear square gossip jewel month tree shock scan" +
                " alpha just spot fluid toilet view dinner"
    }

    @Test
    fun create_spending_key_does_not_mutate_passed_bytes() =
        runTest {
            val bytesOne = Mnemonics.MnemonicCode(SEED_PHRASE).toEntropy()
            val bytesTwo = Mnemonics.MnemonicCode(SEED_PHRASE).toEntropy()

            RustDerivationTool.new().deriveUnifiedSpendingKey(bytesOne, networkId = 1, accountIndex = 0)

            assertContentEquals(bytesTwo, bytesOne)
        }

    @Test
    fun describe_unified_full_viewing_key_reports_the_components_of_a_derived_key() =
        runTest {
            val seed = Mnemonics.MnemonicCode(SEED_PHRASE).toEntropy()
            val derivation = RustDerivationTool.new()
            val ufvk = derivation.deriveUnifiedFullViewingKeys(seed, networkId = 1, numberOfAccounts = 1).single()

            assertEquals(
                JNI_UFVK_COMPONENT_P2PKH or JNI_UFVK_COMPONENT_SAPLING or JNI_UFVK_COMPONENT_ORCHARD,
                derivation.describeUnifiedFullViewingKey(ufvk, networkId = 1)
            )
        }

    @Test
    fun describe_unified_full_viewing_key_returns_invalid_without_throwing() =
        runTest {
            val seed = Mnemonics.MnemonicCode(SEED_PHRASE).toEntropy()
            val derivation = RustDerivationTool.new()
            val mainnetUfvk =
                derivation.deriveUnifiedFullViewingKeys(seed, networkId = 1, numberOfAccounts = 1).single()

            assertEquals(JNI_UFVK_DESCRIPTION_INVALID, derivation.describeUnifiedFullViewingKey(mainnetUfvk, 0))
            assertEquals(JNI_UFVK_DESCRIPTION_INVALID, derivation.describeUnifiedFullViewingKey("", 1))
            assertEquals(JNI_UFVK_DESCRIPTION_INVALID, derivation.describeUnifiedFullViewingKey("not a key", 1))
        }
}
