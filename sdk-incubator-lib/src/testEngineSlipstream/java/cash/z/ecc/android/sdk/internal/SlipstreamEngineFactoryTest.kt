package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.ALIAS
import cash.z.ecc.android.sdk.BIRTHDAY
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.ENDPOINT
import cash.z.ecc.android.sdk.MAIN_WALLET_ALIASES
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.setup
import com.zodl.slipstream.SlipstreamSynchronizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SlipstreamEngineFactory] with [SlipstreamSynchronizer]'s `new` replaced: a helper wallet is a second Slipstream
 * synchronizer under its own alias, restored from the card's seed without exchange rates and with its share of the
 * device's memory, which starts exactly at its birthday when asked to and the preparation could fetch that birthday's
 * tree state, else at the checkpoint, and is idle before its first pass; the main wallet keeps the default alias, the
 * whole device and the checkpoint start; helper wallets are erased by alias through
 * [SlipstreamSynchronizer.Companion.eraseAlias], never the main wallet.
 */
class SlipstreamEngineFactoryTest {
    @Test
    fun aHelperWalletIsASecondSlipstreamSynchronizerUnderItsAlias() =
        runBlocking<Unit> {
            val synchronizer = mock(CloseableSynchronizer::class.java)
            val requests = mutableListOf<SlipstreamWalletRequest>()
            val factory = recordingFactory(requests, synchronizer, resolveBirthdayAs = true)
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
                    onCriticalError = { false }
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
            assertEquals(SlipstreamEngineFactory.HELPER_ENGINE_MEMORY_FRACTION, request.engineMemoryFraction)
            assertNotNull(request.birthdayResolved)
            assertSame(synchronizer, opened.synchronizer)
        }

    @Test
    fun aHelperWalletsEngineNeverPlansWithMoreThanHalfTheDevice() {
        assertTrue(SlipstreamEngineFactory.HELPER_ENGINE_MEMORY_FRACTION > 0f)
        assertTrue(SlipstreamEngineFactory.HELPER_ENGINE_MEMORY_FRACTION <= HALF)
    }

    /**
     * The helper wallet starts at its birthday exactly when it asked to and its preparation, which fetches the tree
     * state over Tor or directly as the wallet's own client does, created the account from it; without the exact
     * birthday nothing is fetched or waited for.
     */
    @Test
    fun aHelperWalletStartsAtItsBirthdayOnlyWhenItsPreparationUsedTheExactTreeState() =
        runBlocking<Unit> {
            listOf(
                Triple(true, true, true),
                Triple(true, false, false),
                Triple(false, null, false)
            ).forEach { (isBirthdayExact, resolved, startsAtBirthday) ->
                listOf(true, false).forEach { isTorEnabled ->
                    val requests = mutableListOf<SlipstreamWalletRequest>()
                    val factory = recordingFactory(requests, resolveBirthdayAs = resolved)

                    val opened = openHelper(factory, isBirthdayExact = isBirthdayExact, isTorEnabled = isTorEnabled)

                    val request = requests.single()
                    assertEquals(startsAtBirthday, opened.startsAtBirthday)
                    assertTrue(opened.isDisconnectedUntilFirstPass)
                    assertEquals(isBirthdayExact, request.birthdayResolved != null)
                    assertEquals(isTorEnabled, request.isTorEnabled)
                }
            }
        }

