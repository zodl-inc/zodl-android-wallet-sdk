package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import cash.z.ecc.android.sdk.ledger.LedgerAppVersion
import cash.z.ecc.android.sdk.ledger.LedgerDeviceIdentity
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A scripted stand-in for the Ledger engine. Commands are one byte naming the command; replies are
 * a one-byte status followed by a payload, interpreted the way the engine interprets a device's
 * status word.
 */
internal object FakeLedgerProtocol {
    const val CMD_VERSION: Byte = 0x10
    const val CMD_IDENTITY: Byte = 0x11
    const val CMD_ADDRESS: Byte = 0x12
    const val CMD_VK: Byte = 0x13
    const val CMD_VK_CONTINUE: Byte = 0x14
    const val CMD_STREAM: Byte = 0x15
    const val CMD_REVIEW: Byte = 0x16
    const val CMD_SIGN: Byte = 0x17

    const val OK: Byte = 0x00
    const val NOT_ACCEPTED: Byte = 0x01
    const val DENY: Byte = 0x02
    const val BAD_STATE: Byte = 0x03

    val NORMAL_TIMEOUT = 5.seconds

    fun ok(vararg payload: Byte) = byteArrayOf(OK, *payload)

    fun status(code: Byte) = byteArrayOf(code)

    fun identity(tag: Char) = "tpk0-" + tag.toString().repeat(64)

    /** Throws what the engine throws for a refusal, or returns the payload of an `OK` reply. */
    fun payload(reply: ByteArray): ByteArray =
        when (reply[0]) {
            OK -> {
                reply.copyOfRange(1, reply.size)
            }

            NOT_ACCEPTED -> {
                throw CommandNotAcceptedException()
            }

            DENY -> {
                throw LedgerException.UserRejected(isRestartable = true)
            }

            BAD_STATE -> {
                throw LedgerException.DeviceRefused(
                    statusWord = 0xB007,
                    isTransient = true,
                    isRestartable = true,
                    reason = null
                )
            }

            else -> {
                throw LedgerException.MalformedReply(reason = null)
            }
        }
}

