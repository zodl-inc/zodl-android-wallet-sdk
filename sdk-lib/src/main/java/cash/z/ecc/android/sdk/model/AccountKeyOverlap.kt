package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_ALREADY_IMPORTED
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_CONFLICTING
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_WOULD_UPGRADE
import cash.z.ecc.android.sdk.internal.model.JniAccountKeyOverlap

/**
 * An existing wallet account that shares at least one viewing key component with a
 * [UnifiedFullViewingKey], as returned by `Synchronizer.findAccountSharingKeyComponents`, together
 * with what `Synchronizer.importAccountByUfvk` would do with that key.
 *
 * Two keys share a component when their Orchard, Sapling or transparent P2PKH incoming viewing keys
 * are equal. This is the comparison account import itself makes, so a Sapling component that
 * differs only in its outgoing viewing key is still shared.
 *
 * [toString] does not print any key: [account] redacts its viewing keys.
 *
 * @param account the existing account.
 * @param kind what importing the key would do to [account].
 */
data class AccountKeyOverlap(
    val account: Account,
    val kind: Kind
) {
    /**
     * Whether `Synchronizer.importAccountByUfvk` would fail for the key with
     * [cash.z.ecc.android.sdk.exception.InitializeException.ImportAccountCollisionException]. It
     * succeeds only for [Kind.WOULD_UPGRADE], and then changes [account] instead of adding one.
     */
    val importWouldFail: Boolean
        get() = kind != Kind.WOULD_UPGRADE

    /**
     * What importing a key would do to the existing account it shares a component with.
     */
    enum class Kind {
        /**
         * The account's viewing key already covers every component of the key: it is the same key,
         * or one with fewer components. The import fails with
         * [cash.z.ecc.android.sdk.exception.InitializeException.ImportAccountCollisionException].
         */
        ALREADY_IMPORTED,

        /**
         * The key has every incoming viewing key of the account and something the account's viewing
         * key does not cover: an additional component, or a component whose outgoing viewing key
         * differs. The import succeeds without adding an account: it replaces the existing account's
         * viewing key with this one, widening what the account can see, and returns that account.
         */
        WOULD_UPGRADE,

        /**
         * The key shares a component with the account but lacks or changes another of its
         * components. The import fails with
         * [cash.z.ecc.android.sdk.exception.InitializeException.ImportAccountCollisionException].
         */
        CONFLICTING
    }

    companion object {
        /**
         * @throws IllegalArgumentException if [jniOverlap] carries an unknown kind.
         */
        fun new(jniOverlap: JniAccountKeyOverlap): AccountKeyOverlap =
            AccountKeyOverlap(
                account = Account.new(jniOverlap.account),
                kind =
                    when (jniOverlap.kind) {
                        JNI_ACCOUNT_KEY_OVERLAP_ALREADY_IMPORTED -> Kind.ALREADY_IMPORTED
                        JNI_ACCOUNT_KEY_OVERLAP_WOULD_UPGRADE -> Kind.WOULD_UPGRADE
                        JNI_ACCOUNT_KEY_OVERLAP_CONFLICTING -> Kind.CONFLICTING
                        else -> throw IllegalArgumentException("Unknown account key overlap kind ${jniOverlap.kind}")
                    }
            )
    }
}
