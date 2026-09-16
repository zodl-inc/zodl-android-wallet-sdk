package cash.z.ecc.android.sdk.ledger

/**
 * The version of the Zcash app running on a Ledger device.
 *
 * @param major The major version.
 * @param minor The minor version.
 * @param patch The patch version.
 * @param supportsPczt Whether this version can sign PCZTs (Zcash app 3.6.0 or later). Signing a
 *        version 6 transaction additionally needs an app with Ironwood support, which the signing
 *        session checks.
 */
@ConsistentCopyVisibility
data class LedgerAppVersion internal constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val supportsPczt: Boolean
)
