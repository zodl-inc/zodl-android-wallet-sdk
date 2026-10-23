package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.BlockSpacing
import cash.z.ecc.android.sdk.model.ZcashNetwork

private const val RAW_TX_HEADER_BYTES = 4
private const val RAW_TX_VERSION_GROUP_BYTES = 4
private const val RAW_TX_BRANCH_ID_BYTES = 4
private const val RAW_TX_BRANCH_ID_OFFSET = RAW_TX_HEADER_BYTES + RAW_TX_VERSION_GROUP_BYTES
private const val RAW_TX_PREFIX_BYTES = RAW_TX_BRANCH_ID_OFFSET + RAW_TX_BRANCH_ID_BYTES
private const val OVERWINTERED_FLAG = 0x8000_0000L
private const val BYTE_MASK = 0xFFL
private const val BITS_PER_BYTE = 8

/**
 * The consensus branch ID a serialized v5+ transaction was built for, or null when [rawTx] is too
 * short or is not an Overwinter-or-later transaction. The header, version group ID and branch ID
 * are the first twelve bytes (ZIP 225, ZIP 230), all little-endian.
 */
internal fun rawTransactionBranchId(rawTx: ByteArray): Long? {
    val isOverwintered = rawTx.size >= RAW_TX_PREFIX_BYTES && littleEndianUInt(rawTx, 0) and OVERWINTERED_FLAG != 0L
    return if (isOverwintered) littleEndianUInt(rawTx, RAW_TX_BRANCH_ID_OFFSET) else null
}

private fun littleEndianUInt(
    bytes: ByteArray,
    offset: Int
): Long =
    (0 until RAW_TX_BRANCH_ID_BYTES).fold(0L) { acc, i ->
        acc or ((bytes[offset + i].toLong() and BYTE_MASK) shl (BITS_PER_BYTE * i))
    }

/**
 * Whether a migration transaction signed before the NU7 activation height would be rejected if it
 * were submitted for inclusion at [targetHeight] (or the reverse). NU7 changes the consensus branch
 * ID, so a transaction built for one side of the activation height is invalid on the other, and it
 * stays invalid whatever its expiry height says. A migration schedule is signed up front, so one
 * that was started before NU7 and is still running at activation contains such transactions.
 * Submitting one only reveals it to the network without any chance of it being mined.
 *
 * Only the NU7 boundary is checked, which is the only boundary a running schedule can cross. It
 * returns false when the transaction's branch ID cannot be read or the network has no known NU7
 * height, so an unreadable transaction never blocks a send.
 */
internal fun isBuiltForOtherConsensusEra(
    rawTx: ByteArray,
    network: ZcashNetwork,
    targetHeight: BlockHeight,
): Boolean {
    val nu7Height = BlockSpacing.nu7ActivationHeight(network)
    val branchId = rawTransactionBranchId(rawTx)
    return if (nu7Height == null || branchId == null) {
        false
    } else {
        (branchId == ConsensusBranchId.NU7.id) != (targetHeight.value >= nu7Height.value)
    }
}
