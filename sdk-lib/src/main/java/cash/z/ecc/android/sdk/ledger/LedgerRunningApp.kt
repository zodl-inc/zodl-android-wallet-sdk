package cash.z.ecc.android.sdk.ledger

/**
 * The app a Ledger device is running, as its dashboard query reports it.
 *
 * @param name The app's name: `Zcash` for the Zcash app, `BOLOS` (or a truncated form of it on
 *        some firmware) for the device's dashboard.
 * @param version The app's version, as the device reports it.
 */
@ConsistentCopyVisibility
data class LedgerRunningApp internal constructor(
    val name: String,
    val version: String
) {
    /**
     * Whether the device is on its dashboard, running no app.
     */
    val isDashboard: Boolean
        get() = name in DASHBOARD_NAMES

    /**
     * Whether the device is running the Zcash app.
     */
    val isZcash: Boolean
        get() = name == ZCASH_APP_NAME

    internal companion object {
        /** The name the Zcash app reports, and the name the device opens it by. */
        const val ZCASH_APP_NAME = "Zcash"

        /** The names the device's dashboard reports, across firmware versions. */
        private val DASHBOARD_NAMES = setOf("BOLOS", "OLOS", "OLOS\u0000")
    }
}
