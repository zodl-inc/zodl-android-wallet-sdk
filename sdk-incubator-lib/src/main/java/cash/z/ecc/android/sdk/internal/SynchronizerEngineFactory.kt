@file:Suppress("LongParameterList")

package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.OpenedCardWallet
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/**
 * Creates and erases the sync engine that backs [cash.z.ecc.android.sdk.WalletCoordinator], and
 * the helper wallets (such as a gift card's temporary wallet) that run on the same engine beside it.
 *
 * Which engine that is gets decided at SDK build time by the `IS_SLIPSTREAM_ENABLED` Gradle
 * property: it selects one of this module's `engineSlipstream`/`engineDefault` source directories,
 * each of which supplies exactly one [engineSynchronizerFactory] implementation.
 */
internal interface SynchronizerEngineFactory {
    suspend fun new(
        context: Context,
        zcashNetwork: ZcashNetwork,
        lightWalletEndpoint: LightWalletEndpoint,
        birthday: BlockHeight?,
        setup: AccountCreateSetup?,
        walletInitMode: WalletInitMode,
        isTorEnabled: Boolean,
        isExchangeRateEnabled: Boolean,
    ): CloseableSynchronizer

    suspend fun erase(
        appContext: Context,
        network: ZcashNetwork
    ): Boolean

    /**
     * Creates and starts the helper wallet under [alias], restored from the seed in [setup] at
     * [birthday] (exactly at it when [isBirthdayExact] and the engine and server support that,
     * else at the bundled checkpoint below it), without exchange rates, beside the main wallet.
     * [onCriticalError] is installed as the wallet's critical error handler. The engine may plan
     * with only [engineMemoryFraction] of the device's memory, where it supports that.
     *
     * @param engineMemoryFraction in `(0, 1]`.
     */
    suspend fun openHelperWallet(
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
    ): OpenedCardWallet

    /**
     * Deletes the local data of the helper wallet under [alias] only, as this engine and any
     * engine used before it stored it, by deleting its files: no synchronizer is started, and the
     * main wallet is never touched.
     *
     * @return true when the engine reports the wallet's data gone.
     * @throws IllegalStateException if a synchronizer for [network] and [alias] is active.
     */
    suspend fun eraseHelperWallet(
        appContext: Context,
        network: ZcashNetwork,
        alias: String
    ): Boolean
}
