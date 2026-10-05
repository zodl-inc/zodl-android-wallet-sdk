package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.model.ConfirmationsPolicy
import cash.z.ecc.android.sdk.internal.model.DbTransactionOverview
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The legacy synchronizer's transaction state, derived in [TransactionOverview.new] from the
 * `v_transactions` row: a received transaction the wallet trusts is confirmed at the ZIP 315
 * trusted confirmation count, every other mined transaction at the untrusted count.
 */
class TransactionStateTest {
    @Test
    fun trusted_receive_is_confirmed_at_the_trusted_count() {
        assertEquals(TransactionState.Pending, stateAt(confirmations = TRUSTED - 1, isSent = false, isTrusted = true))
        assertEquals(TransactionState.Confirmed, stateAt(confirmations = TRUSTED, isSent = false, isTrusted = true))
    }

    @Test
    fun untrusted_receive_is_confirmed_at_the_untrusted_count() {
        assertEquals(
            TransactionState.Pending,
            stateAt(confirmations = UNTRUSTED - 1, isSent = false, isTrusted = false)
        )
        assertEquals(
            TransactionState.Confirmed,
            stateAt(confirmations = UNTRUSTED, isSent = false, isTrusted = false)
        )
    }

    @Test
    fun sent_transaction_keeps_the_untrusted_count_even_when_trusted() {
        assertEquals(TransactionState.Pending, stateAt(confirmations = UNTRUSTED - 1, isSent = true, isTrusted = true))
        assertEquals(TransactionState.Confirmed, stateAt(confirmations = UNTRUSTED, isSent = true, isTrusted = true))
    }

    @Test
    fun policy_matches_zip_315_defaults() {
        // librustzcash `ConfirmationsPolicy::default()`; the Rust backend applies the same numbers
        // to balances, so a trusted receive must not read Confirmed before it is spendable.
        assertEquals(3, TRUSTED)
        assertEquals(10, UNTRUSTED)
    }

    /** A transaction mined in the latest block has 1 confirmation. */
    private fun stateAt(
        confirmations: Int,
        isSent: Boolean,
        isTrusted: Boolean
    ): TransactionState =
        TransactionOverview
            .new(
                overview(isSent = isSent, isTrusted = isTrusted),
                latestBlockHeight = BlockHeight.new(MINED + confirmations - 1)
            ).transactionState

    private fun overview(
        isSent: Boolean,
        isTrusted: Boolean
    ) = DbTransactionOverview(
        rawId = FirstClassByteArray(ByteArray(32) { 1 }),
        minedHeight = BlockHeight.new(MINED),
        expiryHeight = null,
        index = 0,
        raw = null,
        isSentTransaction = isSent,
        netValue = Zatoshi(5_000),
        totalSpent = Zatoshi(0),
        totalReceived = Zatoshi(5_000),
        feePaid = null,
        isChange = false,
        receivedNoteCount = 1,
        sentNoteCount = 0,
        memoCount = 0,
        blockTimeEpochSeconds = 1_700_000_000,
        isShielding = false,
        isExpiredUnmined = false,
        spentNoteCount = 0,
        poolCrossingValue = null,
        isTrusted = isTrusted,
        zip318Kind = Zip318Kind.NOT_CLASSIFIED
    )

    companion object {
        private const val MINED = 3_500_000L
        private const val TRUSTED = ConfirmationsPolicy.TRUSTED_CONFIRMATIONS
        private const val UNTRUSTED = ConfirmationsPolicy.UNTRUSTED_CONFIRMATIONS
    }
}
