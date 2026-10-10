package cash.z.ecc.android.sdk.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackendImpl

/**
 * A stable name for one Ledger device on one network: `tpk0-` followed by 64 lowercase hex digits.
 *
 * It is a hash of the public key the device derives at `m/44'/coin'/0'/0/0`, so two devices restored
 * from the same seed share it, and one device has a different identity on mainnet and testnet. A
 * signing session refuses to send any transaction data to a device whose identity is not the one
 * the account was paired with.
 *
 * It is a *name*, not an attestation, and it is **privacy-sensitive**: that public key is published
 * on chain the first time the account's first transparent address is spent from, so the identity
 * can be matched to that address. Store it next to the account as you would the address, never log
 * it, and never send it anywhere. [toString] does not print it; [encoding] is the value to persist.
 */
class LedgerDeviceIdentity internal constructor(
    /**
     * The identity's string form, for persistence. Parse it back with [new].
     */
    val encoding: String
) {
    override fun equals(other: Any?): Boolean = other is LedgerDeviceIdentity && other.encoding == encoding

    override fun hashCode(): Int = encoding.hashCode()

    // Override to prevent leaking the identity, which identifies a transparent address, to logs
    override fun toString() = "LedgerDeviceIdentity(***)"

    companion object {
        /**
         * Validates a persisted identity.
         *
         * @throws LedgerException.InvalidInput if [encoding] is not a device identity.
         */
        suspend fun new(encoding: String): LedgerDeviceIdentity =
            TypesafeLedgerBackendImpl.new().deviceIdentity(encoding)
    }
}
