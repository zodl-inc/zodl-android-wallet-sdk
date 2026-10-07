package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.requireNotMainWalletAlias
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.OvkPolicy
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.RawTransaction
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.tool.DerivationTool
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Redeems a [GiftCard] into the user's own wallet.
 *
 * A gift card is a small wallet whose key travels in a link. To redeem it, this class restores
 * that wallet as a separate, temporary wallet (its own [Synchronizer] under its own [alias]),
 * syncs it from the card's birthday, and sends its entire spendable balance to the user's
 * address. The transaction is created with [OvkPolicy.Discard], so nobody holding the card's
 * key (including its issuer, who can rederive it) can learn where the funds went. [close]
 * then deletes the temporary wallet.
 *
 * The temporary wallet is fully isolated from the app's main wallet, which can keep running
 * the whole time: it has its own databases and block cache, it never touches the preferences
 * the main wallet uses, and it runs without exchange rates. It runs on the same sync engine as
 * the main wallet when created with `GiftCardRedeemers.new` from the SDK incubator, which is how
 * apps should create redeemers; [new] always runs it on [SdkSynchronizer]. It connects to the
 * server the way the user chose for the main wallet: pass the main wallet's Tor setting as
 * `isTorEnabled`, so that syncing the card, fetching its birthday tree state and submitting the
 * claim do not reveal the user's IP address when the main wallet hides it. Sending needs no
 * Sapling parameters unless the card holds Sapling funds, which cards do not.
 *
 * Only one redeemer at a time may use a card's temporary wallet: a second redeemer for the same
 * card (or the same [alias]) in the same process fails with [GiftCardException.InUse] until the
 * first one is [close]d, and until that close has erased the card wallet: the alias stays held while
 * the close is still tearing the card wallet down, also after [close] has returned to its caller.
 *
 * A redeemer dropped without [close] keeps its alias, and its card wallet's synchronizer with the engine it runs,
 * for the rest of the process's lifetime: nothing closes or erases it on garbage collection. Always [close] a
 * redeemer that has been used; a card wallet left behind by a process that ended first is found by [storedAliases].
 *
 * Pass the main wallet's synchronizer as [redeem]'s `destination` so that it learns about the
 * claim at once and treats it as trusted (ZIP 315): without that, the main wallet sees the
 * incoming funds only when it next syncs, and as an untrusted external receive that it holds for
 * 10 confirmations rather than 3. The card's issuer still holds the card's key, so a reorg deep
 * enough to drop the claim (the trusted count, 3 blocks) would let the issuer get a competing
 * spend of the card's notes mined instead: trusting the claim is sound only when the issuer is
 * trusted, as for cards issued by ZODL.
 *
 * Typical use:
 *
 * ```
 * val card = GiftCard.parse(link)
 * val redeemer = GiftCardRedeemers.new(context, card, network, endpoint, isTorEnabled)
 * try {
 *     when (val status = redeemer.check()) {
 *         is GiftCardRedeemer.Status.Ready ->
 *             redeemer.redeem(RecipientAddress.new(myAddress, network), destination = mySynchronizer)
 *         is GiftCardRedeemer.Status.Pending -> showPending(status.balance)
 *         GiftCardRedeemer.Status.Empty -> showAlreadyRedeemed()
 *     }
 * } finally {
 *     redeemer.close()
 * }
 * ```
 *
 * All functions are safe to call from any coroutine; they are serialized internally.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class GiftCardRedeemer private constructor(
    private val context: Context,
    val card: GiftCard,
    private val lightWalletEndpoint: LightWalletEndpoint,
    private val isTorEnabled: Boolean,
    /** The alias of the temporary wallet. Never [ZcashSdk.DEFAULT_ALIAS]. */
    val alias: String,
    private val wallets: GiftCardWallets,
    private val aliases: GiftCardAliases,
    /** Where [close] tears the card wallet down, so that the teardown outlives a caller that stops waiting. */
    private val teardownScope: CoroutineScope
) {
    /** The network the card, and the redemption, are on. */
    val network: ZcashNetwork = card.network

    private val mutex = Mutex()
    private var synchronizer: CloseableSynchronizer? = null
    private var isClosed = false

    /**
     * Whether this redeemer holds [alias] in [aliases]. Only the redeemer that holds it erases the card wallet, so
     * that closing a redeemer refused as [GiftCardException.InUse] cannot delete the wallet another one is using.
     * Written under [mutex] when acquired, and by the teardown, which nothing races, when released.
     */
    @Volatile
    private var holdsAlias = false

    /**
     * How long [check] lets the card wallet stay disconnected by default: longer over Tor, which is slower to
     * connect.
     */
    private val defaultDisconnectedTimeout: Duration =
        if (isTorEnabled) DEFAULT_TOR_DISCONNECTED_TIMEOUT else DEFAULT_DISCONNECTED_TIMEOUT

    /** How long [awaitLateFunds] waits: longer over Tor, where the engine's mempool stream needs a new circuit. */
    private val emptySettle: Duration = if (isTorEnabled) TOR_EMPTY_SETTLE else EMPTY_SETTLE

    /**
     * The checks in progress, which [close] cancels. Guarded by itself. Nothing else cancels them:
     * a cancellation from inside a check fails it, and a cancelled caller cancels it with itself.
     */
    private val inFlightChecks = mutableSetOf<Job>()

    /**
     * The outcome of the teardown the first [close] started, if any: completed with the error to report to that
     * [close] (the card wallet's failure to close, or the first failure to erase it), or `null`, once the teardown
     * has erased the card wallet and released the alias, as soon as the first erase fails while the retries go on,
     * or when the teardown is cancelled. Written under [teardownLock].
     */
    @Volatile
    private var teardownOutcome: CompletableDeferred<Throwable?>? = null

    private val teardownLock = Any()

    /** Whether [close] has been called: set as soon as it starts the teardown, before it waits for anything. */
    private val isClosing: Boolean get() = teardownOutcome != null

    /**
     * Set once a [close] has returned, or was cancelled, before its teardown finished. A [redeem] still preparing
     * then refuses to create its transaction: the caller may already have wiped the card's key.
     */
    @Volatile
    private var isCloseDetached = false

    /**
     * Whether the card wallet starts exactly at the card's birthday: asked for until a wallet has been opened, then
     * whether the open wallet really does, as the exact tree state may not have been available.
     */
    private var isBirthdayExact = true

    /** The card wallet's account, set once a [check] has completed, including its fee probe. */
    private var checkedAccount: Account? = null

    /** A critical error reported by the open card wallet's synchronizer. */
    private val criticalError = MutableStateFlow<WalletFailure?>(null)

    /** [OpenedCardWallet.isDisconnectedUntilFirstPass] of the open card wallet. */
    private var isDisconnectedUntilFirstPass = false

    /** The failed sync passes the open card wallet, one that [isDisconnectedUntilFirstPass], has reported. */
    private val processorErrors = MutableStateFlow(ProcessorErrors())

    private class WalletFailure(
        val cause: Throwable?
    )

    /**
     * @property isReported whether the open card wallet has reported a failed sync pass, which ends its idle wait
     * before the first pass; reset when a card wallet is opened.
     * @property retries how many times, during the current [check], its engine has been told to retry a failed sync
     * pass; see [MAX_PROCESSOR_ERROR_RETRIES]. Reset with [failure] by every [check].
     * @property failure the failed sync pass the current [check] fails with, once its retries are spent.
     */
    private data class ProcessorErrors(
        val isReported: Boolean = false,
        val retries: Int = 0,
        val failure: WalletFailure? = null
    )

    private class SyncedWallet(
        val account: Account,
        val balance: Balance
    )

    /** The card wallet's balance, as found on chain. */
    data class Balance(
        /** Everything the card holds, spendable or not. */
        val total: Zatoshi,
        /**
         * What can be spent now. A redemption pays this minus the ZIP 317 fee, so it is
         * redeemable only when it exceeds that fee.
         */
        val spendable: Zatoshi,
        /**
         * What the card holds but cannot spend yet: funds received less than 10 blocks ago
         * (about 12 minutes) under the default confirmations policy.
         */
        val pending: Zatoshi
    )

    /** What [check] found on the card. */
    sealed interface Status {
        /**
         * Funds can be redeemed now: [Balance.spendable] exceeds the ZIP 317 [fee] that a
         * redemption of it pays. [Balance.pending] may still be non-zero, in which case a
         * redemption sweeps only [Balance.spendable] and leaves the rest on the card.
         *
         * @property fee the fee a redemption pays now, as proposed for the card's actual notes.
         */
        data class Ready(
            val balance: Balance,
            val fee: Zatoshi
        ) : Status {
            /** What a redemption sends to the recipient now: [Balance.spendable] minus [fee]. */
            val redeemable: Zatoshi get() = balance.spendable - fee
        }

        /**
         * The card holds more than the fee, but not enough of it is spendable yet to pay for a
         * redemption, including when what is spendable does not cover the fee its notes
         * require. Check again later.
         */
        data class Pending(
            val balance: Balance
        ) : Status

        /**
         * The card holds nothing that can be redeemed: it was never funded, it has already been
         * redeemed, or what it holds does not exceed the ZIP 317 fee a redemption would pay
         * (for a card holding many small notes, that fee is above the 10,000 zatoshi minimum).
         */
        data object Empty : Status
    }

    /**
     * The outcome of [redeem].
     *
     * @property fee the fee paid, deducted from the card's balance.
     * @property results one result per created transaction (a redemption is normally a single
     * transaction), in order. Each carries the transaction id. Never empty: a redemption whose
     * proposal created no transaction fails with [GiftCardException.RedemptionIncomplete] instead.
     * @property recordedInDestination `true` when a `destination` was passed to [redeem] and
     * every submitted transaction was recorded in it as trusted (ZIP 315). `false` when no
     * destination was passed, nothing was submitted, or the destination failed to record the
     * claim, including when the claim does not involve the destination's wallet and so was not
     * stored there. Each submitted transaction is recorded on its own, so for a claim of several
     * transactions `false` can also mean that only some of them were recorded: one that failed
     * does not keep the others from being recorded. The redemption itself still stands either
     * way. A destination that owns [redeem]'s `toAddress` finds the funds on its own when it next
     * syncs, as an untrusted receive; a destination the claim does not pay never sees it.
     * @property amount what the redemption sends to the recipient, as its proposal computes it:
     * what the spent notes hold, minus [fee] and any change; `null` when not known or not
     * positive.
     */
    data class Redemption(
        val fee: Zatoshi,
        val results: List<TransactionSubmitResult>,
        val recordedInDestination: Boolean = false,
        val amount: Zatoshi? = null
    ) {
        /**
         * `true` when every transaction was accepted by the server. [redeem] never returns empty [results], so this is
         * never vacuously `true`.
         */
        val isSubmitted: Boolean get() = results.all { it is TransactionSubmitResult.Success }
    }

    /**
     * Creates the temporary wallet if needed, waits until it has synced to the chain tip, and
     * reports the card's balance.
     *
     * The first call scans the chain from the card's birthday, which needs network access and
     * may take a while for an old card. The wallet starts exactly at [GiftCard.birthdayHeight],
     * using the tree state fetched from [lightWalletEndpoint]; if the server cannot provide it, the
     * scan starts at the nearest bundled checkpoint below it instead, which takes longer.
     * Later calls reuse the synced wallet and return quickly, so polling a [Status.Pending] card
     * is cheap.
     *
     * On an engine that reports its balance from a summary refreshed after the sync and watches the mempool only
     * after its first pass (the Slipstream engine), the balance counts only once the wallet has scanned up to the
     * chain tip, and a card that still looks empty then is watched for 15 seconds more (30 seconds over Tor), so that
     * a funding transaction found just after the sync is not missed. A card that holds nothing and has already sent
     * a transaction (one redeemed or spent before) is not watched: it is reported [Status.Empty] at once.
     *
     * A card wallet that started exactly at the birthday and found no transaction at all (as opposed to funds
     * received and then spent), nor any funds during that watch, is then scanned once more from the bundled
     * checkpoint below the birthday before the card is reported [Status.Empty], in case the link's height was above
     * the card's funding; that scan's result is classified at once, without a second watch. A card wallet that
     * started at the checkpoint is not rescanned.
     *
     * A card that looks redeemable is checked against the fee its actual notes require (see
     * [Status.Ready.fee]), so [Status.Ready] means that [redeem] can send something.
     *
     * [close] cancels a check in progress, which then fails with [GiftCardException.Closed]. A check whose caller is
     * cancelled rethrows the cancellation; any other cancellation from inside the check is a failure of the check.
     *
     * A card wallet whose setup failed for good (its [Synchronizer.setupError] is set) is closed and
     * erased when the check fails on it, so the next check starts over with a new one.
     *
     * @param timeout how long to wait for the card wallet's account to be created and for the sync
     * to complete.
     * @param disconnectedTimeout how long the card wallet may stay unable to reach the server
     * before the check gives up, rather than waiting for the whole [timeout]: by default
     * [DEFAULT_DISCONNECTED_TIMEOUT], or [DEFAULT_TOR_DISCONNECTED_TIMEOUT] over Tor. A failure to reach the server
     * while the card wallet starts counts as being disconnected, not as an unrecoverable error. On
     * an engine that reports trouble reaching the server as being idle or as syncing that does not
     * advance, rather than as [Synchronizer.Status.DISCONNECTED] (the Slipstream engine), it is how
     * long the card wallet may go without any sync progress, idle before its first pass included.
     *
     * @throws GiftCardException.SyncFailed if the temporary wallet could not be created or set
     * up, did not sync within [timeout], stayed disconnected or without progress for
     * [disconnectedTimeout], kept failing its sync passes after [MAX_PROCESSOR_ERROR_RETRIES]
     * retries, or stopped on an unrecoverable error. The cause, if any, is attached.
     * @throws GiftCardException.InUse if another redeemer in this process is using the same
     * temporary wallet.
     * @throws GiftCardException.Closed if [close] was called.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun check(
        timeout: Duration = DEFAULT_SYNC_TIMEOUT,
        disconnectedTimeout: Duration = defaultDisconnectedTimeout
    ): Status {
        if (isClosing) throw GiftCardException.Closed()
        val outcome =
            coroutineScope {
                val check =
                    async {
                        try {
                            Result.success(mutex.withLock { checkLocked(timeout, disconnectedTimeout) })
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            Result.failure(GiftCardException.SyncFailed(e))
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                synchronized(inFlightChecks) { inFlightChecks += check }
                if (isClosing) check.cancel()
                try {
                    check.await()
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    Result.failure(GiftCardException.Closed())
                } finally {
                    synchronized(inFlightChecks) { inFlightChecks -= check }
                }
            }
        return outcome.getOrThrow()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun checkLocked(
        timeout: Duration,
        disconnectedTimeout: Duration
    ): Status {
        checkedAccount = null
        processorErrors.update { it.copy(retries = 0, failure = null) }
        try {
            var synchronizer = openSynchronizer(isBirthdayExact = isBirthdayExact)
            var synced = awaitSyncedWallet(synchronizer, timeout, disconnectedTimeout)
            val isUnfundedAtExactBirthday =
                isBirthdayExact && synced.balance.total.value == 0L && !hasHistory(synchronizer, synced.account)
            val isRedeemed =
                isDisconnectedUntilFirstPass &&
                    synced.balance.total.value == 0L &&
                    hasSentTransaction(synchronizer, synced.account)
            var balance = synced.balance
            if (isDisconnectedUntilFirstPass && balance.toStatus() == Status.Empty && !isRedeemed) {
                balance = awaitLateFunds(synchronizer, synced.account) ?: balance
            }
            if (isUnfundedAtExactBirthday && balance.total.value == 0L) {
                Twig.info { "Gift card wallet found nothing from the exact birthday; rescanning from the checkpoint" }
                synchronizer.close()
                this.synchronizer = null
                synchronizer = openSynchronizer(isBirthdayExact = false)
                synced = awaitSyncedWallet(synchronizer, timeout, disconnectedTimeout)
                balance = synced.balance
            }
            return statusOf(synchronizer, synced.account, balance).also { checkedAccount = synced.account }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            discardIfSetupFailed()
            throw e
        }
    }

    /**
     * Closes the open card wallet and erases its data when it has latched a setup error
     * ([Synchronizer.setupError]): such a wallet never recovers, so the next [check] must open a new
     * one instead of failing on the same error. Best-effort: the next [openSynchronizer] erases the
     * card wallet again before opening it, and [close] erases it in any case. Only the failure's
     * type is logged.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun discardIfSetupFailed() {
        val cardWallet = synchronizer ?: return
        if (cardWallet.setupError.value == null) return
        synchronizer = null
        try {
            cardWallet.close()
            wallets.erase(context, network, alias)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.warn { "Discarding a gift card wallet whose setup failed did not complete: ${e::class.simpleName}" }
        }
    }

    /**
     * The card's balance once it holds more than [Status.Empty] allows, if that happens within [emptySettle];
     * `null` otherwise.
     *
     * A card wallet that [isDisconnectedUntilFirstPass] (the Slipstream engine's) can report funds a few seconds
     * after it first reports [Synchronizer.Status.SYNCED]: it opens its mempool stream only after its initial pass,
     * so a funding transaction that is not mined yet arrives later, and the balance it reports with that status can
     * come from a cached summary taken before the pass stored the transactions it enhanced. Waiting here keeps such
     * a card from being reported [Status.Empty]; only a card that really is empty pays the wait. It runs after the
     * sync wait, so it neither counts against the stall and disconnection graces nor can trip them.
     */
    private suspend fun awaitLateFunds(
        synchronizer: Synchronizer,
        account: Account
    ): Balance? =
        withTimeoutOrNull(emptySettle) {
            synchronizer.walletBalances
                .mapNotNull { it?.get(account.accountUuid)?.toGiftCardBalance() }
                .first { it.toStatus() != Status.Empty }
        }

    /** Whether the card wallet has seen any transaction at all. */
    private suspend fun hasHistory(
        synchronizer: Synchronizer,
        account: Account
    ): Boolean = walletCreationStep { synchronizer.getTransactions(account.accountUuid).first().isNotEmpty() }

    /**
     * Whether the card wallet has sent a transaction: the card was redeemed, or otherwise spent, already. A
     * transaction it only received does not count, as a card funded moments ago can list its funding transaction
     * before the balance includes it.
     */
    private suspend fun hasSentTransaction(
        synchronizer: Synchronizer,
        account: Account
    ): Boolean =
        walletCreationStep {
            synchronizer.getTransactions(account.accountUuid).first().any { it.isSentTransaction }
        }

    /**
     * The card's status for [balance]. A card that is [Status.Ready] by the minimum fee is
     * proposed for real, so that the fee its notes require decides.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun statusOf(
        synchronizer: Synchronizer,
        account: Account,
        balance: Balance
    ): Status {
        if (balance.toStatus() !is Status.Ready) return balance.toStatus()
        val fee =
            try {
                val recipient = wallets.feeEstimateRecipient(synchronizer, account, network)
                synchronizer.proposeSendMax(account, recipient, null).totalFeeRequired()
            } catch (e: CancellationException) {
                throw e
            } catch (_: TransactionEncoderException.InsufficientFundsException) {
                null
            } catch (e: Exception) {
                throw GiftCardException.SyncFailed(e)
            }
        return when {
            fee != null && balance.spendable.value > fee.value -> Status.Ready(balance, fee)
            balance.pending.value > 0 -> Status.Pending(balance)
            else -> Status.Empty
        }
    }

    /**
     * Sends the card's entire spendable balance, minus the ZIP 317 fee, to [toAddress] and
     * submits the transaction to the redeemer's lightwalletd endpoint.
     *
     * The transaction is created with [OvkPolicy.Discard]: neither the card's issuer nor anyone
     * else holding the card's key can learn [toAddress] from it. Funds that are not yet
     * spendable stay on the card and can be redeemed later.
     *
     * Call [check] first, and only redeem a [Status.Ready] card. If a result is not a
     * [TransactionSubmitResult.Success], the funds may still be on the card but are held by the
     * unsubmitted transaction in the temporary wallet: [close] this redeemer and start over with
     * a new one to retry. A submission that throws (for example, when no connection to the
     * server can be created) is reported the same way, as a [TransactionSubmitResult.Failure]
     * with [SUBMIT_THREW_CODE], so that every redemption whose transaction was created ends in a
     * [Redemption] rather than an exception.
     *
     * With a [destination], the submitted transaction is also recorded in that wallet as
     * trusted (ZIP 315, see [Synchronizer.recordTrustedTransaction]): the wallet shows the
     * incoming funds immediately rather than after its next sync, and can spend them after 3
     * confirmations rather than the 10 it applies to an external receive. [destination] should be
     * the wallet that owns [toAddress]; recording in a wallet that does not own it stores nothing
     * of value. A failure to record never fails the redemption, which has already happened on
     * chain: it is logged and reported as [Redemption.recordedInDestination] being `false`. A
     * destination that owns [toAddress] then finds the funds by itself when it next syncs; one
     * that does not never sees them. Every transaction the server accepted is recorded on its own,
     * so one that fails to record does not keep the others from being recorded, and they are
     * recorded also when a later submission is interrupted by a cancellation, which is then
     * rethrown.
     *
     * A redemption can be cancelled until its transaction starts being created. From then on it
     * creates, submits and records the claim to the end even if its caller is cancelled, as a
     * claim created but not submitted would hold the card's funds until the redeemer is closed,
     * and one submitted but not recorded would be held by [destination] for 10 confirmations
     * instead of 3; a cancelled caller gets the [CancellationException] once it has. [close] waits
     * for such a redemption before it tears the card wallet down.
     *
     * @param toAddress the user's own address, on [network].
     * @param memo an optional memo for the recipient; must be `null` for a transparent address.
     * @param destination the user's own wallet, to be told about the claim at once; `null` to
     * let it find the funds on its next sync.
     *
     * @throws GiftCardException.NotChecked if no [check] on this redeemer has completed yet.
     * @throws GiftCardException.NothingToRedeem if the spendable balance does not exceed the
     * fee, including when nothing is spendable: decided from the balance before anything is
     * proposed, or from the backend's typed refusal of the proposal.
     * @throws GiftCardException.NetworkMismatch if [toAddress] or [destination] is for another
     * network.
     * @throws GiftCardException.RedemptionIncomplete if the proposal created no transaction, so
     * that a [Redemption] would report nothing. Nothing was sent then.
     * @throws GiftCardException.Closed if [close] was called, including when a [close] stopped
     * waiting for this redemption before it started creating its transaction, or if the card's key
     * was wiped with [GiftCard.wipe].
     * @throws TransactionEncoderException if the transaction could not be created.
     */
    @Suppress("ThrowsCount")
    suspend fun redeem(
        toAddress: RecipientAddress,
        memo: MemoContent? = null,
        destination: Synchronizer? = null
    ): Redemption {
        if (isClosing) throw GiftCardException.Closed()
        return mutex.withLock {
            if (toAddress.network != network) throw GiftCardException.NetworkMismatch()
            if (destination != null && destination.network != network) throw GiftCardException.NetworkMismatch()
            if (isClosed) throw GiftCardException.Closed()
            val cardWallet = synchronizer
            val account = checkedAccount
            if (cardWallet == null || account == null) throw GiftCardException.NotChecked()

            val balance =
                cardWallet.walletBalances.value
                    ?.get(account.accountUuid)
                    ?.toGiftCardBalance()
            if (balance == null || balance.spendable.value <= MINIMUM_FEE.value) {
                throw GiftCardException.NothingToRedeem()
            }

            val proposal =
                try {
                    cardWallet.proposeSendMax(account, toAddress, memo)
                } catch (e: TransactionEncoderException.InsufficientFundsException) {
                    throw GiftCardException.NothingToRedeem(e)
                }

            if (isCloseDetached) throw GiftCardException.Closed()
            val seed = cardSeed()
            val usk =
                try {
                    wallets.deriveSpendingKey(seed, network)
                } finally {
                    seed.fill(0)
                }
            if (isCloseDetached) throw GiftCardException.Closed()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { completeClaim(cardWallet.broadcaster, proposal, usk, destination) }
                .also { currentCoroutineContext().ensureActive() }
        }
    }

    /**
     * A copy of the card's seed, for the caller to wipe after use.
     *
     * @throws GiftCardException.Closed if the card's key was wiped with [GiftCard.wipe].
     */
    private fun cardSeed(): ByteArray =
        try {
            card.seed.copyBytes()
        } catch (_: IllegalStateException) {
            throw GiftCardException.Closed()
        }

    /**
     * Creates the transactions of [proposal], submits them and records the accepted ones in
     * [destination]: the part of [redeem] that runs to the end once it has started, which [redeem]
     * runs uncancellably. A cancellation thrown while submitting is rethrown once the transactions
     * the server accepted before it have been recorded.
     *
     * @throws GiftCardException.RedemptionIncomplete if nothing was created.
     */
    private suspend fun completeClaim(
        broadcaster: Broadcaster,
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        destination: Synchronizer?
    ): Redemption {
        val created = broadcaster.createProposedTransactions(proposal, usk, OvkPolicy.Discard)
        if (created.isEmpty()) throw GiftCardException.RedemptionIncomplete()
        val results = mutableListOf<TransactionSubmitResult>()
        val interruption =
            try {
                submitInOrder(broadcaster, created, results)
                null
            } catch (e: CancellationException) {
                e
            }
        val recorded = destination != null && recordInDestination(destination, created, results)
        interruption?.let { throw it }
        return Redemption(
            fee = proposal.totalFeeRequired(),
            results = results,
            recordedInDestination = recorded,
            amount = proposal.totalSent().takeIf { it.value > 0 }
        )
    }

    /**
     * Submits [created] to the redeemer's endpoint in order, adding each result to [results] and
     * stopping at the first failure: a later transaction of a multi-step proposal depends on the
     * earlier ones. This mirrors what [Synchronizer.createProposedTransactions] does after creating
     * the transactions; [redeem] creates and submits in two steps so that the raw transactions are
     * at hand for its destination. A submission that throws is a [TransactionSubmitResult.Failure]
     * with [SUBMIT_THREW_CODE], including one that times out with a [TimeoutCancellationException]:
     * that one comes from the submission itself, as [redeem] runs this uncancellably, so no timeout
     * of its caller can reach it. Any other cancellation is rethrown, leaving in [results] what was
     * submitted before it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitInOrder(
        broadcaster: Broadcaster,
        created: List<CreatedTransaction>,
        results: MutableList<TransactionSubmitResult>
    ) {
        var failed = false
        created.forEach { transaction ->
            if (failed) {
                results += TransactionSubmitResult.NotAttempted(transaction.txId)
            } else {
                val result =
                    try {
                        broadcaster.submit(transaction, lightWalletEndpoint)
                    } catch (e: TimeoutCancellationException) {
                        submitThrew(transaction, e)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        submitThrew(transaction, e)
                    }
                results += result
                failed = result !is TransactionSubmitResult.Success
            }
        }
    }

    /** The [TransactionSubmitResult.Failure] of a submission of [transaction] that threw [failure]. */
    private fun submitThrew(
        transaction: CreatedTransaction,
        failure: Exception
    ): TransactionSubmitResult.Failure {
        Twig.warn(failure) { "Submitting the gift card claim failed" }
        return TransactionSubmitResult.Failure(
            txId = transaction.txId,
            grpcError = true,
            code = SUBMIT_THREW_CODE,
            description = failure::class.simpleName
        )
    }

    /**
     * Records in [destination] as trusted each transaction of [created] whose result in [results]
     * is a [TransactionSubmitResult.Success], each on its own: one that fails to record does not
     * keep the others from being recorded. Returns `true` only when there was something to record
     * and all of it was recorded; a failure is logged, not thrown, as the funds have moved
     * regardless. A destination that owns the redemption's address finds them when it next syncs;
     * recording fails when a claim does not involve the destination's wallet, which then stores
     * nothing and never sees the claim. Only the failure's type is logged: its message can carry
     * the transaction id.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun recordInDestination(
        destination: Synchronizer,
        created: List<CreatedTransaction>,
        results: List<TransactionSubmitResult>
    ): Boolean {
        val submitted = created.zip(results).filter { (_, result) -> result is TransactionSubmitResult.Success }
        if (submitted.isEmpty()) return false
        var recordedAll = true
        submitted.forEach { (transaction, _) ->
            try {
                destination.recordTrustedTransaction(
                    rawTransaction = RawTransaction(data = transaction.raw.byteArray, height = null),
                    txId = TransactionId.new(transaction.txId)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordedAll = false
                Twig.warn {
                    "The gift card claim could not be recorded in the destination wallet " +
                        "(${e::class.simpleName}); the redemption stands, and that wallet finds the claim " +
                        "at its next sync only if the claim pays it"
                }
            }
        }
        return recordedAll
    }

    /**
     * Closes the temporary wallet and deletes all of its local data. Nothing that belongs to
     * any other wallet is touched. Idempotent; the redeemer cannot be used afterwards: a [check] or
     * [redeem] called once this has been called fails with [GiftCardException.Closed] at once.
     *
     * Always call this when done, including after a failure: it also lets another redeemer
     * for the same card be used. If the app is killed before it runs, the data is removed the
     * next time a redeemer for the same card is used, or explicitly with `GiftCardRedeemers.erase`
     * from the SDK incubator and [alias]; [storedAliases] lists the card wallets left on the
     * device.
     *
     * A [check] in progress is cancelled; a [redeem] in progress is waited for, and a redemption
     * that has started creating its transaction always finishes before the card wallet is torn
     * down. The teardown (closing the card wallet, then erasing it) runs on its own, independently
     * of the caller: this returns after at most 30 seconds ([CLOSE_TIMEOUT]) even if the teardown has not
     * finished, for example because the card wallet's engine does not stop, and a cancelled caller
     * stops waiting at once; the teardown goes on either way. A redemption still preparing when this
     * stops waiting then fails with [GiftCardException.Closed] rather than create its transaction.
     *
     * The alias stays held, so that no other redeemer can use the card, until the card wallet has
     * been erased. An erase that fails (for example while the card wallet's engine is still
     * running, or when the engine reports that some of the card wallet's files remain) is retried
     * after 1, 2, 4, 8 and 16 seconds and then every 30 seconds, for as long as the process lives,
     * until one succeeds; only then is the alias released. A failure that never goes away therefore
     * keeps the card in use, and one erase attempt running every 30 seconds, until the process ends;
     * `GiftCardRedeemers.erase` deletes the leftovers in the next process.
     *
     * @throws Exception the card wallet's failure to close, once the card wallet has been erased, or
     * the first failure to erase it, as soon as it happens. That one may well be transient: the
     * retries go on, and a later one can still erase the card wallet and release the alias. Only the
     * call that starts the teardown reports its failures; a later or concurrent call waits for the
     * same teardown and returns.
     */
    suspend fun close() {
        val (outcome, isStarted) = startTeardown()
        synchronized(inFlightChecks) { inFlightChecks.toList() }.forEach { it.cancel() }
        var isFinished = false
        try {
            isFinished = withTimeoutOrNull(CLOSE_TIMEOUT) { outcome.join() } != null
        } finally {
            if (!isFinished) isCloseDetached = true
        }
        if (!isFinished) {
            Twig.warn { "Closing a gift card wallet takes longer than $CLOSE_TIMEOUT; it goes on in the background" }
            return
        }
        if (isStarted) outcome.await()?.let { throw it }
    }

    /** The outcome of the teardown, started by the first call, with whether this call started it. */
    private fun startTeardown(): Pair<CompletableDeferred<Throwable?>, Boolean> =
        synchronized(teardownLock) {
            teardownOutcome?.let { return it to false }
            val outcome = CompletableDeferred<Throwable?>()
            teardownOutcome = outcome
            teardownScope.launch { tearDown(outcome) }
            outcome to true
        }

    /**
     * Closes the card wallet, once no [redeem] holds [mutex], then erases it and releases the
     * alias; see [close]. Completes [outcome] with the first failure to erase, as soon as it
     * happens, and in any case with the card wallet's failure to close, if any, when it ends,
     * also when [teardownScope] cancels it. Only the failures' types are logged.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun tearDown(outcome: CompletableDeferred<Throwable?>) {
        var closeFailure: Throwable? = null
        try {
            val isEraseNeeded =
                mutex.withLock {
                    isClosed = true
                    checkedAccount = null
                    try {
                        synchronizer?.close()
                    } catch (e: Exception) {
                        closeFailure = e
                        Twig.warn { "Closing a gift card wallet failed: ${e::class.simpleName}" }
                    } finally {
                        synchronizer = null
                    }
                    holdsAlias
                }
            if (isEraseNeeded) eraseAndRelease(outcome)
        } finally {
            outcome.complete(closeFailure)
        }
    }

    /**
     * Erases the card wallet and releases the alias, retrying a failed erase as [close] describes,
     * without a limit. The alias is released only once an erase has succeeded, with no suspension
     * point in between, so that a cancellation cannot leave the card wallet erased but its alias
     * held.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun eraseAndRelease(outcome: CompletableDeferred<Throwable?>) {
        var retry = 0
        while (true) {
            try {
                wallets.erase(context, network, alias)
                aliases.release(network, alias)
                holdsAlias = false
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                outcome.complete(e)
                Twig.warn { "Erasing a gift card wallet failed (${e::class.simpleName}); retrying" }
                delay(ERASE_RETRY_DELAYS[retry])
                retry = minOf(retry + 1, ERASE_RETRY_DELAYS.lastIndex)
            }
        }
    }

    /**
     * The open card wallet, or a new one. A new one always starts from scratch: the temporary
     * wallet is disposable, and a fresh scan is what makes an already-redeemed card show up as
     * empty. Erasing first also clears leftovers of an earlier redemption that was interrupted
     * before [close].
     */
    private suspend fun openSynchronizer(isBirthdayExact: Boolean): CloseableSynchronizer {
        if (isClosed) throw GiftCardException.Closed()
        synchronizer?.let { return it }

        if (!holdsAlias) {
            if (!aliases.acquire(network, alias)) throw GiftCardException.InUse()
            holdsAlias = true
        }

        val opened =
            walletCreationStep {
                wallets.erase(context, network, alias)

                criticalError.update { null }
                val seed = card.seed.copyBytes()
                try {
                    wallets.open(
                        context = context,
                        network = network,
                        alias = alias,
                        birthday = card.birthdayHeight,
                        isBirthdayExact = isBirthdayExact,
                        lightWalletEndpoint = lightWalletEndpoint,
                        isTorEnabled = isTorEnabled,
                        setup =
                            AccountCreateSetup(
                                accountName = ACCOUNT_NAME,
                                keySource = null,
                                seed = FirstClassByteArray(seed)
                            ),
                        onCriticalError = { error ->
                            criticalError.update { it ?: WalletFailure(error) }
                            false
                        }
                    )
                } finally {
                    seed.fill(0)
                }
            }
        this.isBirthdayExact = opened.startsAtBirthday
        isDisconnectedUntilFirstPass = opened.isDisconnectedUntilFirstPass
        processorErrors.update { it.copy(isReported = false) }
        if (opened.isDisconnectedUntilFirstPass) {
            opened.synchronizer.onProcessorErrorHandler = { error ->
                val errors =
                    processorErrors.updateAndGet {
                        val retries = it.retries + 1
                        val isSpent = retries > MAX_PROCESSOR_ERROR_RETRIES
                        ProcessorErrors(true, retries, it.failure ?: if (isSpent) WalletFailure(error) else null)
                    }
                errors.retries <= MAX_PROCESSOR_ERROR_RETRIES
            }
        }
        synchronizer = opened.synchronizer
        return opened.synchronizer
    }

    /**
     * Runs a step of creating or opening the temporary wallet, reporting any failure as the
     * documented [GiftCardException.SyncFailed] (for example, no bundled checkpoint at or below
     * the card's birthday, a database that cannot be opened, or a server that cannot be reached).
     * The cause stays attached for diagnosis; the message is fixed, as the cause may come from
     * the backend.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> walletCreationStep(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GiftCardException) {
            throw e
        } catch (e: Exception) {
            throw GiftCardException.SyncFailed(e)
        }

    /**
     * Waits, for at most [timeout], until the card wallet's account exists and the wallet has synced,
     * and returns the account with its balance.
     *
     * @throws GiftCardException.SyncFailed on [timeout], or when either wait fails.
     */
    private suspend fun awaitSyncedWallet(
        synchronizer: Synchronizer,
        timeout: Duration,
        disconnectedTimeout: Duration
    ): SyncedWallet =
        withTimeoutOrNull(timeout) {
            val account = awaitAccount(synchronizer)
            SyncedWallet(account, awaitSyncedBalance(synchronizer, account, disconnectedTimeout).toGiftCardBalance())
        } ?: throw GiftCardException.SyncFailed(criticalError.value?.cause ?: synchronizer.setupError.value)

    /**
     * The card wallet's account, once it exists. A wallet whose preparation runs after its creation
     * returns (the Slipstream engine's) writes the account only once it has resolved where to start
     * scanning, which over Tor can take tens of seconds: until then its account list is empty or not
     * loaded yet. Fails fast when the wallet latches a setup error instead.
     *
     * @throws GiftCardException.SyncFailed if the setup failed or the accounts cannot be read.
     */
    private suspend fun awaitAccount(synchronizer: Synchronizer): Account =
        walletCreationStep {
            merge(
                synchronizer.accountsFlow.mapNotNull { it?.firstOrNull() },
                synchronizer.setupError.filterNotNull().map { throw GiftCardException.SyncFailed(it) }
            ).first()
        }

    /**
     * Waits until the card wallet is synced and returns its balance. Fails fast on a critical
     * error, on a setup error the wallet latched in [Synchronizer.setupError], on a failed sync pass
     * once [MAX_PROCESSOR_ERROR_RETRIES] retries are spent, when the wallet stops, and when it stays
     * [Synchronizer.Status.DISCONNECTED] for [disconnectedTimeout].
     *
     * For a wallet that [isDisconnectedUntilFirstPass], [Synchronizer.Status.DISCONNECTED] counts as
     * being disconnected only once the wallet has synced at least partly or has reported a failed
     * sync pass; before that it is idle, waiting for its first pass. Such a wallet reports trouble
     * reaching the server as being idle, or as syncing that does not advance, so it fails too once it
     * has gone [disconnectedTimeout] without any sync progress before it is synced (see
     * [failWhenStalled]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("ThrowsCount")
    private suspend fun awaitSyncedBalance(
        synchronizer: Synchronizer,
        account: Account,
        disconnectedTimeout: Duration
    ): AccountBalance {
        var isPastFirstPass = !isDisconnectedUntilFirstPass
        val statusUntilDisconnectedTooLong =
            combine(synchronizer.status, processorErrors.map { it.isReported }.distinctUntilChanged(), ::Pair)
                .transformLatest { (current, errorReported) ->
                    emit(current)
                    if (errorReported ||
                        current == Synchronizer.Status.SYNCING ||
                        current == Synchronizer.Status.SYNCED
                    ) {
                        isPastFirstPass = true
                    }
                    if (current == Synchronizer.Status.DISCONNECTED && isPastFirstPass) {
                        delay(disconnectedTimeout)
                        throw GiftCardException.SyncFailed(null)
                    }
                }
        val isScannedToTip = if (isDisconnectedUntilFirstPass) isScannedToTip(synchronizer) else flowOf(true)
        val status =
            if (isDisconnectedUntilFirstPass) {
                merge(
                    statusUntilDisconnectedTooLong,
                    failWhenStalled(synchronizer, isScannedToTip, disconnectedTimeout)
                )
            } else {
                statusUntilDisconnectedTooLong
            }
        return combine(
            combine(status, isScannedToTip, ::Pair),
            synchronizer.walletBalances,
            criticalError,
            synchronizer.setupError,
            processorErrors.map { it.failure }.distinctUntilChanged()
        ) { (current, scannedToTip), balances, error, setupError, failedPass ->
            error?.let { throw GiftCardException.SyncFailed(it.cause) }
            setupError?.let { throw GiftCardException.SyncFailed(it) }
            failedPass?.let { throw GiftCardException.SyncFailed(it.cause) }
            if (current == Synchronizer.Status.STOPPED) throw GiftCardException.SyncFailed(null)
            balances?.get(account.accountUuid).takeIf { current == Synchronizer.Status.SYNCED && scannedToTip }
        }.filterNotNull().first()
    }

    /**
     * Whether the balance [synchronizer] reports is known to cover the whole chain it has seen: its
     * [Synchronizer.fullyScannedHeight] has reached its [Synchronizer.networkHeight].
     *
     * The Slipstream engine reports the balance and the fully scanned height of a cached wallet summary,
     * refreshed in the background for the next poll, so the poll that first reports
     * [Synchronizer.Status.SYNCED] can still carry the summary taken before the last range was scanned: a card
     * funded in that range would read as empty. Both values come from the same summary, so a summary taken before
     * the last range never passes this, and the next refresh, about two seconds later, does. Exact: the engine's
     * chain tip is the card wallet's own, which its initial pass scans up to before it reports
     * [Synchronizer.Status.SYNCED]. A summary taken after the last range but before the pass stored the
     * transactions it enhanced does pass; [awaitLateFunds] covers that case.
     */
    private fun isScannedToTip(synchronizer: Synchronizer): Flow<Boolean> =
        combine(synchronizer.networkHeight, synchronizer.fullyScannedHeight) { networkHeight, fullyScannedHeight ->
            networkHeight != null && fullyScannedHeight != null && fullyScannedHeight >= networkHeight
        }.distinctUntilChanged()

    /**
     * Fails with [GiftCardException.SyncFailed] once [synchronizer] has gone [grace] without any sync
     * progress (its [Synchronizer.progress] rising above the highest value seen) while it is not
     * [Synchronizer.Status.SYNCED] with [isScannedToTip]: idle before its first pass, for example while
     * its engine cannot reach the server, syncing without advancing, as the Slipstream engine does while
     * a download keeps failing, or synced with a balance that never catches up with the chain tip. The
     * grace restarts with every rise in progress. Never emits.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun failWhenStalled(
        synchronizer: Synchronizer,
        isScannedToTip: Flow<Boolean>,
        grace: Duration
    ): Flow<Nothing> =
        combine(
            combine(synchronizer.status, isScannedToTip) { current, scannedToTip ->
                current == Synchronizer.Status.SYNCED && scannedToTip
            }.distinctUntilChanged(),
            synchronizer.progress
                .map { it.decimal }
                .runningReduce { highest, current -> maxOf(highest, current) }
                .distinctUntilChanged()
        ) { isSynced, _ -> isSynced }
            .transformLatest { isSynced ->
                if (!isSynced) {
                    delay(grace)
                    throw GiftCardException.SyncFailed(null)
                }
            }

    companion object {
        /**
         * How long [close] waits for the card wallet's teardown before it returns; the teardown goes
         * on after that.
         */
        internal val CLOSE_TIMEOUT: Duration = 30.seconds

        /** How long [close] waits before each retry of a failed erase; the last one repeats without a limit. */
        internal val ERASE_RETRY_DELAYS: List<Duration> =
            listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds)

        /**
         * Where every redeemer created through the public API tears its card wallet down: a scope
         * of its own, so that a teardown outlives the [close] that started it. A teardown whose erase
         * keeps failing stays in it for the rest of the process, trying once every 30 seconds
         * ([ERASE_RETRY_DELAYS]); it never retries faster than that, whatever the failure.
         */
        private val PROCESS_TEARDOWN_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** How long [check] waits for the temporary wallet to sync, by default. */
        val DEFAULT_SYNC_TIMEOUT: Duration = 10.minutes

        /**
         * How long [check] lets the temporary wallet stay unable to reach the server, by default,
         * before giving up.
         */
        val DEFAULT_DISCONNECTED_TIMEOUT: Duration = 60.seconds

        /** [DEFAULT_DISCONNECTED_TIMEOUT] for a temporary wallet that connects over Tor. */
        val DEFAULT_TOR_DISCONNECTED_TIMEOUT: Duration = 2.minutes

        /**
         * How many times one [check] tells the temporary wallet's engine to retry a failed sync pass
         * (an engine error, not trouble reaching the server) before the check fails with it. An
         * engine that fails the same way every time is not restarted for the whole of the check's
         * timeout.
         */
        const val MAX_PROCESSOR_ERROR_RETRIES = 2

        /**
         * How long [check] waits, on an engine that opens its mempool stream only after its initial sync
         * pass (the Slipstream engine), for funds to show up on a card that looks empty once synced.
         */
        internal val EMPTY_SETTLE: Duration = 15.seconds

        /** [EMPTY_SETTLE] for a temporary wallet that connects over Tor. */
        internal val TOR_EMPTY_SETTLE: Duration = 30.seconds

        /**
         * The [TransactionSubmitResult.Failure.code] of a submission that threw instead of
         * returning a result.
         */
        const val SUBMIT_THREW_CODE = -1

        /**
         * The smallest fee a ZIP 317 transaction pays (`zip317::MINIMUM_FEE` in
         * `zcash_primitives`: the marginal fee times the grace actions). A card whose spendable
         * balance does not exceed it cannot pay the recipient anything.
         */
        internal val MINIMUM_FEE = Zatoshi(10_000)

        private const val ACCOUNT_NAME = "Gift card"
        private const val ALIAS_PREFIX = "giftcard_"

        /**
         * The alias [new] uses for [card]'s temporary wallet unless told otherwise: unique per
         * card, stable across links for the same card, and revealing nothing about its key.
         */
        fun defaultAlias(card: GiftCard): String = ALIAS_PREFIX + card.id

        /**
         * The aliases of the card wallets whose data is stored on the device for [network], under
         * [defaultAlias]'s naming, whether or not a redeemer is using them. A card wallet whose
         * redeemer was never [close]d, for example because the app was killed, stays on the
         * device until it is erased with `GiftCardRedeemers.erase` from the SDK incubator; this
         * finds it, whichever engine stored it: both lay out the wallet database the same way. Do
         * not erase the wallet of a redeemer that is still in use.
         *
         * @param context any context; its application context is used.
         */
        suspend fun storedAliases(
            context: Context,
            network: ZcashNetwork
        ): Set<String> =
            DatabaseCoordinator
                .getInstance(context.applicationContext)
                .storedAliases(network, ALIAS_PREFIX)

        /**
         * Creates a redeemer for [card] whose temporary wallet runs on [SdkSynchronizer]. Nothing is
         * created on disk or the network until [check] or [redeem] is called.
         *
         * Deprecated because it ignores the engine the app syncs with: `GiftCardRedeemers.new` from
         * the SDK incubator takes the same arguments and runs the temporary wallet on that engine.
         *
         * @param context any context; its application context is used.
         * @param card the card to redeem.
         * @param network the network the app runs on.
         * @param lightWalletEndpoint the server to sync from and submit to; usually the main
         * wallet's.
         * @param isTorEnabled whether the temporary wallet connects to [lightWalletEndpoint] over
         * Tor. Pass the main wallet's setting: with `false`, the server sees the user's IP address
         * along with the card's exact birthday height and the claim transaction.
         * @param alias the temporary wallet's alias. Must be unique to this card and must not be
         * [ZcashSdk.DEFAULT_ALIAS] (also with trailing underscores), the legacy `ZcashSdk`, or any
         * other alias the app uses; 1 to 99 letters, digits, `_` or `-`.
         *
         * @throws GiftCardException.NetworkMismatch if [card] is not for [network].
         * @throws IllegalArgumentException if [alias] is not a valid, non-default alias.
         */
        @Deprecated(
            message =
                "Always runs the card wallet on SdkSynchronizer, whatever engine the app syncs with. " +
                    "Use GiftCardRedeemers.new from the SDK incubator, which runs it on the app's engine."
        )
        @Suppress("LongParameterList")
        fun new(
            context: Context,
            card: GiftCard,
            network: ZcashNetwork,
            lightWalletEndpoint: LightWalletEndpoint,
            isTorEnabled: Boolean,
            alias: String = defaultAlias(card)
        ): GiftCardRedeemer =
            new(
                context = context,
                card = card,
                network = network,
                lightWalletEndpoint = lightWalletEndpoint,
                isTorEnabled = isTorEnabled,
                alias = alias,
                wallets = GiftCardWallets.Default,
                aliases = GiftCardAliases.Process,
                teardownScope = PROCESS_TEARDOWN_SCOPE
            )

        /**
         * [new] with the device-facing and process-wide parts replaced: unit tests pass fakes and
         * their own `teardownScope`, and `GiftCardRedeemers.new` in the SDK incubator passes the
         * card wallets of the engine the app syncs with. That makes this `internal` function a
         * cross-module API: the incubator reaches it because its build registers this module as a
         * Kotlin friend module (`friendPaths`), so keep its signature in step with that caller.
         */
        @Suppress("LongParameterList")
        internal fun new(
            context: Context,
            card: GiftCard,
            network: ZcashNetwork,
            lightWalletEndpoint: LightWalletEndpoint,
            isTorEnabled: Boolean,
            alias: String,
            wallets: GiftCardWallets,
            aliases: GiftCardAliases,
            teardownScope: CoroutineScope = PROCESS_TEARDOWN_SCOPE
        ): GiftCardRedeemer {
            if (card.network != network) throw GiftCardException.NetworkMismatch()
            requireNotMainWalletAlias(alias, "A gift card must not use the default wallet alias")
            require(
                alias.length in ZcashSdk.ALIAS_MIN_LENGTH..ZcashSdk.ALIAS_MAX_LENGTH &&
                    alias.all { it.isLetterOrDigit() || it == '_' || it == '-' }
            ) { "Invalid alias" }
            return GiftCardRedeemer(
                context = context.applicationContext,
                card = card,
                lightWalletEndpoint = lightWalletEndpoint,
                isTorEnabled = isTorEnabled,
                alias = alias,
                wallets = wallets,
                aliases = aliases,
                teardownScope = teardownScope
            )
        }
    }
}

