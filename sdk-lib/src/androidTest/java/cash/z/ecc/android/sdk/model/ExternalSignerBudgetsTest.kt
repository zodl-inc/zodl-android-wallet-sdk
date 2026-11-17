package cash.z.ecc.android.sdk.model

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.internal.jni.MigrationRustBackend
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * [ExternalSignerBudgets] through the Rust backend: the budget comes from librustzcash across the JNI boundary,
 * with no wallet or migration engine.
 */
class ExternalSignerBudgetsTest {
    /** The value the migration engine signs Keystone rounds with, read through its own bridge. */
    @Test
    @SmallTest
    fun theQrRoundBudgetIsTheMigrationEnginesKeystoneBudget() =
        runTest {
            val engineBudget = MigrationRustBackend.new().keystoneSigningRoundBudget()[0]

            assertEquals(engineBudget, ExternalSignerBudgets.qrSigningRoundMaxActions())
            val budget = ExternalSignerBudgets.qrSigningRoundMaxActions()
            assertEquals(budget, ExternalSignerBudgets.qrSigningRoundMaxActions())
        }
}
