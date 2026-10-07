package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState

/**
 * The Slipstream read path's transaction state, derived by the same rule as the SDK's own
 * `TransactionState.new` (reachable here because slipstream-lib is an sdk-lib friend module), so
 * both synchronizers agree. [requiredConfirmations] comes from `ConfirmationsPolicy.requiredConfirmations`:
 * the ZIP 315 trusted count for a received transaction the wallet trusts, the untrusted count
 * otherwise.
 */
internal fun computeTransactionState(
    latestHeight: BlockHeight?,
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
        requiredConfirmations = requiredConfirmations
    )
