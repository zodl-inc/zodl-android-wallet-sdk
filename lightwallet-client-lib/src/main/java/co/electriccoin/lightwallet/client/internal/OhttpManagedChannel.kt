package co.electriccoin.lightwallet.client.internal

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A gRPC [ManagedChannel] that carries every RPC as one Oblivious HTTP (RFC 9458) exchange.
 *
 * Each call is serialised as gRPC-over-HTTP/2 framing (`application/grpc`), wrapped in Binary HTTP,
 * HPKE-encrypted and POSTed to the relay by [OhttpTransport]. The gateway forwards the inner request
 * to lightwalletd over h2c, buffers the (possibly streaming) response and returns it in a single
 * encrypted Binary HTTP response with the gRPC trailers folded into the header section. Server
 * streaming RPCs therefore work unchanged from the stub's point of view: all messages arrive at once
 * and are delivered respecting the listener's flow-control requests.
 */
@Suppress("TooManyFunctions") // gRPC ManagedChannel interface requires 11 methods
internal class OhttpManagedChannel(
    private val authority: String,
    private val transport: OhttpTransport,
    private val logger: (String) -> Unit = {}
) : ManagedChannel() {
    private val shutdown = AtomicBoolean(false)
    private val activeCalls = AtomicInteger(0)

    override fun <RequestT, ResponseT> newCall(
        methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
        callOptions: CallOptions
    ): ClientCall<RequestT, ResponseT> =
        OhttpClientCall(methodDescriptor, callOptions, authority, transport, logger, activeCalls)

    override fun authority(): String = authority

    override fun shutdown(): ManagedChannel {
        shutdown.set(true)
        return this
    }

    override fun shutdownNow(): ManagedChannel = shutdown()

    override fun isShutdown(): Boolean = shutdown.get()

    override fun isTerminated(): Boolean = shutdown.get() && activeCalls.get() == 0

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (!isTerminated) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(TERMINATION_POLL_MS)
        }
        return true
    }

    // There is no persistent connection: the channel is always "ready".
    override fun getState(requestConnection: Boolean): ConnectivityState =
        if (shutdown.get()) ConnectivityState.SHUTDOWN else ConnectivityState.READY

    override fun notifyWhenStateChanged(source: ConnectivityState, callback: Runnable) {
        // State never changes spontaneously; nothing to notify.
    }

    override fun resetConnectBackoff() {
        // No connection, no backoff.
    }

    override fun enterIdle() {
        // Always idle between calls.
    }

    companion object {
        private const val TERMINATION_POLL_MS = 50L

        /** Shared I/O pool for all OHTTP channels (daemon threads so it never blocks process exit). */
        internal val ioExecutor: ExecutorService =
            Executors.newCachedThreadPool(
                object : ThreadFactory {
                    private val counter = AtomicInteger()

                    override fun newThread(r: Runnable): Thread =
                        Thread(r, "ohttp-io-${counter.incrementAndGet()}").apply { isDaemon = true }
                }
            )
    }
}

/**
 * Runs submitted tasks one at a time, in order, on a delegate executor. gRPC requires listener
 * callbacks to be serialised.
 */
internal class SerializingExecutor(
    private val delegate: Executor
) : Executor {
    private val queue = ArrayDeque<Runnable>()
    private var running = false

    override fun execute(command: Runnable) {
        synchronized(queue) {
            queue.add(command)
            if (running) return
            running = true
        }
        delegate.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val next =
                synchronized(queue) {
                    queue.poll() ?: run {
                        running = false
                        return
                    }
                }
            @Suppress("TooGenericExceptionCaught") // Must catch all to protect executor thread
            try {
                next.run()
            } catch (t: Throwable) {
                // A misbehaving listener must not kill the executor; surface and continue.
                Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), t)
            }
        }
    }
}

