package cash.z.ecc.android.sdk.internal.model.ledger

import androidx.annotation.Keep

/**
 * Serves as cross layer (Kotlin, Rust) communication class: every failure of a
 * [cash.z.ecc.android.sdk.internal.jni.LedgerRustBackend] call is thrown as one.
 *
 * Constructed from Rust with the JVM signature `(IIZZLjava/lang/String;)V`; the two change in
 * lockstep (`backend-lib/src/main/rust/ledger/error.rs`).
 *
 * @param kind One of the `KIND_*` constants.
 * @param statusWord The status word the device answered, or `-1` when the failure carries none.
 * @param isTransient Whether the status word describes a device condition that can clear on its
 *        own, as opposed to a function of what was sent.
 * @param isRestartable Whether a fresh operation over the same input could succeed. It never means
 *        "resend the last command".
 * @param reason A human-readable reason. It carries no APDU, reply, PCZT, viewing key, address,
 *        device identity or signature, and is kept out of [message] regardless.
 */
@Keep
class JniLedgerException(
    val kind: Int,
    val statusWord: Int,
    val isTransient: Boolean,
    val isRestartable: Boolean,
    val reason: String?
) : RuntimeException("Ledger operation failed (kind $kind)") {
    companion object {
        const val KIND_INTERNAL = 0
        const val KIND_USER_REJECTED = 1
        const val KIND_WRONG_APP = 2
        const val KIND_APP_TOO_OLD = 3
        const val KIND_DEVICE_MISMATCH = 4
        const val KIND_CAPS_MISMATCH = 5
        const val KIND_DERIVATION_BUDGET_EXHAUSTED = 6
        const val KIND_DEVICE_REFUSED = 7
        const val KIND_TRANSACTION_NOT_SIGNABLE = 8
        const val KIND_MALFORMED_REPLY = 9
        const val KIND_INVALID_INPUT = 10
        const val KIND_CMD_NOT_ACCEPTED = 11

        /** The [statusWord] value of a failure that carries no status word. */
        const val NO_STATUS_WORD = -1
    }
}
