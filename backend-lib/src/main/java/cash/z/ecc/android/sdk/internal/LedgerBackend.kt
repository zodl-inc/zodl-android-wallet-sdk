package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerAppVersion
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerException
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerPolicy
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerSignStep
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerUfvkStep

/**
 * Contract defining the exposed capabilities of the Ledger hardware-wallet engine
 * (`pczt_ledger`) instance.
 *
 * The engine performs no I/O: every function builds a command or absorbs a reply, and the
 * caller moves the bytes. Every failure is thrown as [JniLedgerException].
 *
 * Handles returned by the `*New` functions are opaque, must be freed with the matching `*Free`
 * function, and are serialized per handle on the Rust side.
 */
@Suppress("TooManyFunctions")
interface LedgerBackend {
    fun policy(): JniLedgerPolicy

    fun firmwareVersionApdu(): ByteArray

    fun parseFirmwareVersion(reply: ByteArray): JniLedgerAppVersion

    fun deviceIdentityApdu(networkId: Int): ByteArray

    fun parseDeviceIdentity(reply: ByteArray): String

    fun isValidDeviceIdentity(identity: String): Boolean

    fun unifiedAddressApdu(
        networkId: Int,
        zip32AccountIndex: Long,
        transparentAddressIndex: Long,
        display: Boolean
    ): ByteArray

    fun parseUnifiedAddress(
        reply: ByteArray,
        networkId: Int
    ): String

    fun ufvkExchangeNew(
        networkId: Int,
        zip32AccountIndex: Long
    ): Long

    fun ufvkExchangeNextApdu(handle: Long): ByteArray?

    fun ufvkExchangeWaitsForUser(handle: Long): Boolean

    fun ufvkExchangeProcessResponse(
        handle: Long,
        reply: ByteArray
    ): JniLedgerUfvkStep

    fun ufvkExchangeFree(handle: Long)

    fun bleMtuRequest(): ByteArray

    fun parseBleMtuResponse(notification: ByteArray): Int

    fun bleFrames(
        apdu: ByteArray,
        frameSize: Int
    ): Array<ByteArray>

    fun bleDeframerNew(): Long

    fun bleDeframerPush(
        handle: Long,
        frame: ByteArray
    ): ByteArray?

    fun bleDeframerReset(handle: Long)

    fun bleDeframerFree(handle: Long)

    /**
     * Builds a signing session from the wallet's own account data. Reads the wallet database.
     */
    @Suppress("LongParameterList")
    suspend fun signSessionNew(
        dbDataPath: String,
        networkId: Int,
        accountUuid: ByteArray,
        pczt: ByteArray,
        deviceIdentity: String,
        zip32AccountIndex: Long,
        firmwareVersionReply: ByteArray
    ): Long

    fun signSessionTotalCommands(handle: Long): Int

    fun signSessionNextStep(handle: Long): JniLedgerSignStep

    fun signSessionProcessResponse(
        handle: Long,
        reply: ByteArray
    ): Int

    fun signSessionStage(handle: Long): Int

    fun signSessionIsRestartable(handle: Long): Boolean

    /**
     * Applies the device's signatures, verifying each, and returns the signed PCZT.
     */
    suspend fun signSessionFinish(handle: Long): ByteArray

    fun signSessionFree(handle: Long)

    companion object {
        /** [signSessionProcessResponse] results. */
        const val STATUS_MORE_APDUS = 0
        const val STATUS_RETRY_SAME_APDU = 1
        const val STATUS_AWAITING_USER_ACTION = 2
        const val STATUS_COMPLETE = 3

        /** [signSessionStage] results. */
        const val STAGE_IDENTIFYING_DEVICE = 0
        const val STAGE_STREAMING = 1
        const val STAGE_SIGNING_ORCHARD = 2
        const val STAGE_SIGNING_IRONWOOD = 3
        const val STAGE_SIGNING_TRANSPARENT = 4
        const val STAGE_COMPLETE = 5
        const val STAGE_FAILED = 6
    }
}
