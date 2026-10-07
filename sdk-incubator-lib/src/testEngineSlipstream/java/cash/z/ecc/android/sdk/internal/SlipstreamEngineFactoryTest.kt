package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.zodl.slipstream.SlipstreamSynchronizer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SlipstreamEngineFactory] with [SlipstreamSynchronizer]'s companion replaced: a helper wallet is a second
 * Slipstream synchronizer under its own alias, restored from the card's seed without exchange rates and with its
 * share of the device's memory, which starts at the checkpoint and is idle before its first pass; the main wallet
 * keeps the default alias and the whole device; helper wallets are erased by alias, never the main wallet.
 */
class SlipstreamEngineFactoryTest {
    @Test
    fun aHelperWalletIsASecondSlipstreamSynchronizerUnderItsAlias() =
        runBlocking<Unit> {
            val synchronizer = mock(CloseableSynchronizer::class.java)
            val requests = mutableListOf<SlipstreamWalletRequest>()
            val factory =
                SlipstreamEngineFactory(
                    newSynchronizer = {
                        requests += it
                        synchronizer
                    }
                )
            val context = mock(Context::class.java)
            val setup = setup()

            val opened =
                factory.openHelperWallet(
                    context = context,
                    zcashNetwork = ZcashNetwork.Mainnet,
                    alias = ALIAS,
                    birthday = BlockHeight.new(BIRTHDAY),
                    isBirthdayExact = true,
                    lightWalletEndpoint = ENDPOINT,
                    setup = setup,
                    isTorEnabled = true,
                    onCriticalError = { false },
                    engineMemoryFraction = HALF
                )

            val request = requests.single()
            assertSame(context, request.context)
            assertEquals(ALIAS, request.alias)
            assertEquals(BlockHeight.new(BIRTHDAY), request.birthday)
            assertEquals(ENDPOINT, request.lightWalletEndpoint)
            assertSame(setup, request.setup)
            assertEquals(WalletInitMode.RestoreWallet, request.walletInitMode)
            assertEquals(ZcashNetwork.Mainnet, request.zcashNetwork)
            assertTrue(request.isTorEnabled)
            assertFalse(request.isExchangeRateEnabled)
            assertEquals(HALF, request.engineMemoryFraction)
            assertSame(synchronizer, opened.synchronizer)
        }

    @Test
    fun aHelperWalletStartsAtTheCheckpointAndIsIdleBeforeItsFirstPass() =
        runBlocking<Unit> {
            val factory = SlipstreamEngineFactory(newSynchronizer = { mock(CloseableSynchronizer::class.java) })

            val opened = openHelper(factory, isBirthdayExact = true)

            assertFalse(opened.startsAtBirthday, "the engine cannot start a restore exactly at the birthday")
            assertTrue(opened.isDisconnectedUntilFirstPass)
        }

    @Test
    fun theCriticalErrorHandlerIsInstalledOnTheHelperWallet() =
        runBlocking<Unit> {
            val synchronizer = mock(CloseableSynchronizer::class.java)
            val handler: (Throwable?) -> Boolean = { false }
            val factory = SlipstreamEngineFactory(newSynchronizer = { synchronizer })

            openHelper(factory, onCriticalError = handler)

            verify(synchronizer).onCriticalErrorHandler = handler
        }

    @Test
    fun aHelperWalletNeverUsesTheMainWalletsAlias() =
        runBlocking<Unit> {
            val requests = mutableListOf<SlipstreamWalletRequest>()
            val factory =
                SlipstreamEngineFactory(
                    newSynchronizer = {
                        requests += it
                        mock(CloseableSynchronizer::class.java)
                    }
                )

            MAIN_WALLET_ALIASES.forEach { alias ->
                assertFailsWith<IllegalArgumentException>(alias) { openHelper(factory, alias = alias) }
            }

            assertTrue(requests.isEmpty())
        }

