package cash.z.ecc.android.sdk.ledger

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountPurpose
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey

/**
 * The result of [LedgerDevice.pairAccount].
 *
 * Import the account with `Synchronizer.importAccountByUfvk(pairing.accountImportSetup(name, birthday))`
 * and persist [binding] next to the imported account.
 *
 * @param ufvk The account's unified full viewing key, as the device exported it.
 * @param binding The device and account index to sign with.
 * @param appVersion The Zcash app version the device ran when it was paired.
 */
@ConsistentCopyVisibility
data class LedgerAccountPairing internal constructor(
    val ufvk: UnifiedFullViewingKey,
    val binding: LedgerAccountBinding,
    val appVersion: LedgerAppVersion
) {
    /**
     * The setup that imports this account: [ufvk] as a spending account with no ZIP 32 derivation
     * (the device never reveals its seed fingerprint), under [Account.LEDGER_KEY_SOURCE].
     *
     * @param accountName The account's name, as the user sees it.
     * @param birthday The height to scan from; see [AccountImportSetup.birthday].
     */
    fun accountImportSetup(
        accountName: String,
        birthday: BlockHeight?
    ): AccountImportSetup =
        AccountImportSetup(
            accountName = accountName,
            keySource = Account.LEDGER_KEY_SOURCE,
            purpose = AccountPurpose.Spending(),
            ufvk = ufvk,
            birthday = birthday
        )

    // Override to prevent leaking the viewing key to logs
    override fun toString() = "LedgerAccountPairing(ufvk=***, binding=$binding, appVersion=$appVersion)"
}
