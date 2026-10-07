package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.internal.GiftCardLinks
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.model.JniGiftCard
import cash.z.ecc.android.sdk.internal.recordTrustedTransaction
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
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.RawTransaction
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertIs

/**
 * The card wallet: [status], one account holding [spendable] and [pending], [history]
 * transactions, of which the last [sentHistory] were sent by the card wallet itself (the rest received), counting
 * its [transactionReads], one proposal paying [fee] and sending [sent] (by default [spendable] minus
 * [fee]), and whatever [submitResult] says about each transaction. [setupError] is the setup
 * failure it has latched, if any, [accounts] its account list (`null` while not loaded yet, as
 * [Synchronizer.accountsFlow] reports it), [progress] its sync progress, and [networkHeight] and
 * [fullyScannedHeight] its heights, both [CARD_WALLET_TIP] unless a test moves them. [onCreate] runs while its
 * transactions are being created, after [creations] has counted the creation.
 */
@Suppress("LongParameterList")
internal class FakeCardWallet(
    private val created: List<CreatedTransaction>,
    private val spendable: Long = 1_000_000,
    pending: Long = 0,
    private val proposalFailure: Exception? = null,
    var fee: Zatoshi = Zatoshi(10_000),
    private val history: Int = 1,
    private val sentHistory: Int = 0,
    private val sent: Long? = null,
    private val closeFailure: Exception? = null,
    override val status: Flow<Synchronizer.Status> = MutableStateFlow(Synchronizer.Status.SYNCED),
    private val onCreate: suspend () -> Unit = {},
    private val submitResult: (CreatedTransaction) -> TransactionSubmitResult = {
        TransactionSubmitResult.Success(it.txId)
    }
) : CloseableSynchronizer by mock(CloseableSynchronizer::class.java) {
    var closed = false
    var proposed = false
    val submitted = mutableListOf<CreatedTransaction>()
    var ovkPolicy: OvkPolicy? = null
    var transactionReads = 0
    var creations = 0

    override val network: ZcashNetwork = ZcashNetwork.Mainnet
    override var onCriticalErrorHandler: ((Throwable?) -> Boolean)? = null
    override var onProcessorErrorHandler: ((Throwable?) -> Boolean)? = null
    override val setupError: MutableStateFlow<Throwable?> = MutableStateFlow(null)
    val accounts: MutableStateFlow<List<Account>?> = MutableStateFlow(listOf(AccountFixture.new()))
    override val accountsFlow: Flow<List<Account>?> get() = accounts
    override val progress: MutableStateFlow<PercentDecimal> = MutableStateFlow(PercentDecimal.ZERO_PERCENT)
    override val walletBalances: MutableStateFlow<Map<AccountUuid, AccountBalance>?> =
        MutableStateFlow(
            mapOf(AccountFixture.new().accountUuid to cardAccountBalance(available = spendable, pending = pending))
        )
    override val networkHeight: MutableStateFlow<BlockHeight?> = MutableStateFlow(CARD_WALLET_TIP)
    override val fullyScannedHeight: MutableStateFlow<BlockHeight?> = MutableStateFlow(CARD_WALLET_TIP)

    override suspend fun getAccounts(): List<Account> = accounts.value.orEmpty()

    override suspend fun getTransactions(accountUuid: AccountUuid): Flow<List<TransactionOverview>> {
        transactionReads++
        return flowOf(
            List(history) { index ->
                mock(TransactionOverview::class.java).also {
                    `when`(it.isSentTransaction).thenReturn(index >= history - sentHistory)
                }
            }
        )
    }

    override suspend fun proposeSendMax(
        account: Account,
        recipient: RecipientAddress,
        memo: MemoContent?
    ): Proposal {
        proposed = true
        proposalFailure?.let { throw it }
        val proposal = mock(Proposal::class.java)
        `when`(proposal.totalFeeRequired()).thenReturn(fee)
        `when`(proposal.totalSent()).thenReturn(Zatoshi(sent ?: (spendable - fee.value).coerceAtLeast(0)))
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
                creations++
                onCreate()
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
        closeFailure?.let { throw it }
    }
}