/**
 * What [GiftCardRedeemer] needs from the device: the temporary card wallet and the derivation of
 * its spending key. [Default] uses [Synchronizer] and [DerivationTool]; unit tests replace it.
 */
internal interface GiftCardWallets {
    /**
     * Deletes the local data of the card wallet under [alias]. Returns only once none of it remains: an erase that
     * leaves any of it behind throws, so that [GiftCardRedeemer] keeps the alias and tries again. Finding nothing to
     * delete is success.
     */
    suspend fun erase(
        context: Context,
        network: ZcashNetwork,
        alias: String
    )

    /**
     * Creates and starts the card wallet under [alias] from the card's seed in [setup], at [birthday] when
     * [isBirthdayExact] and its tree state is available, else at the bundled checkpoint below it.
     * [onCriticalError] is installed before it starts syncing; failing to reach the server while it starts is not
     * a critical error.
     */
    @Suppress("LongParameterList")
    suspend fun open(
        context: Context,
        network: ZcashNetwork,
        alias: String,
        birthday: BlockHeight,
        isBirthdayExact: Boolean,
        lightWalletEndpoint: LightWalletEndpoint,
        isTorEnabled: Boolean,
        setup: AccountCreateSetup,
        onCriticalError: (Throwable?) -> Boolean
    ): OpenedCardWallet

