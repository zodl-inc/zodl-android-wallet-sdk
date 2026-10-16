package cash.z.ecc.android.sdk.internal.jni

import kotlinx.coroutines.test.runTest
import org.junit.Test
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
        private const val MAINNET_ID = 1
        private const val DB_PREFIX = "account-key-overlap-"
    }

    @Test
    fun an_empty_wallet_has_no_overlapping_account() =
        runTest {
            val backend = TestnetBackendFixture.newBackend(DB_PREFIX)
            val ufvk = TestnetBackendFixture.deriveUfvk()

            assertNull(backend.findAccountKeyOverlap(ufvk))
        }

    @Test
    fun a_key_of_another_network_is_rejected_without_echoing_it() =
        runTest {
            val backend = TestnetBackendFixture.newBackend(DB_PREFIX)
            val mainnetUfvk = TestnetBackendFixture.deriveUfvk(MAINNET_ID)

            val error = assertFailsWith<RuntimeException> { backend.findAccountKeyOverlap(mainnetUfvk) }
            assertFalse(error.message.orEmpty().contains(mainnetUfvk))
        }
}