@Suppress("SwallowedException")
internal class FakeLedgerBackend(
    /** How many continuation replies the viewing-key export takes after its first reply. */
    private val vkContinuations: Int = 1,
    /** The commands the signing session streams before the review packet. */
    private val streamPackets: Int = 2,
    /** The tag of the device identity the exported viewing key derives. */
    private val ufvkIdentityTag: Char = 'a'
) : TypesafeLedgerBackend {
    var sessionsClosed = 0
    var exportsClosed = 0
    var identityChecks = 0

    override val policy =
        LedgerPolicy(
            cmdNotAcceptedRetryBudget = 3,
            cmdNotAcceptedBackoff = 1.milliseconds,
            normalTimeout = FakeLedgerProtocol.NORMAL_TIMEOUT
        )

    override fun firmwareVersionApdu() = byteArrayOf(FakeLedgerProtocol.CMD_VERSION)

    override fun parseAppVersion(reply: ByteArray): LedgerAppVersion {
        val payload = FakeLedgerProtocol.payload(reply)
        return LedgerAppVersion(3, 9, 3, supportsPczt = payload[0] == 1.toByte())
    }

    override fun deviceIdentityApdu(network: ZcashNetwork) = byteArrayOf(FakeLedgerProtocol.CMD_IDENTITY)

    override fun parseDeviceIdentity(reply: ByteArray) =
        LedgerDeviceIdentity(FakeLedgerProtocol.identity(FakeLedgerProtocol.payload(reply)[0].toInt().toChar()))

    override fun deviceIdentity(encoding: String) = LedgerDeviceIdentity(encoding)

    override fun checkUfvkDeviceIdentity(
        network: ZcashNetwork,
        ufvk: UnifiedFullViewingKey,
        deviceIdentity: LedgerDeviceIdentity
    ) {
        identityChecks++
        if (deviceIdentity.encoding != FakeLedgerProtocol.identity(ufvkIdentityTag)) {
            throw LedgerException.DeviceMismatch()
        }
    }

    /** `utest1-` followed by the key, or [LedgerException.InvalidInput] for an empty key. */
    override fun expectedUnifiedAddress(
        ufvk: UnifiedFullViewingKey,
        network: ZcashNetwork
    ): String =
        if (ufvk.encoding.isEmpty()) {
            throw LedgerException.InvalidInput(reason = "empty key")
        } else {
            "utest1-" + ufvk.encoding
        }

    override fun unifiedAddressApdu(
        network: ZcashNetwork,
        zip32AccountIndex: Zip32AccountIndex,
        transparentAddressIndex: Long,
        display: Boolean
    ) = byteArrayOf(FakeLedgerProtocol.CMD_ADDRESS)

    override fun parseUnifiedAddress(
        reply: ByteArray,
        network: ZcashNetwork
    ): String = String(FakeLedgerProtocol.payload(reply))

    override fun newUfvkExport(
        network: ZcashNetwork,
        zip32AccountIndex: Zip32AccountIndex
    ): LedgerUfvkExport =
        object : LedgerUfvkExport {
            private var chunks = 0
            private var pending: ByteArray? = null
            private var finished = false

            override fun nextApdu(): ByteArray? {
                if (finished) return null
                val apdu =
                    pending ?: byteArrayOf(
                        if (chunks == 0) FakeLedgerProtocol.CMD_VK else FakeLedgerProtocol.CMD_VK_CONTINUE
                    )
                pending = apdu.copyOf()
                return apdu
            }

            override fun waitsForUser() = chunks == 0

            override fun processResponse(reply: ByteArray): UfvkExportStep =
                try {
                    FakeLedgerProtocol.payload(reply)
                    pending = null
                    chunks++
                    if (chunks > vkContinuations) {
                        finished = true
                        UfvkExportStep.Complete(UnifiedFullViewingKey("uviewtest1fake"))
                    } else {
                        UfvkExportStep.MoreChunks
                    }
                } catch (e: CommandNotAcceptedException) {
                    UfvkExportStep.RetrySameApdu
                } catch (e: LedgerException) {
                    finished = true
                    throw e
                }

            override fun close() {
                exportsClosed++
            }
        }

    override fun bleMtuRequest() = byteArrayOf(0x08, 0, 0, 0, 0)

    override fun parseBleMtuResponse(notification: ByteArray) = notification[5].toInt() and 0xFF

    var deframersClosed = 0

    /** Frames are `[last flag] ‖ up to frameSize - 1 data bytes`. */
    override fun bleFrames(
        apdu: ByteArray,
        frameSize: Int
    ): List<ByteArray> {
        val chunks = apdu.toList().chunked(frameSize - 1)
        return chunks.mapIndexed { index, chunk ->
            byteArrayOf(if (index == chunks.lastIndex) 1 else 0) + chunk.toByteArray()
        }
    }

    override fun newBleDeframer(): LedgerBleDeframer =
        object : LedgerBleDeframer {
            private val buffer = mutableListOf<Byte>()

            override fun push(frame: ByteArray): ByteArray? {
                if (frame.isEmpty() || frame[0] > 1) {
                    throw LedgerException.MalformedReply(reason = "bad frame")
                }
                buffer.addAll(frame.drop(1))
                return if (frame[0] == 1.toByte()) {
                    buffer.toByteArray().also { buffer.clear() }
                } else {
                    null
                }
            }

            override fun reset() = buffer.clear()

            override fun close() {
                deframersClosed++
            }
        }

    override suspend fun newSignSession(
        dataDbFile: File,
        network: ZcashNetwork,
        accountUuid: AccountUuid,
        pczt: Pczt,
        deviceIdentity: LedgerDeviceIdentity,
        zip32AccountIndex: Zip32AccountIndex,
        firmwareVersionReply: ByteArray
    ): LedgerSignSession {
        FakeLedgerProtocol.payload(firmwareVersionReply)
        return FakeSignSession(streamPackets) { sessionsClosed++ }
    }
}

