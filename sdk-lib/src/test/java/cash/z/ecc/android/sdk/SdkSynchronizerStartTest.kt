package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.internal.FastestServerFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.transaction.OutboundTransactionManager
import cash.z.ecc.android.sdk.internal.transaction.PendingSubmitPlanStore
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
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
import kotlin.test.assertTrue

/**
 * [SdkSynchronizer.new] installs the critical error handler it is given before it starts the synchronizer, so that
 * a critical error raised while starting reaches it. With an unconfined main dispatcher, the start-up work runs
 * inside [SdkSynchronizer.new] itself: a handler installed only afterwards would miss the error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkSynchronizerStartTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aCriticalErrorWhileStartingReachesTheHandlerGivenToNew() =
        runBlocking {
            val startupError = IllegalStateException("startup")
            val received = mutableListOf<Throwable?>()

            val synchronizer =
                SdkSynchronizer.new(
                    context = mock(Context::class.java),
                    zcashNetwork = ZcashNetwork.Testnet,
                    alias = "start_order_test",
                    repository = mock(DerivedDataRepository::class.java),
                    txManager = mock(OutboundTransactionManager::class.java),
                    processor = processorFailingSetupWith(startupError),
                    backend = mock(TypesafeBackend::class.java),
                    fastestServerFetcher = mock(FastestServerFetcher::class.java),
                    fetchExchangeChangeUsd = null,
                    preferenceProvider = mock(PreferenceProvider::class.java),
                    lazyTorClient = null,
                    walletClient = mock(CombinedWalletClient::class.java),
                    walletClientFactory = mock(WalletClientFactory::class.java),
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, false),
                    pendingSubmitPlanStore = PendingSubmitPlanStore(),
                    sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
                    onCriticalErrorHandler = { error ->
                        received += error
                        false
                    }
                )

            try {
                assertTrue(received.any { it === startupError }, "the start-up error reached the handler")
            } finally {
                synchronizer.close()
            }
        }

    private suspend fun processorFailingSetupWith(error: Exception): CompactBlockProcessor {
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
        `when`(processor.verifySetup()).thenThrow(error)
        return processor
    }
}
