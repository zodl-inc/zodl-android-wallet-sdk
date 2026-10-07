package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.BirthdayException
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.internal.GiftCardLinks
import cash.z.ecc.android.sdk.internal.model.JniGiftCard
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.OvkPolicy
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.RawTransaction
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedAddressRequest
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [GiftCardRedeemer] against a fake card wallet: alias rules, how a balance maps onto the status
 * the UI shows, and what a redemption does with its destination wallet.
 */
class GiftCardRedeemerTest {
    private val endpoint = GIFT_CARD_TEST_ENDPOINT

    /** Still covers the deprecated, SdkSynchronizer-only entry point, which keeps validating its arguments. */
    @Suppress("DEPRECATION")
    @Test
    fun defaultAliasIsValidUniquePerCardAndNeverTheDefault() {
        val alias = GiftCardRedeemer.defaultAlias(card())
        assertNotEquals(ZcashSdk.DEFAULT_ALIAS, alias)
        assertTrue(alias.length in ZcashSdk.ALIAS_MIN_LENGTH..ZcashSdk.ALIAS_MAX_LENGTH)
        assertTrue(alias.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        assertEquals(alias, GiftCardRedeemer.defaultAlias(card()))
        assertNotEquals(alias, GiftCardRedeemer.defaultAlias(card(fundingAddress = "u1other")))

        val redeemer = GiftCardRedeemer.new(context(), card(), ZcashNetwork.Mainnet, endpoint, isTorEnabled = false)
        assertEquals(alias, redeemer.alias)
        assertEquals(ZcashNetwork.Mainnet, redeemer.network)
    }

    /** Still covers the deprecated, SdkSynchronizer-only entry point, which keeps validating its arguments. */
    @Suppress("DEPRECATION")
    @Test
    fun rejectsTheDefaultAliasAndInvalidAliases() {
        listOf(
            ZcashSdk.DEFAULT_ALIAS,
            "${ZcashSdk.DEFAULT_ALIAS}_",
            "ZcashSdk",
            "ZcashSdk_",
            "",
            "a/b",
            "x".repeat(ZcashSdk.ALIAS_MAX_LENGTH + 1)
        ).forEach { alias ->
            assertFailsWith<IllegalArgumentException> {
                GiftCardRedeemer.new(
                    context(),
                    card(),
                    ZcashNetwork.Mainnet,
                    endpoint,
                    isTorEnabled = false,
                    alias = alias
                )
            }
        }
    }

    /** Still covers the deprecated, SdkSynchronizer-only entry point, which keeps validating its arguments. */
    @Suppress("DEPRECATION")
    @Test
    fun rejectsACardForAnotherNetwork() {
        assertFailsWith<GiftCardException.NetworkMismatch> {
            GiftCardRedeemer.new(
                context(),
                card(networkId = ZcashNetwork.ID_TESTNET),
                ZcashNetwork.Mainnet,
                endpoint,
                isTorEnabled = false
            )
        }
    }

    /** The issuer can rederive the card's key, so the sweep must not be decryptable with it. */
    @Test
    fun redeemWithADestinationRecordsTheSubmittedClaimThereAsTrusted() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim)) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination()

            val redemption = redeemer.checkAndRedeem(destination)

            assertEquals(listOf(TransactionSubmitResult.Success(claim.txId)), redemption.results)
            assertTrue(redemption.isSubmitted)
            assertTrue(redemption.recordedInDestination)
            assertEquals(cardWallet.fee, redemption.fee)
            assertEquals(1, destination.recorded.size)
            assertTrue(claim.raw.byteArray.contentEquals(destination.recorded.single().first))
            assertTrue(claim.txId.byteArray.contentEquals(destination.recorded.single().second))
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

            val redemption = redeemer.checkAndRedeem()

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

            val redemption = redeemer.checkAndRedeem(destination)

            assertEquals(listOf(TransactionSubmitResult.Success(claim.txId)), redemption.results)
            assertTrue(redemption.isSubmitted)
            assertFalse(redemption.recordedInDestination)
            assertTrue(destination.recorded.isEmpty())
        }

    /**
     * Contract test: when the destination refuses to trust the claim, as the native backend does
     * for a claim paying another wallet, the claim is reported as not recorded and the redemption
     * still stands. The refusal is scripted by [UninvolvedDestination], so this passes whether or
     * not the native backend refuses; the Rust tests of `set_trust_of_stored_transaction` cover
     * that it does.
     */
    @Test
    fun aClaimTheDestinationDidNotStoreIsReportedAsNotRecorded() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim)) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, _) = redeemer(cardWallet)
            val destination = UninvolvedDestination(claimTxId = claim.txId)

            val redemption = redeemer.checkAndRedeem(destination)

            assertEquals(listOf(TransactionSubmitResult.Success(claim.txId)), redemption.results)
            assertTrue(redemption.isSubmitted)
            assertFalse(redemption.recordedInDestination)
            assertEquals(listOf(claim), cardWallet.submitted)
            assertTrue(claim.txId.byteArray.contentEquals(destination.trustAttempts.single()))
        }

    /** What did reach the network is recorded, and nothing else. */
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

            val redemption = redeemer.checkAndRedeem(destination)

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
            redeemer.check()
            assertEquals(listOf(redeemer.alias), wallets.erased)

            redeemer.close()

            assertFailsWith<GiftCardException.Closed> { redeemer.redeem(recipient()) }
            assertFailsWith<GiftCardException.Closed> { redeemer.check() }
            assertTrue(cardWallet.closed)
            assertEquals(listOf(redeemer.alias, redeemer.alias), wallets.erased)
        }

    /** Transparent funds cannot be swept by a shielded send-max and are not reported. */
    @Test
    fun mapsBalancesToStatus() {
        val empty = AccountBalance(pool(), pool(), pool(), Zatoshi(0))
        assertEquals(GiftCardRedeemer.Status.Empty, empty.toGiftCardBalance().toStatus())

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
                GiftCardRedeemer.Balance(Zatoshi(1_010_005), Zatoshi(1_010_000), Zatoshi(5)),
                GiftCardRedeemer.MINIMUM_FEE
            ),
            ready.toGiftCardBalance().toStatus()
        )
    }

    /** Dust that is spendable now plus funds still confirming is pending: wait for them. */
    @Test
    fun aCardHoldingNoMoreThanTheFeeIsEmptyAndOneAboveItIsReady() {
        assertEquals(10_000, GiftCardRedeemer.MINIMUM_FEE.value)
        assertEquals(GiftCardRedeemer.Status.Empty, balance(available = 10_000).toGiftCardBalance().toStatus())
        assertEquals(GiftCardRedeemer.Status.Empty, balance(pending = 10_000).toGiftCardBalance().toStatus())
        assertIs<GiftCardRedeemer.Status.Ready>(balance(available = 10_001).toGiftCardBalance().toStatus())
        assertIs<GiftCardRedeemer.Status.Pending>(
            balance(available = 5_000, pending = 1_000_000).toGiftCardBalance().toStatus()
        )
    }

    @Test
    fun redeemBeforeCheckIsRefusedWithoutTouchingTheCardWallet() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList()) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, wallets) = redeemer(cardWallet)

            assertFailsWith<GiftCardException.NotChecked> { redeemer.redeem(recipient()) }

            assertTrue(wallets.erased.isEmpty())
            assertFalse(cardWallet.proposed)
        }

    @Test
    fun aDustCardIsNothingToRedeemWithoutAProposal() =
        runBlocking<Unit> {
            val cardWallet =
                FakeCardWallet(emptyList(), spendable = 10_000) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, _) = redeemer(cardWallet)

            assertEquals(GiftCardRedeemer.Status.Empty, redeemer.check())
            assertFailsWith<GiftCardException.NothingToRedeem> { redeemer.redeem(recipient()) }
            assertFalse(cardWallet.proposed)
        }

    @Test
    fun aTypedInsufficientFundsProposalFailureIsNothingToRedeem() =
        runBlocking<Unit> {
            val failure = TransactionEncoderException.InsufficientFundsException(RuntimeException())
            val cardWallet =
                FakeCardWallet(emptyList(), proposalFailure = failure) { TransactionSubmitResult.Success(it.txId) }
            val (redeemer, _) = redeemer(cardWallet)
            redeemer.check()

            val thrown = assertFailsWith<GiftCardException.NothingToRedeem> { redeemer.redeem(recipient()) }
            assertSame(failure, thrown.cause)
        }

    /** The card is not checked, so redeeming is refused rather than attempted on a missing wallet. */
    @Test
    fun aWalletCreationFailureIsReportedAsSyncFailed() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList()) { TransactionSubmitResult.Success(it.txId) }
            val failure = BirthdayException.BirthdayFileNotFoundException("checkpoints", BlockHeight.new(1))
            val (redeemer, _) = redeemer(cardWallet, wallets = FakeWallets(cardWallet, openFailure = failure))

            val thrown = assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() }
            assertSame(failure, thrown.cause)
            assertFailsWith<GiftCardException.NotChecked> { redeemer.redeem(recipient()) }
        }

    @Test
    fun theCardWalletUsesTheConnectionModeItIsGiven() =
        runBlocking {
            listOf(true, false).forEach { isTorEnabled ->
                val cardWallet = FakeCardWallet(emptyList()) { TransactionSubmitResult.Success(it.txId) }
                val (redeemer, wallets) = redeemer(cardWallet, isTorEnabled = isTorEnabled)
                redeemer.check()
                assertEquals(listOf(isTorEnabled), wallets.torSettings)
            }
        }

    /** The refused redeemer neither erases the wallet in use nor erases it when closed. */
    @Test
    fun aSecondRedeemerForTheSameCardIsRefusedUntilTheFirstIsClosed() =
        runBlocking {
            val aliases = GiftCardAliases()
            val first = FakeCardWallet(emptyList()) { TransactionSubmitResult.Success(it.txId) }
            val second = FakeCardWallet(emptyList()) { TransactionSubmitResult.Success(it.txId) }
            val (firstRedeemer, firstWallets) = redeemer(first, aliases = aliases)
            val (secondRedeemer, secondWallets) = redeemer(second, aliases = aliases)

            firstRedeemer.check()
            assertFailsWith<GiftCardException.InUse> { secondRedeemer.check() }
            secondRedeemer.close()
            assertTrue(secondWallets.erased.isEmpty())
            assertFalse(first.closed)

            firstRedeemer.close()
            assertEquals(listOf(firstRedeemer.alias, firstRedeemer.alias), firstWallets.erased)

            val (thirdRedeemer, _) = redeemer(second, aliases = aliases)
            assertIs<GiftCardRedeemer.Status.Ready>(thirdRedeemer.check())
            thirdRedeemer.close()
        }

    @Test
    fun readyCarriesTheFeeTheCardsNotesRequireAndTheAmountARedemptionSends() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim), fee = Zatoshi(25_000))
            val (redeemer, _) = redeemer(cardWallet)

            val ready = assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            assertEquals(Zatoshi(25_000), ready.fee)
            assertEquals(Zatoshi(975_000), ready.redeemable)
            assertEquals(Zatoshi(975_000), redeemer.redeem(recipient()).amount)
        }

    @Test
    fun aCardWhoseNotesNeedMoreThanItHoldsIsNotReady() =
        runBlocking<Unit> {
            val empty = FakeCardWallet(emptyList(), spendable = 15_000, fee = Zatoshi(20_000))
            assertEquals(GiftCardRedeemer.Status.Empty, redeemer(empty).first.check())

            val failure = TransactionEncoderException.InsufficientFundsException(RuntimeException())
            val pending =
                FakeCardWallet(emptyList(), spendable = 15_000, pending = 50_000, proposalFailure = failure)
            assertIs<GiftCardRedeemer.Status.Pending>(redeemer(pending).first.check())
        }

    @Test
    fun closeCancelsACheckInProgressAndReturnsPromptly() =
        runBlocking<Unit> {
            val syncing = MutableStateFlow(Synchronizer.Status.SYNCING)
            val cardWallet = FakeCardWallet(emptyList(), status = syncing)
            val (redeemer, wallets) = redeemer(cardWallet)

            val check = async { runCatching { redeemer.check() } }
            wallets.opened.await()
            withTimeout(5.seconds) { redeemer.close() }

            assertIs<GiftCardException.Closed>(check.await().exceptionOrNull())
            assertTrue(cardWallet.closed)
            assertEquals(listOf(redeemer.alias, redeemer.alias), wallets.erased)
            assertFailsWith<GiftCardException.Closed> { redeemer.check() }
        }

    /**
     * The handler is called while the card wallet is still being opened, i.e. before it could
     * have been installed on a running synchronizer. The error is looked for along the cause
     * chain: with assertions on, coroutines' stack trace recovery wraps the thrown exception in a
     * copy of itself.
     */
    @Test
    fun aCriticalErrorWhileTheCardWalletStartsFailsTheCheck() =
        runBlocking<Unit> {
            val error = IllegalStateException("critical")
            val syncing = MutableStateFlow(Synchronizer.Status.SYNCING)
            val cardWallet = FakeCardWallet(emptyList(), status = syncing)
            val wallets = FakeWallets(listOf(cardWallet), onOpen = { handler -> handler(error) })
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val thrown = withTimeout(5.seconds) { assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() } }
            assertTrue(generateSequence(thrown.cause) { it.cause }.any { it === error })
        }

    @Test
    fun aCardWalletThatStaysDisconnectedFailsTheCheckBeforeTheTimeout() =
        runBlocking<Unit> {
            val disconnected = MutableStateFlow(Synchronizer.Status.DISCONNECTED)
            val cardWallet = FakeCardWallet(emptyList(), status = disconnected)
            val (redeemer, _) = redeemer(cardWallet)

            withTimeout(5.seconds) {
                assertFailsWith<GiftCardException.SyncFailed> {
                    redeemer.check(timeout = 1.seconds * 600, disconnectedTimeout = 50.milliseconds)
                }
            }
        }

    @Test
    fun aBriefDisconnectionDoesNotFailTheCheck() =
        runBlocking<Unit> {
            val reconnecting =
                flow {
                    emit(Synchronizer.Status.DISCONNECTED)
                    delay(20.milliseconds)
                    emit(Synchronizer.Status.SYNCED)
                    awaitCancellation()
                }
            val cardWallet = FakeCardWallet(emptyList(), status = reconnecting)
            val (redeemer, _) = redeemer(cardWallet)

            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check(disconnectedTimeout = 5.seconds))
        }

    @Test
    fun anExactBirthdayScanThatFindsNothingIsRetriedFromTheCheckpoint() =
        runBlocking {
            val nothing = FakeCardWallet(emptyList(), spendable = 0, history = 0)
            val funded = FakeCardWallet(emptyList())
            val wallets = FakeWallets(listOf(nothing, funded))
            val (redeemer, _) = redeemer(nothing, wallets = wallets)

            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            assertEquals(listOf(true, false), wallets.exactBirthdays)
            assertTrue(nothing.closed)
        }

    @Test
    fun aRedeemedCardIsEmptyWithoutARescan() =
        runBlocking {
            val redeemed = FakeCardWallet(emptyList(), spendable = 0, history = 2)
            val wallets = FakeWallets(listOf(redeemed))
            val (redeemer, _) = redeemer(redeemed, wallets = wallets)

            assertEquals(GiftCardRedeemer.Status.Empty, redeemer.check())

            assertEquals(listOf(true), wallets.exactBirthdays)
            assertFalse(redeemed.closed)
        }

    /** A legacy card wallet reads its history only to decide the rescan, sent transactions or not. */
    @Test
    fun aLegacyRedeemedCardReadsItsHistoryOnlyForTheRescan() =
        runBlocking {
            val redeemed = FakeCardWallet(emptyList(), spendable = 0, history = 2, sentHistory = 1)
            val wallets = FakeWallets(listOf(redeemed))
            val (redeemer, _) = redeemer(redeemed, wallets = wallets)

            assertEquals(GiftCardRedeemer.Status.Empty, redeemer.check())

            assertEquals(1, redeemed.transactionReads)
            assertEquals(listOf(true), wallets.exactBirthdays)
        }

    @Test
    fun aCheckpointScanThatFindsNothingIsEmpty() =
        runBlocking {
            val nothing = FakeCardWallet(emptyList(), spendable = 0, history = 0)
            val wallets = FakeWallets(listOf(nothing))
            val (redeemer, _) = redeemer(nothing, wallets = wallets)

            assertEquals(GiftCardRedeemer.Status.Empty, redeemer.check())
            assertEquals(GiftCardRedeemer.Status.Empty, redeemer.check())

            assertEquals(listOf(true, false), wallets.exactBirthdays)
        }

    @Test
    fun aCheckThatDoesNotSyncInTimeFailsAndLeavesTheCardUnchecked() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.SYNCING))
            val (redeemer, _) = redeemer(cardWallet)

            val thrown =
                withTimeout(5.seconds) {
                    assertFailsWith<GiftCardException.SyncFailed> { redeemer.check(timeout = 50.milliseconds) }
                }

            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.all { it is GiftCardException.SyncFailed })
            assertFailsWith<GiftCardException.NotChecked> { redeemer.redeem(recipient()) }
            assertFalse(cardWallet.proposed)
        }

    @Test
    fun aStoppedCardWalletFailsTheCheckAtOnce() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.STOPPED))
            val (redeemer, _) = redeemer(cardWallet)

            withTimeout(5.seconds) { assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() } }
        }

    @Test
    fun aSyncedCardWalletIsWaitedForUntilItReportsTheAccountsBalance() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList())
            val balances = cardWallet.walletBalances.value
            cardWallet.walletBalances.value = null
            val (redeemer, wallets) = redeemer(cardWallet)

            val check = async { redeemer.check() }
            wallets.opened.await()
            delay(50.milliseconds)
            cardWallet.walletBalances.value = emptyMap()
            delay(50.milliseconds)
            assertFalse(check.isCompleted)
            cardWallet.walletBalances.value = balances

            assertIs<GiftCardRedeemer.Status.Ready>(withTimeout(5.seconds) { check.await() })
        }

    @Test
    fun aFailingFeeProbeFailsTheCheckWithItsCause() =
        runBlocking<Unit> {
            val failure = IllegalStateException("backend")
            val cardWallet = FakeCardWallet(emptyList(), proposalFailure = failure)
            val (redeemer, _) = redeemer(cardWallet)

            val thrown = assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() }

            assertTrue(generateSequence(thrown.cause) { it.cause }.any { it === failure })
        }

    @Test
    fun aGiftCardFailureWhileOpeningTheWalletIsReportedAsItIs() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList())
            val wallets = FakeWallets(cardWallet, openFailure = GiftCardException.InUse())
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            assertFailsWith<GiftCardException.InUse> { redeemer.check() }
        }

    @Test
    fun onlyTheFirstCriticalErrorIsReported() =
        runBlocking<Unit> {
            val first = IllegalStateException("first")
            val second = IllegalStateException("second")
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.SYNCING))
            val wallets =
                FakeWallets(
                    listOf(cardWallet),
                    onOpen = { handler ->
                        assertFalse(handler(first))
                        assertFalse(handler(second))
                    }
                )
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val thrown = withTimeout(5.seconds) { assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() } }

            val causes = generateSequence(thrown.cause) { it.cause }.toList()
            assertTrue(causes.any { it === first })
            assertFalse(causes.any { it === second })
        }

    @Test
    fun redeemRejectsAnAddressOnAnotherNetworkBeforeTouchingTheCardWallet() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList())
            val (redeemer, _) = redeemer(cardWallet)
            redeemer.check()
            cardWallet.proposed = false

            assertFailsWith<GiftCardException.NetworkMismatch> {
                redeemer.redeem(recipient(network = ZcashNetwork.Testnet))
            }
            assertFalse(cardWallet.proposed)
            assertTrue(cardWallet.submitted.isEmpty())
        }

    @Test
    fun aBalanceNoLongerReportedForTheAccountIsNothingToRedeem() =
        runBlocking<Unit> {
            listOf(null, emptyMap<AccountUuid, AccountBalance>()).forEach { reported ->
                val cardWallet = FakeCardWallet(listOf(createdTransaction("claim")))
                val (redeemer, _) = redeemer(cardWallet)
                redeemer.check()
                cardWallet.proposed = false
                cardWallet.walletBalances.value = reported

                assertFailsWith<GiftCardException.NothingToRedeem> { redeemer.redeem(recipient()) }
                assertFalse(cardWallet.proposed)
                assertTrue(cardWallet.submitted.isEmpty())
            }
        }

    @Test
    fun aRedemptionWhoseFeeTakesTheWholeBalanceReportsNoAmount() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim))
            val (redeemer, _) = redeemer(cardWallet)
            redeemer.check()
            cardWallet.fee = Zatoshi(1_000_000)

            val redemption = redeemer.redeem(recipient())

            assertEquals(Zatoshi(1_000_000), redemption.fee)
            assertNull(redemption.amount)
        }

    @Test
    fun nothingIsRecordedInTheDestinationWhenNoTransactionWasAccepted() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim)) { rejected(it) }
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination()

            val redemption = redeemer.checkAndRedeem(destination)

            assertFalse(redemption.isSubmitted)
            assertFalse(redemption.recordedInDestination)
            assertTrue(destination.recorded.isEmpty())
            assertEquals(listOf(claim), cardWallet.submitted)
        }

    @Test
    fun aCancellationWhileRecordingInTheDestinationIsNotSwallowed() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(listOf(createdTransaction("claim")))
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination(failure = CancellationException("recording"))

            assertFailsWith<CancellationException> { redeemer.checkAndRedeem(destination) }
        }

    @Test
    fun aRedemptionIsByDefaultNotRecordedAndOfUnknownAmount() {
        val txId = FirstClassByteArray("txid".toByteArray())
        val accepted = GiftCardRedeemer.Redemption(Zatoshi(10_000), listOf(TransactionSubmitResult.Success(txId)))
        val notAttempted =
            GiftCardRedeemer.Redemption(Zatoshi(10_000), listOf(TransactionSubmitResult.NotAttempted(txId)))

        assertFalse(accepted.recordedInDestination)
        assertNull(accepted.amount)
        assertTrue(accepted.isSubmitted)
        assertFalse(notAttempted.isSubmitted)
    }

    private companion object {
        fun pool(
            available: Long = 0,
            pending: Long = 0
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

        fun balance(
            available: Long = 0,
            pending: Long = 0
        ) = AccountBalance(pool(), pool(available = available, pending = pending), pool(), Zatoshi(0))
    }
}