    /**
     * An address to propose a redemption to when only its fee is wanted: the card wallet's own
     * unified address. It has an Orchard receiver, so the probe pays an Orchard output, as a
     * redemption to the app's Orchard destination does. Reading it must not create a new address
     * in the card wallet.
     */
    suspend fun feeEstimateRecipient(
        synchronizer: Synchronizer,
        account: Account,
        network: ZcashNetwork
    ): RecipientAddress

    /** Derives the card wallet's spending key from the card's [seed]. */
    suspend fun deriveSpendingKey(
        seed: ByteArray,
        network: ZcashNetwork
    ): UnifiedSpendingKey

    object Default : GiftCardWallets {
        override suspend fun erase(
            context: Context,
            network: ZcashNetwork,
            alias: String
        ) {
            check(eraseSdkCardWallet(context, network, alias)) { "Some of the card wallet's files remain" }
        }

        /**
         * The card wallet shares the Tor data directory with the main wallet; Arti lets a second
         * client use it read-only, so no fresh bootstrap is needed.
         *
         * The link's height is at or just below the funding height, so the card wallet can start
         * exactly there instead of at the nearest bundled checkpoint, which may be thousands of
         * blocks earlier. The card is not the user's wallet, so revealing its exact height to the
         * server costs it nothing; with `isTorEnabled`, that fetch goes over Tor like the rest of
         * the card wallet's traffic.
         */
        override suspend fun open(
            context: Context,
            network: ZcashNetwork,
            alias: String,
            birthday: BlockHeight,
            isBirthdayExact: Boolean,
            lightWalletEndpoint: LightWalletEndpoint,
            isTorEnabled: Boolean,
            setup: AccountCreateSetup,
            onCriticalError: (Throwable?) -> Boolean
        ): OpenedCardWallet {
            var startsAtBirthday = false
            val synchronizer =
                Synchronizer.new(
                    alias = alias,
                    birthday = birthday,
                    context = context,
                    lightWalletEndpoint = lightWalletEndpoint,
                    setup = setup,
                    walletInitMode = WalletInitMode.RestoreWallet,
                    zcashNetwork = network,
                    isTorEnabled = isTorEnabled,
                    isExchangeRateEnabled = false,
                    isBirthdayExact = isBirthdayExact,
                    onCriticalErrorHandler = onCriticalError,
                    isSetupDisconnectionTolerated = true,
                    onBirthdayResolved = { startsAtBirthday = it }
                )
            return OpenedCardWallet(synchronizer, startsAtBirthday)
        }

