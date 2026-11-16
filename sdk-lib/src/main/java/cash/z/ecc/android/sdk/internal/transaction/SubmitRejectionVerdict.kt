package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.model.SubmitRejection
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/**
 * Whether a transaction is rejected, after [latest] was the answer of one of its endpoints: the
 * rejection to store, or `null` when it should be resubmitted as before.
 *
 * Any acceptance ([isAcceptance]: a [TransactionSubmitResult.Success], or an answer that the server
 * already holds the transaction, such as "already exists in mempool") clears the verdict: one in [outcomes],
 * or one persisted earlier ([acceptedEarlier]: an endpoint accepted the transaction before, possibly
 * in an earlier process), so that a verdict is
 * never drawn from the partial answers left after a restart. Otherwise the transaction is rejected only when
 * every endpoint gave an explicit server answer ([isExplicitServerAnswer]): each of [planEndpoints]
 * and of [outcomes] returned a [TransactionSubmitResult.Failure] from the server itself, and every
 * one of them is an explicit refusal for good ([serverRefusal]). A transient refusal from any endpoint
 * (such as a full mempool) keeps the transaction pending, as the endpoint that gave it may accept the
 * transaction later, and a rejection would lock the user's notes until the transaction expires.
 *
 * An endpoint without an explicit server answer is inconclusive: the request may have reached the
 * server, which may have accepted the transaction. That is one with no answer, recorded as
 * [TransactionSubmitResult.NotAttempted] (its submission is still in flight, or threw, was
 * cancelled or timed out after the request may have been sent) or not submitted to in this process,
 * one whose submission failed in transport ([TransactionSubmitResult.Failure.grpcError]: the
 * server could not be reached, the call timed out or the response was lost), and one the SDK refused
 * locally before sending anything ([isLocalRefusal]: built for another consensus branch than the
 * next block's, or already expired), which the server never saw. While any endpoint is
 * inconclusive no new rejection is drawn and [previous] merely stands: the transaction stays
 * pending and is resubmitted, until an acceptance clears it or a later explicit answer from every
 * endpoint completes the verdict. The same holds while any explicit answer is transient.
 *
 * The stored rejection is [latest]'s when it is one, then [previous] (a rejection recorded earlier,
 * possibly before the process started), then the first explicit refusal in [outcomes].
 */
internal fun submitRejectionVerdict(
    planEndpoints: Collection<LightWalletEndpoint>,
    outcomes: Map<LightWalletEndpoint, TransactionSubmitResult>,
    latest: TransactionSubmitResult,
    previous: StoredRejection?,
    acceptedEarlier: Boolean = false
): StoredRejection? {
    val everyEndpointAnsweredExplicitly =
        (planEndpoints + outcomes.keys).all { outcomes[it]?.isExplicitServerAnswer() == true }
    return when {
        acceptedEarlier || outcomes.values.any { it.isAcceptance() } -> {
            null
        }

        !everyEndpointAnsweredExplicitly || !outcomes.values.all { it.isRefusalForGood() } -> {
            previous
        }

        else -> {
            listOfNotNull(latest.asStoredRejection())
                .plus(listOfNotNull(previous))
                .plus(outcomes.values.mapNotNull { it.asStoredRejection() })
                .firstOrNull()
        }
    }
}

/**
 * Whether this answer means the endpoint holds the transaction: a [TransactionSubmitResult.Success],
 * or a [TransactionSubmitResult.Failure] saying the server already has it (such as "already exists in
 * mempool" or `txn-already-known`; see [SubmitRejection.isAlreadyHeld]). An acceptance is never
 * part of a rejection, and the plan stores persist it like a success.
 */
internal fun TransactionSubmitResult.isAcceptance(): Boolean =
    when (this) {
        is TransactionSubmitResult.Success -> true
        is TransactionSubmitResult.Failure -> SubmitRejection.isAlreadyHeld(description)
        is TransactionSubmitResult.NotAttempted -> false
    }

/**
 * Whether this is an answer the server itself gave, as opposed to a failure to reach it or to
 * receive its response ([TransactionSubmitResult.Failure.grpcError]), a local refusal by the SDK
 * that sent nothing ([isLocalRefusal]), or no answer at all.
 */
internal fun TransactionSubmitResult.isExplicitServerAnswer(): Boolean =
    when (this) {
        is TransactionSubmitResult.Success -> true
        is TransactionSubmitResult.Failure -> !grpcError && !isLocalRefusal()
        is TransactionSubmitResult.NotAttempted -> false
    }

/**
 * Why this one endpoint refused the transaction for good, or `null` when its failure is transient
 * or inconclusive. One endpoint's answer, not the SDK's verdict over every endpoint.
 */
internal val TransactionSubmitResult.Failure.serverRefusal: SubmitRejection?
    get() = SubmitRejection.classify(grpcError, code, description)

/**
 * The [SubmitRejection] a stored rejection is. A rejection is stored only for an explicit refusal
 * for good, so this is `null` only for a malformed entry.
 */
internal fun StoredRejection.toSubmitRejection(): SubmitRejection? =
    SubmitRejection.classify(grpcError = false, code = code, description = reason)

private fun TransactionSubmitResult.asStoredRejection(): StoredRejection? =
    (this as? TransactionSubmitResult.Failure)
        ?.takeIf { it.serverRefusal != null }
        ?.let { StoredRejection(it.code, it.description.orEmpty()) }

private fun TransactionSubmitResult.isRefusalForGood(): Boolean =
    (this as? TransactionSubmitResult.Failure)?.serverRefusal != null
