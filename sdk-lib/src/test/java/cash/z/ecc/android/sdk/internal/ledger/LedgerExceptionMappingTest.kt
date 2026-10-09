package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerExceptionMappingTest {
    private fun jni(
        kind: Int,
        statusWord: Int = JniLedgerException.NO_STATUS_WORD,
        isTransient: Boolean = false,
        isRestartable: Boolean = false,
        reason: String? = "a reason"
    ) = JniLedgerException(kind, statusWord, isTransient, isRestartable, reason)

    @Test
    fun a_user_rejection_keeps_its_restartability() {
        val mapped = jni(JniLedgerException.KIND_USER_REJECTED, isRestartable = true).toLedgerException()
        assertIs<LedgerException.UserRejected>(mapped)
        assertTrue(mapped.isRestartable)
    }

    @Test
    fun a_device_refusal_carries_its_status_word_and_verdicts() {
        val mapped =
            jni(
                JniLedgerException.KIND_DEVICE_REFUSED,
                statusWord = 0x5515,
                isTransient = true,
                isRestartable = true
            ).toLedgerException()
        assertIs<LedgerException.DeviceRefused>(mapped)
        assertEquals(0x5515, mapped.statusWord)
        assertTrue(mapped.isTransient)
        assertTrue(mapped.isRestartable)
    }

    @Test
    fun a_wrong_app_without_a_status_word_has_none() {
        val mapped = jni(JniLedgerException.KIND_WRONG_APP).toLedgerException()
        assertIs<LedgerException.WrongApp>(mapped)
        assertNull(mapped.statusWord)
    }

    @Test
    fun a_refusal_from_the_dashboard_or_another_app_is_a_wrong_app() {
        listOf(0x6E01, 0x6511).forEach { statusWord ->
            val mapped =
                jni(JniLedgerException.KIND_DEVICE_REFUSED, statusWord = statusWord).toLedgerException()
            assertIs<LedgerException.WrongApp>(mapped, "status word $statusWord")
            assertEquals(statusWord, mapped.statusWord)
            assertEquals("a reason", mapped.reason)
            assertTrue(mapped.isRestartable)
        }
    }

    @Test
    fun an_unknown_command_or_class_keeps_the_engine_verdict() {
        listOf(0x6E00, 0x6D00).forEach { statusWord ->
            val wrongApp =
                jni(JniLedgerException.KIND_WRONG_APP, statusWord = statusWord).toLedgerException()
            assertIs<LedgerException.WrongApp>(wrongApp)
            assertEquals(statusWord, wrongApp.statusWord)
        }
        val refused =
            jni(JniLedgerException.KIND_DEVICE_REFUSED, statusWord = 0x6985).toLedgerException()
        assertIs<LedgerException.DeviceRefused>(refused)
        assertEquals(0x6985, refused.statusWord)
    }

    @Test
    fun a_wrong_app_and_an_exhausted_derivation_budget_are_restartable() {
        val wrongApp = jni(JniLedgerException.KIND_WRONG_APP).toLedgerException()
        assertIs<LedgerException.WrongApp>(wrongApp)
        assertTrue(wrongApp.isRestartable)
        val budget = jni(JniLedgerException.KIND_DERIVATION_BUDGET_EXHAUSTED).toLedgerException()
        assertIs<LedgerException.DerivationBudgetExhausted>(budget)
        assertTrue(budget.isRestartable)
    }

    @Test
    fun every_kind_maps_to_its_own_type() {
        val expected =
            mapOf(
                JniLedgerException.KIND_APP_TOO_OLD to LedgerException.AppTooOld::class,
                JniLedgerException.KIND_DEVICE_MISMATCH to LedgerException.DeviceMismatch::class,
                JniLedgerException.KIND_CAPS_MISMATCH to LedgerException.CapsMismatch::class,
                JniLedgerException.KIND_DERIVATION_BUDGET_EXHAUSTED to
                    LedgerException.DerivationBudgetExhausted::class,
                JniLedgerException.KIND_TRANSACTION_NOT_SIGNABLE to LedgerException.TransactionNotSignable::class,
                JniLedgerException.KIND_MALFORMED_REPLY to LedgerException.MalformedReply::class,
                JniLedgerException.KIND_INVALID_INPUT to LedgerException.InvalidInput::class,
                JniLedgerException.KIND_INTERNAL to LedgerException.Internal::class,
                JniLedgerException.KIND_CMD_NOT_ACCEPTED to CommandNotAcceptedException::class
            )
        expected.forEach { (kind, type) ->
            assertEquals(type, jni(kind).toLedgerException()::class, "kind $kind")
        }
    }

    @Test
    fun a_reason_is_kept_out_of_the_message() {
        val mapped = jni(JniLedgerException.KIND_TRANSACTION_NOT_SIGNABLE, reason = "Sapling").toLedgerException()
        assertIs<LedgerException.TransactionNotSignable>(mapped)
        assertEquals("Sapling", mapped.reason)
        assertFalse(mapped.message.orEmpty().contains("Sapling"))
    }
}
