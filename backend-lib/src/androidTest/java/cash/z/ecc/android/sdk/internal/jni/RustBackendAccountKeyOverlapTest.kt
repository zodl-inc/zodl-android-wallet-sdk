package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.bip39.Mnemonics
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Exercises the `findAccountKeyOverlap` JNI export against a fresh, account-less testnet wallet: the
 * export resolves, answers `null` for a valid key, and rejects a key of another network without
 * echoing it. The overlap classification itself is covered by the `account_key_overlap_*` Rust unit
 * tests in backend-lib/src/main/rust/lib.rs, which import real accounts.
 */
class RustBackendAccountKeyOverlapTest {
    companion object {
        private const val SEED_PHRASE =
            "kitchen renew wide common vague fold vacuum tilt amazing pear square gossip jewel month tree shock scan" +
                " alpha just spot fluid toilet view dinner"
        private const val TESTNET_ID = 0
        private const val MAINNET_ID = 1
        private const val INIT_DATA_DB_SUCCESS = 0
    }

    @Test
    fun an_empty_wallet_has_no_overlapping_account() =
        runTest {
            val backend = newTestnetBackend()
            val ufvk = deriveUfvk(TESTNET_ID)

            assertNull(backend.findAccountKeyOverlap(ufvk))
        }

    @Test
    fun a_key_of_another_network_is_rejected_without_echoing_it() =
        runTest {
            val backend = newTestnetBackend()
            val mainnetUfvk = deriveUfvk(MAINNET_ID)

            val error = assertFailsWith<RuntimeException> { backend.findAccountKeyOverlap(mainnetUfvk) }
            assertFalse(error.message.orEmpty().contains(mainnetUfvk))
        }

    private suspend fun newTestnetBackend(): RustBackend {
        val root = createTempDirectory("account-key-overlap-").toFile()
        val backend =
            RustBackend.new(
                fsBlockDbRoot = root.resolve("fs_cache").apply { mkdirs() },
                dataDbFile = root.resolve("data.sqlite3"),
                saplingSpendFile = root.resolve("sapling-spend.params"),
                saplingOutputFile = root.resolve("sapling-output.params"),
                zcashNetworkId = TESTNET_ID
            )
        assertEquals(INIT_DATA_DB_SUCCESS, backend.initDataDb(seed = null))
        return backend
    }

    private suspend fun deriveUfvk(networkId: Int): String =
        RustDerivationTool
            .new()
            .deriveUnifiedFullViewingKeys(
                Mnemonics.MnemonicCode(SEED_PHRASE).toEntropy(),
                networkId = networkId,
                numberOfAccounts = 1
            ).single()
}
