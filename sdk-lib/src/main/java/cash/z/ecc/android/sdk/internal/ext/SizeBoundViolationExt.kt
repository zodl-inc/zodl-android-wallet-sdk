package cash.z.ecc.android.sdk.internal.ext

import cash.z.ecc.android.sdk.internal.jni.ProposalSizeBoundException
import cash.z.ecc.android.sdk.model.SizeBoundViolation
import cash.z.ecc.android.sdk.model.TransactionPool

/** The [SizeBoundViolation.Dimension] of each dimension name the backend reports. */
private val DIMENSIONS =
    mapOf(
        ProposalSizeBoundException.SHIELDED_ACTIONS to SizeBoundViolation.Dimension.SHIELDED_ACTIONS,
        ProposalSizeBoundException.POOL_ACTIONS to SizeBoundViolation.Dimension.POOL_ACTIONS,
        ProposalSizeBoundException.TRANSPARENT_INPUTS to SizeBoundViolation.Dimension.TRANSPARENT_INPUTS,
        ProposalSizeBoundException.TRANSPARENT_OUTPUTS to SizeBoundViolation.Dimension.TRANSPARENT_OUTPUTS,
        ProposalSizeBoundException.SHIELDED_PAYMENT_OUTPUTS to SizeBoundViolation.Dimension.SHIELDED_PAYMENT_OUTPUTS
    )

/**
 * The [SizeBoundViolation] the backend reported, or `null` when its dimension or pool is not one
 * this SDK knows (a newer backend than the Kotlin layer), in which case the failure is still a
 * proposal too large, only without the exceeded cap.
 */
internal fun ProposalSizeBoundException.toViolation(): SizeBoundViolation? {
    val dimension = DIMENSIONS[dimension]
    val pool = pool?.let { name -> TransactionPool.entries.firstOrNull { it.name == name } }
    return if (dimension != null && (this.pool == null || pool != null)) {
        SizeBoundViolation(dimension = dimension, pool = pool, count = count, limit = limit)
    } else {
        null
    }
}