    /** A caller cancelled while the preparation resolves the start closes the wallet nobody else could close. */
    @Test
    fun aHelperWalletWhoseOpenIsCancelledIsClosed() =
        runBlocking<Unit> {
            val synchronizer = mock(CloseableSynchronizer::class.java)
            val created = CompletableDeferred<Unit>()
            val factory =
                SlipstreamEngineFactory(
                    newSynchronizer = {
                        created.complete(Unit)
                        synchronizer
                    }
                )

            val open = launch { openHelper(factory, isBirthdayExact = true) }
            created.await()
            yield()
            open.cancelAndJoin()

            verify(synchronizer).close()
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
    fun theMainWalletKeepsTheDefaultAliasAndTheWholeDevice() =
        runBlocking<Unit> {
            val requests = mutableListOf<SlipstreamWalletRequest>()
            val setup = setup()

            recordingFactory(requests).new(
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
            assertNull(request.birthdayResolved, "the main wallet never reveals an exact height")
            assertEquals(SlipstreamSynchronizer.FULL_ENGINE_MEMORY, request.engineMemoryFraction)
            assertEquals(WalletInitMode.ExistingWallet, request.walletInitMode)
            assertTrue(request.isExchangeRateEnabled)
            assertSame(setup, request.setup)
        }

    @Test
    fun aHelperWalletIsErasedByItsAlias() =
        runBlocking<Unit> {
            val noBackupRoot = Files.createTempDirectory("helper-erase").toFile()
            try {
                val walletDir = File(noBackupRoot, "co.electricoin.zcash").apply { mkdirs() }
                val helper = File(walletDir, "${ALIAS}_testnet_data.sqlite3").apply { writeText("x") }
                val main = File(walletDir, "${ZcashSdk.DEFAULT_ALIAS}_testnet_data.sqlite3").apply { writeText("x") }
                val context = mock(Context::class.java)
                `when`(context.applicationContext).thenReturn(context)
                `when`(context.noBackupFilesDir).thenReturn(noBackupRoot)
                `when`(context.deleteSharedPreferences(anyString())).thenReturn(true)

                assertTrue(SlipstreamEngineFactory().eraseHelperWallet(context, ZcashNetwork.Testnet, ALIAS))

                assertFalse(helper.exists())
                assertTrue(main.exists())
                verify(context).deleteSharedPreferences(
                    "com.zodl.slipstream.submit_plan_${ZcashNetwork.Testnet.id}_$ALIAS"
                )
            } finally {
                noBackupRoot.deleteRecursively()
            }
        }

    @Test
    fun theMainWalletIsNeverErasedAsAHelperWallet() =
        runBlocking<Unit> {
            val context = mock(Context::class.java)

            MAIN_WALLET_ALIASES.forEach { alias ->
                assertFailsWith<IllegalArgumentException>(alias) {
                    SlipstreamEngineFactory().eraseHelperWallet(context, ZcashNetwork.Mainnet, alias)
                }
            }

            verifyNoInteractions(context)
        }

    /**
     * A factory whose `new` records each request in [requests] and returns [synchronizer]. Its preparation, when
     * [resolveBirthdayAs] is set, reports through the request's deferred whether the account starts exactly at the
     * birthday, as the real preparation does once it has created the account.
     */
    private fun recordingFactory(
        requests: MutableList<SlipstreamWalletRequest>,
        synchronizer: CloseableSynchronizer = mock(CloseableSynchronizer::class.java),
        resolveBirthdayAs: Boolean? = null
    ) = SlipstreamEngineFactory(
        newSynchronizer = { request ->
            requests += request
            resolveBirthdayAs?.let { request.birthdayResolved?.complete(it) }
            synchronizer
        }
    )

    private suspend fun openHelper(
        factory: SlipstreamEngineFactory,
        alias: String = ALIAS,
        isBirthdayExact: Boolean = false,
        isTorEnabled: Boolean = false,
        onCriticalError: (Throwable?) -> Boolean = { false }
    ) = factory.openHelperWallet(
        context = mock(Context::class.java),
        zcashNetwork = ZcashNetwork.Mainnet,
        alias = alias,
        birthday = BlockHeight.new(BIRTHDAY),
        isBirthdayExact = isBirthdayExact,
        lightWalletEndpoint = ENDPOINT,
        setup = setup(),
        isTorEnabled = isTorEnabled,
        onCriticalError = onCriticalError
    )

    private companion object {
        const val HALF = 0.5f
    }
}