    @Test
    fun theMainWalletKeepsTheDefaultAliasAndTheWholeDevice() =
        runBlocking<Unit> {
            val requests = mutableListOf<SlipstreamWalletRequest>()
            val factory =
                SlipstreamEngineFactory(
                    newSynchronizer = {
                        requests += it
                        mock(CloseableSynchronizer::class.java)
                    }
                )
            val setup = setup()

            factory.new(
                context = mock(Context::class.java),
                zcashNetwork = ZcashNetwork.Mainnet,
                lightWalletEndpoint = ENDPOINT,
                birthday = BlockHeight.new(BIRTHDAY),
                setup = setup,
                walletInitMode = WalletInitMode.ExistingWallet,
                isTorEnabled = false,
                isExchangeRateEnabled = true
            )

            val request = requests.single()
            assertEquals(ZcashSdk.DEFAULT_ALIAS, request.alias)
            assertEquals(SlipstreamSynchronizer.FULL_ENGINE_MEMORY, request.engineMemoryFraction)
            assertEquals(WalletInitMode.ExistingWallet, request.walletInitMode)
            assertTrue(request.isExchangeRateEnabled)
            assertSame(setup, request.setup)
        }

    @Test
    fun aHelperWalletIsErasedByItsAlias() =
        runBlocking<Unit> {
            val erased = mutableListOf<Pair<ZcashNetwork, String>>()
            val newSynchronizer: suspend (SlipstreamWalletRequest) -> CloseableSynchronizer = { error("not opened") }
            val factory =
                SlipstreamEngineFactory(
                    newSynchronizer = newSynchronizer,
                    eraseHelperAlias = { _, network, alias ->
                        erased += network to alias
                        true
                    }
                )

            assertTrue(factory.eraseHelperWallet(mock(Context::class.java), ZcashNetwork.Testnet, ALIAS))

            assertEquals(listOf(ZcashNetwork.Testnet to ALIAS), erased)
        }

    @Test
    fun theMainWalletIsNeverErasedAsAHelperWallet() =
        runBlocking<Unit> {
            val context = mock(Context::class.java)
            val erased = mutableListOf<String>()
            val newSynchronizer: suspend (SlipstreamWalletRequest) -> CloseableSynchronizer = { error("not opened") }
            val injected =
                SlipstreamEngineFactory(
                    newSynchronizer = newSynchronizer,
                    eraseHelperAlias = { _, _, alias ->
                        erased += alias
                        true
                    }
                )

            MAIN_WALLET_ALIASES.forEach { alias ->
                assertFailsWith<IllegalArgumentException>(alias) {
                    SlipstreamEngineFactory().eraseHelperWallet(context, ZcashNetwork.Mainnet, alias)
                }
                assertFailsWith<IllegalArgumentException>(alias) {
                    injected.eraseHelperWallet(context, ZcashNetwork.Mainnet, alias)
                }
            }

            verifyNoInteractions(context)
            assertTrue(erased.isEmpty())
        }

    private suspend fun openHelper(
        factory: SlipstreamEngineFactory,
        alias: String = ALIAS,
        isBirthdayExact: Boolean = false,
        onCriticalError: (Throwable?) -> Boolean = { false }
    ) = factory.openHelperWallet(
        context = mock(Context::class.java),
        zcashNetwork = ZcashNetwork.Mainnet,
        alias = alias,
        birthday = BlockHeight.new(BIRTHDAY),
        isBirthdayExact = isBirthdayExact,
        lightWalletEndpoint = ENDPOINT,
        setup = setup(),
        isTorEnabled = false,
        onCriticalError = onCriticalError,
        engineMemoryFraction = HALF
    )

    private fun setup() =
        AccountCreateSetup(
            accountName = "Gift card",
            keySource = null,
            seed = FirstClassByteArray(ByteArray(SEED_BYTES))
        )

    private companion object {
        const val ALIAS = "giftcard_test"
        const val BIRTHDAY = 3_000_000L
        const val HALF = 0.5f
        const val SEED_BYTES = 64

        /** Every spelling of an alias that addresses the main wallet's files. */
        val MAIN_WALLET_ALIASES =
            listOf(ZcashSdk.DEFAULT_ALIAS, "${ZcashSdk.DEFAULT_ALIAS}_", "ZcashSdk", "ZCASHSDK_")
        val ENDPOINT = LightWalletEndpoint("localhost", 9067, false)
    }
}
