package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.ext.toHexReversed

/**
 * A result object for a transaction that was created as part of a proposal, indicating
 * whether it was submitted to the network or if an error occurred.
 */
sealed class TransactionSubmitResult(
    open val txId: FirstClassByteArray
) {
    /**
     * @return Transaction ID correctly transformed into String representation
     */
    fun txIdString() = txId.byteArray.toHexReversed()

    /**
     * The transaction was successfully submitted to the mempool.
     */
    data class Success(
        override val txId: FirstClassByteArray
    ) : TransactionSubmitResult(txId)

    /**
     * An error occurred while submitting the transaction.
     *
     * If `grpcError` is true, the call failed in transport: the server could not be reached, the call
     * timed out or its response was lost, so the server may still have accepted the transaction.
     * Otherwise, the transaction reached the `lightwalletd` server but failed to enter
     * the mempool.
     *
     * @param isTorFailure true when this failure specifically originated from Tor circuit
     * setup/bootstrap (surfaced as [co.electriccoin.lightwallet.client.model.Response.Failure.OverTor]
     * at the network layer), as opposed to a generic gRPC/mempool failure that happened to occur
     * while Tor was in use. Callers use this to route to Tor-specific recovery UI instead of the
     * generic "couldn't send" path. Defaults to false so existing call sites that construct this
     * for non-Tor failures don't need to change.
     * @param rejection the SDK's verdict on the transaction when this result was returned: the
     * refusal for good it recorded, non-null only when the transaction is rejected, that is every
     * endpoint it was submitted to answered explicitly, none accepted it and every one refused it for good
     * (the same verdict as [cash.z.ecc.android.sdk.Synchronizer.rejectedTransactions]). `null` while
     * the transaction is pending: this endpoint's refusal is transient or inconclusive, or another
     * endpoint has not answered yet. A transaction another endpoint accepted, in this submission or
     * an earlier one, is returned as [Success] instead of a [Failure]. Defaults to `null`.
     */
    data class Failure(
        override val txId: FirstClassByteArray,
        val grpcError: Boolean,
        val code: Int,
        val description: String?,
        val isTorFailure: Boolean = false,
        val rejection: SubmitRejection? = null,
    ) : TransactionSubmitResult(txId) {
        /**
         * True when the SDK itself refused to submit the transaction because it was built for another consensus
         * branch than the next block's, which is what a network upgrade (NU7) changes. Nothing was sent. The
         * transaction can never be mined, so the caller should build a new one rather than retry this one.
         */
        val isWrongConsensusBranch: Boolean
            get() = !grpcError && code == CODE_WRONG_CONSENSUS_BRANCH

        /**
         * True when the SDK itself refused to submit the transaction because it had already expired by the next
         * block. Nothing was sent.
         */
        val isExpired: Boolean
            get() = !grpcError && code == CODE_EXPIRED

        companion object {
            /** The local code of a refusal for [isWrongConsensusBranch]. Not a code any server returns. */
            const val CODE_WRONG_CONSENSUS_BRANCH = -9101

            /** The local code of a refusal for [isExpired]. Not a code any server returns. */
            const val CODE_EXPIRED = -9102
        }
    }

    /**
     * The transaction was created and is in the local wallet, but was not submitted to
     * the network.
     */
    data class NotAttempted(
        override val txId: FirstClassByteArray
    ) : TransactionSubmitResult(txId)
}
