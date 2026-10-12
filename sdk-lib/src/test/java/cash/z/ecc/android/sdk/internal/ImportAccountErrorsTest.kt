package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.exception.InitializeException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImportAccountErrorsTest {
    @Test
    fun the_collision_marker_maps_to_the_collision_exception() {
        val cause = RuntimeException(COLLISION_MESSAGE)

        val exception = ImportAccountErrors.toImportAccountException(cause)

        assertTrue(ImportAccountErrors.isAccountCollision(cause))
        assertIs<InitializeException.ImportAccountCollisionException>(exception)
        assertSame(cause, exception.cause)
    }

    @Test
    fun the_collision_marker_for_several_accounts_maps_to_the_collision_exception() {
        val cause =
            RuntimeException(
                "ImportAccountCollision: the provided key shares viewing key components with more " +
                    "than one account in the wallet"
            )

        assertIs<InitializeException.ImportAccountCollisionException>(
            ImportAccountErrors.toImportAccountException(cause)
        )
    }

    @Test
    fun the_checkpoints_marker_still_maps_to_the_checkpoints_exception() {
        val cause = RuntimeException("ImportAccountCheckpointsNotReady: transient")

        assertFalse(ImportAccountErrors.isAccountCollision(cause))
        assertIs<InitializeException.ImportAccountCheckpointsNotReadyException>(
            ImportAccountErrors.toImportAccountException(cause)
        )
    }

    @Test
    fun other_failures_map_to_the_generic_import_exception() {
        for (cause in listOf(
            RuntimeException("Error while initializing accounts: something else"),
            RuntimeException("see ImportAccountCollision for details"),
            RuntimeException()
        )) {
            assertFalse(ImportAccountErrors.isAccountCollision(cause))
            assertIs<InitializeException.ImportAccountException>(ImportAccountErrors.toImportAccountException(cause))
        }
    }

    @Test
    fun the_collision_exception_message_is_fixed_and_carries_no_key() {
        val cause = RuntimeException("$COLLISION_MESSAGE $FAKE_UFVK")

        val exception = InitializeException.ImportAccountCollisionException(cause)

        assertEquals(
            "Failed to import new account based on UFVK because an account sharing a viewing key " +
                "component with it already exists in the wallet.",
            exception.message
        )
        assertFalse(exception.message.orEmpty().contains(FAKE_UFVK))
    }

    @Test
    fun the_lookup_exception_message_is_fixed_and_carries_no_key() {
        val exception =
            InitializeException.FindAccountSharingKeyComponentsException(RuntimeException(FAKE_UFVK))

        assertFalse(exception.message.orEmpty().contains(FAKE_UFVK))
    }

    companion object {
        private const val COLLISION_MESSAGE =
            "ImportAccountCollision: an account sharing a viewing key component with the provided key " +
                "already exists in the wallet"
        private const val FAKE_UFVK = "uviewtest1notarealkeybutmustneverbeprinted"
    }
}