@Suppress("TooManyFunctions") // gRPC ClientCall interface requires 15 methods
internal class OhttpClientCall<RequestT, ResponseT>(
    private val method: MethodDescriptor<RequestT, ResponseT>,
    private val callOptions: CallOptions,
    private val authority: String,
    private val transport: OhttpTransport,
    private val logger: (String) -> Unit,
    private val activeCalls: AtomicInteger
) : ClientCall<RequestT, ResponseT>() {
    private val callbackExecutor = SerializingExecutor(callOptions.executor ?: OhttpManagedChannel.ioExecutor)
    private val requestBody = ByteArrayOutputStream()

    private val lock = Any()
    private var listener: Listener<ResponseT>? = null
    private var requested = 0
    private val pending = ArrayDeque<ResponseT>()
    private var responseComplete = false
    private var finalStatus: Status? = null
    private var finalTrailers: Metadata? = null
    private var closed = false
    private var started = false

    override fun start(responseListener: Listener<ResponseT>, headers: Metadata) {
        synchronized(lock) {
            check(!started) { "already started" }
            started = true
            listener = responseListener
        }
        activeCalls.incrementAndGet()
        if (callOptions.deadline?.isExpired == true) {
            finish(Status.DEADLINE_EXCEEDED.withDescription("deadline expired before call started"), Metadata())
        }
    }

    override fun request(numMessages: Int) {
        synchronized(lock) { requested += numMessages }
        deliver()
    }

    override fun cancel(message: String?, cause: Throwable?) {
        finish(
            Status.CANCELLED.withDescription(message ?: "cancelled").withCause(cause),
            Metadata()
        )
    }

    override fun halfClose() {
        val expired = callOptions.deadline?.isExpired == true
        if (expired) {
            finish(Status.DEADLINE_EXCEEDED.withDescription("deadline expired"), Metadata())
            return
        }
        OhttpManagedChannel.ioExecutor.execute { performRoundTrip() }
    }

    override fun sendMessage(message: RequestT) {
        // gRPC length-prefixed message framing: 1 byte compressed flag + 4 byte big-endian length.
        val bytes = method.requestMarshaller.stream(message).use { it.readBytes() }
        requestBody.write(GRPC_UNCOMPRESSED_FLAG)
        requestBody.write(Hpke.i2osp(bytes.size, GRPC_LENGTH_FIELD_SIZE))
        requestBody.write(bytes)
    }

    override fun isReady(): Boolean = true

    @Suppress("TooGenericExceptionCaught") // RuntimeException catch bridges all errors to gRPC Status.INTERNAL
    private fun performRoundTrip() {
        try {
            val timeoutMs = remainingTimeoutMs()
            val headers =
                buildList {
                    add("content-type" to GRPC_CONTENT_TYPE)
                    add("te" to "trailers")
                    add("grpc-accept-encoding" to "identity")
                    add("user-agent" to USER_AGENT)
                    add("grpc-timeout" to "${timeoutMs}m")
                }
            val request =
                BinaryHttp.Request(
                    method = "POST",
                    scheme = "https",
                    authority = authority,
                    path = "/${method.fullMethodName}",
                    headers = headers,
                    body = requestBody.toByteArray()
                )

            val response = transport.roundTrip(request, timeoutMs + TIMEOUT_SLACK_MS)
            onResponse(response)
        } catch (e: SocketTimeoutException) {
            finish(Status.DEADLINE_EXCEEDED.withDescription("OHTTP request timed out").withCause(e), Metadata())
        } catch (e: OhttpTransport.OhttpException) {
            logger("OHTTP ${method.fullMethodName} failed: ${e.message}")
            finish(Status.UNAVAILABLE.withDescription(e.message).withCause(e), Metadata())
        } catch (e: java.io.IOException) {
            logger("OHTTP ${method.fullMethodName} I/O error: ${e.message}")
            finish(Status.UNAVAILABLE.withDescription(e.message).withCause(e), Metadata())
        } catch (e: RuntimeException) {
            logger("OHTTP ${method.fullMethodName} internal error: $e")
            finish(Status.INTERNAL.withDescription(e.toString()).withCause(e), Metadata())
        }
    }

    @Suppress("ReturnCount") // Early returns for validation errors make control flow clearer
    private fun onResponse(response: BinaryHttp.Response) {
        val validationResult = validateResponse(response)
        if (validationResult != null) {
            finish(validationResult.first, validationResult.second)
            return
        }

        val grpcStatusCode = response.field("grpc-status")?.toIntOrNull()
        if (grpcStatusCode == null) {
            finish(Status.INTERNAL.withDescription("response is missing grpc-status"), Metadata())
            return
        }
        val grpcMessage = response.field("grpc-message")?.let { percentDecode(it) }
        val status =
            Status.fromCodeValue(grpcStatusCode).let {
                if (grpcMessage != null) it.withDescription(grpcMessage) else it
            }

        val messages =
            try {
                parseFrames(response.body)
            } catch (e: IllegalArgumentException) {
                finish(Status.INTERNAL.withDescription("malformed gRPC frames: ${e.message}"), Metadata())
                return
            }

        val headerMetadata = toMetadata(response.headers)
        val trailerMetadata = toMetadata(response.trailers)
        val currentListener = synchronized(lock) { listener } ?: return
        callbackExecutor.execute { currentListener.onHeaders(headerMetadata) }
        synchronized(lock) {
            pending.addAll(messages)
            responseComplete = true
            finalStatus = status
            finalTrailers = trailerMetadata
        }
        deliver()
    }

    private fun validateResponse(response: BinaryHttp.Response): Pair<Status, Metadata>? =
        when {
            response.status != HTTP_OK -> {
                val statusMessage = "gateway/lightwalletd returned HTTP ${response.status}"
                httpStatusToGrpc(response.status).withDescription(statusMessage) to Metadata()
            }

            !(response.field("content-type") ?: "").startsWith(GRPC_CONTENT_TYPE) -> {
                val contentType = response.field("content-type") ?: ""
                Status.INTERNAL.withDescription("unexpected content-type '$contentType'") to Metadata()
            }

            response.field("grpc-encoding").let { it != null && it != "identity" } -> {
                val encoding = response.field("grpc-encoding")
                Status.INTERNAL.withDescription("unsupported grpc-encoding '$encoding'") to Metadata()
            }

            else -> {
                null
            }
        }

    private fun parseFrames(body: ByteArray): List<ResponseT> {
        val messages = ArrayList<ResponseT>()
        var pos = 0
        while (pos < body.size) {
            require(body.size - pos >= GRPC_FRAME_HEADER_SIZE) { "truncated frame header" }
            val compressed = body[pos].toInt() and BYTE_MASK
            val length =
                ((body[pos + GRPC_LENGTH_BYTE_1].toInt() and BYTE_MASK) shl SHIFT_24) or
                    ((body[pos + GRPC_LENGTH_BYTE_2].toInt() and BYTE_MASK) shl SHIFT_16) or
                    ((body[pos + GRPC_LENGTH_BYTE_3].toInt() and BYTE_MASK) shl SHIFT_8) or
                    ((body[pos + GRPC_LENGTH_BYTE_4].toInt() and BYTE_MASK))
            pos += GRPC_FRAME_HEADER_SIZE
            require(compressed == 0) { "compressed frames are not supported" }
            require(length >= 0 && length <= body.size - pos) { "truncated frame body" }
            messages.add(method.responseMarshaller.parse(ByteArrayInputStream(body, pos, length)))
            pos += length
        }
        return messages
    }

    /**
     * Delivers queued messages up to the listener's outstanding request count, then closes.
     * Early returns needed for flow control state checks.
     */
    @Suppress("ReturnCount")
    private fun deliver() {
        while (true) {
            val currentListener: Listener<ResponseT>
            val action: Runnable
            synchronized(lock) {
                currentListener = listener ?: return
                if (closed) return
                if (pending.isNotEmpty() && requested > 0) {
                    requested--
                    val message = pending.poll()
                    action = Runnable { currentListener.onMessage(message) }
                } else if (pending.isEmpty() && responseComplete) {
                    closed = true
                    val status = finalStatus ?: Status.OK
                    val trailers = finalTrailers ?: Metadata()
                    action =
                        Runnable {
                            try {
                                currentListener.onClose(status, trailers)
                            } finally {
                                activeCalls.decrementAndGet()
                            }
                        }
                } else {
                    return
                }
            }
            callbackExecutor.execute(action)
        }
    }

    private fun finish(status: Status, trailers: Metadata) {
        val currentListener: Listener<ResponseT>
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            currentListener = listener ?: run {
                activeCalls.decrementAndGet()
                return
            }
        }
        callbackExecutor.execute {
            try {
                currentListener.onClose(status, trailers)
            } finally {
                activeCalls.decrementAndGet()
            }
        }
    }

    private fun remainingTimeoutMs(): Int {
        val deadline = callOptions.deadline ?: return DEFAULT_TIMEOUT_MS
        val remaining = deadline.timeRemaining(TimeUnit.MILLISECONDS)
        return remaining.coerceIn(MIN_TIMEOUT_MS.toLong(), DEFAULT_TIMEOUT_MS.toLong()).toInt()
    }

    private fun toMetadata(fields: List<Pair<String, String>>): Metadata {
        val metadata = Metadata()
        for ((name, value) in fields) {
            if (!isAcceptableMetadataName(name)) continue
            metadata.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value)
        }
        return metadata
    }

    private fun isAcceptableMetadataName(name: String): Boolean =
        !name.startsWith(":") &&
            !name.endsWith(Metadata.BINARY_HEADER_SUFFIX) &&
            name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

    private fun httpStatusToGrpc(httpStatus: Int): Status =
        when (httpStatus) {
            HTTP_BAD_REQUEST -> {
                Status.INTERNAL
            }

            HTTP_UNAUTHORIZED -> {
                Status.UNAUTHENTICATED
            }

            HTTP_FORBIDDEN -> {
                Status.PERMISSION_DENIED
            }

            HTTP_NOT_FOUND -> {
                Status.UNIMPLEMENTED
            }

            HTTP_TOO_MANY_REQUESTS, HTTP_BAD_GATEWAY, HTTP_SERVICE_UNAVAILABLE, HTTP_GATEWAY_TIMEOUT -> {
                Status.UNAVAILABLE
            }

            else -> {
                Status.UNKNOWN
            }
        }

    /** gRPC `grpc-message` uses percent-encoding for non-printable bytes (gRPC HTTP/2 spec). */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + PERCENT_ESCAPE_LENGTH < value.length) {
                val hex = value.substring(i + 1, i + PERCENT_ESCAPE_LENGTH).toIntOrNull(HEX_RADIX)
                if (hex != null) {
                    out.write(hex)
                    i += PERCENT_ESCAPE_LENGTH
                    continue
                }
            }
            out.write(c.code)
            i++
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        private const val GRPC_CONTENT_TYPE = "application/grpc"
        private const val USER_AGENT = "zodl-ohttp-grpc/1.0"
        private const val GRPC_FRAME_HEADER_SIZE = 5
        private const val GRPC_UNCOMPRESSED_FLAG = 0
        private const val GRPC_LENGTH_FIELD_SIZE = 4
        private const val GRPC_LENGTH_BYTE_1 = 1
        private const val GRPC_LENGTH_BYTE_2 = 2
        private const val GRPC_LENGTH_BYTE_3 = 3
        private const val GRPC_LENGTH_BYTE_4 = 4
        private const val DEFAULT_TIMEOUT_MS = 90_000
        private const val MIN_TIMEOUT_MS = 1_000
        private const val TIMEOUT_SLACK_MS = 5_000
        private const val BYTE_MASK = 0xFF
        private const val SHIFT_8 = 8
        private const val SHIFT_16 = 16
        private const val SHIFT_24 = 24
        private const val PERCENT_ESCAPE_LENGTH = 3
        private const val HEX_RADIX = 16

        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_BAD_GATEWAY = 502
        private const val HTTP_SERVICE_UNAVAILABLE = 503
        private const val HTTP_GATEWAY_TIMEOUT = 504
    }
}
