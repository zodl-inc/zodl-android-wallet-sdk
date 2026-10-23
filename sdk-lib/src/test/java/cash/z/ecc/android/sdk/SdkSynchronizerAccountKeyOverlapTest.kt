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
import cash.z.ecc.android.sdk.model.AccountKeyOverlap
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [SdkSynchronizer.findAccountSharingKeyComponents] hands the key to the backend unchanged, returns its
 * answer as is, and wraps a backend failure in a key-free
 * [InitializeException.FindAccountSharingKeyComponentsException], or in
 * [InitializeException.ImportAccountCollisionException] when the key matches several accounts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkSynchronizerAccountKeyOverlapTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun returns_the_overlap_the_backend_finds() =
        withSynchronizer("overlap_found") { synchronizer, backend ->
            val overlap = AccountKeyOverlap(AccountFixture.new(), AccountKeyOverlap.Kind.ALREADY_IMPORTED)
            `when`(backend.findAccountSharingKeyComponents(UFVK)).thenReturn(overlap)

            assertSame(overlap, synchronizer.findAccountSharingKeyComponents(UFVK))
        }

    @Test
    fun returns_null_when_no_account_overlaps() =
        withSynchronizer("overlap_none") { synchronizer, backend ->
            `when`(backend.findAccountSharingKeyComponents(UFVK)).thenReturn(null)

            assertNull(synchronizer.findAccountSharingKeyComponents(UFVK))
        }

    @Test
    fun wraps_a_backend_failure_without_the_key() =
        withSynchronizer("overlap_failure") { synchronizer, backend ->
            val failure = RuntimeException("Value did not decode as a valid UFVK: ${UFVK.encoding}")
            `when`(backend.findAccountSharingKeyComponents(UFVK)).thenThrow(failure)

            val thrown =
                assertFailsWith<InitializeException.FindAccountSharingKeyComponentsException> {
                    synchronizer.findAccountSharingKeyComponents(UFVK)
                }
            assertSame(failure, thrown.cause)
            assertFalse(thrown.message.orEmpty().contains(UFVK.encoding))
        }

    @Test
    fun maps_a_key_matching_several_accounts_to_the_import_collision() =
        withSynchronizer("overlap_several_accounts") { synchronizer, backend ->
            val failure =
                RuntimeException(
                    "ImportAccountCollision: the provided key shares viewing key components with more than " +
                        "one account in the wallet"
                )
            `when`(backend.findAccountSharingKeyComponents(UFVK)).thenThrow(failure)

            val thrown =
                assertFailsWith<InitializeException.ImportAccountCollisionException> {
                    synchronizer.findAccountSharingKeyComponents(UFVK)
                }
            assertSame(failure, thrown.cause)
        }

    @Test
    fun propagates_cancellation_unwrapped() =
        withSynchronizer("overlap_cancelled") { synchronizer, backend ->
            `when`(backend.findAccountSharingKeyComponents(UFVK)).thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> { synchronizer.findAccountSharingKeyComponents(UFVK) }
        }

    private fun withSynchronizer(
        alias: String,
        block: suspend (Synchronizer, TypesafeBackend) -> Unit
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
     * A processor whose setup fails at once, so the synchronizer never starts syncing; the lookup under
     * test does not depend on it.
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
        private val UFVK = UnifiedFullViewingKey("uviewtest1overlapfixturekey")
    }
}
