package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import cash.z.ecc.android.sdk.internal.transaction.MigrationTransactionMarks
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import com.zodl.slipstream.internal.SlipstreamEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A migration transaction created through the legacy `createProposedTransactions` is marked as a migration when it
 * is created, not when its submit result is emitted. The transaction is already stored then, so a resubmission
 * pass can answer for it while the submit is still running or after the submit threw, and an answer for an
 * unmarked transaction can reject it for good.
 */
class SlipstreamSpendServiceMigrationMarkTest {
    private val txIdBytes = byteArrayOf(1, 2, 3)
    private val txId = FirstClassByteArray(txIdBytes)
    private val endpoint = LightWalletEndpoint("a.z.cash", 443, true)
    private val refusedForGood =
        TransactionSubmitResult.Failure(txId, false, -25, "exceeding the configured mempool maximum")
    private val proposalUnsafe: ProposalUnsafe =
        mock(ProposalUnsafe::class.java).also {
            `when`(it.totalFeeRequired()).thenReturn(1_000L)
            `when`(it.toByteArray()).thenReturn(byteArrayOf(4, 2))
        }

    @Test
    fun aMigrationTransactionIsMarkedBeforeItsSubmitAndSoNeverRejected() =
        runBlocking<Unit> {
            val marks = MigrationTransactionMarks(null, "")
            val proposal = Proposal.fromUnsafe(proposalUnsafe)
            marks.markProposal(proposal)

            createAndFailToSubmit(proposal, marks)

            assertTrue(marks.isMigrationTransaction(txId))
            val store = SubmitPlanStore(SubmitPlanStoreRejectionTest.InMemoryPreferences(), marks)
            store.recordAnswer(txId, endpoint, refusedForGood)
            assertNull(store.rejectionFor(txId))
        }

    @Test
    fun anOrdinaryTransactionIsNotMarkedAndIsRejectedWhenItsEndpointRefusesItForGood() =
        runBlocking<Unit> {
            val marks = MigrationTransactionMarks(null, "")

            createAndFailToSubmit(Proposal.fromUnsafe(proposalUnsafe), marks)

            assertFalse(marks.isMigrationTransaction(txId))
            val store = SubmitPlanStore(SubmitPlanStoreRejectionTest.InMemoryPreferences(), marks)
            store.recordAnswer(txId, endpoint, refusedForGood)
            assertNotNull(store.rejectionFor(txId))
        }

    private suspend fun createAndFailToSubmit(
        proposal: Proposal,
        marks: MigrationTransactionMarks
    ) {
        val backend = mock(Backend::class.java)
        val walletClient = mock(CombinedWalletClient::class.java)
        val usk = mock(UnifiedSpendingKey::class.java)
        val uskBytes = byteArrayOf(5, 6, 7, 8)
        `when`(usk.copyBytes()).thenReturn(uskBytes)
        `when`(backend.createProposedTransactions(proposalUnsafe, uskBytes)).thenReturn(listOf(txIdBytes))
        `when`(backend.proposalRequiresSaplingProofs(proposalUnsafe)).thenReturn(false)
        val rawBytes = byteArrayOf(9, 9, 9)
        `when`(walletClient.getLatestBlockHeight(ServiceMode.Direct))
            .thenReturn(Response.Success(BlockHeightUnsafe(MAINNET_TIP)))
        `when`(walletClient.submitTransaction(rawBytes, ServiceMode.Direct)).thenThrow(IllegalStateException("offline"))

        val service =
            SlipstreamSpendService(
                backend = backend,
                walletClient = walletClient,
                engine = mock(SlipstreamEngine::class.java),
                sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
                network = ZcashNetwork.Mainnet,
                walletTip = { null },
                ensureSaplingParams = {},
                readRawTransaction = { FirstClassByteArray(rawBytes) },
                migrationMarks = marks
            )

        // The submit throws or fails: either way the transactions are stored and must already be marked.
        runCatching { service.createProposedTransactions(proposal, usk).toList() }
    }

    private companion object {
        const val MAINNET_TIP = 3_000_000L
    }
}
