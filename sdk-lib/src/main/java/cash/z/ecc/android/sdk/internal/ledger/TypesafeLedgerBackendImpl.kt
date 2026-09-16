package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.LedgerBackend
import cash.z.ecc.android.sdk.internal.jni.LedgerRustBackend
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerException
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerSignStep
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerUfvkStep
import cash.z.ecc.android.sdk.ledger.LedgerAppVersion
import cash.z.ecc.android.sdk.ledger.LedgerDeviceIdentity
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * [TypesafeLedgerBackend] over the JNI [LedgerBackend]. This is where semantic types are reduced to
 * the raw values the JNI boundary takes, and where [JniLedgerException] becomes [LedgerException].
 */
@Suppress("TooManyFunctions")
internal class TypesafeLedgerBackendImpl(
    private val backend: LedgerBackend
) : TypesafeLedgerBackend {
    override val policy: LedgerPolicy by lazy {
        ledgerCall {
            backend.policy().let {
                LedgerPolicy(
                    cmdNotAcceptedRetryBudget = it.cmdNotAcceptedRetryBudget,
                    cmdNotAcceptedBackoff = it.cmdNotAcceptedBackoffMillis.milliseconds,
                    normalTimeout = it.normalTimeoutMillis.milliseconds
                )
            }
        }
    }

    override fun firmwareVersionApdu(): ByteArray = ledgerCall { backend.firmwareVersionApdu() }

    override fun parseAppVersion(reply: ByteArray): LedgerAppVersion =
        ledgerCall {
            backend.parseFirmwareVersion(reply).let {
                LedgerAppVersion(
                    major = it.major,
                    minor = it.minor,
                    patch = it.patch,
                    supportsPczt = it.supportsPczt
                )
            }
        }

    override fun deviceIdentityApdu(network: ZcashNetwork): ByteArray =
        ledgerCall { backend.deviceIdentityApdu(network.id) }

    override fun parseDeviceIdentity(reply: ByteArray): LedgerDeviceIdentity =
        ledgerCall { LedgerDeviceIdentity(backend.parseDeviceIdentity(reply)) }

    override fun deviceIdentity(encoding: String): LedgerDeviceIdentity =
        if (ledgerCall { backend.isValidDeviceIdentity(encoding) }) {
            LedgerDeviceIdentity(encoding)
        } else {
            throw LedgerException.InvalidInput(reason = "the value is not a Ledger device identity")
        }

    override fun unifiedAddressApdu(
        network: ZcashNetwork,
        zip32AccountIndex: Zip32AccountIndex,
        transparentAddressIndex: Long,
        display: Boolean
    ): ByteArray =
        ledgerCall {
            backend.unifiedAddressApdu(network.id, zip32AccountIndex.index, transparentAddressIndex, display)
        }

    override fun parseUnifiedAddress(
        reply: ByteArray,
        network: ZcashNetwork
    ): String = ledgerCall { backend.parseUnifiedAddress(reply, network.id) }

    override fun newUfvkExport(
        network: ZcashNetwork,
        zip32AccountIndex: Zip32AccountIndex
    ): LedgerUfvkExport =
        UfvkExport(backend, ledgerCall { backend.ufvkExchangeNew(network.id, zip32AccountIndex.index) })

    override fun bleMtuRequest(): ByteArray = ledgerCall { backend.bleMtuRequest() }

    override fun parseBleMtuResponse(notification: ByteArray): Int =
        ledgerCall { backend.parseBleMtuResponse(notification) }

    override fun bleFrames(
        apdu: ByteArray,
        frameSize: Int
    ): List<ByteArray> = ledgerCall { backend.bleFrames(apdu, frameSize).asList() }

    override fun newBleDeframer(): LedgerBleDeframer = BleDeframer(backend, ledgerCall { backend.bleDeframerNew() })

    override suspend fun newSignSession(
        dataDbFile: File,
        network: ZcashNetwork,
        accountUuid: AccountUuid,
        pczt: Pczt,
        deviceIdentity: LedgerDeviceIdentity,
        zip32AccountIndex: Zip32AccountIndex,
        firmwareVersionReply: ByteArray
    ): LedgerSignSession {
        val handle =
            ledgerSuspendCall {
                backend.signSessionNew(
                    dbDataPath = dataDbFile.absolutePath,
                    networkId = network.id,
                    accountUuid = accountUuid.value,
                    pczt = pczt.toByteArray(),
                    deviceIdentity = deviceIdentity.encoding,
                    zip32AccountIndex = zip32AccountIndex.index,
                    firmwareVersionReply = firmwareVersionReply
                )
            }
        return SignSession(backend, handle)
    }

    private class UfvkExport(
        private val backend: LedgerBackend,
        private val handle: Long
    ) : LedgerUfvkExport {
        private val closed = AtomicBoolean(false)

        override fun nextApdu(): ByteArray? = ledgerCall { backend.ufvkExchangeNextApdu(handle) }

        override fun waitsForUser(): Boolean = ledgerCall { backend.ufvkExchangeWaitsForUser(handle) }

        override fun processResponse(reply: ByteArray): UfvkExportStep =
            ledgerCall {
                val step = backend.ufvkExchangeProcessResponse(handle, reply)
                when (step.status) {
                    JniLedgerUfvkStep.STATUS_MORE_CHUNKS -> {
                        UfvkExportStep.MoreChunks
                    }

                    JniLedgerUfvkStep.STATUS_RETRY_SAME_APDU -> {
                        UfvkExportStep.RetrySameApdu
                    }

                    JniLedgerUfvkStep.STATUS_COMPLETE -> {
                        UfvkExportStep.Complete(
                            UnifiedFullViewingKey(
                                step.ufvk ?: throw LedgerException.Internal(null)
                            )
                        )
                    }

                    else -> {
                        throw LedgerException.Internal(null)
                    }
                }
            }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                ledgerCall { backend.ufvkExchangeFree(handle) }
            }
        }
    }

    private class BleDeframer(
        private val backend: LedgerBackend,
        private val handle: Long
    ) : LedgerBleDeframer {
        private val closed = AtomicBoolean(false)

        override fun push(frame: ByteArray): ByteArray? = ledgerCall { backend.bleDeframerPush(handle, frame) }

        override fun reset() = ledgerCall { backend.bleDeframerReset(handle) }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                ledgerCall { backend.bleDeframerFree(handle) }
            }
        }
    }

    private class SignSession(
        private val backend: LedgerBackend,
        private val handle: Long
    ) : LedgerSignSession {
        private val closed = AtomicBoolean(false)

        override val totalCommands: Int = ledgerCall { backend.signSessionTotalCommands(handle) }

        override fun nextStep(): SignStep =
            ledgerCall {
                val step = backend.signSessionNextStep(handle)
                when (step.kind) {
                    JniLedgerSignStep.KIND_SEND -> {
                        SignStep.Send(
                            apdu = step.apdu ?: throw LedgerException.Internal(null),
                            waitsForUser = step.waitsForUser,
                            announcesReview = step.announcesReview
                        )
                    }

                    JniLedgerSignStep.KIND_AWAITING_REPLY -> {
                        SignStep.AwaitingReply
                    }

                    JniLedgerSignStep.KIND_DONE -> {
                        SignStep.Done
                    }

                    else -> {
                        throw LedgerException.Internal(null)
                    }
                }
            }

        override fun processResponse(reply: ByteArray): SignStatus =
            ledgerCall {
                when (backend.signSessionProcessResponse(handle, reply)) {
                    LedgerBackend.STATUS_MORE_APDUS -> SignStatus.MORE_APDUS
                    LedgerBackend.STATUS_RETRY_SAME_APDU -> SignStatus.RETRY_SAME_APDU
                    LedgerBackend.STATUS_AWAITING_USER_ACTION -> SignStatus.AWAITING_USER_ACTION
                    LedgerBackend.STATUS_COMPLETE -> SignStatus.COMPLETE
                    else -> throw LedgerException.Internal(null)
                }
            }

        override fun stage(): SignStage =
            ledgerCall {
                when (backend.signSessionStage(handle)) {
                    LedgerBackend.STAGE_IDENTIFYING_DEVICE -> SignStage.IDENTIFYING_DEVICE

                    LedgerBackend.STAGE_STREAMING -> SignStage.STREAMING

                    LedgerBackend.STAGE_SIGNING_ORCHARD,
                    LedgerBackend.STAGE_SIGNING_IRONWOOD,
                    LedgerBackend.STAGE_SIGNING_TRANSPARENT -> SignStage.SIGNING

                    LedgerBackend.STAGE_COMPLETE -> SignStage.COMPLETE

                    LedgerBackend.STAGE_FAILED -> SignStage.FAILED

                    else -> throw LedgerException.Internal(null)
                }
            }

        override suspend fun finish(): Pczt = Pczt(ledgerSuspendCall { backend.signSessionFinish(handle) })

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                ledgerCall { backend.signSessionFree(handle) }
            }
        }
    }

    companion object {
        /**
         * Loads the native library and returns a backend over it.
         */
        suspend fun new(): TypesafeLedgerBackendImpl = TypesafeLedgerBackendImpl(LedgerRustBackend.new())
    }
}

