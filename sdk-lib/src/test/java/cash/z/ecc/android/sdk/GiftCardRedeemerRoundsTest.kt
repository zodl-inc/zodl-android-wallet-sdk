package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.SizeBoundViolation
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionSizeBound
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [GiftCardRedeemer.redeem] under a [TransactionSizeBound]: without the backend selection of what fits (the
 * production state), a card over the bound fails typed with nothing sent; with it (a fake), the card is sent in
 * rounds, each waiting for the one before it to be mined.
 */
class GiftCardRedeemerRoundsTest {
    private val phone = TransactionSizeBound(maxShieldedActions = 75)

    private val tooLarge =
        TransactionEncoderException.ProposalTooLargeException(
            RuntimeException("backend"),
            SizeBoundViolation(SizeBoundViolation.Dimension.SHIELDED_ACTIONS, null, count = 80, limit = 75)
        )

    @Test
    fun withoutRoundsACardOverTheBoundFailsTooManyNotesHavingSentNothing() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(listOf(createdTransaction("claim")), boundFailure = tooLarge)
            val (redeemer, _) = redeemer(cardWallet)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val failure =
                assertFailsWith<GiftCardException.TooManyNotes> { redeemer.redeem(recipient(), sizeBound = phone) }

            assertSame(tooLarge, failure.cause)
            assertEquals(phone, cardWallet.sizeBounds.last())
            assertEquals(0, cardWallet.creations)
            assertTrue(cardWallet.submitted.isEmpty())
        }

    @Test
    fun withoutABoundTheRedemptionIsTheSingleRoundItWas() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(listOf(createdTransaction("claim")))
            val (redeemer, _) = redeemer(cardWallet)

            val redemption = redeemer.checkAndRedeem()

            assertEquals(1, redemption.rounds)
            assertTrue(redemption.isSubmitted)
            assertTrue(cardWallet.sizeBounds.all { it == TransactionSizeBound.CONSENSUS })
        }

    @Test
    fun aCardOverTheBoundIsSentInRoundsEachWaitingForTheOneBefore() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet()
            cardWallet.allTransactions.value = listOf(mined(round(1)))
            val fits = FakeSendWhatFits(listOf(remainder(5), GiftCardRedeemer.Remainder.NONE))
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = fits)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())
            val progress = mutableListOf<GiftCardRedeemer.RedeemProgress>()

            val redemption = redeemer.redeem(recipient(), sizeBound = phone, onProgress = { progress += it })

            assertEquals(2, redemption.rounds)
            assertTrue(redemption.isSubmitted)
            assertEquals(Zatoshi(2 * ROUND_FEE), redemption.fee)
            assertEquals(Zatoshi(2 * ROUND_AMOUNT), redemption.amount)
            assertEquals(listOf(round(1).txId, round(2).txId), cardWallet.submitted.map { it.txId })
            assertEquals(
                listOf(
                    GiftCardRedeemer.RedeemProgress(1, listOf(TransactionId.new(round(1).txId)), remainder(5)),
                    GiftCardRedeemer.RedeemProgress(
                        2,
                        listOf(TransactionId.new(round(2).txId)),
                        GiftCardRedeemer.Remainder.NONE
                    )
                ),
                progress
            )
            assertEquals(listOf(phone, phone), fits.bounds)
            assertEquals(listOf(phone, phone), cardWallet.creationBounds)
        }

    @Test
    fun aThrowingProgressCallbackNeverLosesTheTransactionsSent() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet()
            cardWallet.allTransactions.value = listOf(mined(round(1)))
            val fits = FakeSendWhatFits(listOf(remainder(5), GiftCardRedeemer.Remainder.NONE))
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = fits)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val redemption =
                redeemer.redeem(recipient(), sizeBound = phone, onProgress = { error("the app's callback failed") })

            assertEquals(2, redemption.rounds)
            assertEquals(listOf(round(1).txId, round(2).txId), redemption.results.map { it.txId })
        }

    @Test
    fun aCancellationInTheProgressCallbackCancelsTheRedemption() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet()
            cardWallet.allTransactions.value = listOf(mined(round(1)))
            val fits = FakeSendWhatFits(listOf(remainder(5), GiftCardRedeemer.Remainder.NONE))
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = fits)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            assertFailsWith<CancellationException> {
                redeemer.redeem(
                    recipient(),
                    sizeBound = phone,
                    onProgress = { throw CancellationException("the app cancelled") }
                )
            }
            // The cancellation stopped the redemption after its first round: the second was never sent.
            assertEquals(1, cardWallet.creations)
        }

    @Test
    fun aRoundWaitingToBeMinedHoldsNoLockAClose() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet()
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = FakeSendWhatFits(listOf(remainder(5))))
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())
            val firstRoundSent = CompletableDeferred<Unit>()

            supervisorScope {
                val redemption =
                    async {
                        redeemer.redeem(
                            recipient(),
                            sizeBound = phone,
                            roundTimeout = 1.hours,
                            onProgress = { firstRoundSent.complete(Unit) }
                        )
                    }
                firstRoundSent.await()

                // The first round is never mined: close() still gets the lock at once.
                withTimeout(5.seconds) { redeemer.close() }
                assertFailsWith<GiftCardException.Closed> { redemption.await() }
            }
        }

    @Test
    fun aRoundTheServerRefusesStopsTheRedemptionWithItsResults() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet(submitResult = { if (it == round(2)) rejected(it) else success(it) })
            cardWallet.allTransactions.value = listOf(mined(round(1)))
            val fits = FakeSendWhatFits(listOf(remainder(5), remainder(1)))
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = fits)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val failure =
                assertFailsWith<GiftCardException.RoundFailed> { redeemer.redeem(recipient(), sizeBound = phone) }

            assertEquals(2, failure.round)
            assertEquals(listOf(rejected(round(2))), failure.redemption.results)
            assertEquals(listOf(TransactionId.new(round(1).txId)), failure.sentTxIds)
            assertEquals(2, fits.calls)
        }

    @Test
    fun aFirstRoundTheServerRefusesIsARoundFailureWhenMoreRoundsWereToCome() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet(submitResult = ::rejected)
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = FakeSendWhatFits(listOf(remainder(5))))
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val failure =
                assertFailsWith<GiftCardException.RoundFailed> { redeemer.redeem(recipient(), sizeBound = phone) }

            assertEquals(1, failure.round)
            assertEquals(emptyList(), failure.sentTxIds)
        }

    @Test
    fun aRoundOverTheBoundAfterTheFirstNamesTheTransactionsAlreadySent() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet()
            cardWallet.allTransactions.value = listOf(mined(round(1)))
            val fits = FakeSendWhatFits(listOf(remainder(5)), failureAfter = tooLarge)
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = fits)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val failure =
                assertFailsWith<GiftCardException.TooManyNotes> { redeemer.redeem(recipient(), sizeBound = phone) }

            assertSame(tooLarge, failure.cause)
            assertEquals(listOf(TransactionId.new(round(1).txId)), failure.sentTxIds)
            assertEquals(1, cardWallet.creations)
        }

    @Test
    fun aRoundThatExpiresUnminedStopsTheRedemption() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet()
            cardWallet.allTransactions.value = listOf(overview(round(1), minedHeight = null, TransactionState.Expired))
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = FakeSendWhatFits(listOf(remainder(5))))
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val failure =
                assertFailsWith<GiftCardException.RoundExpired> { redeemer.redeem(recipient(), sizeBound = phone) }

            assertEquals(1, failure.round)
            assertEquals(listOf(TransactionId.new(round(1).txId)), failure.txIds)
        }

    @Test
    fun aRoundNotMinedInTimeStopsTheRedemptionAndARedeemAgainResumes() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet()
            val fits = FakeSendWhatFits(listOf(remainder(5), GiftCardRedeemer.Remainder.NONE))
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = fits)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            assertFailsWith<GiftCardException.RoundExpired> {
                redeemer.redeem(recipient(), sizeBound = phone, roundTimeout = 50.milliseconds)
            }

            // Nothing about the first attempt is kept: the next redemption proposes from what is spendable now.
            val resumed = redeemer.redeem(recipient(), sizeBound = phone)
            assertEquals(1, resumed.rounds)
            assertEquals(listOf(round(2).txId), resumed.results.map { it.txId })
        }

    @Test
    fun whatALaterRoundCannotPayTheFeeOfEndsTheRedemption() =
        runBlocking<Unit> {
            val cardWallet = roundsCardWallet()
            cardWallet.allTransactions.value = listOf(mined(round(1)))
            val dust = TransactionEncoderException.InsufficientFundsException(RuntimeException("Insufficient balance"))
            val fits = FakeSendWhatFits(listOf(remainder(1)), failureAfter = dust)
            val (redeemer, _) = redeemer(cardWallet, sendWhatFits = fits)
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer.check())

            val redemption = redeemer.redeem(recipient(), sizeBound = phone)

            assertEquals(1, redemption.rounds)
            assertEquals(2, fits.calls)
        }

    @Test
    fun aRoundIsSettledOnceEveryTransactionIsMinedAndFailsOnceOneExpires() {
        val wanted = setOf(TransactionId.new(round(1).txId), TransactionId.new(round(2).txId))

        assertNull(roundOutcome(emptyList(), wanted))
        assertNull(roundOutcome(listOf(mined(round(1))), wanted))
        assertNull(roundOutcome(listOf(mined(round(1)), overview(round(2), null, TransactionState.Pending)), wanted))
        assertEquals(true, roundOutcome(listOf(mined(round(1)), mined(round(2)), mined(round(3))), wanted))
        val expired = overview(round(2), null, TransactionState.Expired)
        assertEquals(false, roundOutcome(listOf(mined(round(1)), expired), wanted))
    }

    /** A card wallet that creates [round] `n` at its `n`th creation. */
    private fun roundsCardWallet(
        submitResult: (CreatedTransaction) -> TransactionSubmitResult = ::success
    ) = FakeCardWallet(
        created = emptyList(),
        submitResult = submitResult,
        createdInCreation = { listOf(round(it)) }
    )

    private class FakeSendWhatFits(
        private val remainders: List<GiftCardRedeemer.Remainder>,
        private val failureAfter: Exception? = null
    ) : SendWhatFits {
        var calls = 0
        val bounds = mutableListOf<TransactionSizeBound>()

        override suspend fun propose(
            cardWallet: Synchronizer,
            account: Account,
            toAddress: RecipientAddress,
            memo: MemoContent?,
            sizeBound: TransactionSizeBound
        ): BoundedSendMax {
            calls++
            bounds += sizeBound
            val remainder = remainders.getOrNull(calls - 1)
            if (remainder == null) {
                failureAfter?.let { throw it }
                return BoundedSendMax(proposal(), GiftCardRedeemer.Remainder.NONE)
            }
            return BoundedSendMax(proposal(), remainder)
        }

        private fun proposal(): Proposal {
            val proposal = mock(Proposal::class.java)
            `when`(proposal.totalFeeRequired()).thenReturn(Zatoshi(ROUND_FEE))
            `when`(proposal.totalPaymentAmount()).thenReturn(Zatoshi(ROUND_AMOUNT))
            return proposal
        }
    }

    private companion object {
        const val ROUND_FEE = 10_000L
        const val ROUND_AMOUNT = 100_000L

        fun round(n: Int) = createdTransaction("round-$n")

        fun remainder(notes: Int) = GiftCardRedeemer.Remainder(Zatoshi(notes * 10_000L), notes)

        fun success(transaction: CreatedTransaction) =
            TransactionSubmitResult.Success(transaction.txId)

        fun mined(transaction: CreatedTransaction) = overview(transaction, CARD_WALLET_TIP, TransactionState.Confirmed)

        fun overview(
            transaction: CreatedTransaction,
            minedHeight: BlockHeight?,
            state: TransactionState
        ): TransactionOverview {
            val overview = mock(TransactionOverview::class.java)
            `when`(overview.txId).thenReturn(TransactionId.new(transaction.txId))
            `when`(overview.minedHeight).thenReturn(minedHeight)
            `when`(overview.transactionState).thenReturn(state)
            return overview
        }
    }
}
