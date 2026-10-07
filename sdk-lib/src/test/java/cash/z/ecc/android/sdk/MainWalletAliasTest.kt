package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.isMainWalletAlias
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isMainWalletAlias], the one guard every by-alias erase or open of a helper wallet applies: the default alias, its
 * spellings with trailing underscores (which name the same database file) and the legacy alias in any letter case
 * address the main wallet; card aliases do not. [Synchronizer.eraseAlias] refuses them before touching anything.
 */
class MainWalletAliasTest {
    @Test
    fun theMainWalletsAliasesAreRecognized() {
        MAIN_WALLET_ALIASES.forEach { assertTrue(isMainWalletAlias(it), it) }
    }

    @Test
    fun otherAliasesAreNotTheMainWallets() {
        listOf("giftcard_abc", "zcash_sdk_card", "zcash_sdkx", "ZcashSdkCard", "_zcash_sdk").forEach {
            assertFalse(isMainWalletAlias(it), it)
        }
    }

    @Test
    fun eraseAliasRefusesTheMainWalletBeforeTouchingAnything() =
        runBlocking<Unit> {
            MAIN_WALLET_ALIASES.forEach { alias ->
                val context = mock(Context::class.java)
                assertFailsWith<IllegalArgumentException>(alias) {
                    Synchronizer.eraseAlias(context, ZcashNetwork.Mainnet, alias)
                }
                verifyNoInteractions(context)
            }
        }

    private companion object {
        val MAIN_WALLET_ALIASES =
            listOf(
                ZcashSdk.DEFAULT_ALIAS,
                "${ZcashSdk.DEFAULT_ALIAS}_",
                "${ZcashSdk.DEFAULT_ALIAS}__",
                "ZcashSdk",
                "ZcashSdk_",
                "zcashsdk",
                "ZCASHSDK"
            )
    }
}
