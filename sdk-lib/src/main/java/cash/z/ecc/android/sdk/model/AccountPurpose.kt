package cash.z.ecc.android.sdk.model

/**
 * An enumeration used to control what information is tracked by the wallet for notes received by a given account
 */
sealed class AccountPurpose {
    /**
     * Constant value that uniquely identifies this enum across FFI
     */
    abstract val value: Int

    /**
     * For spending accounts, the wallet will track information needed to spend received notes
     *
     * The seed fingerprint and account index together are the account's ZIP 32 derivation, which the
     * wallet stamps on the PCZTs it creates so that an external signer can recognize its keys. Pass
     * both or neither: an account whose signer cannot name its seed — a Ledger device, whose
     * firmware never reveals one — is imported with neither, and the SDK supplies the derivation
     * paths its signer needs.
     *
     * @param seedFingerprint The [ZIP 32 seed fingerprint](https://zips.z.cash/zip-0032#seed-fingerprints), or
     *        `null` if the account's derivation is unknown
     * @param zip32AccountIndex The ZIP 32 account-level component of the HD derivation path at which to derive the
     *        account's keys, or `null` if the account's derivation is unknown
     * @throws IllegalArgumentException if exactly one of the two is `null`
     */
    data class Spending(
        val seedFingerprint: ByteArray? = null,
        val zip32AccountIndex: Zip32AccountIndex? = null,
    ) : AccountPurpose() {
        init {
            require((seedFingerprint == null) == (zip32AccountIndex == null)) {
                "A seed fingerprint and a ZIP 32 account index are passed together or not at all"
            }
        }

        override val value = 0
    }

    /**
     * For view-only accounts, the wallet will not track spend information
     */
    data object ViewOnly : AccountPurpose() {
        override val value = 1
    }
}
