package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState

/**
 * The Slipstream read path's transaction state, derived by the same rule as the SDK's own
 * `TransactionState.new` (reachable here because slipstream-lib is an sdk-lib friend module), so
 * both synchronizers agree. [requiredConfirmations] comes from `ConfirmationsPolicy.requiredConfirmations`:
 * the ZIP 315 trusted count for a received transaction the wallet trusts, the untrusted count
 * otherwise.
 *
 * [latestHeight] counts confirmations of a mined transaction; [scannedHeight], the engine's fully
 * scanned height, decides whether an unmined one has expired. The live tip alone cannot: after the
 * engine resumes, the tip runs far ahead of the scan, and a send mined in a block not yet scanned
 * would read as Expired.
 *
 * [isExpiredUnmined], the `v_transactions.expired_unmined` flag, is not trusted on its own: it is
 * computed against the highest scanned block, which the engine reaches before it fills older
 * ranges, so it can be true for a send whose mining block is still unscanned. An unmined
 * transaction is Expired only once [scannedHeight] has reached its known, non-zero expiry height,
 * whatever the flag says; an unknown [scannedHeight] or expiry height keeps it Pending.
 */
@Suppress("LongParameterList")
internal fun computeTransactionState(
    latestHeight: BlockHeight?,
    scannedHeight: BlockHeight?,
    minedHeight: BlockHeight?,
    expiryHeight: BlockHeight?,
    isExpiredUnmined: Boolean?,
    requiredConfirmations: Int
): TransactionState =
    TransactionState.new(
        latestBlockHeight = latestHeight,
        minedHeight = minedHeight,
        expiryHeight = expiryHeight,
        isExpiredUnmined = isExpiredUnmined,
        requiredConfirmations = requiredConfirmations,
        scannedHeight = scannedHeight
    )