/**
 * Opens [cardWallets] in turn (the last one again once they run out). [onOpen] runs while a
 * wallet is being opened, with the critical error handler the redeemer passed. A wallet asked
 * to start exactly at the birthday does so only when [isExactBirthdayAvailable]. Every wallet
 * reports [isDisconnectedUntilFirstPass], as the Slipstream engine's do. [seeds] holds the seed
 * array each open was handed, and [seedsAtOpen] a copy of its contents taken during that open. [onErase] runs at
 * every erase with its number, from 1, before [erased] records it: throwing fails that erase.
 * [onDeriveSpendingKey] runs while a redemption derives the card's spending key.
 */
@Suppress("LongParameterList")
internal class FakeWallets(
    private val cardWallets: List<FakeCardWallet>,
    private val openFailure: Exception? = null,
    private val isExactBirthdayAvailable: Boolean = true,
    private val isDisconnectedUntilFirstPass: Boolean = false,
    private val onOpen: suspend ((Throwable?) -> Boolean) -> Unit = {},
    private val onErase: suspend (Int) -> Unit = {},
    private val onDeriveSpendingKey: suspend () -> Unit = {}
) : GiftCardWallets {
    constructor(cardWallet: FakeCardWallet, openFailure: Exception? = null) :
        this(listOf(cardWallet), openFailure)

    val erased = mutableListOf<String>()
    var eraseAttempts = 0
    val torSettings = mutableListOf<Boolean>()
    val exactBirthdays = mutableListOf<Boolean>()
    val seeds = mutableListOf<ByteArray>()
    val seedsAtOpen = mutableListOf<ByteArray>()
    val opened = CompletableDeferred<Unit>()

    override suspend fun erase(
        context: Context,
        network: ZcashNetwork,
        alias: String
    ) {
        eraseAttempts++
        onErase(eraseAttempts)
        erased += alias
    }

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
        torSettings += isTorEnabled
        exactBirthdays += isBirthdayExact
        seeds += setup.seed.byteArray
        seedsAtOpen += setup.seed.byteArray.copyOf()
        openFailure?.let { throw it }
        onOpen(onCriticalError)
        opened.complete(Unit)
        return OpenedCardWallet(
            synchronizer = cardWallets.getOrElse(exactBirthdays.size - 1) { cardWallets.last() },
            startsAtBirthday = isBirthdayExact && isExactBirthdayAvailable,
            isDisconnectedUntilFirstPass = isDisconnectedUntilFirstPass
        )
    }

    override suspend fun feeEstimateRecipient(
        synchronizer: Synchronizer,
        account: Account,
        network: ZcashNetwork
    ): RecipientAddress {
        val address = mock(RecipientAddress::class.java)
        `when`(address.network).thenReturn(network)
        return address
    }

    override suspend fun deriveSpendingKey(
        seed: ByteArray,
        network: ZcashNetwork
    ): UnifiedSpendingKey {
        onDeriveSpendingKey()
        return mock(UnifiedSpendingKey::class.java)
    }
}

/**
 * Card wallets as the Slipstream engine opens them: [cardWallets] in turn, idle before the first pass, and at the
 * birthday when asked to only when [exact], as when the exact birthday's tree state is available, else at the
 * checkpoint.
 */
internal fun engineWallets(
    vararg cardWallets: FakeCardWallet,
    exact: Boolean = false
) = FakeWallets(
    cardWallets.toList(),
    isExactBirthdayAvailable = exact,
    isDisconnectedUntilFirstPass = true
)

/**
 * The user's wallet, remembering what it was asked to record, or refusing to with [failure] the transactions
 * [refuses] picks (all of them by default).
 */
internal class FakeDestination(
    private val failure: Exception? = null,
    private val refuses: (TransactionId) -> Boolean = { true }
) : Synchronizer by mock(Synchronizer::class.java) {
    val recorded = mutableListOf<Pair<ByteArray, ByteArray>>()

    override val network: ZcashNetwork = ZcashNetwork.Mainnet

    override suspend fun recordTrustedTransaction(
        rawTransaction: RawTransaction,
        txId: TransactionId
    ) {
        failure?.takeIf { refuses(txId) }?.let { throw it }
        recorded += rawTransaction.data to txId.value.byteArray
    }
}

