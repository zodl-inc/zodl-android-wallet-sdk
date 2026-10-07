package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.internal.GiftCardLinks
import cash.z.ecc.android.sdk.internal.SynchronizerEngineFactory
import cash.z.ecc.android.sdk.internal.model.JniGiftCard
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [GiftCardRedeemers] and [EngineGiftCardWallets] against a fake engine factory: the card wallet is opened and erased
 * by the engine the SDK was built with, under the card's alias, and the engine-backed redeemer erases the card wallet
 * through that engine both before opening it and when closed. No spelling of the main wallet's alias is ever opened or
 * erased.
 */
class GiftCardRedeemersTest {
    @Test
    fun theCardWalletIsOpenedByTheEngineUnderItsAlias() =
        runBlocking<Unit> {
            val factory = FakeEngineFactory()
            val setup = setup()
            val handler: (Throwable?) -> Boolean = { false }

            val opened = openCardWallet(factory, ALIAS, setup, handler)

            assertSame(factory.openedWallet, opened)
            val request = factory.openRequests.single()
            assertEquals(ALIAS, request.alias)
            assertEquals(ZcashNetwork.Mainnet, request.network)
            assertEquals(BlockHeight.new(BIRTHDAY), request.birthday)
            assertTrue(request.isBirthdayExact)
            assertEquals(ENDPOINT, request.endpoint)
            assertTrue(request.isTorEnabled)
            assertSame(setup, request.setup)
            assertSame(handler, request.onCriticalError)
        }

    @Test
    fun theCardWalletIsNeverOpenedUnderTheMainWalletsAlias() =
        runBlocking<Unit> {
            val factory = FakeEngineFactory()

            MAIN_WALLET_ALIASES.forEach { alias ->
                assertFailsWith<IllegalArgumentException>(alias) { openCardWallet(factory, alias) }
            }

            assertTrue(factory.openRequests.isEmpty())
        }

    @Test
    fun theCardWalletIsErasedByTheEngine() =
        runBlocking<Unit> {
            val factory = FakeEngineFactory()

            EngineGiftCardWallets(factory).erase(context(), ZcashNetwork.Mainnet, ALIAS)

            assertEquals(listOf(ZcashNetwork.Mainnet to ALIAS), factory.erased)
        }

    /** An engine reporting that some of the card wallet's files remain fails the erase, so that it is tried again. */
    @Test
    fun anEraseThatLeavesFilesBehindFails() =
        runBlocking<Unit> {
            val factory = FakeEngineFactory(eraseResults = listOf(false))

            assertFailsWith<IllegalStateException> {
                EngineGiftCardWallets(factory).erase(context(), ZcashNetwork.Mainnet, ALIAS)
            }
        }

    /**
     * A redeemer whose card wallet's files remain after an erase keeps the card's alias and erases it again, and
     * releases the alias only once the engine reports nothing left.
     */
    @Test
    fun aRedeemerKeepsTheAliasUntilTheEngineReportsTheCardWalletGone() =
        runBlocking<Unit> {
            val factory =
                FakeEngineFactory(
                    openFailure = IllegalStateException("no engine in a unit test"),
                    eraseResults = listOf(true, false, true)
                )
            val aliases = GiftCardAliases()
            val card = card()
            val redeemer =
                GiftCardRedeemer.new(
                    context = context(),
                    card = card,
                    network = ZcashNetwork.Mainnet,
                    lightWalletEndpoint = ENDPOINT,
                    isTorEnabled = false,
                    alias = ALIAS,
                    wallets = EngineGiftCardWallets(factory),
                    aliases = aliases
                )
            assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() }

            assertFailsWith<IllegalStateException> { redeemer.close() }
            assertFalse(aliases.acquire(ZcashNetwork.Mainnet, ALIAS))

