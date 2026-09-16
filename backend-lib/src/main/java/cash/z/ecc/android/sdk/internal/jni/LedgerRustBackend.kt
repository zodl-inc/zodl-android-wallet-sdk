package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.LedgerBackend
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerAppVersion
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerPolicy
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerSignStep
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerUfvkStep
import kotlinx.coroutines.withContext

/**
 * Serves as the JNI boundary between the Kotlin layer and the Ledger engine
 * (`backend-lib/src/main/rust/ledger.rs`). Functions in this class should not be called directly
 * by code outside of the SDK; `sdk-lib`'s `TypesafeLedgerBackendImpl` is the only caller.
 *
 * Every failure is thrown as [cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerException].
 */
@Keep
@Suppress("TooManyFunctions")
class LedgerRustBackend private constructor() : LedgerBackend {
    override fun policy(): JniLedgerPolicy = policyNative()

    override fun firmwareVersionApdu(): ByteArray = firmwareVersionApduNative()

    override fun parseFirmwareVersion(reply: ByteArray): JniLedgerAppVersion = parseFirmwareVersionNative(reply)

    override fun deviceIdentityApdu(networkId: Int): ByteArray = deviceIdentityApduNative(networkId)

    override fun parseDeviceIdentity(reply: ByteArray): String = parseDeviceIdentityNative(reply)

    override fun isValidDeviceIdentity(identity: String): Boolean = isValidDeviceIdentityNative(identity)

    override fun unifiedAddressApdu(
        networkId: Int,
        zip32AccountIndex: Long,
        transparentAddressIndex: Long,
        display: Boolean
    ): ByteArray = unifiedAddressApduNative(networkId, zip32AccountIndex, transparentAddressIndex, display)

    override fun parseUnifiedAddress(
        reply: ByteArray,
        networkId: Int
    ): String = parseUnifiedAddressNative(reply, networkId)

    override fun ufvkExchangeNew(
        networkId: Int,
        zip32AccountIndex: Long
    ): Long = ufvkExchangeNewNative(networkId, zip32AccountIndex)

    override fun ufvkExchangeNextApdu(handle: Long): ByteArray? = ufvkExchangeNextApduNative(handle)

    override fun ufvkExchangeWaitsForUser(handle: Long): Boolean = ufvkExchangeWaitsForUserNative(handle)

    override fun ufvkExchangeProcessResponse(
        handle: Long,
        reply: ByteArray
    ): JniLedgerUfvkStep = ufvkExchangeProcessResponseNative(handle, reply)

    override fun ufvkExchangeFree(handle: Long) = ufvkExchangeFreeNative(handle)

    override fun bleMtuRequest(): ByteArray = bleMtuRequestNative()

    override fun parseBleMtuResponse(notification: ByteArray): Int = parseBleMtuResponseNative(notification)

    override fun bleFrames(
        apdu: ByteArray,
        frameSize: Int
    ): Array<ByteArray> = bleFramesNative(apdu, frameSize)

    override fun bleDeframerNew(): Long = bleDeframerNewNative()

    override fun bleDeframerPush(
        handle: Long,
        frame: ByteArray
    ): ByteArray? = bleDeframerPushNative(handle, frame)

    override fun bleDeframerReset(handle: Long) = bleDeframerResetNative(handle)

    override fun bleDeframerFree(handle: Long) = bleDeframerFreeNative(handle)

    override suspend fun signSessionNew(
        dbDataPath: String,
        networkId: Int,
        accountUuid: ByteArray,
        pczt: ByteArray,
        deviceIdentity: String,
        zip32AccountIndex: Long,
        firmwareVersionReply: ByteArray
    ): Long =
        withContext(SdkDispatchers.DATABASE_IO) {
            signSessionNewNative(
                dbDataPath = dbDataPath,
                networkId = networkId,
                accountUuid = accountUuid,
                pczt = pczt,
                deviceIdentity = deviceIdentity,
                zip32AccountIndex = zip32AccountIndex,
                firmwareVersionReply = firmwareVersionReply
            )
        }

    override fun signSessionTotalCommands(handle: Long): Int = signSessionTotalCommandsNative(handle)