/**
 * A destination whose backend behaves as the native one does for a claim that does not involve its
 * wallet: storing returns the claim's id ([claimTxId]) and trusting it throws a
 * [RuntimeException]. Recording goes through the SDK's own `recordTrustedTransaction` step.
 *
 * The throw is unconditional and scripted here, so tests using this fake cover only the contract
 * from that throw onwards: how the SDK and the redeemer react to it. Whether the native backend
 * really throws for a transaction it did not store is covered by the Rust tests of
 * `set_trust_of_stored_transaction` in backend-lib.
 */
internal class UninvolvedDestination(
    claimTxId: FirstClassByteArray
) : Synchronizer by mock(Synchronizer::class.java) {
    val trustAttempts = mutableListOf<ByteArray>()

    private val backend =
        object : TypesafeBackend by mock(TypesafeBackend::class.java) {
            override suspend fun decryptAndStoreTransaction(
                tx: ByteArray,
                minedHeight: BlockHeight?
            ): FirstClassByteArray = claimTxId

            @Suppress("TooGenericExceptionThrown")
            override suspend fun setTransactionTrust(
                txId: ByteArray,
                trusted: Boolean
            ) {
                trustAttempts += txId
                throw RuntimeException("Transaction was not found in this wallet's stored transactions")
            }
        }

    override val network: ZcashNetwork = ZcashNetwork.Mainnet

    override suspend fun recordTrustedTransaction(
        rawTransaction: RawTransaction,
        txId: TransactionId
    ) = backend.recordTrustedTransaction(rawTransaction, txId)
}

/** One account's balance with [available] and [pending] in Orchard. */
internal fun cardAccountBalance(
    available: Long = 0,
    pending: Long = 0
) = AccountBalance(
    sapling = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0)),
    orchard = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending)),
    ironwood = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0)),
    unshielded = Zatoshi(0)
)

/** A card wallet's balances, with [pending] in Orchard and nothing spendable. */
internal fun cardBalances(pending: Long) =
    mapOf(AccountFixture.new().accountUuid to cardAccountBalance(pending = pending))

/** The chain tip a [FakeCardWallet] has seen and scanned up to, unless a test moves it. */
internal val CARD_WALLET_TIP: BlockHeight = BlockHeight.new(3_000_100)

internal val GIFT_CARD_TEST_ENDPOINT = LightWalletEndpoint("localhost", 9067, false)

internal fun card(
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

internal fun context(): Context {
    val context = mock(Context::class.java)
    `when`(context.applicationContext).thenReturn(context)
    return context
}

internal fun recipient(network: ZcashNetwork = ZcashNetwork.Mainnet): RecipientAddress {
    val address = mock(RecipientAddress::class.java)
    `when`(address.network).thenReturn(network)
    return address
}

internal fun rejected(transaction: CreatedTransaction) =
    TransactionSubmitResult.Failure(transaction.txId, grpcError = false, code = -1, description = "rejected")

internal fun createdTransaction(tag: String) =
    CreatedTransaction(
        txId = FirstClassByteArray("txid-$tag".toByteArray()),
        raw = FirstClassByteArray("raw-$tag".toByteArray()),
        expiryHeight = null
    )

internal fun redeemer(
    cardWallet: FakeCardWallet,
    wallets: FakeWallets = FakeWallets(cardWallet),
    aliases: GiftCardAliases = GiftCardAliases(),
    isTorEnabled: Boolean = false,
    teardownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
): Pair<GiftCardRedeemer, FakeWallets> {
    val redeemer =
        GiftCardRedeemer.new(
            context = context(),
            card = card(),
            network = ZcashNetwork.Mainnet,
            lightWalletEndpoint = GIFT_CARD_TEST_ENDPOINT,
            isTorEnabled = isTorEnabled,
            alias = GiftCardRedeemer.defaultAlias(card()),
            wallets = wallets,
            aliases = aliases,
            teardownScope = teardownScope
        )
    return redeemer to wallets
}

/** [check]s first, as callers must, then redeems. */
internal suspend fun GiftCardRedeemer.checkAndRedeem(
    destination: Synchronizer? = null
): GiftCardRedeemer.Redemption {
    assertIs<GiftCardRedeemer.Status.Ready>(check())
    return redeem(recipient(), destination = destination)
}
