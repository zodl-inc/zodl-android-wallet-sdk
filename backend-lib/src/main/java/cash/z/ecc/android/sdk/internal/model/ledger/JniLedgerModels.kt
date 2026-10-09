package cash.z.ecc.android.sdk.internal.model.ledger

import androidx.annotation.Keep

/**
 * Serves as cross layer (Kotlin, Rust) communication class. JVM signature `(IIIZ)V`.
 *
 * @param major The Zcash app's major version.
 * @param minor The Zcash app's minor version.
 * @param patch The Zcash app's patch version.
 * @param supportsPczt Whether this app version has the PCZT signing instructions.
 */
@Keep
class JniLedgerAppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val supportsPczt: Boolean
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class. JVM signature `(ILjava/lang/String;)V`.
 *
 * @param status One of the `STATUS_*` constants.
 * @param ufvk The exported unified full viewing key, when [status] is [STATUS_COMPLETE].
 */
@Keep
class JniLedgerUfvkStep(
    val status: Int,
    val ufvk: String?
) {
    // Override to prevent leaking the key to logs
    override fun toString() = "JniLedgerUfvkStep(status=$status)"

    companion object {
        const val STATUS_MORE_CHUNKS = 0
        const val STATUS_RETRY_SAME_APDU = 1
        const val STATUS_COMPLETE = 2
    }
}

/**
 * Serves as cross layer (Kotlin, Rust) communication class. JVM signature `(I[BZZ)V`.
 *
 * @param kind One of the `KIND_*` constants.
 * @param apdu The command to send, when [kind] is [KIND_SEND].
 * @param waitsForUser Whether the reply to [apdu] may wait on the user, so no timeout applies.
 * @param announcesReview Whether [apdu] is the packet that puts the transaction review on the
 *        device's screen. True once per session; a resend after `0x6901` is the same review.
 */
@Keep
class JniLedgerSignStep(
    val kind: Int,
    val apdu: ByteArray?,
    val waitsForUser: Boolean,
    val announcesReview: Boolean
) {
    // Override to prevent leaking the command, which carries transaction secrets, to logs
    override fun toString() = "JniLedgerSignStep(kind=$kind, apdu size=${apdu?.size})"

    companion object {
        const val KIND_SEND = 0
        const val KIND_AWAITING_REPLY = 1
        const val KIND_DONE = 2
    }
}

/**
 * Serves as cross layer (Kotlin, Rust) communication class. JVM signature `(IJJ)V`.
 *
 * @param cmdNotAcceptedRetryBudget How many consecutive `0x6901` refusals one command tolerates.
 * @param cmdNotAcceptedBackoffMillis The pause before each resend of a refused command.
 * @param normalTimeoutMillis The wait for a reply the device produces without the user.
 */
@Keep
class JniLedgerPolicy(
    val cmdNotAcceptedRetryBudget: Int,
    val cmdNotAcceptedBackoffMillis: Long,
    val normalTimeoutMillis: Long
)
