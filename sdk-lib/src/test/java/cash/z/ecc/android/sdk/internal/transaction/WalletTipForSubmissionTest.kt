package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.model.BlockHeight
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletTipForSubmissionTest {
    private fun backend(
        chainTip: Long?,
        scanned: Long?
    ): TypesafeBackend =
        mock(TypesafeBackend::class.java).also {
            runBlocking {
                `when`(it.getChainTipHeight()).thenReturn(chainTip?.let { h -> BlockHeight.new(h) })
                `when`(it.getMaxScannedHeight()).thenReturn(scanned?.let { h -> BlockHeight.new(h) })
            }
        }

    @Test
    fun the_chain_tip_counts_when_it_is_ahead_of_the_scan() =
        runBlocking {
            assertEquals(3_543_410L, walletTipForSubmission(backend(chainTip = 3_543_410L, scanned = 3_543_400L)))
        }

    @Test
    fun the_scanned_height_counts_when_the_chain_tip_is_unknown() =
        runBlocking {
            assertEquals(3_543_400L, walletTipForSubmission(backend(chainTip = null, scanned = 3_543_400L)))
        }

    @Test
    fun the_chain_tip_alone_is_enough() =
        runBlocking {
            assertEquals(3_543_410L, walletTipForSubmission(backend(chainTip = 3_543_410L, scanned = null)))
        }

    @Test
    fun a_failing_chain_tip_read_falls_back_to_the_scanned_height() =
        runBlocking {
            val backend = backend(chainTip = null, scanned = 3_543_400L)
            runBlocking { `when`(backend.getChainTipHeight()).thenThrow(IllegalStateException("db locked")) }
            assertEquals(3_543_400L, walletTipForSubmission(backend))
        }

    @Test
    fun an_unreadable_wallet_has_no_tip() =
        runBlocking {
            assertNull(walletTipForSubmission(backend(chainTip = null, scanned = null)))
        }
}