        override suspend fun feeEstimateRecipient(
            synchronizer: Synchronizer,
            account: Account,
            network: ZcashNetwork
        ): RecipientAddress = RecipientAddress.new(feeEstimateAddress(synchronizer, account), network)

        override suspend fun deriveSpendingKey(
            seed: ByteArray,
            network: ZcashNetwork
        ): UnifiedSpendingKey =
            DerivationTool.getInstance().deriveUnifiedSpendingKey(
                seed = seed,
                network = network,
                accountIndex = Zip32AccountIndex.new(0)
            )
    }
}

/**
 * Deletes the local data of the [SdkSynchronizer] card wallet under [alias] with [Synchronizer.eraseAlias], and
 * returns whether none of it remains. [Synchronizer.eraseAlias] itself reports `false` both when it found nothing to
 * delete and when a deletion failed, so what remains is looked up afterwards instead.
 *
 * @throws IllegalArgumentException if [alias] is not a valid alias, or addresses the main wallet's files.
 * @throws IllegalStateException if a synchronizer for [network] and [alias] is active.
 */
internal suspend fun eraseSdkCardWallet(
    context: Context,
    network: ZcashNetwork,
    alias: String
): Boolean {
    Synchronizer.eraseAlias(context, network, alias)
    return !DatabaseCoordinator.getInstance(context.applicationContext).hasStoredData(network, alias)
}

