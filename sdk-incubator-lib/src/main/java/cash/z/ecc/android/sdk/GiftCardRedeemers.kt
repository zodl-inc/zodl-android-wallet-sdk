package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.SynchronizerEngineFactory
import cash.z.ecc.android.sdk.internal.engineSynchronizerFactory
import cash.z.ecc.android.sdk.internal.requireNotMainWalletAlias
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/**
 * Creates [GiftCardRedeemer]s whose temporary card wallet runs on the sync engine this SDK build
 * uses for the main wallet ([WalletCoordinator]'s), and cleans up the card wallets they leave
 * behind. With the Slipstream engine, each card wallet is a second Slipstream synchronizer under
 * the card's own alias, beside the main one; without it, an [SdkSynchronizer], as
 * [GiftCardRedeemer.new] creates.
 */
object GiftCardRedeemers {
    /**
     * Creates a redeemer for [card]. Nothing is created on disk or the network until
     * [GiftCardRedeemer.check] or [GiftCardRedeemer.redeem] is called. The arguments are those of
     * [GiftCardRedeemer.new], which documents them.
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
        alias: String = GiftCardRedeemer.defaultAlias(card)
    ): GiftCardRedeemer =
        new(
            context = context,
            card = card,
            network = network,
            lightWalletEndpoint = lightWalletEndpoint,
            isTorEnabled = isTorEnabled,
            alias = alias,
            factory = engineSynchronizerFactory
        )

    /** [new] on [factory] instead of this build's engine, for unit tests. */
    @Suppress("LongParameterList")
    internal fun new(
        context: Context,
        card: GiftCard,
        network: ZcashNetwork,
        lightWalletEndpoint: LightWalletEndpoint,
        isTorEnabled: Boolean,
        alias: String,
        factory: SynchronizerEngineFactory
    ): GiftCardRedeemer =
        GiftCardRedeemer.new(
            context = context,
            card = card,
            network = network,
            lightWalletEndpoint = lightWalletEndpoint,
            isTorEnabled = isTorEnabled,
            alias = alias,
            wallets = EngineGiftCardWallets(factory),
            aliases = GiftCardAliases.Process
        )

    /**
     * The aliases of the card wallets stored on the device for [network], whichever engine stored
     * them; see [GiftCardRedeemer.storedAliases].
     *
     * @param context any context; its application context is used.
     */
    suspend fun storedAliases(
        context: Context,
        network: ZcashNetwork
    ): Set<String> = GiftCardRedeemer.storedAliases(context, network)

    /**
     * Deletes the local data of the card wallet under [alias] on [network], as found by
     * [storedAliases]: what this build's engine stored, and what an earlier engine left under the
     * same alias. Only files are deleted; no synchronizer is started, and nothing of the main
     * wallet or of any other card wallet is touched. Do not erase the wallet of a redeemer that is
     * still in use.
     *
     * @param context any context; its application context is used.
     * @return true when the engine reports the card wallet's data gone.
     * @throws IllegalArgumentException if [alias] is not a valid alias, or addresses the main
     * wallet's files: [ZcashSdk.DEFAULT_ALIAS], also with trailing underscores, or the legacy
     * `ZcashSdk`. Nothing is touched then.
     * @throws IllegalStateException if the card wallet is open.
     */
    suspend fun erase(
        context: Context,
        network: ZcashNetwork,
        alias: String
    ): Boolean = erase(context, network, alias, engineSynchronizerFactory)

    /** [erase] on [factory] instead of this build's engine, for unit tests. */
    internal suspend fun erase(
        context: Context,
        network: ZcashNetwork,
        alias: String,
        factory: SynchronizerEngineFactory
    ): Boolean {
        requireNotMainWalletAlias(alias, "A gift card wallet never uses the default wallet alias")
        return factory.eraseHelperWallet(context.applicationContext, network, alias)
    }
}

/**
 * The [GiftCardWallets] of [GiftCardRedeemers]: the card wallet is opened and erased by [factory]'s
 * engine, telling it the device has [engineMemoryFraction] of its memory; the fee probe's address and the
 * spending key are engine-independent and come from [GiftCardWallets.Default].
 */
internal class EngineGiftCardWallets(
    private val factory: SynchronizerEngineFactory,
    private val engineMemoryFraction: Float = CARD_ENGINE_MEMORY_FRACTION
) : GiftCardWallets {
    override suspend fun erase(
        context: Context,
        network: ZcashNetwork,
        alias: String
    ) {
        factory.eraseHelperWallet(context, network, alias)
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
    ): OpenedCardWallet =
        factory.openHelperWallet(
            context = context,
            zcashNetwork = network,
            alias = alias,
            birthday = birthday,
            isBirthdayExact = isBirthdayExact,
            lightWalletEndpoint = lightWalletEndpoint,
            setup = setup,
            isTorEnabled = isTorEnabled,
            onCriticalError = onCriticalError,
            engineMemoryFraction = engineMemoryFraction
        )

    override suspend fun feeEstimateRecipient(
        synchronizer: Synchronizer,
        account: Account,
        network: ZcashNetwork
    ): RecipientAddress = GiftCardWallets.Default.feeEstimateRecipient(synchronizer, account, network)

    override suspend fun deriveSpendingKey(
        seed: ByteArray,
        network: ZcashNetwork
    ): UnifiedSpendingKey = GiftCardWallets.Default.deriveSpendingKey(seed, network)

    companion object {
        /**
         * The share of the device's memory a card wallet's engine is told the device has: half. The
         * Slipstream engine uses that figure only to switch to its smaller, fixed budgets below its
         * 3 GiB small-device threshold, so the card wallet's engine takes those smaller budgets on
         * devices below 6 GiB, where the main wallet's engine (told the whole device) may still take
         * its defaults, and the same default budgets as the main wallet's on larger devices; never
         * larger ones.
         */
        const val CARD_ENGINE_MEMORY_FRACTION = 0.5f
    }
}
