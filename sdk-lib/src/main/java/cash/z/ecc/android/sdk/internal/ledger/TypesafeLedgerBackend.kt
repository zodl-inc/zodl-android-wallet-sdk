package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.ledger.LedgerAppVersion
import cash.z.ecc.android.sdk.ledger.LedgerDeviceIdentity
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import java.io.File
import kotlin.time.Duration

/**
 * The Ledger engine behind semantic types. Every failure is a [LedgerException], except the
 * device's `0x6901` refusal of a one-shot command, which is [CommandNotAcceptedException] for the
 * caller to resend.
 */
@Suppress("TooManyFunctions")
internal interface TypesafeLedgerBackend {
    val policy: LedgerPolicy

    fun firmwareVersionApdu(): ByteArray

    fun parseAppVersion(reply: ByteArray): LedgerAppVersion

    fun deviceIdentityApdu(network: ZcashNetwork): ByteArray

    fun parseDeviceIdentity(reply: ByteArray): LedgerDeviceIdentity

    fun deviceIdentity(encoding: String): LedgerDeviceIdentity

    fun unifiedAddressApdu(
        network: ZcashNetwork,
        zip32AccountIndex: Zip32AccountIndex,
        transparentAddressIndex: Long,
        display: Boolean
    ): ByteArray

    fun parseUnifiedAddress(
        reply: ByteArray,
        network: ZcashNetwork
    ): String

    fun newUfvkExport(
        network: ZcashNetwork,
        zip32AccountIndex: Zip32AccountIndex
    ): LedgerUfvkExport

    fun bleMtuRequest(): ByteArray

    fun parseBleMtuResponse(notification: ByteArray): Int

    fun bleFrames(
        apdu: ByteArray,
        frameSize: Int
    ): List<ByteArray>

    fun newBleDeframer(): LedgerBleDeframer

    @Suppress("LongParameterList")
    suspend fun newSignSession(
        dataDbFile: File,
        network: ZcashNetwork,
        accountUuid: AccountUuid,
        pczt: Pczt,
        deviceIdentity: LedgerDeviceIdentity,
        zip32AccountIndex: Zip32AccountIndex,
        firmwareVersionReply: ByteArray
    ): LedgerSignSession
}

/**
 * The engine's timing policy.
 *
 * @param cmdNotAcceptedRetryBudget How many consecutive `0x6901` refusals one command tolerates.
 * @param cmdNotAcceptedBackoff The pause before each resend of a refused command.
 * @param normalTimeout The wait for a reply the device produces without the user.
 */
internal data class LedgerPolicy(
    val cmdNotAcceptedRetryBudget: Int,
    val cmdNotAcceptedBackoff: Duration,
    val normalTimeout: Duration
)

/**
 * The device SDK refused a one-shot command before the app saw it (`0x6901`). Not a failure: the
 * same command is resent after [LedgerPolicy.cmdNotAcceptedBackoff].
 */
internal class CommandNotAcceptedException : Exception("The device refused the frame (0x6901)")

/**
 * A unified full viewing key export: one request plus the continuations the key's length needs.
 * Terminal on any error but `0x6901`, which it absorbs; the request that shows the approval screen
 * is never re-issued.
 */
internal interface LedgerUfvkExport : AutoCloseable {
    /** The next command, or `null` once the export has completed or failed. */
    fun nextApdu(): ByteArray?

    /** Whether the reply to [nextApdu]'s command waits on the user approving the export. */
    fun waitsForUser(): Boolean

    fun processResponse(reply: ByteArray): UfvkExportStep
}

internal sealed interface UfvkExportStep {
    data object MoreChunks : UfvkExportStep

    data object RetrySameApdu : UfvkExportStep

    class Complete(
        val ufvk: UnifiedFullViewingKey
    ) : UfvkExportStep {
        // Override to prevent leaking the key to logs
        override fun toString() = "Complete(ufvk=***)"
    }
}

/**
 * Reassembles one BLE reply at a time from its notification frames.
 */
internal interface LedgerBleDeframer : AutoCloseable {
    /** The complete reply on the frame that finishes it, `null` while more frames are expected. */
    fun push(frame: ByteArray): ByteArray?

    fun reset()
}

/**
 * A PCZT signing ceremony against one device.
 */
internal interface LedgerSignSession : AutoCloseable {
    /** Commands a complete ceremony exchanges, `0x6901` resends not counted. */
    val totalCommands: Int

    fun nextStep(): SignStep

    fun processResponse(reply: ByteArray): SignStatus

    fun stage(): SignStage

    /** Applies and verifies the device's signatures. Valid once, after [SignStatus.Complete]. */
    suspend fun finish(): Pczt
}

internal sealed interface SignStep {
    /**
     * @param waitsForUser Whether the reply may wait on the user, so no timeout applies.
     * @param announcesReview Whether this command puts the transaction review on screen.
     */
    class Send(
        val apdu: ByteArray,
        val waitsForUser: Boolean,
        val announcesReview: Boolean
    ) : SignStep {
        // Override to prevent leaking the command, which carries transaction secrets, to logs
        override fun toString() = "Send(apdu size=${apdu.size}, waitsForUser=$waitsForUser)"
    }

    data object AwaitingReply : SignStep

    data object Done : SignStep
}

internal enum class SignStatus {
    MORE_APDUS,
    RETRY_SAME_APDU,
    AWAITING_USER_ACTION,
    COMPLETE
}

internal enum class SignStage {
    IDENTIFYING_DEVICE,
    STREAMING,
    SIGNING,
    COMPLETE,
    FAILED
}
