package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.util.WalletClientFactory
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.internal.db.SlipstreamTransactionReader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Regression coverage for the [SlipstreamBroadcaster.createProposedTransactions] USK wipe (MOB-1689) -
 * mirrors [SlipstreamSpendServiceOrderingTest]'s wipe assertion, which is the identical fix in the
 * sibling send path. Conventions follow that test: plain Mockito with exact-argument stubs (no
 * matcher library is a project dependency), JUnit4 and `runBlocking`.
 *
 * [SlipstreamBroadcaster] does not take the same injected `readRawTransaction` seam
 * [SlipstreamSpendService] does for its whole flow, so [SubmitPlanStore] and [SlipstreamTransactionReader]
 * are mocked directly instead. `ensureSaplingParams` is injectable here too (added alongside this
 * test, same reasoning as the sibling class: tests must never trigger a real sapling-params download).
 */
class SlipstreamBroadcasterTest {
    @Test
    fun createProposedTransactionsWipesTheTransientUskCopy() =
        runBlocking {
            val fixture = Fixture()
            val created =
                CreatedTransaction(
                    txId = FirstClassByteArray(TX_ID),
                    raw = FirstClassByteArray(RAW),
                    expiryHeight = null
                )
            `when`(fixture.backend.createProposedTransactions(fixture.proposalUnsafe, fixture.uskBytes))
                .thenReturn(listOf(TX_ID))
            `when`(fixture.transactionReader.readCreatedTransaction(FirstClassByteArray(TX_ID))).thenReturn(created)

            val results = fixture.broadcaster.createProposedTransactions(fixture.proposal, fixture.usk)

            assertEquals(listOf(created), results)
            assertContentEquals(
                ByteArray(fixture.uskBytes.size),
                fixture.uskBytes,
                "the transient usk copy must be wiped after use"
            )
        }

    @Test
    fun createProposedTransactionsWipesTheTransientUskCopyEvenWhenTheBackendThrows() =
        runBlocking {
            val fixture = Fixture()
            val failure = RuntimeException("the backend blew up")
            `when`(fixture.backend.createProposedTransactions(fixture.proposalUnsafe, fixture.uskBytes))
                .thenThrow(failure)

            val thrown =
                assertFailsWith<RuntimeException> {
                    fixture.broadcaster.createProposedTransactions(fixture.proposal, fixture.usk)
                }

            assertSame(failure, thrown)
            assertContentEquals(
                ByteArray(fixture.uskBytes.size),
                fixture.uskBytes,
                "the transient usk copy must be wiped even when the backend throws"
            )
        }

    /**
     * Shared mocks/fixtures for both tests above, wired the same way
     * [SlipstreamSpendServiceOrderingTest] wires its own.
     */
    private class Fixture {
        val backend: Backend = mock(Backend::class.java)
        val engine: SlipstreamEngine = mock(SlipstreamEngine::class.java)
        val planStore: SubmitPlanStore = mock(SubmitPlanStore::class.java)
        val transactionReader: SlipstreamTransactionReader = mock(SlipstreamTransactionReader::class.java)

        val proposalUnsafe: ProposalUnsafe =
            mock(ProposalUnsafe::class.java).also {
                `when`(it.totalFeeRequired()).thenReturn(FEE)
            }
        val proposal: Proposal = Proposal.fromUnsafe(proposalUnsafe)

        val uskBytes: ByteArray = byteArrayOf(5, 6, 7, 8)
        val usk: UnifiedSpendingKey =
            mock(UnifiedSpendingKey::class.java).also {
                `when`(it.copyBytes()).thenReturn(uskBytes)
            }

        val broadcaster: SlipstreamBroadcaster =
            SlipstreamBroadcaster(
                backend = backend,
                walletClientFactory = mock(WalletClientFactory::class.java),
                sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
                engine = engine,
                planStore = planStore,
                saplingParamsDir = File("unused"),
                transactionReader = transactionReader,
                ensureSaplingParams = { }
            )
    }

    private companion object {
        const val FEE = 1_000L
        val TX_ID = byteArrayOf(1, 2, 3)
        val RAW = byteArrayOf(9, 9, 9)
    }
}
