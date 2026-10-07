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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.assertContentEquals
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
 * [GiftCardRedeemer]'s failure paths against a fake card wallet: cancellations from inside and from the caller, a
 * submission that throws, a check that failed at its fee probe, an exact birthday the server could not provide, the
 * disconnected grace, and what a redemption reports it sent; and how a redemption and a close end when they are cut
 * short: a redemption cancelled before or after its transaction starts being created, a close whose teardown hangs or
 * whose erase fails, a claim that creates nothing, and a claim only partly recorded in the destination.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GiftCardRedeemerFailureTest {
    /**
     * [GiftCardRedeemer.redeem] refuses with [GiftCardException.NotChecked] "if no check on this redeemer has
     * completed yet": a check that failed at its fee probe has not completed, so a later redeem must neither propose
     * nor submit, and the probe's raw backend exception must not escape it.
     */
    @Test
    fun aCheckThatFailedAtItsFeeProbeLeavesTheCardUnchecked() =
        runBlocking<Unit> {
            val failure = IllegalStateException("backend")
            val cardWallet = FakeCardWallet(listOf(createdTransaction("claim")), proposalFailure = failure)
            val (redeemer, _) = redeemer(cardWallet)
            assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() }

            assertFailsWith<GiftCardException.NotChecked> { redeemer.redeem(recipient()) }
            assertTrue(cardWallet.submitted.isEmpty())
        }

    /**
     * A [CancellationException] thrown from inside the check while its caller is not cancelled would otherwise end
     * the check silently, leaving a caller that only expects a [GiftCardRedeemer.Status] or a [GiftCardException]
     * waiting for neither.
     */
    @Test
    fun aCancellationFromInsideTheCheckIsASyncFailure() =
        runBlocking<Unit> {
            val probeCancellation = CancellationException("probe")
            val probe = FakeCardWallet(emptyList(), proposalFailure = probeCancellation)
            val probeFailure = assertFailsWith<GiftCardException.SyncFailed> { redeemer(probe).first.check() }
            assertTrue(generateSequence(probeFailure.cause) { it.cause }.any { it.message == "probe" })

            val opening = FakeCardWallet(emptyList())
            val wallets = FakeWallets(opening, openFailure = CancellationException("open"))
            val (redeemer, _) = redeemer(opening, wallets = wallets)
            assertFailsWith<GiftCardException.SyncFailed> { redeemer.check() }
            assertEquals(listOf(true), wallets.exactBirthdays)
        }

    @Test
    fun aCheckWhoseCallerIsCancelledRethrowsTheCancellation() =
        runBlocking<Unit> {
            val syncing = MutableStateFlow(Synchronizer.Status.SYNCING)
            val cardWallet = FakeCardWallet(emptyList(), status = syncing)
            val (redeemer, wallets) = redeemer(cardWallet)

            val check = async { redeemer.check() }
            wallets.opened.await()
            check.cancel()

            assertFailsWith<CancellationException> { check.await() }
            assertTrue(check.isCancelled)
        }

    /**
     * The caller is cancelled while the card wallet is being opened (for example, the user closes the screen during
     * the first check): the redeemer must still erase the card wallet and release the card when closed, so that a
     * later redeemer for the same card can use it.
     */
    @Test
    fun aCheckCancelledWhileTheWalletOpensLeavesTheCardUsableAfterClose() =
        runBlocking<Unit> {
            val aliases = GiftCardAliases()
            val cardWallet = FakeCardWallet(emptyList())
            val opening = CompletableDeferred<Unit>()
            val wallets =
                FakeWallets(
                    listOf(cardWallet),
                    onOpen = {
                        opening.complete(Unit)
                        awaitCancellation()
                    }
                )
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets, aliases = aliases)

            val check = async { redeemer.check() }
            opening.await()
            check.cancel()
            assertFailsWith<CancellationException> { check.await() }
            withTimeout(5.seconds) { redeemer.close() }

            assertEquals(listOf(redeemer.alias, redeemer.alias), wallets.erased)
            val (next, _) = redeemer(FakeCardWallet(emptyList()), aliases = aliases)
            assertIs<GiftCardRedeemer.Status.Ready>(next.check())
        }

    /**
     * A card wallet that throws while closing must not strand the card: its data is still erased and its alias is
     * released, so the same card can be opened again by a new redeemer in this process.
     */
    @Test
    fun aCardWalletThatFailsToCloseIsStillErasedAndItsAliasReleased() =
        runBlocking<Unit> {
            val aliases = GiftCardAliases()
            val cardWallet = FakeCardWallet(emptyList(), closeFailure = IllegalStateException("close"))
            val wallets = FakeWallets(listOf(cardWallet))
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets, aliases = aliases)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            assertFailsWith<IllegalStateException> { redeemer.close() }

            assertTrue(wallets.erased.contains(redeemer.alias))
            val (next, _) = redeemer(FakeCardWallet(emptyList()), aliases = aliases)
            assertIs<GiftCardRedeemer.Status.Ready>(next.check())
        }

    @Test
    fun aSubmissionThatThrowsIsAFailedResultThatIsNotRecorded() =
        runBlocking {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim)) { error("no connection to the server") }
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination()

            val redemption = redeemer.checkAndRedeem(destination)

            val failure = assertIs<TransactionSubmitResult.Failure>(redemption.results.single())
            assertEquals(claim.txId, failure.txId)
            assertEquals(GiftCardRedeemer.SUBMIT_THREW_CODE, failure.code)
            assertTrue(failure.grpcError)
            assertFalse(redemption.isSubmitted)
            assertFalse(redemption.recordedInDestination)
            assertTrue(destination.recorded.isEmpty())
        }

    @Test
    fun aCancellationWhileSubmittingIsNotReportedAsAFailedResult() =
        runBlocking<Unit> {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim)) { throw CancellationException("submit") }
            val (redeemer, _) = redeemer(cardWallet)

            assertFailsWith<CancellationException> { redeemer.checkAndRedeem() }
        }

    @Test
    fun theAmountRedeemedIsWhatTheProposalSends() =
        runBlocking {
            val cardWallet = FakeCardWallet(listOf(createdTransaction("claim")), sent = 900_000)
            val (redeemer, _) = redeemer(cardWallet)

            assertEquals(Zatoshi(900_000), redeemer.checkAndRedeem().amount)
        }

    @Test
    fun aScanThatFellBackToTheCheckpointIsNotRepeated() =
        runBlocking {
            val nothing = FakeCardWallet(emptyList(), spendable = 0, history = 0)
            val wallets = FakeWallets(listOf(nothing), isExactBirthdayAvailable = false)
            val (redeemer, _) = redeemer(nothing, wallets = wallets)

            assertEquals(GiftCardRedeemer.Status.Empty, redeemer.check())

            assertEquals(listOf(true), wallets.exactBirthdays)
            assertFalse(nothing.closed)
        }

    @Test
    fun theFeeProbeReadsTheCardWalletsAddressWithoutCreatingOne() =
        runBlocking<Unit> {
            val synchronizer = mock(Synchronizer::class.java)
            val account = AccountFixture.new()
            `when`(synchronizer.getUnifiedAddress(account)).thenReturn("u1card")

            assertEquals("u1card", feeEstimateAddress(synchronizer, account))
            verify(synchronizer).getUnifiedAddress(account)
            verify(synchronizer, never()).getCustomUnifiedAddress(account, UnifiedAddressRequest.Orchard)
        }

    /**
     * The status is a [kotlinx.coroutines.flow.StateFlow], which does not re-emit an unchanged value: the grace
     * counts continuous disconnection from the first [Synchronizer.Status.DISCONNECTED], and starts over after any
     * other status in between.
     */
    @Test
    fun aReconnectionRestartsTheDisconnectedGrace() =
        runBlocking<Unit> {
            val status = MutableStateFlow(Synchronizer.Status.DISCONNECTED)
            val cardWallet = FakeCardWallet(emptyList(), status = status)
            val (redeemer, wallets) = redeemer(cardWallet)

            val check = async { runCatching { redeemer.check(disconnectedTimeout = 300.milliseconds) } }
            wallets.opened.await()
            delay(200.milliseconds)
            status.value = Synchronizer.Status.SYNCING
            delay(20.milliseconds)
            status.value = Synchronizer.Status.DISCONNECTED
            delay(180.milliseconds)
            assertFalse(check.isCompleted, "a disconnection of 180 ms must not exceed a 300 ms grace")

            assertIs<GiftCardException.SyncFailed>(withTimeout(5.seconds) { check.await() }.exceptionOrNull())
        }

    @Test
    fun aCardWalletOverTorIsGivenLongerToConnect() {
        assertTrue(GiftCardRedeemer.DEFAULT_TOR_DISCONNECTED_TIMEOUT > GiftCardRedeemer.DEFAULT_DISCONNECTED_TIMEOUT)
    }

    /** A redemption cancelled while it prepares, before its transaction starts being created, creates nothing. */
    @Test
    fun aRedemptionCancelledBeforeItsTransactionIsCreatedCreatesNothing() =
        runTest {
            val deriving = CompletableDeferred<Unit>()
            val cardWallet = FakeCardWallet(listOf(createdTransaction("claim")))
            val wallets =
                FakeWallets(
                    listOf(cardWallet),
                    onDeriveSpendingKey = {
                        deriving.complete(Unit)
                        awaitCancellation()
                    }
                )
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets, teardownScope = backgroundScope)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val redemption = async { redeemer.redeem(recipient(), destination = FakeDestination()) }
            deriving.await()
            redemption.cancel()

            assertFailsWith<CancellationException> { redemption.await() }
            assertEquals(0, cardWallet.creations)
            assertTrue(cardWallet.submitted.isEmpty())
            redeemer.close()
        }

    /**
     * Once its transaction is being created, a redemption whose caller is cancelled still submits it and records it
     * in the destination, and [GiftCardRedeemer.close] waits for that before it erases the card wallet.
     */
    @Test
    fun aRedemptionCancelledWhileItsTransactionIsCreatedStillSubmitsAndRecordsIt() =
        runTest {
            val creating = CompletableDeferred<Unit>()
            val created = CompletableDeferred<Unit>()
            val claim = createdTransaction("claim")
            val cardWallet =
                FakeCardWallet(
                    listOf(claim),
                    onCreate = {
                        creating.complete(Unit)
                        created.await()
                    }
                )
            val (redeemer, wallets) = redeemer(cardWallet, teardownScope = backgroundScope)
            val destination = FakeDestination()
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val redemption = async { redeemer.redeem(recipient(), destination = destination) }
            creating.await()
            redemption.cancel()
            val close = async { redeemer.close() }
            runCurrent()
            assertFalse(close.isCompleted)
            assertFalse(cardWallet.closed)
            assertEquals(listOf(redeemer.alias), wallets.erased)

            created.complete(Unit)

            assertFailsWith<CancellationException> { redemption.await() }
            close.await()
            assertEquals(listOf(claim), cardWallet.submitted)
            assertContentEquals(claim.raw.byteArray, destination.recorded.single().first)
            assertTrue(cardWallet.closed)
            assertEquals(listOf(redeemer.alias, redeemer.alias), wallets.erased)
        }

    /**
     * A teardown that does not finish keeps [GiftCardRedeemer.close] waiting for [GiftCardRedeemer.CLOSE_TIMEOUT]
     * only, and the redeemer refuses further use. The alias stays held until the teardown has erased the card wallet.
     */
    @Test
    fun aCloseWhoseTeardownHangsReturnsAfterItsTimeoutAndKeepsTheAlias() =
        runTest {
            val aliases = GiftCardAliases()
            val erasing = CompletableDeferred<Unit>()
            val cardWallet = FakeCardWallet(listOf(createdTransaction("claim")))
            val wallets = FakeWallets(listOf(cardWallet), onErase = { if (it > 1) erasing.await() })
            val (redeemer, _) =
                redeemer(cardWallet, wallets = wallets, aliases = aliases, teardownScope = backgroundScope)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val start = currentTime
            redeemer.close()

            assertEquals(GiftCardRedeemer.CLOSE_TIMEOUT.inWholeMilliseconds, currentTime - start)
            assertFalse(aliases.acquire(ZcashNetwork.Mainnet, redeemer.alias))
            assertFailsWith<GiftCardException.Closed> { redeemer.redeem(recipient()) }

            erasing.complete(Unit)
            runCurrent()

            assertEquals(listOf(redeemer.alias, redeemer.alias), wallets.erased)
            assertTrue(aliases.acquire(ZcashNetwork.Mainnet, redeemer.alias))
        }

    /**
     * A failed erase is reported by [GiftCardRedeemer.close] and retried after 1, 2 and 4 seconds; the alias is
     * released only once an erase has succeeded.
     */
    @Test
    fun aFailedEraseIsRetriedWithBackoffAndTheAliasReleasedOnlyOnceItSucceeds() =
        runTest {
            val aliases = GiftCardAliases()
            val attempts = mutableListOf<Long>()
            val cardWallet = FakeCardWallet(emptyList())
            val wallets =
                FakeWallets(
                    listOf(cardWallet),
                    onErase = {
                        if (it > 1) attempts += testScheduler.currentTime
                        if (it in 2..4) error("engine still running")
                    }
                )
            val (redeemer, _) =
                redeemer(cardWallet, wallets = wallets, aliases = aliases, teardownScope = backgroundScope)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            assertFailsWith<IllegalStateException> { redeemer.close() }
            assertFalse(aliases.acquire(ZcashNetwork.Mainnet, redeemer.alias))
            advanceTimeBy(6_999.milliseconds)
            runCurrent()
            assertFalse(aliases.acquire(ZcashNetwork.Mainnet, redeemer.alias))
            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), attempts.map { it - attempts.first() })
            assertEquals(listOf(redeemer.alias, redeemer.alias), wallets.erased)
            assertTrue(aliases.acquire(ZcashNetwork.Mainnet, redeemer.alias))
        }

    /**
     * An erase that keeps failing is given up after [GiftCardRedeemer.MAX_ERASE_RETRIES] retries with the alias
     * still held; a later [GiftCardRedeemer.close] tries again and releases it once the erase succeeds.
     */
    @Test
    fun anEraseGivenUpOnIsTriedAgainByTheNextClose() =
        runTest {
            val aliases = GiftCardAliases()
            var isEraseFailing = true
            val cardWallet = FakeCardWallet(emptyList())
            val wallets =
                FakeWallets(
                    listOf(cardWallet),
                    onErase = { if (it > 1 && isEraseFailing) error("locked") }
                )
            val (redeemer, _) =
                redeemer(cardWallet, wallets = wallets, aliases = aliases, teardownScope = backgroundScope)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            assertFailsWith<IllegalStateException> { redeemer.close() }
            advanceTimeBy(GiftCardRedeemer.ERASE_RETRY_DELAYS.last() * GiftCardRedeemer.MAX_ERASE_RETRIES)
            runCurrent()

            assertEquals(1 + 1 + GiftCardRedeemer.MAX_ERASE_RETRIES, wallets.eraseAttempts)
            assertFalse(aliases.acquire(ZcashNetwork.Mainnet, redeemer.alias))

            isEraseFailing = false
            redeemer.close()

            assertEquals(listOf(redeemer.alias, redeemer.alias), wallets.erased)
            assertTrue(aliases.acquire(ZcashNetwork.Mainnet, redeemer.alias))
        }

    /**
     * A proposal that creates no transaction fails the redemption rather than return a [GiftCardRedeemer.Redemption]
     * with no results, whose [GiftCardRedeemer.Redemption.isSubmitted] would be vacuously `true`.
     */
    @Test
    fun aRedemptionThatCreatesNoTransactionFails() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList())
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination()

            assertFailsWith<GiftCardException.RedemptionIncomplete> { redeemer.checkAndRedeem(destination) }
            assertEquals(1, cardWallet.creations)
            assertTrue(cardWallet.submitted.isEmpty())
            assertTrue(destination.recorded.isEmpty())
        }

    /** One transaction of a claim that fails to record does not keep the others from being recorded. */
    @Test
    fun aClaimTransactionThatFailsToRecordDoesNotStopTheOthers() =
        runBlocking {
            val first = createdTransaction("first")
            val second = createdTransaction("second")
            val third = createdTransaction("third")
            val cardWallet = FakeCardWallet(listOf(first, second, third))
            val (redeemer, _) = redeemer(cardWallet)
            val destination =
                FakeDestination(failure = IllegalStateException("record")) { it.value == second.txId }

            val redemption = redeemer.checkAndRedeem(destination)

            assertTrue(redemption.isSubmitted)
            assertFalse(redemption.recordedInDestination)
            assertEquals(
                listOf(first.raw, third.raw),
                destination.recorded.map { FirstClassByteArray(it.first) }
            )
        }

    /** The transactions the server accepted are recorded also when a later submission is cancelled. */
    @Test
    fun acceptedTransactionsAreRecordedWhenALaterSubmissionIsCancelled() =
        runBlocking {
            val first = createdTransaction("first")
            val second = createdTransaction("second")
            val cardWallet =
                FakeCardWallet(listOf(first, second)) {
                    if (it == second) throw CancellationException("submit")
                    TransactionSubmitResult.Success(it.txId)
                }
            val (redeemer, _) = redeemer(cardWallet)
            val destination = FakeDestination()

            assertFailsWith<CancellationException> { redeemer.checkAndRedeem(destination) }

            assertEquals(listOf(first.raw), destination.recorded.map { FirstClassByteArray(it.first) })
        }
}
