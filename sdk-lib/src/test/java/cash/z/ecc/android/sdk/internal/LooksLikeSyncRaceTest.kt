package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.ProposalInsufficientFundsException
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which migration failures [OrchardMigrationSdkImpl] retries as a transient sync race: exactly
 * those whose own message contains `InsufficientFunds` or `database is locked`, and nothing else.
 */
class LooksLikeSyncRaceTest {
    @Test
    fun theImmediateMigrationsInsufficientFundsTextIsARace() {
        assertTrue(
            RuntimeException(
                "Error proposing immediate send-max: InsufficientFunds { available: 0, required: 10000 }"
            ).looksLikeSyncRace()
        )
    }

    @Test
    fun theEnginesInsufficientFundsTextIsARace() {
        assertTrue(RuntimeException("Error planning migration: Preparation(InsufficientFunds)").looksLikeSyncRace())
    }

    @Test
    fun aDatabaseLockIsARace() {
        assertTrue(RuntimeException("database is locked").looksLikeSyncRace())
    }

    @Test
    fun theProposalMachinerysInsufficientBalanceTextIsNotARace() {
        assertFalse(RuntimeException("Insufficient balance (have 0, need 1)").looksLikeSyncRace())
    }

    @Test
    fun theTypedInsufficientFundsExceptionIsNotARaceByType() {
        assertFalse(ProposalInsufficientFundsException("Insufficient balance (have 0, need 1)").looksLikeSyncRace())
    }

    @Test
    fun aMatchOnlyInTheCauseIsNotARace() {
        assertFalse(RuntimeException("wrapper", RuntimeException("database is locked")).looksLikeSyncRace())
    }

    @Test
    fun anyOtherFailureIsNotARace() {
        assertFalse(RuntimeException("Error committing migration: Unsatisfiable").looksLikeSyncRace())
        assertFalse(RuntimeException().looksLikeSyncRace())
    }
}
