package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.ext.existsSuspend
import cash.z.ecc.android.sdk.internal.ext.mkdirsSuspend
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import co.electriccoin.lightwallet.client.PartialTorWalletClient
import co.electriccoin.lightwallet.client.util.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal

class TorClient private constructor(
    private var nativeHandle: Long?,
    private val backend: Backend,
) : Disposable {
    private val accessMutex = Mutex()

    // Guards freeing the native runtime while a caller is still using a handle it obtained
    // from [pinRawRuntimeHandle] -- see that function's own doc comment for the hazard this
    // closes. Both fields are only ever touched under [accessMutex].
    private var rawHandlePinCount = 0
    private var disposalPending = false

    override suspend fun dispose() =
        accessMutex.withLock {
            if (rawHandlePinCount > 0) {
                // A pinned raw handle is still outstanding (e.g. a round-driver session mid
                // multi-bundle run) -- freeing the runtime now would leave that caller's
                // in-flight native call holding a dangling pointer. Defer the actual free to
                // whichever unpinRawRuntimeHandle call brings the count back to zero.
                disposalPending = true
                return@withLock
            }
            withContext(Dispatchers.IO) {
                nativeHandle?.let { freeTorRuntime(it) }
                nativeHandle = null
            }
        }

    /**
     * Returns a new isolated `TorClient` handle.
     *
     * The two `TorClient`s will share internal state and configuration, but their streams
     * will never share circuits with one another.
     *
     * Use this method when you want separate parts of your program to each have a
     * `TorClient` handle, but where you don't want their activities to be linkable to one
     * another over the Tor network.
     *
     * Calling this method is usually preferable to creating a completely separate
     * `TorClient` instance, since it can share its internals with the existing `TorClient`.
     */
    suspend fun isolatedTorClient(): TorClient = accessMutex.withLock { isolatedTorClientInternal() }

    /**
     * The caller MUST acquire `accessMutex` before calling this function.
     *
     * @return a new isolated `TorClient` handle.
     */
    private suspend fun isolatedTorClientInternal() =
        withContext(Dispatchers.IO) {
            checkNotNull(nativeHandle) { "TorClient is disposed" }
            TorClient(isolatedClient(nativeHandle!!), backend)
        }

    /**
     * Changes the client's current dormant mode, putting background tasks to sleep or waking
     * them up as appropriate.
     *
     * This can be used to conserve CPU usage if you aren’t planning on using the client for
     * a while, especially on mobile platforms.
     */
    suspend fun setDormant(mode: TorDormantMode) =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                checkNotNull(nativeHandle) { "TorClient is disposed" }
                setDormant(nativeHandle!!, mode.ordinal)
            }
        }

    /**
     * Returns the raw native Tor-runtime handle backing this client, pinned so [dispose] cannot
     * free the underlying runtime until a matching [unpinRawRuntimeHandle] call releases it.
     *
     * Deliberately narrow: this exists only for handing this runtime off across a JNI boundary
     * to a *different* native subsystem that already accepts a raw runtime handle -- today,
     * `cash.z.ecc.android.sdk.VotingDbSession.openRoundSession`'s and
     * `cash.z.ecc.android.sdk.VotingShareTrackingSession.run`'s `torRuntime`
     * parameter (see `Synchronizer.getVotingTorRuntimeHandle`, the sanctioned way for a caller
     * outside this module to reach this value). Do not use it to bypass this client's own
     * request dispatch ([httpGet]/[httpPost]/[createWalletClient]/...) -- those remain the only
     * sanctioned way to actually use this Tor runtime for HTTP.
     *
     * A round-driver session can hold the returned handle for the whole session's lifetime
     * (potentially 20-30 minutes across a multi-bundle round), well past any single native call.
     * [dispose] running concurrently with that -- a `Synchronizer` rebuild mid-vote (server-switch
     * hysteresis, a wallet reset) -- must not free the runtime out from under the in-flight round
     * drive: pinning defers that free (see [dispose]'s own doc comment) rather than racing it.
     * Every call here MUST be matched by exactly one [unpinRawRuntimeHandle] call once the caller
     * is done with the handle, in a `finally`/`close()` path that always runs.
     */
    suspend fun pinRawRuntimeHandle(): Long =
        accessMutex.withLock {
            checkNotNull(nativeHandle) { "TorClient is disposed" }.also { rawHandlePinCount++ }
        }

    /**
     * Releases a pin obtained from [pinRawRuntimeHandle]. Once the pin count returns to zero, if
     * [dispose] was called while pinned, the deferred free runs now.
     */
    suspend fun unpinRawRuntimeHandle() =
        accessMutex.withLock {
            check(rawHandlePinCount > 0) { "unpinRawRuntimeHandle called without a matching pin" }
            rawHandlePinCount--
            if (rawHandlePinCount == 0 && disposalPending) {
                withContext(Dispatchers.IO) {
                    nativeHandle?.let { freeTorRuntime(it) }
                    nativeHandle = null
                }
            }
        }

    suspend fun httpGet(url: String, headers: List<JniHttpHeader>, retryLimit: Int): JniHttpResponseBytes =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                checkNotNull(nativeHandle) { "TorClient is disposed" }
                httpGet(
                    nativeHandle!!,
                    url,
                    headers.toTypedArray(),
                    retryLimit
                )
            }
        }

    suspend fun httpPost(
        url: String,
        headers: List<JniHttpHeader>,
        body: ByteArray,
        retryLimit: Int
    ): JniHttpResponseBytes =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                checkNotNull(nativeHandle) { "TorClient is disposed" }
                httpPost(
                    nativeHandle!!,
                    url,
                    headers.toTypedArray(),
                    body,
                    retryLimit
                )
            }
        }

    suspend fun getExchangeRateUsd(): BigDecimal =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                checkNotNull(nativeHandle) { "TorClient is disposed" }
                getExchangeRateUsd(nativeHandle!!)
            }
        }

    /**
     * Connects to the lightwalletd server at the given endpoint.
     *
     * This client is isolated from any other Tor usage, and queries made with this client
     * are isolated from each other (but may still be correlatable by the server through
     * request timing, if the caller does not mitigate timing attacks).
     */
    suspend fun createIsolatedWalletClient(endpoint: String): PartialTorWalletClient =
        accessMutex.withLock {
            checkNotNull(nativeHandle) { "TorClient is disposed" }
            IsolatedTorWalletClient.new(
                isolatedTorClient = isolatedTorClientInternal(),
                endpoint = endpoint
            )
        }

    /**
     * Connects to the lightwalletd server at the given endpoint.
     *
     * This client is isolated from any other Tor usage. Queries made with this client are
     * not isolated from each other; use `createIsolatedWalletClient()` if you need this.
     */
    suspend fun createWalletClient(endpoint: String): PartialTorWalletClient =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                checkNotNull(nativeHandle) { "TorClient is disposed" }
                TorWalletClient.new(
                    nativeHandle =
                        connectToLightwalletd(
                            nativeHandle = nativeHandle!!,
                            endpoint = endpoint
                        ),
                    backend = backend
                )
            }
        }

    companion object {
        suspend fun new(torDir: File, backend: Backend): TorClient =
            withContext(Dispatchers.IO) {
                RustBackend.loadLibrary()

                // Ensure that the directory exists.
                torDir.mkdirsSuspend()
                if (!torDir.existsSuspend()) {
                    error("${torDir.path} directory does not exist and could not be created.")
                }

                TorClient(createTorRuntime(torDir.path), backend)
            }

        //
        // External Functions
        //

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun createTorRuntime(torDir: String): Long

        @JvmStatic
        private external fun freeTorRuntime(nativeHandle: Long)

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun isolatedClient(nativeHandle: Long): Long

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun setDormant(nativeHandle: Long, mode: Int)

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun httpGet(
            nativeHandle: Long,
            url: String,
            headers: Array<JniHttpHeader>,
            retryLimit: Int
        ): JniHttpResponseBytes

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun httpPost(
            nativeHandle: Long,
            url: String,
            headers: Array<JniHttpHeader>,
            body: ByteArray,
            retryLimit: Int
        ): JniHttpResponseBytes

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getExchangeRateUsd(nativeHandle: Long): BigDecimal

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun connectToLightwalletd(nativeHandle: Long, endpoint: String): Long
    }
}

/**
 * What level of sleep to put a Tor client into.
 *
 * The order of the enum constants MUST match the order in `parse_tor_dormant_mode()` in
 * `backend-lib/src/main/rust/lib.rs`.
 */
enum class TorDormantMode {
    /**
     * The client functions as normal, and background tasks run periodically.
     */
    NORMAL,

    /**
     * Background tasks are suspended, conserving CPU usage. Attempts to use the
     * client will wake it back up again.
     */
    SOFT,
}
