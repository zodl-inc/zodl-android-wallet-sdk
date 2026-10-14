package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.internal.FastestServerFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.transaction.OutboundTransactionManager
import cash.z.ecc.android.sdk.internal.transaction.PendingSubmitPlanStore
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * [SdkSynchronizer.renameAccount] stores the name through the backend, makes
 * [SdkSynchronizer.accountsFlow] re-read the accounts, and wraps a backend failure in
 * [InitializeException.RenameAccountException].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkSynchronizerRenameAccountTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun a_rename_makes_accountsFlow_re_read_the_accounts() =
        withSynchronizer("rename_refresh") { synchronizer, backend ->
            val original = AccountFixture.new()
            val renamed = AccountFixture.new(accountName = NEW_NAME)
            val stored = AtomicReference(listOf(original))
            `when`(backend.getAccounts()).thenAnswer { stored.get() }
            `when`(backend.setAccountName(original.accountUuid, NEW_NAME)).thenAnswer {
                stored.set(listOf(renamed))
                true
            }

            val reEmitted =
                async {
                    synchronizer.accountsFlow.first { accounts -> accounts.namesOrNull() == listOf(NEW_NAME) }
                }
            synchronizer.accountsFlow.first { accounts -> accounts.namesOrNull() == listOf(original.accountName) }

            withTimeout(TIMEOUT_MS) {
                while (!reEmitted.isCompleted) {
                    synchronizer.renameAccount(original.accountUuid, NEW_NAME)
                    delay(POLL_MS)
                }
            }

            assertEquals(listOf(NEW_NAME), reEmitted.await().namesOrNull())
        }

    @Test
    fun wraps_a_backend_failure() =
        withSynchronizer("rename_failure") { synchronizer, backend ->
            val account = AccountFixture.new()
            val failure = RuntimeException("Account not found")
            `when`(backend.setAccountName(account.accountUuid, NEW_NAME)).thenThrow(failure)

            val thrown =
                assertFailsWith<InitializeException.RenameAccountException> {
                    synchronizer.renameAccount(account.accountUuid, NEW_NAME)
                }
            assertSame(failure, thrown.cause)
        }

    @Test
    fun treats_a_name_the_backend_did_not_store_as_a_failure() =
        withSynchronizer("rename_not_stored") { synchronizer, backend ->
            val account = AccountFixture.new()
            `when`(backend.setAccountName(account.accountUuid, NEW_NAME)).thenReturn(false)

            val thrown =
                assertFailsWith<InitializeException.RenameAccountException> {
                    synchronizer.renameAccount(account.accountUuid, NEW_NAME)
                }
            assertIs<IllegalStateException>(thrown.cause)
        }

    @Test
    fun propagates_cancellation_unwrapped() =
        withSynchronizer("rename_cancelled") { synchronizer, backend ->
            val account = AccountFixture.new()
            `when`(backend.setAccountName(account.accountUuid, NEW_NAME))
                .thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> { synchronizer.renameAccount(account.accountUuid, NEW_NAME) }
        }

    private fun List<Account>?.namesOrNull(): List<String?>? = this?.map { it.accountName }

    private fun withSynchronizer(
        alias: String,
        block: suspend CoroutineScope.(Synchronizer, TypesafeBackend) -> Unit
    ) = runBlocking {
        val backend = mock(TypesafeBackend::class.java)
        val synchronizer =
            SdkSynchronizer.new(
                context = mock(Context::class.java),
                zcashNetwork = ZcashNetwork.Testnet,
                alias = alias,
                repository = mock(DerivedDataRepository::class.java),
                txManager = mock(OutboundTransactionManager::class.java),
                processor = processorFailingSetup(),
                backend = backend,
                fastestServerFetcher = mock(FastestServerFetcher::class.java),
                fetchExchangeChangeUsd = null,
                preferenceProvider = mock(PreferenceProvider::class.java),
                lazyTorClient = null,
                walletClient = mock(CombinedWalletClient::class.java),
                walletClientFactory = mock(WalletClientFactory::class.java),
                defaultSubmitEndpoint = LightWalletEndpoint("localhost", PORT, false),
                pendingSubmitPlanStore = PendingSubmitPlanStore(),
                sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
                onCriticalErrorHandler = { false }
            )
        try {
            block(synchronizer, backend)
        } finally {
            synchronizer.close()
        }
    }

    /**
     * A processor whose setup fails at once, so the synchronizer never starts syncing; the rename
     * under test does not depend on it.
     */
    private suspend fun processorFailingSetup(): CompactBlockProcessor {
        val processor = mock(CompactBlockProcessor::class.java)
        `when`(processor.network).thenReturn(ZcashNetwork.Testnet)
        `when`(processor.walletBalances).thenReturn(MutableStateFlow(null))
        `when`(processor.progress).thenReturn(MutableStateFlow(PercentDecimal.ZERO_PERCENT))
        `when`(processor.scanProgress).thenReturn(MutableStateFlow(PercentDecimal.ZERO_PERCENT))
        `when`(processor.processorInfo).thenReturn(
            MutableStateFlow(CompactBlockProcessor.ProcessorInfo(null, null, null))
        )
        `when`(processor.networkHeight).thenReturn(MutableStateFlow(null))
        `when`(processor.fullyScannedHeight).thenReturn(MutableStateFlow(null))
        `when`(processor.verifySetup()).thenThrow(IllegalStateException("not started in this test"))
        return processor
    }

    companion object {
        private const val PORT = 9067
        private const val NEW_NAME = "Renamed account"
        private const val TIMEOUT_MS = 5_000L
        private const val POLL_MS = 20L
    }
}
