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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [SdkSynchronizer.renameAccount] stores the name through the backend, makes
 * [SdkSynchronizer.accountsFlow] re-read the accounts, and wraps a backend failure in
 * [InitializeException.RenameAccountException] without re-reading the accounts.
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
            }

            val emissions = Channel<List<Account>>(Channel.UNLIMITED)
            val collector =
                launch(Dispatchers.Default) {
                    synchronizer.accountsFlow.filterNotNull().collect { emissions.send(it) }
                }
            try {
                assertEquals(
                    listOf(original.accountName),
                    withTimeout(TIMEOUT_MS) { emissions.receive() }.namesOrNull()
                )

                synchronizer.renameAccount(original.accountUuid, NEW_NAME)

                assertEquals(listOf(NEW_NAME), withTimeout(TIMEOUT_MS) { emissions.receive() }.namesOrNull())
            } finally {
                collector.cancel()
            }
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

    /**
     * The failed rename comes first and a successful one after it, so the accounts are read once at
     * the start and once for the successful rename; a re-read for the failure would be a third read.
     */
    @Test
    fun a_failed_rename_does_not_re_read_the_accounts() =
        withSynchronizer("rename_failure_no_refresh") { synchronizer, backend ->
            val original = AccountFixture.new()
            val renamed = AccountFixture.new(accountName = NEW_NAME)
            val stored = AtomicReference(listOf(original))
            val reads = AtomicInteger()
            `when`(backend.getAccounts()).thenAnswer {
                reads.incrementAndGet()
                stored.get()
            }
            `when`(backend.setAccountName(original.accountUuid, FAILING_NAME))
                .thenThrow(RuntimeException("database locked"))
            `when`(backend.setAccountName(original.accountUuid, NEW_NAME)).thenAnswer {
                stored.set(listOf(renamed))
            }

            val emissions = Channel<List<Account>>(Channel.UNLIMITED)
            val collector =
                launch(Dispatchers.Default) {
                    synchronizer.accountsFlow.filterNotNull().collect { emissions.send(it) }
                }
            try {
                withTimeout(TIMEOUT_MS) { emissions.receive() }

                assertFailsWith<InitializeException.RenameAccountException> {
                    synchronizer.renameAccount(original.accountUuid, FAILING_NAME)
                }
                synchronizer.renameAccount(original.accountUuid, NEW_NAME)

                assertEquals(listOf(NEW_NAME), withTimeout(TIMEOUT_MS) { emissions.receive() }.namesOrNull())
                assertEquals(2, reads.get())
            } finally {
                collector.cancel()
            }
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
        private const val FAILING_NAME = "Unsaved account"
        private const val TIMEOUT_MS = 5_000L
    }
}
