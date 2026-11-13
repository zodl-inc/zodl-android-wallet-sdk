package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep

/**
 * Thrown across the JNI boundary when a proposal exceeds a count cap of the transaction size bound
 * its caller passed. Thrown by `proposeTransfer`, `proposeTransferFromUri` and
 * `proposeSendMaxTransfer`, and attached as a suppressed exception to the failure of
 * `createProposedTransactions` and `createPcztFromProposal`. A byte limit below a block is reported
 * as [ProposalRejectedException.TRANSACTION_TOO_LARGE] instead, as a block's own is.
 *
 * [dimension] is one of the fixed names reported by `size_bound.rs` ([SHIELDED_ACTIONS],
 * [POOL_ACTIONS], [TRANSPARENT_INPUTS], [TRANSPARENT_OUTPUTS], [SHIELDED_PAYMENT_OUTPUTS]); [pool]
 * is the pool name (`SAPLING`, `ORCHARD` or `IRONWOOD`) for [POOL_ACTIONS] and `null` otherwise.
 * Nothing here carries the recipient or the memo.
 *
 * Constructed from native code, which is why it must be kept: the class name and the
 * `(String, String, long, long)` constructor signature are part of the JNI contract with
 * `new_rejection_exception` in `lib.rs`.
 */
@Keep
class ProposalSizeBoundException(
    val dimension: String,
    val pool: String?,
    val count: Long,
    val limit: Long
) : RuntimeException("proposal exceeds the size bound: $dimension") {
    companion object {
        const val SHIELDED_ACTIONS = "shielded_actions"
        const val POOL_ACTIONS = "pool_actions"
        const val TRANSPARENT_INPUTS = "transparent_inputs"
        const val TRANSPARENT_OUTPUTS = "transparent_outputs"
        const val SHIELDED_PAYMENT_OUTPUTS = "shielded_payment_outputs"
    }
}
