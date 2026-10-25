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
        val cause = RuntimeException(SEVERAL_ACCOUNTS_MESSAGE)

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
    fun a_lookup_failing_on_several_accounts_maps_to_the_collision_exception() {
        val cause = RuntimeException(SEVERAL_ACCOUNTS_MESSAGE)

        val exception = ImportAccountErrors.toFindAccountSharingKeyComponentsException(cause)

        assertIs<InitializeException.ImportAccountCollisionException>(exception)
        assertSame(cause, exception.cause)
    }

    @Test
    fun other_lookup_failures_map_to_the_lookup_exception() {
        for (cause in listOf(
            RuntimeException("Value did not decode as a valid UFVK: invalid checksum"),
            RuntimeException("ImportAccountCheckpointsNotReady: transient"),
            RuntimeException()
        )) {
            val exception = ImportAccountErrors.toFindAccountSharingKeyComponentsException(cause)

            assertIs<InitializeException.FindAccountSharingKeyComponentsException>(exception)
            assertSame(cause, exception.cause)
        }
    }

    @Test
    fun the_lookup_exception_message_is_fixed_and_carries_no_key() {
        val exception =
            InitializeException.FindAccountSharingKeyComponentsException(RuntimeException(FAKE_UFVK))

        assertFalse(exception.message.orEmpty().contains(FAKE_UFVK))
    }

    @Test
    fun the_import_create_and_rename_exception_messages_are_fixed_and_carry_no_key() {
        val cause = RuntimeException("Value did not decode as a valid UFVK: $FAKE_UFVK")

        val import = InitializeException.ImportAccountException(cause)
        val create = InitializeException.CreateAccountException(cause)
        val rename = InitializeException.RenameAccountException(cause)

        assertEquals("Failed to import new account based on UFVK.", import.message)
        assertEquals("Failed to create new account.", create.message)
        assertEquals("Failed to rename account.", rename.message)
        assertSame(cause, import.cause)
        assertSame(cause, create.cause)
        assertSame(cause, rename.cause)
    }

    companion object {
        private const val COLLISION_MESSAGE =
            "ImportAccountCollision: an account sharing a viewing key component with the provided key " +
                "already exists in the wallet"
        private const val SEVERAL_ACCOUNTS_MESSAGE =
            "ImportAccountCollision: the provided key shares viewing key components with more than one " +
                "account in the wallet"
        private const val FAKE_UFVK = "uviewtest1notarealkeybutmustneverbeprinted"
    }
}
