package com.zodl.slipstream

import android.content.Context
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.ZcashNetwork
import com.zodl.slipstream.internal.DataDbPath
import com.zodl.slipstream.internal.InstanceGuard
import com.zodl.slipstream.internal.SlipstreamKey
import com.zodl.slipstream.internal.spend.submitPlanPreferencesName
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SlipstreamSynchronizer.Companion.erase] and [SlipstreamSynchronizer.Companion.eraseAlias] against a real
 * temporary no-backup directory and a mocked [Context]: the wallet's `data.sqlite3`, `-wal` and `-shm` and its
 * submit-plan preferences go, every other alias' files and preferences (the main wallet's in particular) stay, an
 * active instance refuses the erase, and the legacy layout's deletion runs only for
 * [SlipstreamSynchronizer.Companion.eraseAlias], under the same guard, only when the legacy layout's own files exist,
 * and without ever failing the erase. [SlipstreamSynchronizer.Companion.eraseAlias] refuses every spelling of the
 * main wallet's alias before touching any file.
 */
class SlipstreamEraseTest {
    private lateinit var noBackupRoot: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        noBackupRoot = Files.createTempDirectory("slipstream-erase").toFile()
        context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.noBackupFilesDir).thenReturn(noBackupRoot)
        `when`(context.deleteSharedPreferences(preferencesOf(CARD_ALIAS))).thenReturn(true)
        `when`(context.deleteSharedPreferences(preferencesOf(ZcashSdk.DEFAULT_ALIAS))).thenReturn(true)
    }

    @After
    fun tearDown() {
        noBackupRoot.deleteRecursively()
    }

    @Test
    fun eraseDeletesTheDatabaseItsJournalsAndTheSubmitPlans() =
        runBlocking<Unit> {
            val card = walletFiles(CARD_ALIAS)

            assertTrue(SlipstreamSynchronizer.erase(context, NETWORK, CARD_ALIAS))

            card.forEach { assertFalse(it.exists(), "${it.name} must be deleted") }
            verify(context).deleteSharedPreferences(preferencesOf(CARD_ALIAS))
        }

    @Test
    fun eraseLeavesTheMainWalletAndOtherAliasesAlone() =
        runBlocking<Unit> {
            walletFiles(CARD_ALIAS)
            val main = walletFiles(ZcashSdk.DEFAULT_ALIAS)
            val otherCard = walletFiles(OTHER_CARD_ALIAS)
            val otherNetwork = walletFiles(CARD_ALIAS, ZcashNetwork.Testnet)

            assertTrue(SlipstreamSynchronizer.erase(context, NETWORK, CARD_ALIAS))

            (main + otherCard + otherNetwork).forEach { assertTrue(it.exists(), "${it.name} must be kept") }
            verify(context, never()).deleteSharedPreferences(preferencesOf(ZcashSdk.DEFAULT_ALIAS))
            verify(context, never()).deleteSharedPreferences(preferencesOf(OTHER_CARD_ALIAS))
            verify(context, never()).deleteSharedPreferences(
                submitPlanPreferencesName(ZcashNetwork.Testnet.id, CARD_ALIAS)
            )
        }

    @Test
    fun theMainWalletsEraseAlsoDeletesItsSubmitPlans() =
        runBlocking<Unit> {
            val main = walletFiles(ZcashSdk.DEFAULT_ALIAS)

            assertTrue(SlipstreamSynchronizer.erase(context, NETWORK))

            main.forEach { assertFalse(it.exists()) }
            verify(context).deleteSharedPreferences(preferencesOf(ZcashSdk.DEFAULT_ALIAS))
        }

    @Test
    fun anEraseWhoseSubmitPlansRemainReportsFailure() =
        runBlocking<Unit> {
            walletFiles(CARD_ALIAS)
            `when`(context.deleteSharedPreferences(preferencesOf(CARD_ALIAS))).thenReturn(false)

            assertFalse(SlipstreamSynchronizer.erase(context, NETWORK, CARD_ALIAS))
        }

    @Test
    fun anActiveWalletIsNotErased() =
        runBlocking<Unit> {
            val card = walletFiles(CARD_ALIAS)
            val key = SlipstreamKey(NETWORK, CARD_ALIAS)
            InstanceGuard.acquire(key)
            try {
                assertFailsWith<IllegalStateException> { SlipstreamSynchronizer.erase(context, NETWORK, CARD_ALIAS) }
                assertFailsWith<IllegalStateException> {
                    SlipstreamSynchronizer.eraseAlias(context, NETWORK, CARD_ALIAS)
                }
            } finally {
                InstanceGuard.release(key)
            }

            card.forEach { assertTrue(it.exists()) }
            verify(context, never()).deleteSharedPreferences(preferencesOf(CARD_ALIAS))
        }

    @Test
    fun eraseAliasNeverErasesTheMainWallet() =
        runBlocking<Unit> {
            val main = walletFiles(ZcashSdk.DEFAULT_ALIAS)
            val legacyErases = AtomicInteger(0)

            (MAIN_WALLET_ALIASES + "not a valid alias!").forEach { alias ->
                assertFailsWith<IllegalArgumentException>(alias) {
                    SlipstreamSynchronizer.eraseAlias(context, NETWORK, alias) { legacyErases.incrementAndGet() }
                }
            }

            main.forEach { assertTrue(it.exists(), "${it.name} must be kept") }
            verify(context, never()).deleteSharedPreferences(preferencesOf(ZcashSdk.DEFAULT_ALIAS))
            verify(context, never()).deleteSharedPreferences(preferencesOf("${ZcashSdk.DEFAULT_ALIAS}_"))
            assertEquals(0, legacyErases.get())
        }

    @Test
    fun theLegacyLayoutIsErasedOnlyWhenItsFilesExist() =
        runBlocking<Unit> {
            walletFiles(CARD_ALIAS)
            val legacyErases = AtomicInteger(0)

            assertTrue(
                SlipstreamSynchronizer.eraseAlias(context, NETWORK, CARD_ALIAS) { legacyErases.incrementAndGet() }
            )
            assertEquals(0, legacyErases.get(), "a wallet only this engine ran has no legacy layout to erase")

            DataDbPath.legacyOnlyFiles(noBackupRoot, CARD_ALIAS, NETWORK).forEach { legacyFile ->
                walletFiles(CARD_ALIAS)
                legacyFile.writeText("x")
                legacyErases.set(0)

                assertTrue(
                    SlipstreamSynchronizer.eraseAlias(context, NETWORK, CARD_ALIAS) { legacyErases.incrementAndGet() }
                )

                assertEquals(1, legacyErases.get(), "${legacyFile.name} marks a legacy layout")
                legacyFile.delete()
            }
        }

    @Test
    fun aFailingLegacyEraseNeverFailsTheErase() =
        runBlocking<Unit> {
            val card = walletFiles(CARD_ALIAS)
            DataDbPath.legacyOnlyFiles(noBackupRoot, CARD_ALIAS, NETWORK).first().mkdirs()

            val erased =
                SlipstreamSynchronizer.eraseAlias(context, NETWORK, CARD_ALIAS) {
                    throw IllegalStateException("keystore unavailable")
                }

            assertTrue(erased)
            card.forEach { assertFalse(it.exists(), "${it.name} must be deleted") }
            verify(context).deleteSharedPreferences(preferencesOf(CARD_ALIAS))
        }

    @Test
    fun theLegacyLayoutFilesAreNamedAsTheDefaultEngineNamesThem() {
        val names = DataDbPath.legacyOnlyFiles(noBackupRoot, CARD_ALIAS, NETWORK).map { it.name }

        assertEquals(
            listOf(
                "giftcard_abc_mainnet_fs_cache",
                "giftcard_abc_mainnet_pending_transactions.sqlite3",
                "giftcard_abc_mainnet_pending_transactions.sqlite3-journal",
                "giftcard_abc_mainnet_pending_transactions.sqlite3-wal",
                "giftcard_abc_mainnet_pending_transactions.sqlite3-shm"
            ),
            names
        )
        assertTrue(
            DataDbPath.legacyOnlyFiles(noBackupRoot, CARD_ALIAS, NETWORK).all {
                it.parentFile == DataDbPath.dataDbFile(noBackupRoot, CARD_ALIAS, NETWORK).parentFile
            }
        )
    }

    /**
     * The legacy deletion's observations are recorded and asserted after the erase, which logs and swallows any
     * failure of that deletion.
     */
    @Test
    fun theLegacyLayoutIsErasedAfterTheEnginesFilesUnderTheGuard() =
        runBlocking<Unit> {
            val card = walletFiles(CARD_ALIAS)
            DataDbPath.legacyOnlyFiles(noBackupRoot, CARD_ALIAS, NETWORK).first().writeText("x")
            val key = SlipstreamKey(NETWORK, CARD_ALIAS)
            val enginesFilesLeft = mutableListOf<Boolean>()
            val acquiredDuringLegacyErase = mutableListOf<Unit?>()

            val erased =
                SlipstreamSynchronizer.eraseAlias(context, NETWORK, CARD_ALIAS) {
                    enginesFilesLeft += card.any { it.exists() }
                    val acquired = withTimeoutOrNull(SHORT_TIMEOUT_MS) { InstanceGuard.acquire(key) }
                    if (acquired != null) InstanceGuard.release(key)
                    acquiredDuringLegacyErase += acquired
                }

            assertTrue(erased)
            assertEquals(listOf(false), enginesFilesLeft, "the engine's files go first, and the legacy erase runs once")
            assertNull(acquiredDuringLegacyErase.single(), "the guard is held while the legacy layout is erased")
        }

    /** Creates the database, `-wal` and `-shm` files of the wallet at [alias] and [network]. */
    private fun walletFiles(
        alias: String,
        network: ZcashNetwork = NETWORK
    ): List<File> {
        val db = DataDbPath.dataDbFile(noBackupRoot, alias, network)
        db.parentFile?.mkdirs()
        return listOf(db, File("${db.path}-wal"), File("${db.path}-shm")).onEach { it.writeText("x") }
    }

    private fun preferencesOf(alias: String) = submitPlanPreferencesName(NETWORK.id, alias)

    private companion object {
        val NETWORK = ZcashNetwork.Mainnet
        const val CARD_ALIAS = "giftcard_abc"
        const val OTHER_CARD_ALIAS = "giftcard_def"
        const val SHORT_TIMEOUT_MS = 200L

        /** Every spelling of an alias that addresses the main wallet's files. */
        val MAIN_WALLET_ALIASES =
            listOf(ZcashSdk.DEFAULT_ALIAS, "${ZcashSdk.DEFAULT_ALIAS}_", "ZcashSdk", "ZCASHSDK_", "zcashsdk")
    }
}
