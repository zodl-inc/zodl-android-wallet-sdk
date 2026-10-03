package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.exception.GiftCardException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.GiftCardLinks
import cash.z.ecc.android.sdk.internal.model.JniGiftCard
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The parts of [GiftCardRedeemer] that do not need a running wallet: alias rules and how a
 * balance maps onto the status the UI shows. The redemption itself needs a live synchronizer.
 */
class GiftCardRedeemerTest {
    private val endpoint = LightWalletEndpoint("localhost", 9067, false)

    private fun card(
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

    private fun context(): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        return context
    }

    @Test
    fun defaultAliasIsValidUniquePerCardAndNeverTheDefault() {
        val alias = GiftCardRedeemer.defaultAlias(card())
        assertNotEquals(ZcashSdk.DEFAULT_ALIAS, alias)
        assertTrue(alias.length in ZcashSdk.ALIAS_MIN_LENGTH..ZcashSdk.ALIAS_MAX_LENGTH)
        assertTrue(alias.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        assertEquals(alias, GiftCardRedeemer.defaultAlias(card()))
        assertNotEquals(alias, GiftCardRedeemer.defaultAlias(card(fundingAddress = "u1other")))

        val redeemer = GiftCardRedeemer.new(context(), card(), ZcashNetwork.Mainnet, endpoint)
        assertEquals(alias, redeemer.alias)
        assertEquals(ZcashNetwork.Mainnet, redeemer.network)
    }

    @Test
    fun rejectsTheDefaultAliasAndInvalidAliases() {
        listOf(ZcashSdk.DEFAULT_ALIAS, "", "a/b", "x".repeat(ZcashSdk.ALIAS_MAX_LENGTH + 1)).forEach { alias ->
            assertFailsWith<IllegalArgumentException> {
                GiftCardRedeemer.new(context(), card(), ZcashNetwork.Mainnet, endpoint, alias)
            }
        }
    }

    @Test
    fun rejectsACardForAnotherNetwork() {
        assertFailsWith<GiftCardException.NetworkMismatch> {
            GiftCardRedeemer.new(context(), card(networkId = ZcashNetwork.ID_TESTNET), ZcashNetwork.Mainnet, endpoint)
        }
    }

    @Test
    fun mapsBalancesToStatus() {
        fun pool(
            available: Long = 0,
            pending: Long = 0
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

        val empty = AccountBalance(pool(), pool(), pool(), Zatoshi(0))
        assertEquals(GiftCardRedeemer.Status.Empty, empty.toGiftCardBalance().toStatus())

        // Transparent funds cannot be swept by a shielded send-max and are not reported.
        val transparentOnly = AccountBalance(pool(), pool(), pool(), Zatoshi(5_000))
        assertEquals(GiftCardRedeemer.Status.Empty, transparentOnly.toGiftCardBalance().toStatus())

        val pending = AccountBalance(pool(), pool(), pool(pending = 1_010_000), Zatoshi(0))
        assertEquals(
            GiftCardRedeemer.Status.Pending(
                GiftCardRedeemer.Balance(Zatoshi(1_010_000), Zatoshi(0), Zatoshi(1_010_000))
            ),
            pending.toGiftCardBalance().toStatus()
        )

        val ready =
            AccountBalance(pool(), pool(available = 10_000), pool(available = 1_000_000, pending = 5), Zatoshi(0))
        assertEquals(
            GiftCardRedeemer.Status.Ready(
                GiftCardRedeemer.Balance(Zatoshi(1_010_005), Zatoshi(1_010_000), Zatoshi(5))
            ),
            ready.toGiftCardBalance().toStatus()
        )
    }
}
