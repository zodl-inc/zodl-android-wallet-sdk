package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_ALREADY_IMPORTED
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_CONFLICTING
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_WOULD_UPGRADE

/**
 * Serves as cross layer (Kotlin, Rust) communication class. It pairs an existing account that shares
 * at least one incoming viewing key item with a candidate UFVK with what importing that UFVK would do
 * to it.
 *
 * @param account the existing account.
 * @param kind one of [JNI_ACCOUNT_KEY_OVERLAP_ALREADY_IMPORTED], [JNI_ACCOUNT_KEY_OVERLAP_WOULD_UPGRADE]
 * or [JNI_ACCOUNT_KEY_OVERLAP_CONFLICTING]; the Kotlin handler layer validates it.
 */
@Keep
class JniAccountKeyOverlap(
    val account: JniAccount,
    val kind: Int
)