    override fun signSessionNextStep(handle: Long): JniLedgerSignStep = signSessionNextStepNative(handle)

    override fun signSessionProcessResponse(
        handle: Long,
        reply: ByteArray
    ): Int = signSessionProcessResponseNative(handle, reply)

    override fun signSessionStage(handle: Long): Int = signSessionStageNative(handle)

    override fun signSessionIsRestartable(handle: Long): Boolean = signSessionIsRestartableNative(handle)

    override suspend fun signSessionFinish(handle: Long): ByteArray =
        withContext(SdkDispatchers.CPU_BOUND) {
            signSessionFinishNative(handle)
        }

    override fun signSessionFree(handle: Long) = signSessionFreeNative(handle)

    @Suppress("TooManyFunctions")
    companion object {
        /**
         * Loads the native library, which the Ledger engine is part of, and returns the backend.
         * Idempotent.
         */
        suspend fun new(): LedgerRustBackend {
            RustBackend.loadLibrary()
            return LedgerRustBackend()
        }

        //
        // External Functions
        //

        @JvmStatic
        private external fun policyNative(): JniLedgerPolicy

        @JvmStatic
        private external fun firmwareVersionApduNative(): ByteArray

        @JvmStatic
        private external fun parseFirmwareVersionNative(reply: ByteArray): JniLedgerAppVersion

        @JvmStatic
        private external fun deviceIdentityApduNative(networkId: Int): ByteArray

        @JvmStatic
        private external fun parseDeviceIdentityNative(reply: ByteArray): String

        @JvmStatic
        private external fun isValidDeviceIdentityNative(identity: String): Boolean

        @JvmStatic
        private external fun unifiedAddressApduNative(
            networkId: Int,
            zip32AccountIndex: Long,
            transparentAddressIndex: Long,
            display: Boolean
        ): ByteArray

        @JvmStatic
        private external fun parseUnifiedAddressNative(
            reply: ByteArray,
            networkId: Int
        ): String

        @JvmStatic
        private external fun ufvkExchangeNewNative(
            networkId: Int,
            zip32AccountIndex: Long
        ): Long

        @JvmStatic
        private external fun ufvkExchangeNextApduNative(handle: Long): ByteArray?

        @JvmStatic
        private external fun ufvkExchangeWaitsForUserNative(handle: Long): Boolean

        @JvmStatic
        private external fun ufvkExchangeProcessResponseNative(
            handle: Long,
            reply: ByteArray
        ): JniLedgerUfvkStep

        @JvmStatic
        private external fun ufvkExchangeFreeNative(handle: Long)

        @JvmStatic
        private external fun bleMtuRequestNative(): ByteArray

        @JvmStatic
        private external fun parseBleMtuResponseNative(notification: ByteArray): Int

        @JvmStatic
        private external fun bleFramesNative(
            apdu: ByteArray,
            frameSize: Int
        ): Array<ByteArray>

        @JvmStatic
        private external fun bleDeframerNewNative(): Long

        @JvmStatic
        private external fun bleDeframerPushNative(
            handle: Long,
            frame: ByteArray
        ): ByteArray?

        @JvmStatic
        private external fun bleDeframerResetNative(handle: Long)

        @JvmStatic
        private external fun bleDeframerFreeNative(handle: Long)

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun signSessionNewNative(
            dbDataPath: String,
            networkId: Int,
            accountUuid: ByteArray,
            pczt: ByteArray,
            deviceIdentity: String,
            zip32AccountIndex: Long,
            firmwareVersionReply: ByteArray
        ): Long

        @JvmStatic
        private external fun signSessionTotalCommandsNative(handle: Long): Int

        @JvmStatic
        private external fun signSessionNextStepNative(handle: Long): JniLedgerSignStep

        @JvmStatic
        private external fun signSessionProcessResponseNative(
            handle: Long,
            reply: ByteArray
        ): Int

        @JvmStatic
        private external fun signSessionStageNative(handle: Long): Int

        @JvmStatic
        private external fun signSessionIsRestartableNative(handle: Long): Boolean

        @JvmStatic
        private external fun signSessionFinishNative(handle: Long): ByteArray

        @JvmStatic
        private external fun signSessionFreeNative(handle: Long)
    }
}
