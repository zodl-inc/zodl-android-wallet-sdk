package cash.z.ecc.android.sdk.model

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

class ExternalSignerBudgetsTest {
    private val original = ExternalSignerBudgets.source

    @After
    fun restore() {
        ExternalSignerBudgets.source = original
        ExternalSignerBudgets.resetForTest()
    }

    @Test
    fun the_budget_is_read_from_the_backend_once_and_cached() =
        runBlocking {
            var reads = 0
            ExternalSignerBudgets.resetForTest()
            ExternalSignerBudgets.source = {
                reads++
                READ_VALUE
            }

            assertEquals(READ_VALUE, ExternalSignerBudgets.qrSigningRoundMaxActions())
            assertEquals(READ_VALUE, ExternalSignerBudgets.qrSigningRoundMaxActions())
            assertEquals(1, reads)
        }

    private companion object {
        /** An arbitrary value standing in for the backend's. */
        const val READ_VALUE = 7
    }
}
