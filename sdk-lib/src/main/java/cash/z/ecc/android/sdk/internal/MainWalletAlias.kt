package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator

/**
 * Whether [alias] addresses the files of the main wallet, the one under [ZcashSdk.DEFAULT_ALIAS]: that alias itself,
 * also spelled with trailing underscores (a wallet's file names add one `_` to an alias that does not end with one,
 * so `zcash_sdk_` names the main wallet's database), or [DatabaseCoordinator.ALIAS_LEGACY] in any letter case, the
 * name the legacy layout stores the main wallet under.
 *
 * Every API that deletes or opens a helper wallet (such as a gift card's temporary wallet) by alias refuses such an
 * alias before it touches any file, whichever engine it runs on: see [requireNotMainWalletAlias].
 */
internal fun isMainWalletAlias(alias: String): Boolean {
    val base = alias.trimEnd('_')
    return base == ZcashSdk.DEFAULT_ALIAS || base.equals(DatabaseCoordinator.ALIAS_LEGACY, ignoreCase = true)
}

/**
 * Refuses an [alias] that [isMainWalletAlias].
 *
 * @throws IllegalArgumentException with [message] if [alias] addresses the main wallet's files.
 */
internal fun requireNotMainWalletAlias(
    alias: String,
    message: String
) {
    require(!isMainWalletAlias(alias)) { message }
}
