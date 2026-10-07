package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.LedgerBackend
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerAppVersion
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerException
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerPolicy
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerSignStep
import cash.z.ecc.android.sdk.internal.model.ledger.JniLedgerUfvkStep
import cash.z.ecc.android.sdk.ledger.LedgerDeviceIdentity
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypesafeLedgerBackendImplTest {
    /**
     * A JNI backend that only builds and frees signing sessions. [signSessionNew] blocks a worker
     * thread until [proceed] opens, the way the native call cannot be interrupted, and allocates the
     * handle only once it returns.
     */
    private class SessionBackend(
        private val totalCommandsFails: Boolean = false
    ) : LedgerBackend {
        val entered = CompletableDeferred<Unit>()
        val proceed = CountDownLatch(1)
        val allocated = mutableListOf<Long>()
        val freed = mutableListOf<Long>()

        override suspend fun signSessionNew(
            dbDataPath: String,
            networkId: Int,
            accountUuid: ByteArray,
            pczt: ByteArray,
            deviceIdentity: String,
            zip32AccountIndex: Long,
            firmwareVersionReply: ByteArray
        ): Long =
            withContext(Dispatchers.IO) {
                entered.complete(Unit)
                proceed.await()
                synchronized(allocated) { allocated.add(HANDLE) }
                HANDLE
            }

        override fun signSessionTotalCommands(handle: Long): Int =
            if (totalCommandsFails) {
                throw JniLedgerException(
                    kind = JniLedgerException.KIND_INTERNAL,
                    statusWord = JniLedgerException.NO_STATUS_WORD,
                    isTransient = false,
                    isRestartable = false,
                    reason = null
                )
            } else {
                1
            }

        override fun signSessionFree(handle: Long) {
            freed.add(handle)
        }

        override fun policy(): JniLedgerPolicy = unused()

        override fun firmwareVersionApdu(): ByteArray = unused()

        override fun parseFirmwareVersion(reply: ByteArray): JniLedgerAppVersion = unused()

        override fun deviceIdentityApdu(networkId: Int): ByteArray = unused()

        override fun parseDeviceIdentity(reply: ByteArray): String = unused()

        override fun isValidDeviceIdentity(identity: String): Boolean = unused()

        override fun checkUfvkDeviceIdentity(
            networkId: Int,
            ufvk: String,
            deviceIdentity: String
        ): Unit = unused()

        override fun unifiedAddressApdu(
            networkId: Int,
            zip32AccountIndex: Long,
            transparentAddressIndex: Long,
            display: Boolean
        ): ByteArray = unused()

        override fun parseUnifiedAddress(
            reply: ByteArray,
            networkId: Int
        ): String = unused()

        override fun ufvkExchangeNew(
            networkId: Int,
            zip32AccountIndex: Long
        ): Long = unused()

        override fun ufvkExchangeNextApdu(handle: Long): ByteArray? = unused()

        override fun ufvkExchangeWaitsForUser(handle: Long): Boolean = unused()

        override fun ufvkExchangeProcessResponse(
            handle: Long,
            reply: ByteArray
        ): JniLedgerUfvkStep = unused()

        override fun ufvkExchangeFree(handle: Long) = unused()

        override fun bleMtuRequest(): ByteArray = unused()

        override fun parseBleMtuResponse(notification: ByteArray): Int = unused()

        override fun bleFrames(
            apdu: ByteArray,
            frameSize: Int
        ): Array<ByteArray> = unused()

        override fun bleDeframerNew(): Long = unused()

        override fun bleDeframerPush(
            handle: Long,
            frame: ByteArray
        ): ByteArray? = unused()

        override fun bleDeframerReset(handle: Long) = unused()

        override fun bleDeframerFree(handle: Long) = unused()

        override fun signSessionNextStep(handle: Long): JniLedgerSignStep = unused()

        override fun signSessionProcessResponse(
            handle: Long,
            reply: ByteArray
        ): Int = unused()

        override fun signSessionStage(handle: Long): Int = unused()

        override fun signSessionIsRestartable(handle: Long): Boolean = unused()

        override suspend fun signSessionFinish(handle: Long): ByteArray = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used by these tests")
    }

    private suspend fun TypesafeLedgerBackendImpl.newSession() =
        newSignSession(
            dataDbFile = File("unused.sqlite"),
            network = ZcashNetwork.Testnet,
            accountUuid = AccountUuid.new(ByteArray(16)),
            pczt = Pczt(byteArrayOf(1, 2, 3)),
            deviceIdentity = LedgerDeviceIdentity("tpk0-unused"),
            zip32AccountIndex = Zip32AccountIndex.new(0),
            firmwareVersionReply = byteArrayOf(0x90.toByte(), 0x00)
        )

    @Test
    fun a_session_is_freed_once_by_close() =
        runBlocking<Unit> {
            val backend = SessionBackend().apply { proceed.countDown() }
            val session = TypesafeLedgerBackendImpl(backend).newSession()

            assertEquals(1, session.totalCommands)
            session.close()
            session.close()
            assertEquals(listOf(HANDLE), backend.freed)
        }

    @Test
    fun a_caller_cancelled_while_the_session_is_built_still_frees_the_handle() =
        runBlocking<Unit> {
            val backend = SessionBackend()
            val call = async { TypesafeLedgerBackendImpl(backend).newSession() }
            backend.entered.await()

            call.cancel()
            backend.proceed.countDown()

            assertFailsWith<CancellationException> { call.await() }
            assertEquals(listOf(HANDLE), backend.allocated)
            assertEquals(listOf(HANDLE), backend.freed)
        }

    @Test
    fun a_session_that_fails_to_build_frees_the_handle() =
        runBlocking<Unit> {
            val backend = SessionBackend(totalCommandsFails = true).apply { proceed.countDown() }

            assertFailsWith<LedgerException.Internal> { TypesafeLedgerBackendImpl(backend).newSession() }
            assertEquals(listOf(HANDLE), backend.freed)
        }

    private companion object {
        const val HANDLE = 42L
    }
}
