@file:Suppress("LongParameterList")

package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.OpenedCardWallet
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.zodl.slipstream.SlipstreamSynchronizer

/**
 * The `IS_SLIPSTREAM_ENABLED=true` variant of the engine seam, compiled only when this SDK build
 * includes `:slipstream-lib`.
 */
internal val engineSynchronizerFactory: SynchronizerEngineFactory = SlipstreamEngineFactory()

/**
 * What [SlipstreamEngineFactory] asks [SlipstreamSynchronizer.Companion.new] for, one property per
 * parameter. Deliberately not a data class: [setup] carries a seed, which must never end up in a
 * generated `toString`.
 */
internal class SlipstreamWalletRequest(
    val context: Context,
    val alias: String,
    val birthday: BlockHeight?,
    val lightWalletEndpoint: LightWalletEndpoint,
    val setup: AccountCreateSetup?,
    val walletInitMode: WalletInitMode,
    val zcashNetwork: ZcashNetwork,
    val isTorEnabled: Boolean,
    val isExchangeRateEnabled: Boolean,
    val engineMemoryFraction: Float
)

/**
 * Every wallet of this SDK build runs on [SlipstreamSynchronizer]: the main wallet under the
 * default alias, and each helper wallet as a second instance under its own alias, with its own
 * database and engine handle, beside it. [newSynchronizer] and [eraseHelperAlias] default to
 * [SlipstreamSynchronizer]'s companion and are replaced only by unit tests.
 */
internal class SlipstreamEngineFactory(
    private val newSynchronizer: suspend (SlipstreamWalletRequest) -> CloseableSynchronizer =
        ::newSlipstreamSynchronizer,
    private val eraseHelperAlias: suspend (Context, ZcashNetwork, String) -> Boolean =
        { context, network, alias ->
            SlipstreamSynchronizer.eraseAlias(appContext = context, network = network, alias = alias)
        }
) : SynchronizerEngineFactory {
    override suspend fun new(
        context: Context,
        zcashNetwork: ZcashNetwork,
        lightWalletEndpoint: LightWalletEndpoint,
        birthday: BlockHeight?,
        setup: AccountCreateSetup?,
        walletInitMode: WalletInitMode,
        isTorEnabled: Boolean,
        isExchangeRateEnabled: Boolean,
    ): CloseableSynchronizer =
        newSynchronizer(
            SlipstreamWalletRequest(
                context = context,
                alias = ZcashSdk.DEFAULT_ALIAS,
                birthday = birthday,
                lightWalletEndpoint = lightWalletEndpoint,
                setup = setup,
                walletInitMode = walletInitMode,
                zcashNetwork = zcashNetwork,
                isTorEnabled = isTorEnabled,
                isExchangeRateEnabled = isExchangeRateEnabled,
                engineMemoryFraction = SlipstreamSynchronizer.FULL_ENGINE_MEMORY
            )
        )

    override suspend fun erase(
        appContext: Context,
        network: ZcashNetwork
    ): Boolean =
        SlipstreamSynchronizer.erase(
            appContext = appContext,
            network = network
        )

    /**
     * A second [SlipstreamSynchronizer] under [alias], restored with [WalletInitMode.RestoreWallet]
     * and without exchange rates. The Slipstream engine always starts a restored wallet at the
     * bundled checkpoint at or below [birthday], so [isBirthdayExact] cannot be honoured and the
     * wallet reports [OpenedCardWallet.startsAtBirthday] `false`. Its engine reports
     * `DISCONNECTED` while idle before the first sync pass, hence
     * [OpenedCardWallet.isDisconnectedUntilFirstPass].
     *
     * The engine's Tor state directory is fixed per app, so the helper wallet's engine uses the
     * main wallet's rather than bootstrapping a directory of its own.
     *
     * [onCriticalError] is installed as soon as [SlipstreamSynchronizer.Companion.new] returns: its
     * preparation (anchor, database, engine open) is still running then, and critical errors only
     * come from the poll loop, which starts at the end of that preparation.
     *
     * @throws IllegalArgumentException if [alias] is the main wallet's alias.
     */
    override suspend fun openHelperWallet(
        context: Context,
        zcashNetwork: ZcashNetwork,
        alias: String,
        birthday: BlockHeight,
        isBirthdayExact: Boolean,
        lightWalletEndpoint: LightWalletEndpoint,
        setup: AccountCreateSetup,
        isTorEnabled: Boolean,
        onCriticalError: (Throwable?) -> Boolean,
        engineMemoryFraction: Float,
    ): OpenedCardWallet {
        require(alias != ZcashSdk.DEFAULT_ALIAS) { "A helper wallet must not use the default wallet alias" }
        val synchronizer =
            newSynchronizer(
                SlipstreamWalletRequest(
                    context = context,
                    alias = alias,
                    birthday = birthday,
                    lightWalletEndpoint = lightWalletEndpoint,
                    setup = setup,
                    walletInitMode = WalletInitMode.RestoreWallet,
                    zcashNetwork = zcashNetwork,
                    isTorEnabled = isTorEnabled,
                    isExchangeRateEnabled = false,
                    engineMemoryFraction = engineMemoryFraction
                )
            )
        synchronizer.onCriticalErrorHandler = onCriticalError
        return OpenedCardWallet(
            synchronizer = synchronizer,
            startsAtBirthday = false,
            isDisconnectedUntilFirstPass = true
        )
    }

    /**
     * [SlipstreamSynchronizer.Companion.eraseAlias]: this engine's files and preferences for
     * [alias], and whatever an `SdkSynchronizer` left under it, by file-level deletion.
     */
    override suspend fun eraseHelperWallet(
        appContext: Context,
        network: ZcashNetwork,
        alias: String
    ): Boolean = eraseHelperAlias(appContext, network, alias)
}

private suspend fun newSlipstreamSynchronizer(request: SlipstreamWalletRequest): CloseableSynchronizer =
    SlipstreamSynchronizer.new(
        alias = request.alias,
        birthday = request.birthday,
        context = request.context,
        lightWalletEndpoint = request.lightWalletEndpoint,
        setup = request.setup,
        walletInitMode = request.walletInitMode,
        zcashNetwork = request.zcashNetwork,
        isTorEnabled = request.isTorEnabled,
        isExchangeRateEnabled = request.isExchangeRateEnabled,
        engineMemoryFraction = request.engineMemoryFraction
    )
