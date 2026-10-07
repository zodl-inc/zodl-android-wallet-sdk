package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.model.ConfirmationsPolicy
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.model.SlipstreamSnapshot
import com.zodl.slipstream.model.SlipstreamTransactionRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TransactionsController]'s flow wiring, against a fake row source and a mocked engine whose
 * flows the test drives directly. The production bug this pins: rows were re-read (and their
 * [TransactionState] recomputed) only on [SlipstreamEngine.requeryTicks], which keys on the
 * engine's `txSetVersion` - so a received transaction stayed Pending forever once the chain tip
 * moved past its 10th confirmation without the transaction set changing.
 */
class TransactionsControllerFlowTest {
    private val requeryTicks = MutableSharedFlow<Unit>(replay = 1)
    private val networkHeight = MutableStateFlow<BlockHeight?>(null)
    private val lastSnapshot = MutableStateFlow<SlipstreamSnapshot?>(null)
    private val queries = AtomicInteger(0)

    private val engine =
        mock(SlipstreamEngine::class.java).also {
            `when`(it.requeryTicks).thenReturn(requeryTicks)
            `when`(it.networkHeight).thenReturn(networkHeight)
            `when`(it.lastSnapshot).thenReturn(lastSnapshot)
        }

    private fun rows(trustStatus: Long?) =
        VisibleTransactionRows { _, _ ->
            queries.incrementAndGet()
            listOf(receivedRow(MINED, trustStatus))
        }

    private fun controller(
        maxScannedHeight: BlockHeight?,
        trustStatus: Long? = null
    ): TransactionsController {
        val backend = mock(TypesafeBackend::class.java)
        runBlocking { `when`(backend.getMaxScannedHeight()).thenReturn(maxScannedHeight) }
        return TransactionsController(rows(trustStatus), engine, backend)
    }

