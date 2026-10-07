package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep

/**
 * Thrown across the JNI boundary when a proposal fails because the account's spendable funds
 * cannot cover it (`zcash_client_backend`'s `Error::InsufficientFunds`). Thrown today by
 * `proposeSendMaxTransfer`, when nothing is spendable or the spendable value does not exceed
 * the fee.
 *
 * This is a distinct type so that `sdk-lib` can surface a typed error instead of matching
 * message text. It is constructed from native code, which is why it must be kept: the class
 * name and the `(String)` constructor signature are part of the JNI contract with
 * `proposeSendMaxTransfer` in `lib.rs`, and are never referenced from bytecode.
 */
@Keep
class ProposalInsufficientFundsException(
    message: String
) : RuntimeException(message)
