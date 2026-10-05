package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.Twig
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
 * 10 confirmations rather than 3.
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
@Suppress("LongParameterList")
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
    private var holdsAlias = false

    /** The card wallet's account, set once [check] has seen the wallet synced. */
    private var checkedAccount: Account? = null

    @Volatile
    private var criticalError: Throwable? = null

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
         * Funds can be redeemed now: [Balance.spendable] exceeds the ZIP 317 fee.
         * [Balance.pending] may still be non-zero, in which case a redemption sweeps only
         * [Balance.spendable] and leaves the rest on the card.
         */
        data class Ready(
            val balance: Balance
        ) : Status

        /**
         * The card holds more than the fee, but not enough of it is spendable yet to pay for a
         * redemption. Check again later.
         */
        data class Pending(
            val balance: Balance
        ) : Status

        /**
         * The card holds nothing that can be redeemed: it was never funded, it has already been
         * redeemed, or what it holds does not exceed the ZIP 317 fee a redemption would pay.
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
     * claim; in the last case the redemption itself still stands, and the destination wallet
     * finds the funds on its own when it next syncs, as an untrusted receive.
     */
    data class Redemption(
        val fee: Zatoshi,
        val results: List<TransactionSubmitResult>,
        val recordedInDestination: Boolean = false
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
     * @param timeout how long to wait for the sync to complete.
     *
     * @throws GiftCardException.SyncFailed if the temporary wallet could not be created, did
     * not sync within [timeout], or stopped on an unrecoverable error. The cause, if any, is
     * attached.
     * @throws GiftCardException.InUse if another redeemer in this process is using the same
     * temporary wallet.
     * @throws GiftCardException.Closed if [close] was called.
     */
    suspend fun check(timeout: Duration = DEFAULT_SYNC_TIMEOUT): Status =
        mutex.withLock {
            val synchronizer = openSynchronizer()
            val account = walletCreationStep { synchronizer.getAccounts().first() }
            val balance = awaitSyncedBalance(synchronizer, account, timeout).toGiftCardBalance()
            checkedAccount = account
            balance.toStatus()
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
     * a new one to retry.
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
     * fee, including when nothing is spendable.
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

            // Decided from the balance, not from a failed proposal: a card holding no more than
            // the fee can never be redeemed, and saying so needs no error text from the backend.
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
                    // Typed by the backend: the notes' total fee exceeds what they hold.
                    throw GiftCardException.NothingToRedeem(e)
                }

            val seed = card.seed.copyBytes()
            val usk =
                try {
                    wallets.deriveSpendingKey(seed, network)
                } finally {
                    seed.fill(0)
                }
            // Created and submitted in two steps, rather than with `createProposedTransactions`, so
            // that the raw transaction is at hand for the destination.
            val broadcaster = cardWallet.broadcaster
            val created = broadcaster.createProposedTransactions(proposal, usk, OvkPolicy.Discard)
            val results = submitInOrder(broadcaster, created)
            val recorded = destination != null && recordInDestination(destination, created, results)
            Redemption(fee = proposal.totalFeeRequired(), results = results, recordedInDestination = recorded)
        }

    /**
     * Submits [created] to the redeemer's endpoint in order, stopping at the first failure: a
     * later transaction of a multi-step proposal depends on the earlier ones. This mirrors what
     * [Synchronizer.createProposedTransactions] does after creating the transactions.
     */
    private suspend fun submitInOrder(
        broadcaster: Broadcaster,
        created: List<CreatedTransaction>
    ): List<TransactionSubmitResult> {
        var failed = false
        return created.map { transaction ->
            if (failed) {
                TransactionSubmitResult.NotAttempted(transaction.txId)
            } else {
                broadcaster.submit(transaction, lightWalletEndpoint).also {
                    failed = it !is TransactionSubmitResult.Success
                }
            }
        }
    }

    /**
     * Records every submitted transaction in [destination] as trusted. Returns `true` only when
     * there was something to record and all of it was recorded; a failure is logged, not thrown,
     * as the funds have moved regardless and the destination will find them when it next syncs.
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
            Twig.warn(e) {
                "The gift card claim could not be recorded in the destination wallet; " +
                    "it will be found by that wallet's next sync"
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
     * [Synchronizer.eraseAlias] and [alias].
     */
    suspend fun close() {
        withContext(NonCancellable) {
            mutex.withLock {
                isClosed = true
                checkedAccount = null
                synchronizer?.close()
                synchronizer = null
                // Erased only by the redeemer that holds the alias, so that closing a redeemer
                // that was refused as `InUse` cannot delete the wallet another one is using.
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

    private suspend fun openSynchronizer(): CloseableSynchronizer {
        if (isClosed) throw GiftCardException.Closed()
        synchronizer?.let { return it }

        if (!holdsAlias) {
            if (!aliases.acquire(network, alias)) throw GiftCardException.InUse()
            holdsAlias = true
        }

        val created =
            walletCreationStep {
                // Start from scratch: the temporary wallet is disposable, and a fresh scan is what
                // makes an already-redeemed card show up as empty. This also clears leftovers of
                // an earlier redemption that was interrupted before `close()`.
                wallets.erase(context, network, alias)

                val seed = card.seed.copyBytes()
                try {
                    wallets.open(
                        context = context,
                        network = network,
                        alias = alias,
                        birthday = card.birthdayHeight,
                        lightWalletEndpoint = lightWalletEndpoint,
                        isTorEnabled = isTorEnabled,
                        setup =
                            AccountCreateSetup(
                                accountName = ACCOUNT_NAME,
                                keySource = null,
                                seed = FirstClassByteArray(seed)
                            )
                    )
                } finally {
                    seed.fill(0)
                }
            }
        created.onCriticalErrorHandler = { error ->
            criticalError = error
            false
        }
        synchronizer = created
        return created
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

    private suspend fun awaitSyncedBalance(
        synchronizer: Synchronizer,
        account: Account,
        timeout: Duration
    ): AccountBalance =
        withTimeoutOrNull(timeout) {
            combine(synchronizer.status, synchronizer.walletBalances) { status, balances ->
                criticalError?.let { throw GiftCardException.SyncFailed(it) }
                if (status == Synchronizer.Status.STOPPED) throw GiftCardException.SyncFailed(null)
                balances?.get(account.accountUuid).takeIf { status == Synchronizer.Status.SYNCED }
            }.first { it != null }
        } ?: throw GiftCardException.SyncFailed(criticalError)

    companion object {
        /** How long [check] waits for the temporary wallet to sync, by default. */
        val DEFAULT_SYNC_TIMEOUT: Duration = 10.minutes

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

    /** Creates and starts the card wallet under [alias] from the card's seed in [setup]. */
    @Suppress("LongParameterList")
    suspend fun open(
        context: Context,
        network: ZcashNetwork,
        alias: String,
        birthday: BlockHeight,
        lightWalletEndpoint: LightWalletEndpoint,
        isTorEnabled: Boolean,
        setup: AccountCreateSetup
    ): CloseableSynchronizer

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

        override suspend fun open(
            context: Context,
            network: ZcashNetwork,
            alias: String,
            birthday: BlockHeight,
            lightWalletEndpoint: LightWalletEndpoint,
            isTorEnabled: Boolean,
            setup: AccountCreateSetup
        ): CloseableSynchronizer =
            Synchronizer.new(
                alias = alias,
                birthday = birthday,
                context = context,
                lightWalletEndpoint = lightWalletEndpoint,
                setup = setup,
                walletInitMode = WalletInitMode.RestoreWallet,
                zcashNetwork = network,
                // The card wallet shares the Tor data directory with the main wallet; Arti lets
                // a second client use it read-only, so no fresh bootstrap is needed.
                isTorEnabled = isTorEnabled,
                isExchangeRateEnabled = false,
                // The link's height is at or just below the funding height, so the card wallet
                // can start exactly there instead of at the nearest bundled checkpoint, which may
                // be thousands of blocks earlier. The card is not the user's wallet, so revealing
                // its exact height to the server costs it nothing; with `isTorEnabled`, that fetch
                // goes over Tor like the rest of the card wallet's traffic.
                isBirthdayExact = true
            )

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

/** The card's balance as [GiftCardRedeemer] reports it, from the card wallet's account balance. */
internal fun AccountBalance.toGiftCardBalance(): GiftCardRedeemer.Balance {
    val pools = listOf(sapling, orchard, ironwood)
    val spendable = pools.fold(Zatoshi(0)) { sum, pool -> sum + pool.available }
    val pending = pools.fold(Zatoshi(0)) { sum, pool -> sum + pool.pending }
    // Transparent funds are not counted: a card is funded at a shielded address, and a
    // shielded send-max cannot sweep transparent funds anyway.
    return GiftCardRedeemer.Balance(total = spendable + pending, spendable = spendable, pending = pending)
}

/**
 * What [GiftCardRedeemer.check] reports for a card with this balance. A redemption pays at least
 * [GiftCardRedeemer.MINIMUM_FEE], so only value above it counts as redeemable.
 */
internal fun GiftCardRedeemer.Balance.toStatus(): GiftCardRedeemer.Status =
    when {
        spendable.value > GiftCardRedeemer.MINIMUM_FEE.value -> GiftCardRedeemer.Status.Ready(this)
        total.value > GiftCardRedeemer.MINIMUM_FEE.value -> GiftCardRedeemer.Status.Pending(this)
        else -> GiftCardRedeemer.Status.Empty
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
