package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.internal.GiftCardLinks
import cash.z.ecc.android.sdk.internal.model.JniGiftCard
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.OvkPolicy
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [GiftCardRedeemer] against a fake card wallet: alias rules, how a balance maps onto the status
 * the UI shows, and what a redemption does with its destination wallet.
 */
class GiftCardRedeemerTest {
    private val endpoint = LightWalletEndpoint("localhost", 9067, false)

    private fun card(
        networkId: Int = ZcashNetwork.ID_MAINNET,
        fundingAddress: String = "u1fundingaddress"
    ): GiftCard =
        GiftCard.parse(
            "link",
            object : GiftCardLinks {
                override fun parse(link: String) =
                    JniGiftCard(0, networkId, 3_000_000, -1, null, ByteArray(64), fundingAddress)
            }
        )

    private fun context(): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        return context
    }

    private fun recipient(network: ZcashNetwork = ZcashNetwork.Mainnet): RecipientAddress {
        val address = mock(RecipientAddress::class.java)
        `when`(address.network).thenReturn(network)
        return address
    }

    private fun rejected(transaction: CreatedTransaction) =
        TransactionSubmitResult.Failure(transaction.txId, grpcError = false, code = -1, description = "rejected")

    private fun createdTransaction(tag: String) =
        CreatedTransaction(
            txId = FirstClassByteArray("txid-$tag".toByteArray()),
            raw = FirstClassByteArray("raw-$tag".toByteArray()),
            expiryHeight = null
        )

    /** The card wallet: one account, one proposal, and whatever [submit] says about each transaction. */
    private class FakeCardWallet(
        private val created: List<CreatedTransaction>,
        private val submitResult: (CreatedTransaction) -> TransactionSubmitResult
    ) : CloseableSynchronizer by mock(CloseableSynchronizer::class.java) {
        val fee = Zatoshi(10_000)
        var closed = false
        val submitted = mutableListOf<CreatedTransaction>()
        var ovkPolicy: OvkPolicy? = null

        override val network: ZcashNetwork = ZcashNetwork.Mainnet
        override var onCriticalErrorHandler: ((Throwable?) -> Boolean)? = null

        override suspend fun getAccounts(): List<Account> = listOf(AccountFixture.new())

        override suspend fun proposeSendMax(
            account: Account,
            recipient: RecipientAddress,
            memo: MemoContent?
        ): Proposal {
            val proposal = mock(Proposal::class.java)
            `when`(proposal.totalFeeRequired()).thenReturn(fee)
            return proposal
        }

        override val broadcaster: Broadcaster =
            object : Broadcaster {
                override suspend fun createProposedTransactions(
                    proposal: Proposal,
                    usk: UnifiedSpendingKey,
                    ovkPolicy: OvkPolicy
                ): List<CreatedTransaction> {
                    this@FakeCardWallet.ovkPolicy = ovkPolicy
                    return created
                }

                override suspend fun createTransactionFromPczt(
                    pcztWithProofs: Pczt,
                    pcztWithSignatures: Pczt
                ): List<CreatedTransaction> = error("Not a gift card operation")

                override suspend fun submit(
                    transaction: CreatedTransaction,
                    endpoint: LightWalletEndpoint
                ): TransactionSubmitResult {
                    submitted += transaction
                    return submitResult(transaction)
                }
            }

        override fun close() {
            closed = true
        }
    }

    private class FakeWallets(
        private val cardWallet: FakeCardWallet
    ) : GiftCardWallets {
        val erased = mutableListOf<String>()

        override suspend fun erase(
            context: Context,
            network: ZcashNetwork,
            alias: String
        ) {
            erased += alias
        }

        override suspend fun open(
            context: Context,
            network: ZcashNetwork,
            alias: String,
            birthday: BlockHeight,
            lightWalletEndpoint: LightWalletEndpoint,
            setup: AccountCreateSetup
        ): CloseableSynchronizer = cardWallet

        override suspend fun deriveSpendingKey(
            seed: ByteArray,
            network: ZcashNetwork
        ): UnifiedSpendingKey = mock(UnifiedSpendingKey::class.java)
    }

    /** The user's wallet, remembering what it was asked to record, or refusing to. */
    private class FakeDestination(
        private val failure: Exception? = null
    ) : Synchronizer by mock(Synchronizer::class.java) {
        val recorded = mutableListOf<Pair<ByteArray, ByteArray>>()

        override val network: ZcashNetwork = ZcashNetwork.Mainnet

        override suspend fun recordTrustedTransaction(
            rawTransaction: ByteArray,
            txId: ByteArray
        ) {
            failure?.let { throw it }
            recorded += rawTransaction to txId
        }
    }

    private fun redeemer(cardWallet: FakeCardWallet): Pair<GiftCardRedeemer, FakeWallets> {
        val wallets = FakeWallets(cardWallet)
        val redeemer =
            GiftCardRedeemer.new(
                context = context(),
                card = card(),
                network = ZcashNetwork.Mainnet,
                lightWalletEndpoint = endpoint,
                alias = GiftCardRedeemer.defaultAlias(card()),
                wallets = wallets
            )
        return redeemer to wallets
    }

    @Test
    fun defaultAliasIsValidUniquePerCardAndNeverTheDefault() {
        val alias = GiftCardRedeemer.defaultAlias(card())
        assertNotEquals(ZcashSdk.DEFAULT_ALIAS, alias)
        assertTrue(alias.length in ZcashSdk.ALIAS_MIN_LENGTH..ZcashSdk.ALIAS_MAX_LENGTH)
        assertTrue(alias.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        assertEquals(alias, GiftCardRedeemer.defaultAlias(card()))
        assertNotEquals(alias, GiftCardRedeemer.defaultAlias(card(fundingAddress = "u1other")))

        val redeemer = GiftCardRedeemer.new(context(), card(), ZcashNetwork.Mainnet, endpoint)
        assertEquals(alias, redeemer.alias)
        assertEquals(ZcashNetwork.Mainnet, redeemer.network)
    }

    @Test
    fun rejectsTheDefaultAliasAndInvalidAliases() {
        listOf(ZcashSdk.DEFAULT_ALIAS, "", "a/b", "x".repeat(ZcashSdk.ALIAS_MAX_LENGTH + 1)).forEach { alias ->
            assertFailsWith<IllegalArgumentException> {
                GiftCardRedeemer.new(context(), card(), ZcashNetwork.Mainnet, endpoint, alias)
            }
        }
    }

    @Test
    fun rejectsACardForAnotherNetwork() {
        assertFailsWith<GiftCardException.NetworkMismatch> {
            GiftCardRedeemer.new(context(), card(networkId = ZcashNetwork.ID_TESTNET), ZcashNetwork.Mainnet, endpoint)
        }
    }

    @Test
    fun redeemWithADestinationRecordsTheSubmittedClaimThereAsTrusted() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim)) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination()

            val redemption = redeemer.redeem(recipient(), destination = destination)

            assertEquals(listOf(TransactionSubmitResult.Success(claim.txId)), redemption.results)
            assertTrue(redemption.isSubmitted)
            assertTrue(redemption.recordedInDestination)
            assertEquals(cardWallet.fee, redemption.fee)
            assertEquals(1, destination.recorded.size)
            assertTrue(claim.raw.byteArray.contentEquals(destination.recorded.single().first))
            assertTrue(claim.txId.byteArray.contentEquals(destination.recorded.single().second))
            // The issuer can rederive the card's key, so the sweep must not be decryptable with it.
            assertEquals(OvkPolicy.Discard, cardWallet.ovkPolicy)
            assertEquals(listOf(claim), cardWallet.submitted)
        }

    @Test
    fun redeemWithoutADestinationTouchesNone() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim)) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, _) = redeemer(cardWallet)
            val uninvolved = mock(Synchronizer::class.java)

            val redemption = redeemer.redeem(recipient())

            assertTrue(redemption.isSubmitted)
            assertFalse(redemption.recordedInDestination)
            assertEquals(listOf(claim), cardWallet.submitted)
            verifyNoInteractions(uninvolved)
        }

    @Test
    fun aDestinationFailureLeavesTheRedemptionSuccessful() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim)) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination(failure = IllegalStateException("database is locked"))

            val redemption = redeemer.redeem(recipient(), destination = destination)

            assertEquals(listOf(TransactionSubmitResult.Success(claim.txId)), redemption.results)
            assertTrue(redemption.isSubmitted)
            assertFalse(redemption.recordedInDestination)
            assertTrue(destination.recorded.isEmpty())
        }

    @Test
    fun onlySubmittedTransactionsAreRecordedAndLaterOnesAreNotAttemptedAfterAFailure() =
        runBlocking {
            val first = createdTransaction("first")
            val second = createdTransaction("second")
            val third = createdTransaction("third")
            val cardWallet =
                FakeCardWallet(listOf(first, second, third)) {
                    if (it == second) {
                        rejected(it)
                    } else {
                        TransactionSubmitResult.Success(it.txId)
                    }
                }
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination()

            val redemption = redeemer.redeem(recipient(), destination = destination)

            assertEquals(
                listOf(
                    TransactionSubmitResult.Success(first.txId),
                    rejected(second),
                    TransactionSubmitResult.NotAttempted(third.txId)
                ),
                redemption.results
            )
            assertFalse(redemption.isSubmitted)
            assertEquals(listOf(first, second), cardWallet.submitted)
            // What did reach the network is recorded, and nothing else.
            assertEquals(1, destination.recorded.size)
            assertTrue(first.txId.byteArray.contentEquals(destination.recorded.single().second))
            assertTrue(redemption.recordedInDestination)
        }

    @Test
    fun redeemRejectsADestinationOnAnotherNetworkBeforeOpeningTheCardWallet() =
        runBlocking {
            val cardWallet = FakeCardWallet(emptyList()) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, wallets) = redeemer(cardWallet)
            val destination =
                object : Synchronizer by mock(Synchronizer::class.java) {
                    override val network: ZcashNetwork = ZcashNetwork.Testnet
                }

            assertFailsWith<GiftCardException.NetworkMismatch> {
                redeemer.redeem(recipient(), destination = destination)
            }
            assertTrue(wallets.erased.isEmpty())
        }

    @Test
    fun closeDeletesTheCardWalletAndRefusesFurtherUse() =
        runBlocking {
            val cardWallet = FakeCardWallet(emptyList()) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, wallets) = redeemer(cardWallet)
            redeemer.redeem(recipient())
            assertEquals(listOf(redeemer.alias), wallets.erased)

            redeemer.close()

            assertFailsWith<GiftCardException.Closed> { redeemer.redeem(recipient()) }
            assertTrue(cardWallet.closed)
            assertEquals(listOf(redeemer.alias, redeemer.alias), wallets.erased)
        }

    @Test
    fun mapsBalancesToStatus() {
        fun pool(
            available: Long = 0,
            pending: Long = 0
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

        val empty = AccountBalance(pool(), pool(), pool(), Zatoshi(0))
        assertEquals(GiftCardRedeemer.Status.Empty, empty.toGiftCardBalance().toStatus())

        // Transparent funds cannot be swept by a shielded send-max and are not reported.
        val transparentOnly = AccountBalance(pool(), pool(), pool(), Zatoshi(5_000))
        assertEquals(GiftCardRedeemer.Status.Empty, transparentOnly.toGiftCardBalance().toStatus())

        val pending = AccountBalance(pool(), pool(), pool(pending = 1_010_000), Zatoshi(0))
        assertEquals(
            GiftCardRedeemer.Status.Pending(
                GiftCardRedeemer.Balance(Zatoshi(1_010_000), Zatoshi(0), Zatoshi(1_010_000))
            ),
            pending.toGiftCardBalance().toStatus()
        )

        val ready =
            AccountBalance(pool(), pool(available = 10_000), pool(available = 1_000_000, pending = 5), Zatoshi(0))
        assertEquals(
            GiftCardRedeemer.Status.Ready(
                GiftCardRedeemer.Balance(Zatoshi(1_010_005), Zatoshi(1_010_000), Zatoshi(5))
            ),
            ready.toGiftCardBalance().toStatus()
        )
    }
}
