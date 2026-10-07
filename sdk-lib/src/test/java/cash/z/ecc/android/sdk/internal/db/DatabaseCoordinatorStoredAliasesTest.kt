package cash.z.ecc.android.sdk.internal.db

import android.content.Context
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How the stored card wallets are found. Every alias found is erased by the app's startup sweep, so a name that is
 * not a card wallet of the asked network must never come back.
 */
class DatabaseCoordinatorStoredAliasesTest {
    private fun walletFiles(
        alias: String,
        network: ZcashNetwork
    ): List<String> {
        val base = "${alias}_${network.networkName}_"
        val databases =
            listOf(DatabaseCoordinator.DB_DATA_NAME, DatabaseCoordinator.DB_PENDING_TRANSACTIONS_NAME).map { base + it }
        val rollbackFiles =
            databases.flatMap { database ->
                listOf(
                    DatabaseCoordinator.DATABASE_FILE_JOURNAL_SUFFIX,
                    DatabaseCoordinator.DATABASE_FILE_WAL_SUFFIX,
                    DatabaseCoordinator.DATABASE_FILE_SHM_SUFFIX
                ).map { "$database-$it" }
            }
        return databases + rollbackFiles + (base + DatabaseCoordinator.DB_FS_BLOCK_DB_ROOT_NAME)
    }

    private fun aliases(
        fileNames: List<String>,
        network: ZcashNetwork = ZcashNetwork.Mainnet
    ) = DatabaseCoordinator.aliasesAmong(fileNames, network, PREFIX)

    @Test
    fun everyFileOfACardWalletCollapsesToItsAlias() {
        assertEquals(setOf(CARD), aliases(walletFiles(CARD, ZcashNetwork.Mainnet)))
    }

    @Test
    fun eachKindOfDatabaseAloneIsEnoughToFindTheWallet() {
        walletFiles(CARD, ZcashNetwork.Mainnet).forEach { name ->
            assertEquals(setOf(CARD), aliases(listOf(name)), name)
        }
    }

    @Test
    fun severalCardWalletsAreAllFound() {
        val files = walletFiles(CARD, ZcashNetwork.Mainnet) + walletFiles(OTHER_CARD, ZcashNetwork.Mainnet)

        assertEquals(setOf(CARD, OTHER_CARD), aliases(files.shuffled()))
    }

    @Test
    fun walletsOfTheOtherNetworkAreNeverReturned() {
        val files = walletFiles(CARD, ZcashNetwork.Mainnet) + walletFiles(OTHER_CARD, ZcashNetwork.Testnet)

        assertEquals(setOf(CARD), aliases(files, ZcashNetwork.Mainnet))
        assertEquals(setOf(OTHER_CARD), aliases(files, ZcashNetwork.Testnet))
    }

    @Test
    fun anAliasNamingTheOtherNetworkStaysOnItsOwn() {
        val alias = "${PREFIX}abc_testnet"
        val files = walletFiles(alias, ZcashNetwork.Mainnet)

        assertEquals(setOf(alias), aliases(files, ZcashNetwork.Mainnet))
        assertTrue(aliases(files, ZcashNetwork.Testnet).isEmpty())
    }

    @Test
    fun theMainWalletIsNeverReturned() {
        val files =
            walletFiles(ZcashSdk.DEFAULT_ALIAS, ZcashNetwork.Mainnet) +
                walletFiles(DatabaseCoordinator.ALIAS_LEGACY, ZcashNetwork.Mainnet)

        assertTrue(aliases(files).isEmpty())
    }

    @Test
    fun theBarePrefixIsNotAnAlias() {
        val files =
            walletFiles(PREFIX.removeSuffix("_"), ZcashNetwork.Mainnet) +
                walletFiles(PREFIX, ZcashNetwork.Mainnet) +
                "${PREFIX}_${ZcashNetwork.Mainnet.networkName}_${DatabaseCoordinator.DB_DATA_NAME}"

        assertTrue(aliases(files).isEmpty())
    }

    @Test
    fun aNameThatOnlyContainsThePrefixIsNotACardWallet() {
        val files =
            walletFiles("main_$PREFIX", ZcashNetwork.Mainnet) +
                walletFiles("x$CARD", ZcashNetwork.Mainnet) +
                walletFiles(PREFIX.uppercase() + "abc", ZcashNetwork.Mainnet)

        assertTrue(aliases(files).isEmpty())
    }

    @Test
    fun unrelatedFilesAreIgnored() {
        val files =
            listOf(
                "tor",
                "$CARD.txt",
                "${CARD}_mainnet_data.sqlite3.bak",
                "${CARD}_mainnet_cache.sqlite3",
                "${CARD}_mainnet_${DatabaseCoordinator.DB_DATA_NAME_LEGACY}",
                "${CARD}_mainnet_data.sqlite3-backup",
                CARD,
                ""
            )

        assertTrue(aliases(files).isEmpty())
    }

    @Test
    fun noFilesMeansNoAliases() {
        assertTrue(aliases(emptyList()).isEmpty())
    }

    @Test
    fun anEmptyPrefixIsRefusedBeforeAnyFileIsListed() =
        runBlocking<Unit> {
            val context = mock(Context::class.java)
            `when`(context.applicationContext).thenReturn(context)

            assertFailsWith<IllegalArgumentException> {
                DatabaseCoordinator.getInstance(context).storedAliases(ZcashNetwork.Mainnet, "")
            }
        }

    private companion object {
        const val PREFIX = "giftcard_"
        const val CARD = "${PREFIX}0123456789abcdef0123456789abcdef"
        const val OTHER_CARD = "${PREFIX}fedcba9876543210fedcba9876543210"
    }
}
