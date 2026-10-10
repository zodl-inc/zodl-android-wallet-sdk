package cash.z.ecc.android.sdk.ledger

import cash.z.ecc.android.sdk.model.Zip32AccountIndex

/**
 * What a wallet persists next to an account imported from a Ledger device, and passes back when
 * signing for it: which device the account belongs to, and which ZIP 32 account on that device.
 *
 * Persist [LedgerDeviceIdentity.encoding] and [Zip32AccountIndex.index]; restore them with
 * [LedgerDeviceIdentity.new] and [Zip32AccountIndex.new]. The identity is per network, so a binding
 * is only meaningful for the network it was paired on. See [LedgerDeviceIdentity] for how to store it.
 *
 * @param deviceIdentity The paired device.
 * @param zip32AccountIndex The account's ZIP 32 index on that device.
 */
data class LedgerAccountBinding(
    val deviceIdentity: LedgerDeviceIdentity,
    val zip32AccountIndex: Zip32AccountIndex
)