/**
 * Two identification commands, [streamPackets] stream packets, the review packet and one signing
 * command.
 */
@Suppress("SwallowedException", "ReturnCount")
internal class FakeSignSession(
    streamPackets: Int,
    private val onClose: () -> Unit
) : LedgerSignSession {
    private val commands =
        listOf(FakeLedgerProtocol.CMD_VERSION, FakeLedgerProtocol.CMD_IDENTITY) +
            List(streamPackets) { FakeLedgerProtocol.CMD_STREAM } +
            listOf(FakeLedgerProtocol.CMD_REVIEW, FakeLedgerProtocol.CMD_SIGN)
    private var cursor = 0
    private var outstanding = false
    private var failed = false
    private var reviewHandedOut = false

    override val totalCommands = commands.size

    override fun nextStep(): SignStep {
        if (failed || cursor == commands.size) return SignStep.Done
        if (outstanding) return SignStep.AwaitingReply
        outstanding = true
        val command = commands[cursor]
        val announces = command == FakeLedgerProtocol.CMD_REVIEW && !reviewHandedOut
        if (command == FakeLedgerProtocol.CMD_REVIEW) reviewHandedOut = true
        return SignStep.Send(byteArrayOf(command), waitsForUser = reviewHandedOut, announcesReview = announces)
    }

    override fun processResponse(reply: ByteArray): SignStatus {
        outstanding = false
        try {
            FakeLedgerProtocol.payload(reply)
        } catch (e: CommandNotAcceptedException) {
            return SignStatus.RETRY_SAME_APDU
        } catch (e: LedgerException) {
            failed = true
            throw e
        }
        cursor++
        return when {
            cursor == commands.size -> SignStatus.COMPLETE
            commands[cursor] == FakeLedgerProtocol.CMD_REVIEW -> SignStatus.AWAITING_USER_ACTION
            else -> SignStatus.MORE_APDUS
        }
    }

    override fun stage(): SignStage =
        when {
            failed -> SignStage.FAILED
            cursor == commands.size -> SignStage.COMPLETE
            cursor < 2 -> SignStage.IDENTIFYING_DEVICE
            commands[cursor] == FakeLedgerProtocol.CMD_SIGN -> SignStage.SIGNING
            else -> SignStage.STREAMING
        }

    override suspend fun finish(): Pczt = Pczt(byteArrayOf(0x5A))

    override fun close() = onClose()
}

/**
 * A transport that answers from a script and records what it was sent.
 */
internal class ScriptedTransport(
    replies: List<ByteArray>,
    /** The exchange index that fails on the wire, and how. */
    private val failAt: Pair<Int, Throwable>? = null,
    /**
     * Whether the failing exchange first waits out its timeout, as a stalled link does, instead of
     * failing at once; one without a timeout waits until cancelled.
     */
    private val stallBeforeFailing: Boolean = false
) : LedgerApduTransport {
    private val replies = ArrayDeque(replies)
    val sent = mutableListOf<ByteArray>()
    val timeouts = mutableListOf<Duration?>()

    /** How many times [close] was called. */
    var closes = 0
        private set
    val closed get() = closes > 0

    override suspend fun exchange(
        apdu: ByteArray,
        timeout: Duration?
    ): ByteArray {
        check(!closed) { "exchange on a closed transport" }
        val index = sent.size
        // Copied: the caller wipes the command once the exchange returns.
        sent.add(apdu.copyOf())
        timeouts.add(timeout)
        failAt?.let { (at, error) -> if (at == index) fail(error, timeout) }
        return replies.removeFirstOrNull() ?: error("no scripted reply for exchange $index")
    }

    private suspend fun fail(
        error: Throwable,
        timeout: Duration?
    ): Nothing {
        if (stallBeforeFailing) {
            if (timeout != null) delay(timeout) else awaitCancellation()
        }
        throw error
    }

    override suspend fun close() {
        closes++
    }
}
