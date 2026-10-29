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
     * If `grpcError` is true, the transaction failed to reach the `lightwalletd` server.
     * Otherwise, the transaction reached the `lightwalletd` server but failed to enter
     * the mempool.
     *
     * @param isTorFailure true when this failure specifically originated from Tor circuit
     * setup/bootstrap (surfaced as [co.electriccoin.lightwallet.client.model.Response.Failure.OverTor]
     * at the network layer), as opposed to a generic gRPC/mempool failure that happened to occur
     * while Tor was in use. Callers use this to route to Tor-specific recovery UI instead of the
     * generic "couldn't send" path. Defaults to false so existing call sites that construct this
     * for non-Tor failures don't need to change.
     */
    data class Failure(
        override val txId: FirstClassByteArray,
        val grpcError: Boolean,
        val code: Int,
        val description: String?,
        val isTorFailure: Boolean = false,
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
