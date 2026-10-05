package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.internal.TypesafeBackend
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
import kotlin.test.assertNull

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

    private val rows =
        VisibleTransactionRows { _, _ ->
            queries.incrementAndGet()
            listOf(receivedRow(MINED))
        }

    private fun controller(maxScannedHeight: BlockHeight?): TransactionsController {
        val backend = mock(TypesafeBackend::class.java)
        runBlocking { `when`(backend.getMaxScannedHeight()).thenReturn(maxScannedHeight) }
        return TransactionsController(rows, engine, backend)
    }

    @Test
    fun tip_advance_without_a_requery_tick_confirms_a_received_transaction() =
        withEmissions(controller(maxScannedHeight = null).allTransactions, tip = MINED + 3) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            // Only the tip moves - no requery tick, so no new row read.
            networkHeight.value = BlockHeight.new(MINED + 9)
            assertEquals(TransactionState.Confirmed, emissions.nextState())
            assertEquals(1, queries.get(), "a tip change must re-map the rows, not re-read them")
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

    @Test
    fun stale_seeded_tip_is_lifted_to_the_scanned_height() =
        withEmissions(
            controller(maxScannedHeight = BlockHeight.new(MINED + 9)).allTransactions,
            tip = MINED + 3
        ) { emissions ->
            // A tip that lags what the wallet has already scanned must not under-count.
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
    fun resolve_latest_height_takes_the_highest_known_reading() {
        assertNull(resolveLatestHeight(null, null, null))
        assertEquals(BlockHeight.new(10), resolveLatestHeight(0L, null, BlockHeight.new(10)))
        assertEquals(BlockHeight.new(12), resolveLatestHeight(12L, BlockHeight.new(11), BlockHeight.new(10)))
        assertEquals(BlockHeight.new(13), resolveLatestHeight(12L, BlockHeight.new(13), BlockHeight.new(10)))
        assertEquals(BlockHeight.new(14), resolveLatestHeight(12L, BlockHeight.new(13), BlockHeight.new(14)))
    }

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

    private fun receivedRow(minedHeight: Long) =
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
            trustStatus = null
        )

    companion object {
        private const val MINED = 3_500_000L
        private const val TIMEOUT_MS = 5_000L
    }
}