/**
 * Runs a JNI call, turning its failures into [LedgerException] (or [CommandNotAcceptedException]).
 *
 * A failure that did not come from the Ledger engine's own classifier — a JNI or runtime error — is
 * [LedgerException.Internal], with the original kept as the cause rather than spliced into the
 * message.
 */
@Suppress("TooGenericExceptionCaught")
private inline fun <T> ledgerCall(block: () -> T): T =
    try {
        block()
    } catch (e: JniLedgerException) {
        throw e.toLedgerException()
    } catch (e: LedgerException) {
        throw e
    } catch (e: RuntimeException) {
        throw LedgerException.Internal(e)
    }

@Suppress("TooGenericExceptionCaught")
private suspend inline fun <T> ledgerSuspendCall(crossinline block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: JniLedgerException) {
        throw e.toLedgerException()
    } catch (e: LedgerException) {
        throw e
    } catch (e: RuntimeException) {
        throw LedgerException.Internal(e)
    }

/**
 * Maps the engine's structured failure onto the public exception hierarchy.
 */
internal fun JniLedgerException.toLedgerException(): Exception {
    val status = statusWord.takeIf { it != JniLedgerException.NO_STATUS_WORD }
    return when (kind) {
        JniLedgerException.KIND_USER_REJECTED -> {
            LedgerException.UserRejected(isRestartable)
        }

        JniLedgerException.KIND_WRONG_APP -> {
            LedgerException.WrongApp(status, reason)
        }

        JniLedgerException.KIND_APP_TOO_OLD -> {
            LedgerException.AppTooOld(reason)
        }

        JniLedgerException.KIND_DEVICE_MISMATCH -> {
            LedgerException.DeviceMismatch()
        }

        JniLedgerException.KIND_CAPS_MISMATCH -> {
            LedgerException.CapsMismatch(reason)
        }

        JniLedgerException.KIND_DERIVATION_BUDGET_EXHAUSTED -> {
            LedgerException.DerivationBudgetExhausted()
        }

        JniLedgerException.KIND_DEVICE_REFUSED -> {
            LedgerException.DeviceRefused(
                statusWord = status ?: 0,
                isTransient = isTransient,
                isRestartable = isRestartable,
                reason = reason
            )
        }

        JniLedgerException.KIND_TRANSACTION_NOT_SIGNABLE -> {
            LedgerException.TransactionNotSignable(reason)
        }

        JniLedgerException.KIND_MALFORMED_REPLY -> {
            LedgerException.MalformedReply(reason)
        }

        JniLedgerException.KIND_INVALID_INPUT -> {
            LedgerException.InvalidInput(reason)
        }

        JniLedgerException.KIND_CMD_NOT_ACCEPTED -> {
            CommandNotAcceptedException()
        }

        else -> {
            LedgerException.Internal(this)
        }
    }
}