            withTimeout(RETRY_WAIT) {
                while (!aliases.acquire(ZcashNetwork.Mainnet, ALIAS)) delay(POLL_INTERVAL)
            }
            assertEquals(3, factory.erased.size)
        }

    @Test
    fun aRedeemerErasesItsCardWalletThroughTheEngineBeforeOpeningAndWhenClosed() =
        runBlocking<Unit> {
            val factory = FakeEngineFactory(openFailure = IllegalStateException("no engine in a unit test"))
            val card = card()
            val redeemer =
                GiftCardRedeemers.new(
                    context = context(),
                    card = card,
                    network = ZcashNetwork.Mainnet,
                    lightWalletEndpoint = ENDPOINT,
                    isTorEnabled = false,
                    alias = GiftCardRedeemer.defaultAlias(card),
                    factory = factory
                )

            assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() }
            redeemer.close()

            val alias = GiftCardRedeemer.defaultAlias(card)
            assertEquals(alias, redeemer.alias)
            assertEquals(listOf(alias), factory.openRequests.map { it.alias })
            assertEquals(listOf(ZcashNetwork.Mainnet to alias, ZcashNetwork.Mainnet to alias), factory.erased)
        }

    @Test
    fun aRedeemerForACardOnAnotherNetworkIsRefused() {
        assertFailsWith<GiftCardException.NetworkMismatch> {
            GiftCardRedeemers.new(
                context = context(),
                card = card(),
                network = ZcashNetwork.Testnet,
                lightWalletEndpoint = ENDPOINT,
                isTorEnabled = false,
                alias = ALIAS,
                factory = FakeEngineFactory()
            )
        }
    }

    @Test
    fun aRedeemerNeverUsesTheMainWalletsAlias() {
        MAIN_WALLET_ALIASES.forEach { alias ->
            assertFailsWith<IllegalArgumentException>(alias) {
                GiftCardRedeemers.new(
                    context = context(),
                    card = card(),
                    network = ZcashNetwork.Mainnet,
                    lightWalletEndpoint = ENDPOINT,
                    isTorEnabled = false,
                    alias = alias,
                    factory = FakeEngineFactory()
                )
            }
        }
    }

    @Test
    fun anOrphanedCardWalletIsErasedByTheEngineUnderItsAlias() =
        runBlocking<Unit> {
            val factory = FakeEngineFactory()

            assertTrue(GiftCardRedeemers.erase(context(), ZcashNetwork.Testnet, ALIAS, factory))

            assertEquals(listOf(ZcashNetwork.Testnet to ALIAS), factory.erased)
        }

    @Test
    fun theMainWalletIsNeverErasedAsACardWallet() =
        runBlocking<Unit> {
            val factory = FakeEngineFactory()

            MAIN_WALLET_ALIASES.forEach { alias ->
                assertFailsWith<IllegalArgumentException>(alias) {
                    GiftCardRedeemers.erase(context(), ZcashNetwork.Mainnet, alias, factory)
                }
            }

            assertTrue(factory.erased.isEmpty())
        }

    private suspend fun openCardWallet(
        factory: FakeEngineFactory,
        alias: String,
        setup: AccountCreateSetup = setup(),
        onCriticalError: (Throwable?) -> Boolean = { false }
    ) = EngineGiftCardWallets(factory).open(
        context = context(),
        network = ZcashNetwork.Mainnet,
        alias = alias,
        birthday = BlockHeight.new(BIRTHDAY),
        isBirthdayExact = true,
        lightWalletEndpoint = ENDPOINT,
        isTorEnabled = true,
        setup = setup,
        onCriticalError = onCriticalError
    )

    /** What [FakeEngineFactory.openHelperWallet] was asked for. */
    @Suppress("LongParameterList")
    private class OpenRequest(
        val network: ZcashNetwork,
        val alias: String,
        val birthday: BlockHeight,
        val isBirthdayExact: Boolean,
        val endpoint: LightWalletEndpoint,
        val setup: AccountCreateSetup,
        val isTorEnabled: Boolean,
        val onCriticalError: (Throwable?) -> Boolean
    )

    /**
     * Records helper wallet requests; opens [openedWallet], or fails with [openFailure]. Never opens a main wallet.
     * Each erase reports the next of [eraseResults], then `true` once they run out.
     */
    private class FakeEngineFactory(
        private val openFailure: Exception? = null,
        private val eraseResults: List<Boolean> = emptyList()
    ) : SynchronizerEngineFactory {
        val openRequests = mutableListOf<OpenRequest>()
        val erased = mutableListOf<Pair<ZcashNetwork, String>>()
        val openedWallet = OpenedCardWallet(mock(CloseableSynchronizer::class.java), startsAtBirthday = false)

        override suspend fun new(
            context: Context,
            zcashNetwork: ZcashNetwork,
            lightWalletEndpoint: LightWalletEndpoint,
            birthday: BlockHeight?,
            setup: AccountCreateSetup?,
            walletInitMode: WalletInitMode,
            isTorEnabled: Boolean,
            isExchangeRateEnabled: Boolean
        ): CloseableSynchronizer = error("Not a gift card operation")

        override suspend fun erase(
            appContext: Context,
            network: ZcashNetwork
        ): Boolean = error("Not a gift card operation")

        override suspend fun openHelperWallet(
            context: Context,
            zcashNetwork: ZcashNetwork,
            alias: String,
            birthday: BlockHeight,
            isBirthdayExact: Boolean,
            lightWalletEndpoint: LightWalletEndpoint,
            setup: AccountCreateSetup,
            isTorEnabled: Boolean,
            onCriticalError: (Throwable?) -> Boolean
        ): OpenedCardWallet {
            openRequests +=
                OpenRequest(
                    network = zcashNetwork,
                    alias = alias,
                    birthday = birthday,
                    isBirthdayExact = isBirthdayExact,
                    endpoint = lightWalletEndpoint,
                    setup = setup,
                    isTorEnabled = isTorEnabled,
                    onCriticalError = onCriticalError
                )
            openFailure?.let { throw it }
            return openedWallet
        }

        override suspend fun eraseHelperWallet(
            appContext: Context,
            network: ZcashNetwork,
            alias: String
        ): Boolean {
            erased += network to alias
            return eraseResults.getOrElse(erased.size - 1) { true }
        }
    }

    private companion object {
        val RETRY_WAIT = 10.seconds
        val POLL_INTERVAL = 50.milliseconds

        fun card(): GiftCard =
            GiftCard.parse(
                "link",
                object : GiftCardLinks {
                    override fun parse(link: String) =
                        JniGiftCard(0, ZcashNetwork.ID_MAINNET, BIRTHDAY, -1, null, ByteArray(64), "u1fundingaddress")
                }
            )
    }
}
