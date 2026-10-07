package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** The alias of the card wallet in the incubator's gift card tests. */
internal const val ALIAS = "giftcard_test"

/** The card's birthday in the incubator's gift card tests. */
internal const val BIRTHDAY = 3_000_000L

/** The length of a card's seed. */
internal const val SEED_BYTES = 64

internal val ENDPOINT = LightWalletEndpoint("localhost", 9067, false)

/** Every spelling of an alias that addresses the main wallet's files. */
internal val MAIN_WALLET_ALIASES =
    listOf(ZcashSdk.DEFAULT_ALIAS, "${ZcashSdk.DEFAULT_ALIAS}_", "ZcashSdk", "ZCASHSDK_")

/** The card wallet's account setup, with a seed of its own. */
internal fun setup() =
    AccountCreateSetup(
        accountName = "Gift card",
        keySource = null,
        seed = FirstClassByteArray(ByteArray(SEED_BYTES))
    )

/** A mocked [Context] that is its own application context. */
internal fun context(): Context {
    val context = mock(Context::class.java)
    `when`(context.applicationContext).thenReturn(context)
    return context
}