    /** Only the tip moves: no requery tick, so no new row read. */
    @Test
    fun tip_advance_without_a_requery_tick_confirms_a_received_transaction() =
        withEmissions(controller(maxScannedHeight = null).allTransactions, tip = MINED + 3) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            networkHeight.value = BlockHeight.new(MINED + 9)
            assertEquals(TransactionState.Confirmed, emissions.nextState())
            assertEquals(1, queries.get(), "a tip change must re-map the rows, not re-read them")
        }

    /** Starts at `TRUSTED - 1` confirmations. */
    @Test
    fun tip_advance_confirms_a_trusted_receive_at_the_trusted_count() =
        withEmissions(
            controller(maxScannedHeight = null, trustStatus = 1L).allTransactions,
            tip = MINED + TRUSTED - 2
        ) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            networkHeight.value = BlockHeight.new(MINED + TRUSTED - 1)
            assertEquals(TransactionState.Confirmed, emissions.nextState())
            assertEquals(1, queries.get(), "a tip change must re-map the rows, not re-read them")
        }

    /** Starts at `TRUSTED` confirmations: not enough for an untrusted receive. */
    @Test
    fun tip_advance_keeps_an_untrusted_receive_pending_until_the_untrusted_count() =
        withEmissions(controller(maxScannedHeight = null).allTransactions, tip = MINED + TRUSTED - 1) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            networkHeight.value = BlockHeight.new(MINED + UNTRUSTED - 2)
            assertEquals(TransactionState.Pending, emissions.nextState())

            networkHeight.value = BlockHeight.new(MINED + UNTRUSTED - 1)
            assertEquals(TransactionState.Confirmed, emissions.nextState())
        }

    @Test
    fun requery_tick_still_re_reads_rows() =
        withEmissions(controller(maxScannedHeight = null).allTransactions, tip = MINED + 3) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            requeryTicks.emit(Unit)
            assertEquals(TransactionState.Pending, emissions.nextState())
            assertEquals(2, queries.get())
        }

    @Test
    fun account_scoped_flow_also_follows_the_tip() =
        withEmissions(
            controller(maxScannedHeight = null).forAccount(AccountUuid.new(ByteArray(16))),
            tip = MINED + 3
        ) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            networkHeight.value = BlockHeight.new(MINED + 9)
            assertEquals(TransactionState.Confirmed, emissions.nextState())
        }

    /** A tip that lags what the wallet has already scanned must not under-count. */
    @Test
    fun stale_seeded_tip_is_lifted_to_the_scanned_height() =
        withEmissions(
            controller(maxScannedHeight = BlockHeight.new(MINED + 9)).allTransactions,
            tip = MINED + 3
        ) { emissions ->
            assertEquals(TransactionState.Confirmed, emissions.nextState())
        }

    @Test
    fun unknown_tip_falls_back_to_the_scanned_height() =
        withEmissions(
            controller(maxScannedHeight = BlockHeight.new(MINED + 9)).allTransactions,
            tip = null
        ) { emissions ->
            assertEquals(TransactionState.Confirmed, emissions.nextState())
        }

    @Test
    fun a_live_tip_spares_the_scanned_height_read_on_tip_changes() {
        val reads = AtomicInteger(0)
        lastSnapshot.value = snapshot(chainTip = MINED + 3)
        withEmissions(countingController(BlockHeight.new(MINED + 3), reads).allTransactions, tip = MINED + 3) {
            assertEquals(TransactionState.Pending, it.nextState())

            lastSnapshot.value = snapshot(chainTip = MINED + 9)
            networkHeight.value = BlockHeight.new(MINED + 9)
            assertEquals(TransactionState.Confirmed, it.nextState())
            assertEquals(1, reads.get(), "only the first computation reads the scanned height under a known live tip")
        }
    }

    @Test
    fun a_stale_low_live_tip_on_the_first_computation_is_lifted_to_the_scanned_height() {
        val reads = AtomicInteger(0)
        lastSnapshot.value = snapshot(chainTip = MINED + 3)
        withEmissions(countingController(BlockHeight.new(MINED + 9), reads).allTransactions, tip = MINED + 3) {
            assertEquals(TransactionState.Confirmed, it.nextState())
            assertEquals(1, reads.get())
        }
    }

    @Test
    fun an_unknown_live_tip_reads_the_scanned_height_on_every_tip_change() {
        val reads = AtomicInteger(0)
        withEmissions(countingController(null, reads).allTransactions, tip = MINED + 3) {
            assertEquals(TransactionState.Pending, it.nextState())

            networkHeight.value = BlockHeight.new(MINED + 9)
            assertEquals(TransactionState.Confirmed, it.nextState())
            assertEquals(2, reads.get())
        }
    }

    @Test
    fun a_live_tip_below_the_last_height_used_reads_the_scanned_height_again() {
        val reads = AtomicInteger(0)
        lastSnapshot.value = snapshot(chainTip = MINED + 3)
        withEmissions(countingController(BlockHeight.new(MINED + 3), reads).allTransactions, tip = MINED + 9) {
            assertEquals(TransactionState.Confirmed, it.nextState())
            assertEquals(1, reads.get())

            networkHeight.value = BlockHeight.new(MINED + 10)
            assertEquals(TransactionState.Confirmed, it.nextState())
            assertEquals(2, reads.get(), "a live tip below the height used last may be stale")
        }
    }

    @Test
    fun needs_scanned_height_first_and_without_a_trustworthy_live_tip() {
        assertTrue(needsScannedHeight(liveChainTip = null, lastResolved = null))
        assertTrue(needsScannedHeight(liveChainTip = null, lastResolved = BlockHeight.new(10)))
        assertTrue(needsScannedHeight(liveChainTip = BlockHeight.new(10), lastResolved = null))
        assertFalse(needsScannedHeight(liveChainTip = BlockHeight.new(10), lastResolved = BlockHeight.new(10)))
        assertTrue(needsScannedHeight(liveChainTip = BlockHeight.new(9), lastResolved = BlockHeight.new(10)))
    }

    @Test
    fun resolve_latest_height_takes_the_highest_known_reading() {
        assertNull(resolveLatestHeight(null, null, null))
        assertEquals(BlockHeight.new(10), resolveLatestHeight(0L, null, BlockHeight.new(10)))
        assertEquals(BlockHeight.new(12), resolveLatestHeight(12L, BlockHeight.new(11), BlockHeight.new(10)))
        assertEquals(BlockHeight.new(13), resolveLatestHeight(12L, BlockHeight.new(13), BlockHeight.new(10)))
        assertEquals(BlockHeight.new(14), resolveLatestHeight(12L, BlockHeight.new(13), BlockHeight.new(14)))
    }

    private fun countingController(
        maxScannedHeight: BlockHeight?,
        reads: AtomicInteger
    ): TransactionsController {
        val backend =
            object : TypesafeBackend by mock(TypesafeBackend::class.java) {
                override suspend fun getMaxScannedHeight(): BlockHeight? {
                    reads.incrementAndGet()
                    return maxScannedHeight
                }
            }
        return TransactionsController(rows(trustStatus = null), engine, backend)
    }

    private fun snapshot(chainTip: Long) =
        SlipstreamSnapshot(chainTip, 0, 0, 0, 0, 1, 0, false, 0, false, 0, 0, false, 0)

    /** Publishes [tip] BEFORE collecting, so the first emission is deterministic. */
    private fun withEmissions(
        flow: Flow<List<TransactionOverview>>,
        tip: Long?,
        block: suspend (ReceiveChannel<List<TransactionOverview>>) -> Unit
    ) = runBlocking {
        networkHeight.value = tip?.let(BlockHeight::new)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            block(flow.produceIn(scope))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun ReceiveChannel<List<TransactionOverview>>.nextState(): TransactionState =
        withTimeout(TIMEOUT_MS) { receive() }.single().transactionState

    private fun receivedRow(
        minedHeight: Long,
        trustStatus: Long?
    ) =
        SlipstreamTransactionRow(
            txId = ByteArray(32) { 1 },
            minedHeight = minedHeight,
            expiryHeight = null,
            txIndex = 0,
            raw = null,
            accountBalanceDelta = 5_000,
            totalSpent = 0,
            totalReceived = 5_000,
            feePaid = null,
            hasChange = false,
            sentNoteCount = 0,
            receivedNoteCount = 1,
            memoCount = 0,
            blockTime = 1_700_000_000,
            isShielding = false,
            isExpiredUnmined = 0L,
            zip318Kind = 0,
            spentNoteCount = 0,
            poolCrossingValue = null,
            trustStatus = trustStatus
        )

    companion object {
        private const val MINED = 3_500_000L
        private const val TIMEOUT_MS = 5_000L
        private const val TRUSTED = ConfirmationsPolicy.TRUSTED_CONFIRMATIONS
        private const val UNTRUSTED = ConfirmationsPolicy.UNTRUSTED_CONFIRMATIONS
    }
}
