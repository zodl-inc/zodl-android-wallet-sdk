package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.OvkPolicy
import cash.z.ecc.android.sdk.model.PercentDecimal
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [GiftCardRedeemer] against a card wallet that behaves as one on the Slipstream engine does: it latches setup
 * failures in [Synchronizer.setupError] instead of throwing them out of its creation, it reports
 * [Synchronizer.Status.DISCONNECTED] while idle before its first sync pass and reports trouble reaching the server
 * as being idle or as syncing that does not advance, it reports a failed sync pass through
 * [Synchronizer.onProcessorErrorHandler], its account appears only once its preparation has created it, and it always
 * starts at the bundled checkpoint.
 */
class GiftCardRedeemerEngineTest {
    @Test
    fun aLatchedSetupErrorFailsTheCheckAtOnceWithItsCause() =
        runBlocking<Unit> {
            val setupFailure = IllegalStateException("setup")
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.DISCONNECTED))
            cardWallet.setupError.value = setupFailure
            val (redeemer, _) = redeemer(cardWallet, wallets = engineWallets(cardWallet))

            val thrown =
                withTimeout(5.seconds) {
                    runCatching { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 10.minutes) }
                }.exceptionOrNull()

            val failure = assertIs<GiftCardException.SyncFailed>(thrown)
            assertTrue(generateSequence(failure.cause) { it.cause }.any { it.message == "setup" })
        }

    @Test
    fun aSetupErrorLatchedWhileTheCheckWaitsFailsIt() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.INITIALIZING))
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check = async { runCatching { redeemer.check(timeout = 10.minutes) } }
            wallets.opened.await()
            delay(50.milliseconds)
            assertFalse(check.isCompleted)
            cardWallet.setupError.value = IllegalStateException("anchor")

            val outcome = withTimeout(5.seconds) { check.await() }
            val failure = assertIs<GiftCardException.SyncFailed>(outcome.exceptionOrNull())
            assertTrue(generateSequence(failure.cause) { it.cause }.any { it.message == "anchor" })
        }

    @Test
    fun aCardWalletIdleBeforeItsFirstPassWithinTheGraceIsNotTreatedAsDisconnected() =
        runBlocking<Unit> {
            val status = MutableStateFlow(Synchronizer.Status.DISCONNECTED)
            val cardWallet = FakeCardWallet(emptyList(), status = status)
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check = async { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 2.seconds) }
            wallets.opened.await()
            delay(400.milliseconds)
            assertFalse(check.isCompleted, "an idle wallet within its grace must not fail the check")
            status.value = Synchronizer.Status.SYNCED

            assertIs<GiftCardRedeemer.Status.Ready>(withTimeout(5.seconds) { check.await() })
        }

    @Test
    fun aCardWalletIdleBeforeItsFirstPassForLongerThanTheGraceFails() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.DISCONNECTED))
            val (redeemer, _) = redeemer(cardWallet, wallets = engineWallets(cardWallet))

            val outcome =
                withTimeout(5.seconds) {
                    runCatching { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 100.milliseconds) }
                }

            assertIs<GiftCardException.SyncFailed>(outcome.exceptionOrNull())
        }

    @Test
    fun aCardWalletSyncingWithoutProgressFailsAfterTheGrace() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.SYNCING))
            cardWallet.progress.value = PercentDecimal(0.4f)
            val (redeemer, _) = redeemer(cardWallet, wallets = engineWallets(cardWallet))

            val outcome =
                withTimeout(5.seconds) {
                    runCatching { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 100.milliseconds) }
                }

            assertIs<GiftCardException.SyncFailed>(outcome.exceptionOrNull())
        }

    @Test
    fun aCardWalletWhoseProgressKeepsRisingIsNotStalled() =
        runBlocking<Unit> {
            val status = MutableStateFlow(Synchronizer.Status.SYNCING)
            val cardWallet = FakeCardWallet(emptyList(), status = status)
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check = async { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 200.milliseconds) }
            wallets.opened.await()
            repeat(12) { step ->
                delay(50.milliseconds)
                cardWallet.progress.value = PercentDecimal((step + 1) / 20f)
            }
            assertFalse(check.isCompleted, "a wallet that keeps progressing must not exhaust its grace")
            status.value = Synchronizer.Status.SYNCED

            assertIs<GiftCardRedeemer.Status.Ready>(withTimeout(5.seconds) { check.await() })
        }

    @Test
    fun aLegacyCardWalletSyncingWithoutProgressIsNotFailedByTheGrace() =
        runBlocking<Unit> {
            val status = MutableStateFlow(Synchronizer.Status.SYNCING)
            val cardWallet = FakeCardWallet(emptyList(), status = status)
            val wallets = FakeWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check = async { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 50.milliseconds) }
            wallets.opened.await()
            delay(400.milliseconds)
            assertFalse(check.isCompleted, "the legacy engine reports trouble reaching the server as DISCONNECTED")
            status.value = Synchronizer.Status.SYNCED

            assertIs<GiftCardRedeemer.Status.Ready>(withTimeout(5.seconds) { check.await() })
        }

    @Test
    fun theCheckWaitsForTheCardWalletsAccountToBeCreated() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList())
            cardWallet.accounts.value = null
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check = async { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 10.minutes) }
            wallets.opened.await()
            delay(100.milliseconds)
            assertFalse(check.isCompleted, "the account list is not loaded yet")
            cardWallet.accounts.value = emptyList()
            delay(100.milliseconds)
            assertFalse(check.isCompleted, "the account is not created yet")
            cardWallet.accounts.value = listOf(AccountFixture.new())

            assertIs<GiftCardRedeemer.Status.Ready>(withTimeout(5.seconds) { check.await() })
        }

    @Test
    fun aSetupErrorWhileWaitingForTheAccountFailsTheCheckAtOnce() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.INITIALIZING))
            cardWallet.accounts.value = null
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check = async { runCatching { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 10.minutes) } }
            wallets.opened.await()
            delay(50.milliseconds)
            assertFalse(check.isCompleted)
            cardWallet.setupError.value = IllegalStateException("anchor")

            val outcome = withTimeout(5.seconds) { check.await() }
            val failure = assertIs<GiftCardException.SyncFailed>(outcome.exceptionOrNull())
            assertTrue(generateSequence(failure.cause) { it.cause }.any { it.message == "anchor" })
        }

    @Test
    fun aSecondCheckAfterALatchedSetupErrorOpensANewCardWallet() =
        runBlocking<Unit> {
            val broken = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.DISCONNECTED))
            broken.setupError.value = IllegalStateException("setup")
            val healthy = FakeCardWallet(emptyList())
            val wallets =
                FakeWallets(
                    listOf(broken, healthy),
                    isExactBirthdayAvailable = false,
                    isDisconnectedUntilFirstPass = true
                )
            val (redeemer, _) = redeemer(broken, wallets = wallets)

            assertIs<GiftCardException.SyncFailed>(runCatching { redeemer.check() }.exceptionOrNull())
            assertTrue(broken.closed, "a card wallet whose setup failed must be closed")

            assertIs<GiftCardRedeemer.Status.Ready>(withTimeout(5.seconds) { redeemer.check() })
            assertEquals(2, wallets.exactBirthdays.size, "the second check must open a new card wallet")
            assertFalse(healthy.closed)
            assertEquals(List(3) { GiftCardRedeemer.defaultAlias(card()) }, wallets.erased)
        }

    @Test
    fun aFailedSyncPassIsRetriedAtMostTwiceThenFailsTheCheck() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.DISCONNECTED))
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check = async { runCatching { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 10.minutes) } }
            wallets.opened.await()
            val handler = assertNotNull(cardWallet.onProcessorErrorHandler)
            repeat(GiftCardRedeemer.MAX_PROCESSOR_ERROR_RETRIES) {
                assertTrue(handler(IllegalStateException("pass $it")), "a failed pass is retried")
            }
            delay(50.milliseconds)
            assertFalse(check.isCompleted)
            assertFalse(handler(IllegalStateException("panic")), "the retries are spent")

            val outcome = withTimeout(5.seconds) { check.await() }
            val failure = assertIs<GiftCardException.SyncFailed>(outcome.exceptionOrNull())
            assertTrue(generateSequence(failure.cause) { it.cause }.any { it.message == "panic" })
        }

    @Test
    fun theRetriesOfAFailedSyncPassAreCountedPerCheck() =
        runBlocking<Unit> {
            val status = MutableStateFlow(Synchronizer.Status.DISCONNECTED)
            val cardWallet = FakeCardWallet(emptyList(), status = status)
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val first = async { runCatching { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 10.minutes) } }
            wallets.opened.await()
            val handler = assertNotNull(cardWallet.onProcessorErrorHandler)
            repeat(GiftCardRedeemer.MAX_PROCESSOR_ERROR_RETRIES + 1) { handler(IllegalStateException("pass $it")) }
            assertIs<GiftCardException.SyncFailed>(withTimeout(5.seconds) { first.await() }.exceptionOrNull())

            val second = async { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 10.minutes) }
            delay(50.milliseconds)
            assertTrue(handler(IllegalStateException("again")), "a new check retries again")
            status.value = Synchronizer.Status.SYNCED

            assertIs<GiftCardRedeemer.Status.Ready>(withTimeout(5.seconds) { second.await() })
            assertEquals(1, wallets.exactBirthdays.size, "the card wallet is kept")
        }

    @Test
    fun aCardWalletThatDisconnectsAfterItsFirstPassFailsAfterTheGrace() =
        runBlocking<Unit> {
            val status = MutableStateFlow(Synchronizer.Status.DISCONNECTED)
            val cardWallet = FakeCardWallet(emptyList(), status = status)
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check =
                async { runCatching { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 100.milliseconds) } }
            wallets.opened.await()
            delay(50.milliseconds)
            status.value = Synchronizer.Status.SYNCING
            delay(20.milliseconds)
            status.value = Synchronizer.Status.DISCONNECTED

            assertIs<GiftCardException.SyncFailed>(withTimeout(5.seconds) { check.await() }.exceptionOrNull())
        }

    @Test
    fun aFailedFirstPassStartsTheDisconnectedGraceAndIsRetried() =
        runBlocking<Unit> {
            val cardWallet = FakeCardWallet(emptyList(), status = MutableStateFlow(Synchronizer.Status.DISCONNECTED))
            val wallets = engineWallets(cardWallet)
            val (redeemer, _) = redeemer(cardWallet, wallets = wallets)

            val check =
                async { runCatching { redeemer.check(timeout = 10.minutes, disconnectedTimeout = 1.seconds) } }
            wallets.opened.await()
            delay(200.milliseconds)
            assertFalse(check.isCompleted)
            val handler = assertNotNull(cardWallet.onProcessorErrorHandler)
            assertTrue(handler(IllegalStateException("unreachable")), "the engine must be told to retry")

            assertIs<GiftCardException.SyncFailed>(withTimeout(5.seconds) { check.await() }.exceptionOrNull())
        }

    @Test
    fun theProcessorErrorHandlerIsTakenOverOnlyForAWalletIdleBeforeItsFirstPass() =
        runBlocking<Unit> {
            val legacy = FakeCardWallet(emptyList())
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer(legacy).first.check())
            assertNull(legacy.onProcessorErrorHandler)

            val engine = FakeCardWallet(emptyList())
            assertIs<GiftCardRedeemer.Status.Ready>(redeemer(engine, wallets = engineWallets(engine)).first.check())
            assertNotNull(engine.onProcessorErrorHandler)
        }

    @Test
    fun aCardWalletStartedAtTheCheckpointThatFindsNothingIsEmptyWithoutARescan() =
        runBlocking<Unit> {
            val nothing = FakeCardWallet(emptyList(), spendable = 0, history = 0)
            val wallets = engineWallets(nothing)
            val (redeemer, _) = redeemer(nothing, wallets = wallets)

            assertEquals(GiftCardRedeemer.Status.Empty, redeemer.check())

            assertEquals(listOf(true), wallets.exactBirthdays)
            assertFalse(nothing.closed)
        }

    @Test
    fun aCardWalletOnTheEngineRedeemsWithTheOutgoingViewingKeyDiscarded() =
        runBlocking<Unit> {
            val claim = createdTransaction("claim")
            val cardWallet = FakeCardWallet(listOf(claim))
            val (redeemer, _) = redeemer(cardWallet, wallets = engineWallets(cardWallet))

            val redemption = redeemer.checkAndRedeem()

            assertTrue(redemption.isSubmitted)
            assertEquals(listOf(claim), cardWallet.submitted)
            assertEquals(OvkPolicy.Discard, cardWallet.ovkPolicy)
        }

    /** Card wallets as the Slipstream engine opens them: at the checkpoint, and idle before the first pass. */
    private fun engineWallets(cardWallet: FakeCardWallet) =
        FakeWallets(
            listOf(cardWallet),
            isExactBirthdayAvailable = false,
            isDisconnectedUntilFirstPass = true
        )
}