/**
 * A card wallet [GiftCardWallets.open] created.
 *
 * @property startsAtBirthday whether it starts exactly at the card's birthday rather than at the bundled
 * checkpoint below it.
 * @property isDisconnectedUntilFirstPass whether its synchronizer, as the Slipstream engine's does,
 * reports [Synchronizer.Status.DISCONNECTED] while merely idle before its first sync pass, trouble
 * reaching the server as being idle or as syncing that does not advance, and engine errors through
 * [Synchronizer.onProcessorErrorHandler], which [GiftCardRedeemer] then takes over. `false` for a
 * wallet that reports DISCONNECTED only when it cannot reach the server.
 */
internal class OpenedCardWallet(
    val synchronizer: CloseableSynchronizer,
    val startsAtBirthday: Boolean,
    val isDisconnectedUntilFirstPass: Boolean = false
)

/**
 * The address [GiftCardWallets.Default] proposes a fee probe to: the card account's current
 * unified address, which has an Orchard receiver. Unlike a custom address request, reading it
 * does not generate and store a new diversified address on every check.
 */
internal suspend fun feeEstimateAddress(
    synchronizer: Synchronizer,
    account: Account
): String = synchronizer.getUnifiedAddress(account)

/**
 * The card's balance as [GiftCardRedeemer] reports it, from the card wallet's account balance.
 * Transparent funds are not counted: a card is funded at a shielded address, and a shielded
 * send-max cannot sweep transparent funds anyway.
 */
