package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.storage.preference.EncryptedPreferenceProvider
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [OrchardMigrationSdkImpl.proposeImmediateMigration] lets the backend's failure through exactly
 * as thrown, unwrapped and untyped, once the sync-race retries are spent.
 */
class OrchardMigrationSdkImplImmediateMigrationFailureTest {
    @Test
    fun anInsufficientFundsFailurePassesThroughUnwrappedAfterTheRetries() =
        runTest {
            val failure =
                RuntimeException(
                    "Error proposing immediate send-max: InsufficientFunds { available: 0, required: 10000 }"
                )
            val backend = FailingBackend(failure)

            val thrown = assertFailsWith<RuntimeException> { sdk(backend.mock).proposeImmediateMigration() }

            assertSame(failure, thrown)
            assertEquals(CALLS_WITH_RETRIES, backend.calls)
        }

    @Test
    fun anyOtherFailurePassesThroughUnwrappedWithoutRetrying() =
        runTest {
            val failure = RuntimeException("Error proposing immediate send-max: ScanRequired")
            val backend = FailingBackend(failure)

            val thrown = assertFailsWith<RuntimeException> { sdk(backend.mock).proposeImmediateMigration() }

            assertSame(failure, thrown)
            assertEquals(1, backend.calls)
        }

    @Test
    fun cancellationIsRethrownUnwrapped() =
        runTest {
            val cancellation = CancellationException("scope cancelled")
            val backend = FailingBackend(cancellation)

            val thrown = assertFailsWith<CancellationException> { sdk(backend.mock).proposeImmediateMigration() }

            assertSame(cancellation, thrown)
            assertEquals(1, backend.calls)
        }

    /** A backend whose `proposeImmediateSendMax` always throws [failure]; every other call answers null. */
    private class FailingBackend(
        failure: Throwable
    ) {
        var calls = 0
            private set

        val mock: TypesafeMigrationBackend =
            mock(TypesafeMigrationBackend::class.java) { invocation ->
                if (invocation.method.name == "proposeImmediateSendMax") {
                    calls++
                    throw failure
                } else {
                    null
                }
            }
    }

    private fun sdk(backend: TypesafeMigrationBackend): OrchardMigrationSdkImpl {
        val context = fakeAndroidContext()
        return OrchardMigrationSdkImpl(
            context = context,
            network = ZcashNetwork.Testnet,
            alias = "OrchardMigrationSdkImplImmediateMigrationFailureTest",
            account = AccountUuid.new(ByteArray(ACCOUNT_UUID_SIZE)),
            migrationBackend = backend,
            defaultSubmitEndpoint = LightWalletEndpoint("localhost", PORT, true),
            preferenceProviderHolder = EncryptedPreferenceProvider(context),
        )
    }

    private fun fakeAndroidContext(): Context {
        val tempDir =
            kotlin.io.path
                .createTempDirectory("OrchardMigrationSdkImplImmediateMigrationFailureTest")
                .toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
        `when`(context.getDatabasePath(anyString())).thenReturn(File(tempDir, "databases/unused.db"))
        return context
    }

    private companion object {
        const val ACCOUNT_UUID_SIZE = 16
        const val PORT = 9067

        // The first attempt and OrchardMigrationSdkImpl's three sync-race retries.
        const val CALLS_WITH_RETRIES = 4
    }
}
