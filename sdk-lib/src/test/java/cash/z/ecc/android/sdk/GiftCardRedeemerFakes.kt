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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertIs

/**
 * The card wallet: [status], one account holding [spendable] and [pending], [history]
 * transactions, one proposal paying [fee] and sending [sent] (by default [spendable] minus
 * [fee]), and whatever [submitResult] says about each transaction.
 */
@Suppress("LongParameterList")
internal class FakeCardWallet(
    private val created: List<CreatedTransaction>,
    private val spendable: Long = 1_000_000,
    pending: Long = 0,
    private val proposalFailure: Exception? = null,
    var fee: Zatoshi = Zatoshi(10_000),
    private val history: Int = 1,
    private val sent: Long? = null,
    private val closeFailure: Exception? = null,
    override val status: Flow<Synchronizer.Status> = MutableStateFlow(Synchronizer.Status.SYNCED),
    private val submitResult: (CreatedTransaction) -> TransactionSubmitResult = {
        TransactionSubmitResult.Success(it.txId)
    }
) : CloseableSynchronizer by mock(CloseableSynchronizer::class.java) {
    var closed = false
    var proposed = false
    val submitted = mutableListOf<CreatedTransaction>()
    var ovkPolicy: OvkPolicy? = null

    override val network: ZcashNetwork = ZcashNetwork.Mainnet
    override var onCriticalErrorHandler: ((Throwable?) -> Boolean)? = null
    override val walletBalances: MutableStateFlow<Map<AccountUuid, AccountBalance>?> =
        MutableStateFlow(
            mapOf(AccountFixture.new().accountUuid to cardAccountBalance(available = spendable, pending = pending))
        )

    override suspend fun getAccounts(): List<Account> = listOf(AccountFixture.new())

    override suspend fun getTransactions(accountUuid: AccountUuid): Flow<List<TransactionOverview>> =
        flowOf(List(history) { mock(TransactionOverview::class.java) })

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
 * to start exactly at the birthday does so only when [isExactBirthdayAvailable].
 */
internal class FakeWallets(
    private val cardWallets: List<FakeCardWallet>,
    private val openFailure: Exception? = null,
    private val isExactBirthdayAvailable: Boolean = true,
    private val onOpen: suspend ((Throwable?) -> Boolean) -> Unit = {}
) : GiftCardWallets {
    constructor(cardWallet: FakeCardWallet, openFailure: Exception? = null) :
        this(listOf(cardWallet), openFailure)

    val erased = mutableListOf<String>()
    val torSettings = mutableListOf<Boolean>()
    val exactBirthdays = mutableListOf<Boolean>()
    val opened = CompletableDeferred<Unit>()

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
        isBirthdayExact: Boolean,
        lightWalletEndpoint: LightWalletEndpoint,
        isTorEnabled: Boolean,
        setup: AccountCreateSetup,
        onCriticalError: (Throwable?) -> Boolean
    ): OpenedCardWallet {
        torSettings += isTorEnabled
        exactBirthdays += isBirthdayExact
        openFailure?.let { throw it }
        onOpen(onCriticalError)
        opened.complete(Unit)
        return OpenedCardWallet(
            synchronizer = cardWallets.getOrElse(exactBirthdays.size - 1) { cardWallets.last() },
            startsAtBirthday = isBirthdayExact && isExactBirthdayAvailable
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
    ): UnifiedSpendingKey = mock(UnifiedSpendingKey::class.java)
}

/** The user's wallet, remembering what it was asked to record, or refusing to. */
internal class FakeDestination(
    private val failure: Exception? = null
) : Synchronizer by mock(Synchronizer::class.java) {
    val recorded = mutableListOf<Pair<ByteArray, ByteArray>>()

    override val network: ZcashNetwork = ZcashNetwork.Mainnet

    override suspend fun recordTrustedTransaction(
        rawTransaction: RawTransaction,
        txId: TransactionId
    ) {
        failure?.let { throw it }
        recorded += rawTransaction.data to txId.value.byteArray
    }
}

/**
 * A wallet that the claim does not involve, recording through the SDK's own
 * `recordTrustedTransaction` step. As in the native backend, storing such a transaction stores
 * nothing yet still returns its id ([claimTxId]), and trusting it then throws a
 * [RuntimeException] because no stored transaction has that id.
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
                throw RuntimeException("Transaction is not stored in this wallet; its trust status was not set")
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
    isTorEnabled: Boolean = false
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
            aliases = aliases
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