internal fun AccountBalance.toGiftCardBalance(): GiftCardRedeemer.Balance {
    val pools = listOf(sapling, orchard, ironwood)
    val spendable = pools.fold(Zatoshi(0)) { sum, pool -> sum + pool.available }
    val pending = pools.fold(Zatoshi(0)) { sum, pool -> sum + pool.pending }
    return GiftCardRedeemer.Balance(total = spendable + pending, spendable = spendable, pending = pending)
}

/**
 * What [GiftCardRedeemer.check] reports for a card with this balance, judged by the minimum fee
 * alone: a redemption pays at least [GiftCardRedeemer.MINIMUM_FEE], so only value above it counts
 * as redeemable. [GiftCardRedeemer.check] then confirms a [GiftCardRedeemer.Status.Ready] card
 * against the fee its notes actually require.
 */
internal fun GiftCardRedeemer.Balance.toStatus(): GiftCardRedeemer.Status =
    when {
        spendable.value > GiftCardRedeemer.MINIMUM_FEE.value -> {
            GiftCardRedeemer.Status.Ready(this, GiftCardRedeemer.MINIMUM_FEE)
        }

        total.value > GiftCardRedeemer.MINIMUM_FEE.value -> {
            GiftCardRedeemer.Status.Pending(this)
        }

        else -> {
            GiftCardRedeemer.Status.Empty
        }
    }

/**
 * The aliases of the card wallets that redeemers in this process are using. Two redeemers must
 * never share one: each erases the wallet before opening it and again when closed, and the
 * synchronizer allows one instance per alias.
 */
internal class GiftCardAliases {
    private val held = mutableSetOf<Pair<Int, String>>()

    /** Claims [alias] on [network]; `false` if another redeemer holds it. */
    @Synchronized
    fun acquire(
        network: ZcashNetwork,
        alias: String
    ): Boolean = held.add(network.id to alias)

    @Synchronized
    fun release(
        network: ZcashNetwork,
        alias: String
    ) {
        held.remove(network.id to alias)
    }

    companion object {
        /** The registry every redeemer created through the public API shares. */
        val Process = GiftCardAliases()
    }
}
