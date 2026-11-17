package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep

/**
 * Thrown across the JNI boundary when a proposal is rejected for a reason other than insufficient
 * funds (which is [ProposalInsufficientFundsException]). Thrown by `proposeTransfer`,
 * `proposeTransferFromUri`, `proposeShielding` and `proposeSendMaxTransfer`; the Orchard to
 * Ironwood migration proposals do not throw it.
 *
 * [reason] is one of the fixed categories reported by `lib.rs` ([TRANSACTION_TOO_LARGE],
 * [TRANSPARENT_MEMO], [UNSUPPORTED_ADDRESS], [SYNC_REQUIRED]). Neither it nor the message ever
 * contains the recipient or the memo.
 *
 * Constructed from native code, which is why it must be kept: the class name and the `(String)`
 * constructor signature are part of the JNI contract with `throw_proposal_rejection` in `lib.rs`.
 */
@Keep
class ProposalRejectedException(
    val reason: String
) : RuntimeException("proposal rejected: $reason") {
    companion object {
        /** The transaction would spend too many notes to fit in a block. */
        const val TRANSACTION_TOO_LARGE = "transaction_too_large"

        /** A memo was given for a recipient that cannot receive one. */
        const val TRANSPARENT_MEMO = "transparent_memo"

        /** The recipient has no receiver the wallet is able to pay. */
        const val UNSUPPORTED_ADDRESS = "unsupported_address"

        /** The wallet has not scanned enough of the chain to propose a transaction. */
        const val SYNC_REQUIRED = "sync_required"
    }
}
