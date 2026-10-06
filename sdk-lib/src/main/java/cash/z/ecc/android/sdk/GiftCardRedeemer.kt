package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.OvkPolicy
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
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
 * the main wallet uses, and it runs without exchange rates. It connects to the server the way
 * the user chose for the main wallet: pass the main wallet's Tor setting as `isTorEnabled` to
 * [new], so that syncing the card, fetching its birthday tree state and submitting the claim
 * do not reveal the user's IP address when the main wallet hides it. Sending needs no Sapling
 * parameters unless the card holds Sapling funds, which cards do not.
 *
 * Only one redeemer at a time may use a card's temporary wallet: a second redeemer for the same
 * card (or the same [alias]) in the same process fails with [GiftCardException.InUse] until the
 * first one is [close]d.
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
 * val redeemer = GiftCardRedeemer.new(context, card, network, endpoint, isTorEnabled)
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
    private val aliases: GiftCardAliases
) {
    /** The network the card, and the redemption, are on. */
    val network: ZcashNetwork = card.network

    private val mutex = Mutex()
    private var synchronizer: CloseableSynchronizer? = null
    private var isClosed = false

    /**
     * Whether this redeemer holds [alias] in [aliases]. Only the redeemer that holds it erases the card wallet, so
     * that closing a redeemer refused as [GiftCardException.InUse] cannot delete the wallet another one is using.
     */
    private var holdsAlias = false

    /**
     * How long [check] lets the card wallet stay disconnected by default: longer over Tor, which is slower to
     * connect.
     */
    private val defaultDisconnectedTimeout: Duration =
        if (isTorEnabled) DEFAULT_TOR_DISCONNECTED_TIMEOUT else DEFAULT_DISCONNECTED_TIMEOUT

    /** Set as soon as [close] is called, before it waits for [mutex]. */
    @Volatile
    private var isClosing = false

    /**
     * The checks in progress, which [close] cancels. Guarded by itself. Nothing else cancels them:
     * a cancellation from inside a check fails it, and a cancelled caller cancels it with itself.
     */
    private val inFlightChecks = mutableSetOf<Job>()

    /**
     * Whether the card wallet starts exactly at the card's birthday: asked for until a wallet has been opened, then
     * whether the open wallet really does, as the exact tree state may not have been available.
     */
    private var isBirthdayExact = true

    /** The card wallet's account, set once a [check] has completed, including its fee probe. */
    private var checkedAccount: Account? = null

    /** A critical error reported by the open card wallet's synchronizer. */
    private val criticalError = MutableStateFlow<CriticalError?>(null)

    private class CriticalError(
        val cause: Throwable?
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
     * transaction), in order. Each carries the transaction id.
     * @property recordedInDestination `true` when a `destination` was passed to [redeem] and
     * every submitted transaction was recorded in it as trusted (ZIP 315). `false` when no
     * destination was passed, nothing was submitted, or the destination failed to record the
     * claim, including when the claim does not involve the destination's wallet and so was not
     * stored there; in the last case the redemption itself still stands, and the destination wallet
     * finds the funds on its own when it next syncs, as an untrusted receive.
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
        /** `true` when every transaction was accepted by the server. */
        val isSubmitted: Boolean get() = results.all { it is TransactionSubmitResult.Success }
    }

    /**
     * Creates the temporary wallet if needed, waits until it has synced to the chain tip, and
     * reports the card's balance.
     *
     * The first call scans the chain from the card's birthday, which needs network access and
     * may take a while for an old card. The wallet starts exactly at [GiftCard.birthdayHeight],
     * using the tree state fetched from [lightWalletEndpoint]; if the server cannot provide it,
     * the scan starts at the nearest bundled checkpoint below it instead, which takes longer.
     * Later calls reuse the synced wallet and return quickly, so polling a [Status.Pending] card
     * is cheap.
     *
     * A card wallet that started exactly at the birthday and found no transaction at all (as
     * opposed to funds received and then spent) is scanned once more from the bundled checkpoint
     * below the birthday before the card is reported [Status.Empty], in case the link's height
     * was above the card's funding.
     *
     * A card that looks redeemable is checked against the fee its actual notes require (see
     * [Status.Ready.fee]), so [Status.Ready] means that [redeem] can send something.
     *
     * [close] cancels a check in progress, which then fails with [GiftCardException.Closed]. A check whose caller is
     * cancelled rethrows the cancellation; any other cancellation from inside the check is a failure of the check.
     *
     * @param timeout how long to wait for the sync to complete.
     * @param disconnectedTimeout how long the card wallet may stay unable to reach the server
     * before the check gives up, rather than waiting for the whole [timeout]: by default
     * [DEFAULT_DISCONNECTED_TIMEOUT], or [DEFAULT_TOR_DISCONNECTED_TIMEOUT] over Tor. A failure to reach the server
     * while the card wallet starts counts as being disconnected, not as an unrecoverable error.
     *
     * @throws GiftCardException.SyncFailed if the temporary wallet could not be created, did
     * not sync within [timeout], stayed disconnected for [disconnectedTimeout], or stopped on an
     * unrecoverable error. The cause, if any, is attached.
     * @throws GiftCardException.InUse if another redeemer in this process is using the same
     * temporary wallet.
     * @throws GiftCardException.Closed if [close] was called.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun check(
        timeout: Duration = DEFAULT_SYNC_TIMEOUT,
        disconnectedTimeout: Duration = defaultDisconnectedTimeout
    ): Status {
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

    private suspend fun checkLocked(
        timeout: Duration,
        disconnectedTimeout: Duration
    ): Status {
        checkedAccount = null
        var synchronizer = openSynchronizer(isBirthdayExact = isBirthdayExact)
        var account = walletCreationStep { synchronizer.getAccounts().first() }
        var balance = awaitSyncedBalance(synchronizer, account, timeout, disconnectedTimeout).toGiftCardBalance()
        if (isBirthdayExact && balance.total.value == 0L && !hasHistory(synchronizer, account)) {
            Twig.info { "Gift card wallet found nothing from the exact birthday; rescanning from the checkpoint" }
            synchronizer.close()
            this.synchronizer = null
            synchronizer = openSynchronizer(isBirthdayExact = false)
            account = walletCreationStep { synchronizer.getAccounts().first() }
            balance = awaitSyncedBalance(synchronizer, account, timeout, disconnectedTimeout).toGiftCardBalance()
        }
        return statusOf(synchronizer, account, balance).also { checkedAccount = account }
    }

    /** Whether the card wallet has seen any transaction at all. */
    private suspend fun hasHistory(
        synchronizer: Synchronizer,
        account: Account
    ): Boolean = walletCreationStep { synchronizer.getTransactions(account.accountUuid).first().isNotEmpty() }

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
     * chain: it is logged and reported as [Redemption.recordedInDestination] being `false`, and
     * the destination finds the funds by itself when it next syncs.
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
     * @throws GiftCardException.Closed if [close] was called.
     * @throws TransactionEncoderException if the transaction could not be created.
     */
    suspend fun redeem(
        toAddress: RecipientAddress,
        memo: MemoContent? = null,
        destination: Synchronizer? = null
    ): Redemption =
        mutex.withLock {
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

            val seed = card.seed.copyBytes()
            val usk =
                try {
                    wallets.deriveSpendingKey(seed, network)
                } finally {
                    seed.fill(0)
                }
            val broadcaster = cardWallet.broadcaster
            val created = broadcaster.createProposedTransactions(proposal, usk, OvkPolicy.Discard)
            val results = submitInOrder(broadcaster, created)
            val recorded = destination != null && recordInDestination(destination, created, results)
            Redemption(
                fee = proposal.totalFeeRequired(),
                results = results,
                recordedInDestination = recorded,
                amount = proposal.totalSent().takeIf { it.value > 0 }
            )
        }

    /**
     * Submits [created] to the redeemer's endpoint in order, stopping at the first failure: a
     * later transaction of a multi-step proposal depends on the earlier ones. This mirrors what
     * [Synchronizer.createProposedTransactions] does after creating the transactions; [redeem]
     * creates and submits in two steps so that the raw transactions are at hand for its
     * destination. A submission that throws is a [TransactionSubmitResult.Failure] with
     * [SUBMIT_THREW_CODE].
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitInOrder(
        broadcaster: Broadcaster,
        created: List<CreatedTransaction>
    ): List<TransactionSubmitResult> {
        var failed = false
        return created.map { transaction ->
            if (failed) {
                TransactionSubmitResult.NotAttempted(transaction.txId)
            } else {
                val result =
                    try {
                        broadcaster.submit(transaction, lightWalletEndpoint)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Twig.warn(e) { "Submitting the gift card claim failed" }
                        TransactionSubmitResult.Failure(
                            txId = transaction.txId,
                            grpcError = true,
                            code = SUBMIT_THREW_CODE,
                            description = e::class.simpleName
                        )
                    }
                result.also { failed = it !is TransactionSubmitResult.Success }
            }
        }
    }

    /**
     * Records every submitted transaction in [destination] as trusted. Returns `true` only when
     * there was something to record and all of it was recorded; a failure is logged, not thrown,
     * as the funds have moved regardless and the destination will find them when it next syncs.
     * Recording fails when a claim does not involve the destination's wallet, which then stores
     * nothing. Only the failure's type is logged: its message can carry the transaction id.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun recordInDestination(
        destination: Synchronizer,
        created: List<CreatedTransaction>,
        results: List<TransactionSubmitResult>
    ): Boolean {
        val submitted = created.filterIndexed { index, _ -> results[index] is TransactionSubmitResult.Success }
        if (submitted.isEmpty()) return false
        return try {
            submitted.forEach {
                destination.recordTrustedTransaction(
                    rawTransaction = RawTransaction(data = it.raw.byteArray, height = null),
                    txId = TransactionId.new(it.txId)
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.warn {
                "The gift card claim could not be recorded in the destination wallet " +
                    "(${e::class.simpleName}); it will be found by that wallet's next sync"
            }
            false
        }
    }

    /**
     * Closes the temporary wallet and deletes all of its local data. Nothing that belongs to
     * any other wallet is touched. Idempotent; the redeemer cannot be used afterwards.
     *
     * Always call this when done, including after a failure: it also lets another redeemer
     * for the same card be used. If the app is killed before it runs, the data is removed the
     * next time a redeemer for the same card is used, or explicitly with
     * [Synchronizer.eraseAlias] and [alias]; [storedAliases] lists the card wallets left on the
     * device.
     *
     * A [check] in progress is cancelled, so this returns promptly; a [redeem] in progress is
     * waited for. The rest runs even if the caller is cancelled.
     */
    suspend fun close() {
        isClosing = true
        synchronized(inFlightChecks) { inFlightChecks.toList() }.forEach { it.cancel() }
        withContext(NonCancellable) {
            mutex.withLock {
                isClosed = true
                checkedAccount = null
                try {
                    synchronizer?.close()
                } finally {
                    synchronizer = null
                    if (holdsAlias) {
                        try {
                            wallets.erase(context, network, alias)
                        } finally {
                            aliases.release(network, alias)
                            holdsAlias = false
                        }
                    }
                }
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
                            criticalError.update { it ?: CriticalError(error) }
                            false
                        }
                    )
                } finally {
                    seed.fill(0)
                }
            }
        this.isBirthdayExact = opened.startsAtBirthday
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
     * Waits until the card wallet is synced and returns its balance. Fails fast on a critical
     * error, when the wallet stops, and when it stays [Synchronizer.Status.DISCONNECTED] for
     * [disconnectedTimeout].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("ThrowsCount")
    private suspend fun awaitSyncedBalance(
        synchronizer: Synchronizer,
        account: Account,
        timeout: Duration,
        disconnectedTimeout: Duration
    ): AccountBalance {
        val statusUntilDisconnectedTooLong =
            synchronizer.status.transformLatest { current ->
                emit(current)
                if (current == Synchronizer.Status.DISCONNECTED) {
                    delay(disconnectedTimeout)
                    throw GiftCardException.SyncFailed(null)
                }
            }
        return withTimeoutOrNull(timeout) {
            combine(
                statusUntilDisconnectedTooLong,
                synchronizer.walletBalances,
                criticalError
            ) { status, balances, error ->
                error?.let { throw GiftCardException.SyncFailed(it.cause) }
                if (status == Synchronizer.Status.STOPPED) throw GiftCardException.SyncFailed(null)
                balances?.get(account.accountUuid).takeIf { status == Synchronizer.Status.SYNCED }
            }.first { it != null }
        } ?: throw GiftCardException.SyncFailed(criticalError.value?.cause)
    }

    companion object {
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
         * device until it is erased with [Synchronizer.eraseAlias]; this finds it. Do not erase
         * the wallet of a redeemer that is still in use.
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
         * Creates a redeemer for [card]. Nothing is created on disk or the network until
         * [check] or [redeem] is called.
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
         * [ZcashSdk.DEFAULT_ALIAS] or any other alias the app uses; 1 to 99 letters, digits,
         * `_` or `-`.
         *
         * @throws GiftCardException.NetworkMismatch if [card] is not for [network].
         * @throws IllegalArgumentException if [alias] is not a valid, non-default alias.
         */
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
                aliases = GiftCardAliases.Process
            )

        /** [new] with the device-facing and process-wide parts replaced, for unit tests. */
        @Suppress("LongParameterList")
        internal fun new(
            context: Context,
            card: GiftCard,
            network: ZcashNetwork,
            lightWalletEndpoint: LightWalletEndpoint,
            isTorEnabled: Boolean,
            alias: String,
            wallets: GiftCardWallets,
            aliases: GiftCardAliases
        ): GiftCardRedeemer {
            if (card.network != network) throw GiftCardException.NetworkMismatch()
            require(alias != ZcashSdk.DEFAULT_ALIAS) { "A gift card must not use the default wallet alias" }
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
                aliases = aliases
            )
        }
    }
}

/**
 * What [GiftCardRedeemer] needs from the device: the temporary card wallet and the derivation of
 * its spending key. [Default] uses [Synchronizer] and [DerivationTool]; unit tests replace it.
 */
internal interface GiftCardWallets {
    /** Deletes the local data of the card wallet under [alias]. */
    suspend fun erase(
        context: Context,
        network: ZcashNetwork,
        alias: String
    )

    /**
     * Creates and starts the card wallet under [alias] from the card's seed in [setup], starting
     * exactly at [birthday] when [isBirthdayExact] and the server provides the tree state for it,
     * else at the bundled checkpoint below it. [onCriticalError] is installed before the wallet
     * starts syncing, and failing to reach the server while it starts is not a critical error.
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
            Synchronizer.eraseAlias(context, network, alias)
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
 * A card wallet [GiftCardWallets.open] created.
 *
 * @property startsAtBirthday whether it starts exactly at the card's birthday; `false` when it
 * starts at the bundled checkpoint below it, whether asked to or because the exact tree state
 * was not available.
 */
internal class OpenedCardWallet(
    val synchronizer: CloseableSynchronizer,
    val startsAtBirthday: Boolean
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
