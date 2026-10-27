package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.backend.BuildConfig
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.ext.existsSuspend
import cash.z.ecc.android.sdk.internal.ext.mkdirsSuspend
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import co.electriccoin.lightwallet.client.PartialTorWalletClient
import co.electriccoin.lightwallet.client.util.Disposable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.math.BigDecimal

@Suppress("TooManyFunctions")
class TorClient private constructor(
    private var nativeHandle: Long?,
    private val backend: Backend,
) : Disposable {
    private val accessMutex = Mutex()

    // Guards freeing the native runtime while a [TorRuntimeLease] from [leaseRuntime] is still
    // outstanding -- see that function's own doc comment for the hazard this closes. Both fields
    // are only ever touched under [accessMutex].
    private var leaseCount = 0
    private var disposalPending = false

    override suspend fun dispose() =
        accessMutex.withLock {
            if (leaseCount > 0) {
                // A lease is still outstanding (e.g. a round-driver session mid multi-bundle
                // run) -- freeing the runtime now would leave that caller's in-flight native call
                // holding a dangling pointer. Defer the actual free to whichever lease release
                // brings the count back to zero.
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
     * Leases this client's native Tor runtime so [dispose] cannot free it until the returned
     * [TorRuntimeLease] is released.
     *
     * Deliberately narrow: this exists only for handing this runtime off across a JNI boundary
     * to a *different* native subsystem that holds it beyond a single call -- today, the voting
     * round driver and share-tracking driver (see `Synchronizer.acquireVotingTorLease`, the
     * sanctioned way for a caller outside this module to get one). Do not use it to bypass this
     * client's own request dispatch ([httpGet]/[httpPost]/[createWalletClient]/...).
     *
     * A round-driver session can hold the lease for the whole session's lifetime (potentially
     * 20-30 minutes across a multi-bundle round). [dispose] running concurrently with that -- a
     * `Synchronizer` rebuild mid-vote -- defers the free (see [dispose]'s own doc comment) until the
     * last outstanding lease is released. The lease releases against this exact instance, so it
     * stays releasable after the holder that handed out this client has been disposed.
     *
     * @throws IllegalStateException if this client is already disposed, or has a dispose pending
     * behind outstanding leases -- a runtime on its way out must not gain new holders.
     */
    suspend fun leaseRuntime(): TorRuntimeLease =
        accessMutex.withLock {
            check(!disposalPending) { "TorClient is being disposed" }
            val handle = checkNotNull(nativeHandle) { "TorClient is disposed" }
            leaseCount++
            TorRuntimeLease(handle, ::releaseRuntimeLease)
        }

    // Only ever invoked through TorRuntimeLease.release(), which is idempotent and already runs
    // under NonCancellable -- so each lease decrements exactly once, and the deferred free below
    // runs even when the releasing coroutine has been cancelled.
    private suspend fun releaseRuntimeLease() =
        accessMutex.withLock {
            check(leaseCount > 0) { "TorRuntimeLease released without an outstanding lease" }
            leaseCount--
            if (leaseCount == 0 && disposalPending) {
                withContext(Dispatchers.IO) {
                    nativeHandle?.let { freeTorRuntime(it) }
                    nativeHandle = null
                }
            }
        }

    /**
     * Makes an HTTP GET request over Tor.
     *
     * @param maxResponseBytes the maximum response body size the caller will accept, enforced while
     * reading the stream. The call fails with "Tor HTTP response body exceeds <limit> bytes" if
     * the body is larger than this limit.
     * @param timeoutMs overall deadline for the whole call, retries included, enforced inside the
     * native call (so it really interrupts the request and releases this client's mutex). The
     * default [NO_TIMEOUT_MS] (or any value `<= 0`) keeps the previous behavior: no caller-supplied
     * deadline, only the Tor HTTP client's own per-phase timeouts.
     * @throws TorHttpTimeoutException if [timeoutMs] elapsed
     * @throws TorHttpTransportException on a network/transport failure
     */
    suspend fun httpGet(
        url: String,
        headers: List<JniHttpHeader>,
        retryLimit: Int,
        maxResponseBytes: Int,
        timeoutMs: Long = NO_TIMEOUT_MS
    ): JniHttpResponseBytes =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                checkNotNull(nativeHandle) { "TorClient is disposed" }
                httpGet(
                    nativeHandle!!,
                    url,
                    headers.toTypedArray(),
                    retryLimit,
                    maxResponseBytes,
                    timeoutMs
                )
            }
        }

    /**
     * Makes an HTTP POST request over Tor. See [httpGet] for [maxResponseBytes], [timeoutMs] and the exceptions.
     */
    @Suppress("LongParameterList")
    suspend fun httpPost(
        url: String,
        headers: List<JniHttpHeader>,
        body: ByteArray,
        retryLimit: Int,
        maxResponseBytes: Int,
        timeoutMs: Long = NO_TIMEOUT_MS
    ): JniHttpResponseBytes =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                checkNotNull(nativeHandle) { "TorClient is disposed" }
                httpPost(
                    nativeHandle!!,
                    url,
                    headers.toTypedArray(),
                    body,
                    retryLimit,
                    maxResponseBytes,
                    timeoutMs
                )
            }
        }

    /**
     * Makes an HTTP POST request over Tor and hands the response to [read] as soon as its head has
     * arrived: [read] gets the status, the headers and the [HttpBody], whose bytes it reads as they
     * arrive, on a blocking thread. [timeoutMs] bounds the whole exchange, the body included.
     * Cancelling the caller ends the exchange within 100 ms, while it waits for the response head or
     * in a waiting [HttpBody.read]. The client's mutex is held until [read] returns, as for [httpPost].
     *
     * @throws TorHttpTimeoutException if [timeoutMs] elapsed (from [HttpBody.read] too)
     * @throws TorHttpTransportException on a network/transport failure (from [HttpBody.read] too)
     */
    @Suppress("LongParameterList")
    suspend fun <T> httpPostStreaming(
        url: String,
        headers: List<JniHttpHeader>,
        body: ByteArray,
        retryLimit: Int,
        timeoutMs: Long,
        read: (status: Int, headers: Array<JniHttpHeader>, body: HttpBody) -> T
    ): T =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                checkNotNull(nativeHandle) { "TorClient is disposed" }
                val signal = httpCancelSignalNew()
                try {
                    coroutineScope {
                        val canceller =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    awaitCancellation()
                                } finally {
                                    httpCancelSignalSet(signal)
                                }
                            }
                        try {
                            val response =
                                httpPostStream(
                                    nativeHandle!!,
                                    url,
                                    headers.toTypedArray(),
                                    body,
                                    retryLimit,
                                    timeoutMs,
                                    signal
                                )
                            HttpBody(response.body).use { read(response.status, response.headers, it) }
                        } finally {
                            canceller.cancel()
                        }
                    }
                } finally {
                    httpCancelSignalFree(signal)
                }
            }
        }

    /**
     * The body of an [httpPostStreaming] response. [read] and [close] are serialised and [close] is
     * idempotent, so the native body is never freed under a read nor twice: a [close] during a read
     * waits for it (a waiting read ends within 100 ms of the exchange's cancellation).
     */
    class HttpBody internal constructor(
        handle: Long
    ) : Closeable {
        private val lock = Any()
        private var handle = handle

        /**
         * The next bytes of the body, null once it has ended.
         *
         * @throws TorHttpTimeoutException if the request's deadline passed
         * @throws TorHttpTransportException on a broken connection or once the caller is cancelled
         */
        fun read(): ByteArray? =
            synchronized(lock) {
                check(handle != 0L) { "The response body is closed" }
                httpBodyRead(handle)
            }

        override fun close() {
            synchronized(lock) {
                val freed = handle
                handle = 0
                if (freed != 0L) httpBodyFree(freed)
            }
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
        /** No caller-supplied deadline for [httpGet] / [httpPost] (the previous behavior). */
        const val NO_TIMEOUT_MS = 0L

        suspend fun new(torDir: File, backend: Backend, clientTracePrefix: String? = null): TorClient =
            withContext(Dispatchers.IO) {
                RustBackend.loadLibrary()

                // Ensure that the directory exists.
                torDir.mkdirsSuspend()
                if (!torDir.existsSuspend()) {
                    error("${torDir.path} directory does not exist and could not be created.")
                }

                // Trace ids are debug-only: the Rust side no longer gates this itself.
                TorClient(createTorRuntime(torDir.path, clientTracePrefix.takeIf { BuildConfig.DEBUG }), backend)
            }

        //
        // External Functions
        //

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun createTorRuntime(torDir: String, clientTracePrefix: String?): Long

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
         * @throws TorHttpTimeoutException if [timeoutMs] elapsed
         * @throws TorHttpTransportException on a network/transport failure
         * @throws RuntimeException as a common indicator of any other operation failure
         */
        @JvmStatic
        @Suppress("LongParameterList")
        @Throws(RuntimeException::class)
        private external fun httpGet(
            nativeHandle: Long,
            url: String,
            headers: Array<JniHttpHeader>,
            retryLimit: Int,
            maxResponseBytes: Int,
            timeoutMs: Long
        ): JniHttpResponseBytes

        /**
         * @throws TorHttpTimeoutException if [timeoutMs] elapsed
         * @throws TorHttpTransportException on a network/transport failure
         * @throws RuntimeException as a common indicator of any other operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        @Suppress("LongParameterList")
        private external fun httpPost(
            nativeHandle: Long,
            url: String,
            headers: Array<JniHttpHeader>,
            body: ByteArray,
            retryLimit: Int,
            maxResponseBytes: Int,
            timeoutMs: Long
        ): JniHttpResponseBytes

        /**
         * @throws TorHttpTimeoutException if [timeoutMs] elapsed
         * @throws TorHttpTransportException on a network/transport failure
         * @throws RuntimeException as a common indicator of any other operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        @Suppress("LongParameterList")
        private external fun httpPostStream(
            nativeHandle: Long,
            url: String,
            headers: Array<JniHttpHeader>,
            body: ByteArray,
            retryLimit: Int,
            timeoutMs: Long,
            cancelSignal: Long
        ): JniHttpStreamResponse

        /**
         * @throws TorHttpTimeoutException if the request's deadline passed
         * @throws TorHttpTransportException on a network/transport failure or a cancelled exchange
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun httpBodyRead(body: Long): ByteArray?

        @JvmStatic
        private external fun httpBodyFree(body: Long)

        @JvmStatic
        private external fun httpCancelSignalNew(): Long

        @JvmStatic
        private external fun httpCancelSignalSet(signal: Long)

        @JvmStatic
        private external fun httpCancelSignalFree(signal: Long)

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
