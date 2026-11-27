package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.internal.model.ConfirmationsPolicy
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState
import org.junit.Test
import kotlin.test.assertEquals

class TransactionStatesTest {
    @Test
    fun expired_unmined_flag_with_the_scan_past_expiry_is_expired() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(100),
                scannedHeight = BlockHeight.new(100),
                minedHeight = null,
                expiryHeight = BlockHeight.new(50),
                isExpiredUnmined = true,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Expired, state)
    }

    @Test
    fun mined_with_at_least_min_confirmations_is_confirmed() {
        // chainTip + 1 - minedHeight >= 10
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_009),
                scannedHeight = BlockHeight.new(1_009),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = null,
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Confirmed, state)
    }

    @Test
    fun mined_with_fewer_than_min_confirmations_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_005),
                scannedHeight = BlockHeight.new(1_005),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = null,
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unmined_within_expiry_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_000),
                scannedHeight = BlockHeight.new(1_000),
                minedHeight = null,
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unmined_past_expiry_is_expired() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_020),
                scannedHeight = BlockHeight.new(1_020),
                minedHeight = null,
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Expired, state)
    }

    @Test
    fun unmined_with_the_tip_past_expiry_but_the_scan_short_of_it_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_200),
                scannedHeight = BlockHeight.new(1_009),
                minedHeight = null,
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unmined_with_the_scan_at_expiry_is_expired() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_200),
                scannedHeight = BlockHeight.new(1_010),
                minedHeight = null,
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Expired, state)
    }

    @Test
    fun unmined_with_an_unknown_scanned_height_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_200),
                scannedHeight = null,
                minedHeight = null,
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun expired_unmined_flag_without_a_scanned_height_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_200),
                scannedHeight = null,
                minedHeight = null,
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = true,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    /**
     * The flag is computed against the highest scanned block, which can sit above a gap the
     * scanner has not filled yet, so it alone cannot prove a send was never mined.
     */
    @Test
    fun expired_unmined_flag_with_the_scan_short_of_expiry_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(200),
                scannedHeight = BlockHeight.new(40),
                minedHeight = null,
                expiryHeight = BlockHeight.new(50),
                isExpiredUnmined = true,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun expired_unmined_flag_with_an_unknown_expiry_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(200),
                scannedHeight = BlockHeight.new(200),
                minedHeight = null,
                expiryHeight = null,
                isExpiredUnmined = true,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun expired_unmined_flag_does_not_affect_a_mined_transaction() {
        assertEquals(
            TransactionState.Confirmed,
            computeTransactionState(
                latestHeight = BlockHeight.new(1_009),
                scannedHeight = BlockHeight.new(1_009),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = BlockHeight.new(1_005),
                isExpiredUnmined = true,
                requiredConfirmations = UNTRUSTED
            )
        )
        assertEquals(
            TransactionState.Pending,
            computeTransactionState(
                latestHeight = BlockHeight.new(1_008),
                scannedHeight = BlockHeight.new(1_008),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = BlockHeight.new(1_005),
                isExpiredUnmined = true,
                requiredConfirmations = UNTRUSTED
            )
        )
    }

    @Test
    fun mined_confirmations_count_from_the_tip_not_the_scanned_height() {
        assertEquals(
            TransactionState.Confirmed,
            computeTransactionState(
                latestHeight = BlockHeight.new(1_009),
                scannedHeight = BlockHeight.new(1_000),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        )
        assertEquals(
            TransactionState.Pending,
            computeTransactionState(
                latestHeight = BlockHeight.new(1_008),
                scannedHeight = BlockHeight.new(2_000),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        )
    }

    @Test
    fun unmined_with_zero_expiry_never_expires() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_000_000),
                scannedHeight = BlockHeight.new(1_000_000),
                minedHeight = null,
                expiryHeight = BlockHeight.new(0),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unknown_chain_tip_is_always_pending() {
        val state =
            computeTransactionState(
                latestHeight = null,
                scannedHeight = null,
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unmined_unknown_expiry_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(500),
                scannedHeight = BlockHeight.new(500),
                minedHeight = null,
                expiryHeight = null,
                isExpiredUnmined = false,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun null_expired_unmined_flag_does_not_short_circuit() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_009),
                scannedHeight = BlockHeight.new(1_009),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = null,
                isExpiredUnmined = null,
                requiredConfirmations = UNTRUSTED
            )
        assertEquals(TransactionState.Confirmed, state)
    }

    @Test
    fun trusted_receive_is_pending_one_short_of_the_trusted_count() {
        assertEquals(TransactionState.Pending, minedStateAt(TRUSTED - 1, isSent = false, isTrusted = true))
    }

    @Test
    fun trusted_receive_is_confirmed_at_the_trusted_count() {
        assertEquals(TransactionState.Confirmed, minedStateAt(TRUSTED, isSent = false, isTrusted = true))
    }

    @Test
    fun untrusted_receive_is_pending_one_short_of_the_untrusted_count() {
        assertEquals(TransactionState.Pending, minedStateAt(UNTRUSTED - 1, isSent = false, isTrusted = false))
    }

    @Test
    fun untrusted_receive_is_confirmed_at_the_untrusted_count() {
        assertEquals(TransactionState.Confirmed, minedStateAt(UNTRUSTED, isSent = false, isTrusted = false))
    }

    @Test
    fun sent_transaction_keeps_the_untrusted_count_even_when_trusted() {
        assertEquals(TransactionState.Pending, minedStateAt(UNTRUSTED - 1, isSent = true, isTrusted = true))
        assertEquals(TransactionState.Confirmed, minedStateAt(UNTRUSTED, isSent = true, isTrusted = true))
    }

    /** A transaction mined in the latest block has 1 confirmation. */
    private fun minedStateAt(
        confirmations: Int,
        isSent: Boolean,
        isTrusted: Boolean
    ) = computeTransactionState(
        latestHeight = BlockHeight.new(MINED + confirmations - 1),
        scannedHeight = BlockHeight.new(MINED + confirmations - 1),
        minedHeight = BlockHeight.new(MINED),
        expiryHeight = null,
        isExpiredUnmined = false,
        requiredConfirmations = ConfirmationsPolicy.requiredConfirmations(isSent, isTrusted)
    )

    companion object {
        private const val MINED = 1_000L
        private const val TRUSTED = ConfirmationsPolicy.TRUSTED_CONFIRMATIONS
        private const val UNTRUSTED = ConfirmationsPolicy.UNTRUSTED_CONFIRMATIONS
    }
}
