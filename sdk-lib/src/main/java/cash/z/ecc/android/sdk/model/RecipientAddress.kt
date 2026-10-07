package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.ext.masked
import cash.z.ecc.android.sdk.internal.jni.RustBackend

/**
 * A Zcash address that can receive a payment: a unified, Sapling, transparent or TEX address,
 * validated for a specific network by the Rust backend.
 *
 * @property encoding the address in its standard string encoding.
 */
class RecipientAddress private constructor(
    val encoding: String,
    val network: ZcashNetwork
) {
    override fun equals(other: Any?): Boolean =
        other is RecipientAddress && other.encoding == encoding && other.network == network

    override fun hashCode(): Int = 31 * encoding.hashCode() + network.hashCode()

    /** Masks the address, so that it never reaches logs in full. */
    override fun toString() = "RecipientAddress(${encoding.masked()})"

    companion object {
        /**
         * Validates [encoding] as an address on [network].
         *
         * @throws IllegalArgumentException if it is not a valid address for [network].
         */
        suspend fun new(
            encoding: String,
            network: ZcashNetwork
        ): RecipientAddress {
            RustBackend.loadLibrary()
            require(RustBackend.isValidRecipientAddress(encoding, network.id)) {
                "Not a valid ${network.networkName} address"
            }
            return RecipientAddress(encoding, network)
        }
    }
}
