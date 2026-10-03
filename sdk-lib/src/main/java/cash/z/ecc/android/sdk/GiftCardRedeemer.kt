package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.OvkPolicy
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.tool.DerivationTool
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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
 * the main wallet uses, and it runs without Tor and without exchange rates. Sending needs no
 * Sapling parameters unless the card holds Sapling funds, which cards do not.
 *
 * Typical use:
 *
 * ```
 * val card = GiftCard.parse(link)
 * val redeemer = GiftCardRedeemer.new(context, card, network, endpoint)
 * try {
 *     when (val status = redeemer.check()) {
 *         is GiftCardRedeemer.Status.Ready -> redeemer.redeem(RecipientAddress.new(myAddress, network))
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
class GiftCardRedeemer private constructor(
    private val context: Context,
    val card: GiftCard,
    private val lightWalletEndpoint: LightWalletEndpoint,
    /** The alias of the temporary wallet. Never [ZcashSdk.DEFAULT_ALIAS]. */
    val alias: String
) {
    /** The network the card, and the redemption, are on. */
    val network: ZcashNetwork = card.network

    private val mutex = Mutex()
    private var synchronizer: CloseableSynchronizer? = null
    private var isClosed = false

    @Volatile
    private var criticalError: Throwable? = null

    /** The card wallet's balance, as found on chain. */
    data class Balance(
        /** Everything the card holds, spendable or not. */
        val total: Zatoshi,
        /** What can be redeemed now. A redemption pays this minus the ZIP 317 fee. */
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
         * Funds can be redeemed now. [Balance.pending] may still be non-zero, in which case a
         * redemption sweeps only [Balance.spendable] and leaves the rest on the card.
         */
        data class Ready(
            val balance: Balance
        ) : Status

        /** The card holds funds, but none of them are spendable yet. Check again later. */
        data class Pending(
            val balance: Balance
        ) : Status

        /** The card holds nothing: it was never funded, or it has already been redeemed. */
        data object Empty : Status
    }

    /**
     * The outcome of [redeem].
     *
     * @property fee the fee paid, deducted from the card's balance.
     * @property results one result per created transaction (a redemption is normally a single
     * transaction), in order. Each carries the transaction id.
     */
    data class Redemption(
        val fee: Zatoshi,
        val results: List<TransactionSubmitResult>
    ) {
        /** `true` when every transaction was accepted by the server. */
        val isSubmitted: Boolean get() = results.all { it is TransactionSubmitResult.Success }
    }

    /**
     * Creates the temporary wallet if needed, waits until it has synced to the chain tip, and
     * reports the card's balance.
     *
     * The first call scans the chain from the card's birthday, which needs network access and
     * may take a while for an old card. Later calls reuse the synced wallet and return quickly,
     * so polling a [Status.Pending] card is cheap.
     *
     * @param timeout how long to wait for the sync to complete.
     *
     * @throws GiftCardException.SyncFailed if the wallet did not sync within [timeout], or
     * stopped on an unrecoverable error.
     * @throws GiftCardException.Closed if [close] was called.
     */
    suspend fun check(timeout: Duration = DEFAULT_SYNC_TIMEOUT): Status =
        mutex.withLock {
            val synchronizer = openSynchronizer()
            val account = synchronizer.getAccounts().first()
            awaitSyncedBalance(synchronizer, account, timeout).toGiftCardBalance().toStatus()
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
     * @param toAddress the user's own address, on [network].
     * @param memo an optional memo for the recipient; must be `null` for a transparent address.
     *
     * @throws GiftCardException.NothingToRedeem if nothing is spendable, or the spendable
     * balance does not cover the fee.
     * @throws GiftCardException.NetworkMismatch if [toAddress] is for another network.
     * @throws GiftCardException.Closed if [close] was called.
     * @throws TransactionEncoderException if the transaction could not be created.
     */
    suspend fun redeem(
        toAddress: RecipientAddress,
        memo: MemoContent? = null
    ): Redemption =
        mutex.withLock {
            if (toAddress.network != network) throw GiftCardException.NetworkMismatch()
            val synchronizer = openSynchronizer()
            val account = synchronizer.getAccounts().first()

            val proposal =
                try {
                    synchronizer.proposeSendMax(account, toAddress, memo)
                } catch (e: TransactionEncoderException.InsufficientFundsException) {
                    throw GiftCardException.NothingToRedeem(e)
                }

            val usk =
                DerivationTool.getInstance().deriveUnifiedSpendingKey(
                    seed = card.seed.copyBytes(),
                    network = network,
                    accountIndex = Zip32AccountIndex.new(0)
                )
            val results =
                synchronizer
                    .createProposedTransactions(proposal, usk, OvkPolicy.Discard)
                    .toList()
            Redemption(fee = proposal.totalFeeRequired(), results = results)
        }

    /**
     * Closes the temporary wallet and deletes all of its local data. Nothing that belongs to
     * any other wallet is touched. Idempotent; the redeemer cannot be used afterwards.
     *
     * Always call this when done, including after a failure. If the app is killed before it
     * runs, the data is removed the next time a redeemer for the same card is used, or
     * explicitly with [Synchronizer.eraseAlias] and [alias].
     */
    suspend fun close() {
        withContext(NonCancellable) {
            mutex.withLock {
                isClosed = true
                synchronizer?.close()
                synchronizer = null
                Synchronizer.eraseAlias(context, network, alias)
            }
        }
    }

    private suspend fun openSynchronizer(): CloseableSynchronizer {
        if (isClosed) throw GiftCardException.Closed()
        synchronizer?.let { return it }

        // Start from scratch: the temporary wallet is disposable, and a fresh scan is what makes
        // an already-redeemed card show up as empty. This also clears leftovers of an earlier
        // redemption that was interrupted before `close()`.
        Synchronizer.eraseAlias(context, network, alias)

        val seed = card.seed.copyBytes()
        val created =
            try {
                Synchronizer.new(
                    alias = alias,
                    birthday = card.birthdayHeight,
                    context = context,
                    lightWalletEndpoint = lightWalletEndpoint,
                    setup =
                        AccountCreateSetup(
                            accountName = ACCOUNT_NAME,
                            keySource = null,
                            seed = FirstClassByteArray(seed)
                        ),
                    walletInitMode = WalletInitMode.RestoreWallet,
                    zcashNetwork = network,
                    isTorEnabled = false,
                    isExchangeRateEnabled = false
                )
            } finally {
                seed.fill(0)
            }
        created.onCriticalErrorHandler = { error ->
            criticalError = error
            false
        }
        synchronizer = created
        return created
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
         * @param alias the temporary wallet's alias. Must be unique to this card and must not be
         * [ZcashSdk.DEFAULT_ALIAS] or any other alias the app uses; 1 to 99 letters, digits,
         * `_` or `-`.
         *
         * @throws GiftCardException.NetworkMismatch if [card] is not for [network].
         * @throws IllegalArgumentException if [alias] is not a valid, non-default alias.
         */
        fun new(
            context: Context,
            card: GiftCard,
            network: ZcashNetwork,
            lightWalletEndpoint: LightWalletEndpoint,
            alias: String = defaultAlias(card)
        ): GiftCardRedeemer {
            if (card.network != network) throw GiftCardException.NetworkMismatch()
            require(alias != ZcashSdk.DEFAULT_ALIAS) { "A gift card must not use the default wallet alias" }
            require(
                alias.length in ZcashSdk.ALIAS_MIN_LENGTH..ZcashSdk.ALIAS_MAX_LENGTH &&
                    alias.all { it.isLetterOrDigit() || it == '_' || it == '-' }
            ) { "Invalid alias" }
            return GiftCardRedeemer(context.applicationContext, card, lightWalletEndpoint, alias)
        }
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

/** What [GiftCardRedeemer.check] reports for a card with this balance. */
internal fun GiftCardRedeemer.Balance.toStatus(): GiftCardRedeemer.Status =
    when {
        spendable.value > 0 -> GiftCardRedeemer.Status.Ready(this)
        total.value > 0 -> GiftCardRedeemer.Status.Pending(this)
        else -> GiftCardRedeemer.Status.Empty
    }
