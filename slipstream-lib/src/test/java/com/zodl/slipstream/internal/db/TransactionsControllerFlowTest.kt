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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    private val fullyScannedHeight = MutableStateFlow<BlockHeight?>(null)
    private val lastSnapshot = MutableStateFlow<SlipstreamSnapshot?>(null)
    private val queries = AtomicInteger(0)

    private val engine =
        mock(SlipstreamEngine::class.java).also {
            `when`(it.requeryTicks).thenReturn(requeryTicks)
            `when`(it.networkHeight).thenReturn(networkHeight)
            `when`(it.fullyScannedHeight).thenReturn(fullyScannedHeight)
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

    /**
     * The resume case: the tip jumps far past the send's expiry while the scan is still short of
     * it. The send stays Pending, and once the scan records its mined height, which bumps no
     * requery tick, the advance of the fully scanned height re-reads it as mined.
     */
    @Test
    fun scan_advance_re_reads_an_unmined_send_once_its_mined_height_is_recorded() {
        val stored = AtomicReference(listOf(sentRow(minedHeight = null)))
        fullyScannedHeight.value = BlockHeight.new(SENT - 1)
        withEmissions(controllerOver(stored).allTransactions, tip = EXPIRY + 100) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            stored.set(listOf(sentRow(minedHeight = SENT)))
            fullyScannedHeight.value = BlockHeight.new(SENT + 1)
            assertEquals(TransactionState.Confirmed, emissions.nextState())
            assertEquals(2, queries.get(), "a scan advance over an unmined row must re-read the rows")
        }
    }

    /**
     * The first read is still in flight when the scan records the send's mined height and the fully
     * scanned height advances. That advance must not be dropped: the read finishes with the rows it
     * took before the scan, and the send would stay Pending until a later scan advance.
     */
    @Test
    fun scan_advance_during_the_first_read_is_not_lost() {
        val stored = AtomicReference(listOf(sentRow(minedHeight = null)))
        val firstReadStarted = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        val source =
            VisibleTransactionRows { _, _ ->
                val read = queries.incrementAndGet()
                val rowsAtStart = stored.get()
                if (read == 1) {
                    firstReadStarted.complete(Unit)
                    releaseFirstRead.await()
                    rowsAtStart
                } else {
                    stored.get()
                }
            }
        val controller = TransactionsController(source, engine, mock(TypesafeBackend::class.java))
        fullyScannedHeight.value = BlockHeight.new(SENT - 1)
        withEmissions(controller.allTransactions, tip = EXPIRY + 100) { emissions ->
            withTimeout(TIMEOUT_MS) { firstReadStarted.await() }

            stored.set(listOf(sentRow(minedHeight = SENT)))
            fullyScannedHeight.value = BlockHeight.new(SENT + 1)
            releaseFirstRead.complete(Unit)

            // The first read may still publish its stale Pending rows before the re-read lands.
            withTimeout(TIMEOUT_MS) {
                while (emissions.receive().single().transactionState != TransactionState.Confirmed) {
                    // keep draining until the re-read's rows arrive
                }
            }
            assertTrue(queries.get() >= 2, "the advance during the first read must trigger a second read")
        }
    }

    /**
     * Reads slower than the scan updates: a running read must not be cancelled by every advance, or
     * the first result never arrives until the updates stop.
     */
    @Test
    fun repeated_scan_advances_do_not_starve_the_first_read() {
        val stored = AtomicReference(listOf(sentRow(minedHeight = null)))
        val source =
            VisibleTransactionRows { _, _ ->
                queries.incrementAndGet()
                delay(SLOW_READ_MS)
                stored.get()
            }
        val controller = TransactionsController(source, engine, mock(TypesafeBackend::class.java))
        fullyScannedHeight.value = BlockHeight.new(SENT - 10)
        withEmissions(controller.allTransactions, tip = EXPIRY + 100) { emissions ->
            // The scan keeps advancing faster than a read takes, until the first result arrives.
            val advancing =
                CoroutineScope(Dispatchers.Default).launch {
                    var height = SENT - 9
                    while (true) {
                        delay(SLOW_READ_MS / 3)
                        fullyScannedHeight.value = BlockHeight.new(height++)
                    }
                }
            try {
                assertEquals(TransactionState.Pending, emissions.nextState())
            } finally {
                advancing.cancel()
            }
        }
    }

    /**
     * A scan advance arrives during a later read, whose previous result held only confirmed rows
     * and that read captured a newly visible send as pending: the advance must still be judged
     * against the rows that read returned, so the send is re-read once the scan records its mined height.
     */
    @Test
    fun scan_advance_during_a_later_read_is_judged_against_that_reads_rows() {
        val stored = AtomicReference(listOf(receivedRow(MINED, null)))
        val secondReadStarted = CompletableDeferred<Unit>()
        val releaseSecondRead = CompletableDeferred<Unit>()
        val source =
            VisibleTransactionRows { _, _ ->
                val read = queries.incrementAndGet()
                val rowsAtStart = stored.get()
                if (read == 2) {
                    secondReadStarted.complete(Unit)
                    releaseSecondRead.await()
                }
                rowsAtStart
            }
        val controller = TransactionsController(source, engine, mock(TypesafeBackend::class.java))
        fullyScannedHeight.value = BlockHeight.new(SENT - 1)
        withEmissions(controller.allTransactions, tip = EXPIRY + 100) { emissions ->
            withTimeout(TIMEOUT_MS) { emissions.receive() }

            // The new send is visible but not mined yet; the second read captures it as pending.
            stored.set(listOf(sentRow(minedHeight = null)))
            requeryTicks.emit(Unit)
            withTimeout(TIMEOUT_MS) { secondReadStarted.await() }

            // The scan records the mined height and advances while that read is still running.
            stored.set(listOf(sentRow(minedHeight = SENT)))
            fullyScannedHeight.value = BlockHeight.new(SENT + 1)
            releaseSecondRead.complete(Unit)

            withTimeout(TIMEOUT_MS) {
                while (emissions.receive().single().transactionState != TransactionState.Confirmed) {
                    // keep draining until the follow-up read's rows arrive
                }
            }
            assertTrue(queries.get() >= 3, "the advance during the later read must queue a follow-up read")
        }
    }

    /** A send that is never mined still expires, once the scan reaches its expiry height. */
    @Test
    fun scan_advance_past_expiry_expires_a_send_that_was_never_mined() {
        val stored = AtomicReference(listOf(sentRow(minedHeight = null)))
        fullyScannedHeight.value = BlockHeight.new(EXPIRY - 1)
        withEmissions(controllerOver(stored).allTransactions, tip = EXPIRY + 100) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            fullyScannedHeight.value = BlockHeight.new(EXPIRY)
            assertEquals(TransactionState.Expired, emissions.nextState())
            assertEquals(2, queries.get())
        }
    }

    @Test
    fun scan_advance_with_every_cached_row_mined_re_reads_nothing() {
        fullyScannedHeight.value = BlockHeight.new(MINED)
        withEmissions(controller(maxScannedHeight = null).allTransactions, tip = MINED + 3) { emissions ->
            assertEquals(TransactionState.Pending, emissions.nextState())

            fullyScannedHeight.value = BlockHeight.new(MINED + 1)
            fullyScannedHeight.value = BlockHeight.new(MINED + 2)
            delay(SETTLE_MS)
            networkHeight.value = BlockHeight.new(MINED + 9)
            assertEquals(TransactionState.Confirmed, emissions.nextState())
            assertEquals(1, queries.get(), "a scan advance over mined rows only must not re-read them")
        }
    }

    @Test
    fun needs_read_on_scan_advance_only_above_the_last_read_and_with_an_unmined_row() {
        val unmined = listOf(sentRow(minedHeight = null))
        val mined = listOf(sentRow(minedHeight = SENT))
        val at = BlockHeight.new(SENT)
        val above = BlockHeight.new(SENT + 1)

        assertFalse(needsReadOnScanAdvance(lastRead = null, fullyScanned = above))
        assertFalse(needsReadOnScanAdvance(RowsRead(unmined, at), fullyScanned = null))
        assertFalse(needsReadOnScanAdvance(RowsRead(unmined, at), fullyScanned = at))
        assertFalse(needsReadOnScanAdvance(RowsRead(unmined, above), fullyScanned = at))
        assertFalse(needsReadOnScanAdvance(RowsRead(mined, at), fullyScanned = above))
        assertFalse(needsReadOnScanAdvance(RowsRead(emptyList(), at), fullyScanned = above))
        assertTrue(needsReadOnScanAdvance(RowsRead(unmined, at), fullyScanned = above))
        assertTrue(needsReadOnScanAdvance(RowsRead(unmined, scannedHeight = null), fullyScanned = at))
    }

    /** A row already expired at the last read never gets mined, so it must not re-read on every block. */
    @Test
    fun needs_read_on_scan_advance_ignores_rows_already_expired_at_the_last_read() {
        val expiredAtRead = listOf(sentRow(minedHeight = null).copy(expiryHeight = SENT))
        val expiresLater = listOf(sentRow(minedHeight = null).copy(expiryHeight = SENT + 5))
        val neverExpires = listOf(sentRow(minedHeight = null).copy(expiryHeight = 0))
        val at = BlockHeight.new(SENT)
        val above = BlockHeight.new(SENT + 1)

        assertFalse(needsReadOnScanAdvance(RowsRead(expiredAtRead, at), fullyScanned = above))
        assertTrue(needsReadOnScanAdvance(RowsRead(expiredAtRead, scannedHeight = null), fullyScanned = above))
        assertTrue(needsReadOnScanAdvance(RowsRead(expiresLater, at), fullyScanned = above))
        assertTrue(needsReadOnScanAdvance(RowsRead(neverExpires, at), fullyScanned = above))
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

    private fun controllerOver(stored: AtomicReference<List<SlipstreamTransactionRow>>): TransactionsController {
        val source =
            VisibleTransactionRows { _, _ ->
                queries.incrementAndGet()
                stored.get()
            }
        return TransactionsController(source, engine, mock(TypesafeBackend::class.java))
    }

    private fun sentRow(minedHeight: Long?) =
        receivedRow(minedHeight = minedHeight ?: 0, trustStatus = null).copy(
            minedHeight = minedHeight,
            expiryHeight = EXPIRY,
            accountBalanceDelta = -5_000,
            totalSpent = 5_000,
            totalReceived = 0,
            sentNoteCount = 1,
            receivedNoteCount = 0,
            blockTime = null
        )

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
        private const val SENT = MINED + 100
        private const val EXPIRY = SENT + 40
        private const val SETTLE_MS = 200L
        private const val SLOW_READ_MS = 300L
        private const val TIMEOUT_MS = 5_000L
        private const val TRUSTED = ConfirmationsPolicy.TRUSTED_CONFIRMATIONS
        private const val UNTRUSTED = ConfirmationsPolicy.UNTRUSTED_CONFIRMATIONS
    }
}
