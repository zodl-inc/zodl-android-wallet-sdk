package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.internal.model.ConfirmationsPolicy
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState
import com.zodl.slipstream.model.SlipstreamTransactionRow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionOverviewCursorTest {
    /** `chainTip + 1 - minedHeight == 10`, the untrusted confirmation count -> Confirmed. */
    @Test
    fun received_transaction_has_positive_net_value_and_is_not_sent() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 1 },
                        minedHeight = 1_000,
                        expiryHeight = null,
                        txIndex = 3,
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
                    ),
                latestHeight = BlockHeight.new(1_009)
            )

        assertFalse(overview.isSentTransaction)
        assertEquals(5_000L, overview.netValue.value)
        assertEquals(TransactionState.Confirmed, overview.transactionState)
    }

    @Test
    fun sent_transaction_has_positive_net_value_via_absolute_value() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 2 },
                        minedHeight = null,
                        expiryHeight = 1_010,
                        txIndex = null,
                        raw = byteArrayOf(1, 2, 3),
                        accountBalanceDelta = -7_500,
                        totalSpent = 8_000,
                        totalReceived = 0,
                        feePaid = 500,
                        hasChange = true,
                        sentNoteCount = 1,
                        receivedNoteCount = 0,
                        memoCount = 1,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 0,
                        spentNoteCount = 0,
                        poolCrossingValue = null,
                        trustStatus = null
                    ),
                latestHeight = BlockHeight.new(1_000)
            )

        assertTrue(overview.isSentTransaction)
        assertEquals(7_500L, overview.netValue.value)
        assertEquals(500L, overview.feePaid?.value)
        assertTrue(overview.isChange)
        assertEquals(TransactionState.Pending, overview.transactionState)
    }

    @Test
    fun zero_expiry_height_maps_to_no_expiry() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 3 },
                        minedHeight = null,
                        expiryHeight = 0,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = 100,
                        totalSpent = 0,
                        totalReceived = 100,
                        feePaid = null,
                        hasChange = false,
                        sentNoteCount = 0,
                        receivedNoteCount = 1,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 0,
                        spentNoteCount = 0,
                        poolCrossingValue = null,
                        trustStatus = null
                    ),
                latestHeight = BlockHeight.new(1_000_000)
            )

        assertNull(overview.expiryHeight)
        assertEquals(TransactionState.Pending, overview.transactionState)
    }

    /** Unknown chain tip -> Pending regardless of a mined height (section 3.6 base case). */
    @Test
    fun raw_and_index_pass_through_when_present() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 4 },
                        minedHeight = 500,
                        expiryHeight = null,
                        txIndex = 9,
                        raw = byteArrayOf(9, 9),
                        accountBalanceDelta = 1,
                        totalSpent = 0,
                        totalReceived = 1,
                        feePaid = null,
                        hasChange = false,
                        sentNoteCount = 0,
                        receivedNoteCount = 1,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = true,
                        isExpiredUnmined = null,
                        zip318Kind = 0,
                        spentNoteCount = 0,
                        poolCrossingValue = null,
                        trustStatus = null
                    ),
                latestHeight = null
            )

        assertEquals(9L, overview.index)
        assertTrue(overview.raw!!.byteArray.contentEquals(byteArrayOf(9, 9)))
        assertTrue(overview.isShielding)
        assertEquals(TransactionState.Pending, overview.transactionState)
    }

    /**
     * Shape of a row from `v_transactions_with_pending_migrations`'s migration branch: no
     * `raw` (not broadcast yet), no `mined_height`, a real `expiry_height`, `fee_paid` present
     * (unlike an ordinary unbroadcast send, which has none yet either), and a real
     * `zip318Kind`. Locks in that the existing null-safe mapping needs no changes for
     * z/wt/migration_fixes/spec/2026-08-06-activity-pending-migrations-plan.md.
     */
    @Test
    fun migration_transfer_pending_row_maps_to_pending_state_with_transfer_kind() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 5 },
                        minedHeight = null,
                        expiryHeight = 2_500_000,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = -1_000,
                        totalSpent = 500_000,
                        totalReceived = 499_000,
                        feePaid = 1_000,
                        hasChange = false,
                        sentNoteCount = 1,
                        receivedNoteCount = 1,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 3, // Zip318Kind.TRANSFER
                        spentNoteCount = 0,
                        poolCrossingValue = null,
                        trustStatus = null
                    ),
                latestHeight = BlockHeight.new(2_000_000)
            )

        assertTrue(overview.isSentTransaction)
        assertNull(overview.raw)
        assertNull(overview.minedHeight)
        assertEquals(TransactionState.Pending, overview.transactionState)
        assertEquals(cash.z.ecc.android.sdk.model.Zip318Kind.TRANSFER, overview.zip318Kind)
    }

    /**
     * MOB-1665: an Expired transaction with no real block_time (never mined, so nothing in the
     * `blocks` table joins to it) used to reach the UI as a null timestamp, sorting as if it
     * happened at the end of today (GetActivitiesUseCase.kt's `?: endOfDay` fallback) no matter
     * how long ago it actually expired. Estimated from the block-height gap to the known chain
     * tip instead, at the fixed 75s/block protocol target.
     */
    @Test
    fun expired_transaction_with_no_block_time_gets_an_estimated_timestamp() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 7 },
                        minedHeight = null,
                        expiryHeight = 100,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = -1,
                        totalSpent = 1,
                        totalReceived = 0,
                        feePaid = null,
                        hasChange = false,
                        sentNoteCount = 1,
                        receivedNoteCount = 0,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 0,
                        spentNoteCount = 0,
                        poolCrossingValue = null,
                        trustStatus = null
                    ),
                latestHeight = BlockHeight.new(200),
                nowEpochSeconds = 1_800_000_000L
            )

        assertEquals(TransactionState.Expired, overview.transactionState)
        // 100 blocks past expiry, at 75s/block = 7_500s ago.
        assertEquals(1_800_000_000L - 7_500L, overview.blockTimeEpochSeconds)
    }

    /** A real block_time on the row is always used verbatim — the estimate is a fallback only. */
    @Test
    fun expired_transaction_with_a_real_block_time_keeps_it_unestimated() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 8 },
                        minedHeight = null,
                        expiryHeight = 100,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = -1,
                        totalSpent = 1,
                        totalReceived = 0,
                        feePaid = null,
                        hasChange = false,
                        sentNoteCount = 1,
                        receivedNoteCount = 0,
                        memoCount = 0,
                        blockTime = 1_650_000_000L,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 0,
                        spentNoteCount = 0,
                        poolCrossingValue = null,
                        trustStatus = null
                    ),
                latestHeight = BlockHeight.new(200),
                nowEpochSeconds = 1_800_000_000L
            )

        assertEquals(TransactionState.Expired, overview.transactionState)
        assertEquals(1_650_000_000L, overview.blockTimeEpochSeconds)
    }

    /** A non-expired (Pending) transaction with no block_time is left null — no estimate applies. */
    @Test
    fun pending_transaction_with_no_block_time_is_not_estimated() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 9 },
                        minedHeight = null,
                        expiryHeight = 300,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = -1,
                        totalSpent = 1,
                        totalReceived = 0,
                        feePaid = null,
                        hasChange = false,
                        sentNoteCount = 1,
                        receivedNoteCount = 0,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 0,
                        spentNoteCount = 0,
                        poolCrossingValue = null,
                        trustStatus = null
                    ),
                latestHeight = BlockHeight.new(200),
                nowEpochSeconds = 1_800_000_000L
            )

        assertEquals(TransactionState.Pending, overview.transactionState)
        assertNull(overview.blockTimeEpochSeconds)
    }

    /** Same shape, `PREPARATION` (note-split) kind — the other migration branch case. */
    @Test
    fun migration_preparation_pending_row_maps_to_pending_state_with_preparation_kind() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 6 },
                        minedHeight = null,
                        expiryHeight = 2_500_100,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = -300,
                        totalSpent = 300,
                        totalReceived = 0,
                        feePaid = 300,
                        hasChange = false,
                        sentNoteCount = 0,
                        receivedNoteCount = 3,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 2, // Zip318Kind.PREPARATION
                        spentNoteCount = 0,
                        poolCrossingValue = null,
                        trustStatus = null
                    ),
                latestHeight = BlockHeight.new(2_000_000)
            )

        assertTrue(overview.isSentTransaction)
        assertEquals(TransactionState.Pending, overview.transactionState)
        assertEquals(cash.z.ecc.android.sdk.model.Zip318Kind.PREPARATION, overview.zip318Kind)
    }

    /**
     * `trust_status` follows the SDK's own `AllTransactionView` rule: only an explicit `1` is
     * trusted (e.g. a gift-card claim recorded as trusted per ZIP 315); `0` and SQL NULL - a
     * transaction the wallet never marked, or a migration-pending row - read as untrusted.
     */
    @Test
    fun trust_status_one_maps_to_trusted() {
        val overview = TransactionOverviewCursor.fromRow(receivedRow(trustStatus = 1L), BlockHeight.new(1_001))

        assertTrue(overview.isTrusted)
    }

    @Test
    fun trust_status_zero_maps_to_untrusted() {
        val overview = TransactionOverviewCursor.fromRow(receivedRow(trustStatus = 0L), BlockHeight.new(1_001))

        assertFalse(overview.isTrusted)
    }

    @Test
    fun null_trust_status_maps_to_untrusted() {
        val overview = TransactionOverviewCursor.fromRow(receivedRow(trustStatus = null), BlockHeight.new(1_001))

        assertFalse(overview.isTrusted)
    }

    /** A received row mined at 1_000 has `latestHeight + 1 - 1_000` confirmations. */
    @Test
    fun trusted_receive_is_confirmed_at_the_trusted_count() {
        val trusted = receivedRow(trustStatus = 1L)
        val atHeight = { confirmations: Int -> BlockHeight.new(1_000L + confirmations - 1) }

        assertEquals(
            TransactionState.Pending,
            TransactionOverviewCursor.fromRow(trusted, atHeight(TRUSTED - 1)).transactionState
        )
        assertEquals(
            TransactionState.Confirmed,
            TransactionOverviewCursor.fromRow(trusted, atHeight(TRUSTED)).transactionState
        )
    }

    @Test
    fun untrusted_receive_is_confirmed_at_the_untrusted_count() {
        val untrusted = receivedRow(trustStatus = null)
        val atHeight = { confirmations: Int -> BlockHeight.new(1_000L + confirmations - 1) }

        assertEquals(
            TransactionState.Pending,
            TransactionOverviewCursor.fromRow(untrusted, atHeight(UNTRUSTED - 1)).transactionState
        )
        assertEquals(
            TransactionState.Confirmed,
            TransactionOverviewCursor.fromRow(untrusted, atHeight(UNTRUSTED)).transactionState
        )
    }

    @Test
    fun spent_note_count_and_pool_crossing_value_are_carried_through() {
        val overview =
            TransactionOverviewCursor.fromRow(
                receivedRow(trustStatus = null).copy(spentNoteCount = 2, poolCrossingValue = 4_000L),
                BlockHeight.new(1_001)
            )

        assertEquals(2, overview.spentNoteCount)
        assertEquals(4_000L, overview.poolCrossingValue?.value)
        assertNull(TransactionOverviewCursor.fromRow(receivedRow(trustStatus = null), null).poolCrossingValue)
    }

    private fun receivedRow(trustStatus: Long?) =
        SlipstreamTransactionRow(
            txId = ByteArray(32) { 7 },
            minedHeight = 1_000,
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
        private const val TRUSTED = ConfirmationsPolicy.TRUSTED_CONFIRMATIONS
        private const val UNTRUSTED = ConfirmationsPolicy.UNTRUSTED_CONFIRMATIONS
    }
}
