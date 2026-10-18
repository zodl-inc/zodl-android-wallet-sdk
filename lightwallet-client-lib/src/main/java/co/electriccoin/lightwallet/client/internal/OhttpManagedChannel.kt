package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpInternalException
import co.electriccoin.lightwallet.client.OhttpRequestTooLargeException
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
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
 * HPKE-encrypted (by the shared native OHTTP core) and POSTed to the relay by [OhttpTransport]. The
 * gateway forwards the inner request to lightwalletd over h2c and streams the response back as
 * encrypted chunks of an indeterminate-length Binary HTTP response. Each chunk is opened as soon as it
 * has arrived ([OhttpTransport.stream]) and each gRPC message is delivered as soon as it is complete,
 * respecting the listener's flow-control requests, so a server-streaming RPC (GetBlockRange, the
 * mempool and taddress streams) works unchanged from the stub's point of view; the status comes from
 * the trailer section, else the header section.
 */
@Suppress("TooManyFunctions") // gRPC ManagedChannel interface requires 11 methods
internal class OhttpManagedChannel(
    private val authority: String,
    private val transport: OhttpTransport,
    private val logger: (String) -> Unit = {},
) : ManagedChannel() {
    private val shutdown = AtomicBoolean(false)
    private val activeCalls = AtomicInteger(0)
    private val callRegistry = ConcurrentHashMap.newKeySet<OhttpClientCall<*, *>>()

    override fun <RequestT, ResponseT> newCall(
        methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
        callOptions: CallOptions,
    ): ClientCall<RequestT, ResponseT> {
        check(!shutdown.get()) { "Channel is shut down" }
        val call =
            OhttpClientCall(methodDescriptor, callOptions, authority, transport, logger, activeCalls, callRegistry)
        callRegistry.add(call)
        return call
    }

    override fun authority(): String = authority

    override fun shutdown(): ManagedChannel {
        shutdown.set(true)
        return this
    }

    override fun shutdownNow(): ManagedChannel {
        shutdown.set(true)
        callRegistry.forEach { it.cancel("Channel shut down", null) }
        return this
    }

    override fun isShutdown(): Boolean = shutdown.get()

    override fun isTerminated(): Boolean = shutdown.get() && activeCalls.get() == 0

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
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

    override fun notifyWhenStateChanged(
        source: ConnectivityState,
        callback: Runnable,
    ) {
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
                },
            )
    }
}

/**
 * Runs submitted tasks one at a time, in order, on a delegate executor. gRPC requires listener
 * callbacks to be serialised.
 */
internal class SerializingExecutor(
    private val delegate: Executor,
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

@Suppress("TooManyFunctions", "LongParameterList") // gRPC ClientCall interface + cancel registry
internal class OhttpClientCall<RequestT, ResponseT>(
    private val method: MethodDescriptor<RequestT, ResponseT>,
    private val callOptions: CallOptions,
    private val authority: String,
    private val transport: OhttpTransport,
    private val logger: (String) -> Unit,
    private val activeCalls: AtomicInteger,
    private val callRegistry: MutableSet<OhttpClientCall<*, *>>,
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
    private var cancelled = false
    private var exchange: Job? = null

    override fun start(
        responseListener: Listener<ResponseT>,
        headers: Metadata,
    ) {
        val countAsActive: Boolean
        synchronized(lock) {
            check(!started) { "already started" }
            started = true
            listener = responseListener
            // Call cancelled before start() is already closed and never decrements the counter.
            countAsActive = !closed
        }
        if (countAsActive) activeCalls.incrementAndGet()
        if (callOptions.deadline?.isExpired == true) {
            finish(Status.DEADLINE_EXCEEDED.withDescription("deadline expired before call started"), Metadata())
        }
    }

    override fun request(numMessages: Int) {
        synchronized(lock) { requested += numMessages }
        deliver()
    }

    override fun cancel(
        message: String?,
        cause: Throwable?,
    ) {
        val running =
            synchronized(lock) {
                cancelled = true
                exchange
            }
        finish(
            Status.CANCELLED.withDescription(message ?: "cancelled").withCause(cause),
            Metadata(),
        )
        // Closes the relay connection under a blocked read (DirectOhttpSender) and ends the exchange.
        running?.cancel()
    }

    override fun halfClose() {
        synchronized(lock) {
            if (cancelled) return
        }
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
        requestBody.write(ByteBuffer.allocate(GRPC_LENGTH_FIELD_SIZE).putInt(bytes.size).array())
        requestBody.write(bytes)
    }

    override fun isReady(): Boolean = true

    @Suppress("TooGenericExceptionCaught") // RuntimeException catch bridges all errors to gRPC Status.INTERNAL
    private fun performRoundTrip() {
        val timeoutMs = remainingTimeoutMs()
        val budget = exchangeBudget(timeoutMs)
        try {
            val headers =
                buildList {
                    add("content-type" to GRPC_CONTENT_TYPE)
                    add("te" to "trailers")
                    add("grpc-accept-encoding" to "identity")
                    add("user-agent" to USER_AGENT)
                    add("grpc-timeout" to "${timeoutMs}m")
                }
            val request =
                OhttpCodecRequest(
                    method = "POST",
                    scheme = "https",
                    authority = authority,
                    path = "/${method.fullMethodName}",
                    headers = headers,
                    body = requestBody.toByteArray(),
                )

            runBlocking {
                synchronized(lock) {
                    if (cancelled) return@runBlocking
                    exchange = coroutineContext.job
                }
                transport.stream(request, budget.timeoutMs, ResponseReader())
            }
        } catch (ignored: CancellationException) {
            // The call was cancelled and is already closed.
        } catch (ignored: ReadAbandoned) {
            // The response was refused while it was read and the call is already closed.
        } catch (e: SocketTimeoutException) {
            // A transport timeout is UNAVAILABLE unless the call's own deadline is what ran out.
            val deadlineHit = budget.boundByDeadline || callOptions.deadline?.isExpired == true
            val status = if (deadlineHit) Status.DEADLINE_EXCEEDED else Status.UNAVAILABLE
            finish(status.withDescription("OHTTP request timed out").withCause(e), Metadata())
        } catch (e: OhttpException) {
            logger("OHTTP ${method.fullMethodName} failed: ${e.message}")
            finish(grpcStatusOf(e).withDescription(e.message).withCause(e), Metadata())
        } catch (e: java.io.IOException) {
            logger("OHTTP ${method.fullMethodName} I/O error: ${e.message}")
            finish(Status.UNAVAILABLE.withDescription(e.message).withCause(e), Metadata())
        } catch (e: RuntimeException) {
            logger("OHTTP ${method.fullMethodName} internal error: $e")
            finish(Status.INTERNAL.withDescription(e.toString()).withCause(e), Metadata())
        } finally {
            synchronized(lock) { exchange = null }
        }
    }

    /** Thrown by [ResponseReader] to stop reading a response it has already closed the call for. */
    private class ReadAbandoned : RuntimeException()

    /**
     * The inner response as it is decoded: the head is checked and delivered, every complete gRPC
     * message is queued and delivered at once (as far as the listener has requested), the status is
     * taken when the response has ended.
     */
    private inner class ResponseReader : OhttpResponseSink {
        private var headers: List<Pair<String, String>> = emptyList()
        private var trailers: List<Pair<String, String>> = emptyList()
        private val frames = GrpcFrameReader()

        override fun onHead(
            status: Int,
            headers: List<Pair<String, String>>,
        ) {
            validateHead(status, headers)?.let { abandon(it) }
            if (callOptions.deadline?.isExpired == true) {
                abandon(Status.DEADLINE_EXCEEDED.withDescription("deadline expired"))
            }
            this.headers = headers
            val headerMetadata = toMetadata(headers)
            val currentListener = synchronized(lock) { listener.takeUnless { closed } } ?: throw ReadAbandoned()
            callbackExecutor.execute { currentListener.onHeaders(headerMetadata) }
        }

        override fun onData(bytes: ByteArray) {
            val messages =
                try {
                    frames.push(bytes).map { method.responseMarshaller.parse(ByteArrayInputStream(it)) }
                } catch (e: IllegalArgumentException) {
                    abandon(Status.INTERNAL.withDescription("malformed gRPC frames: ${e.message}"))
                }
            if (messages.isEmpty()) return
            synchronized(lock) {
                if (closed) throw ReadAbandoned()
                pending.addAll(messages)
            }
            deliver()
        }

        override fun onTrailers(trailers: List<Pair<String, String>>) {
            this.trailers = trailers
        }

        override fun onEnd() {
            if (frames.hasPartialFrame()) {
                abandon(Status.INTERNAL.withDescription("malformed gRPC frames: truncated frame"))
            }
            if (callOptions.deadline?.isExpired == true) {
                abandon(Status.DEADLINE_EXCEEDED.withDescription("deadline expired"))
            }
            val grpcStatusCode = fieldFromTrailersFirst("grpc-status")?.toIntOrNull()
            if (grpcStatusCode == null) {
                abandon(Status.INTERNAL.withDescription("response is missing grpc-status"))
            }
            val grpcMessage = fieldFromTrailersFirst("grpc-message")?.let { percentDecode(it) }
            val status =
                Status.fromCodeValue(grpcStatusCode).let {
                    if (grpcMessage != null) it.withDescription(grpcMessage) else it
                }
            val trailerMetadata = toMetadata(trailers)
            synchronized(lock) {
                responseComplete = true
                finalStatus = status
                finalTrailers = trailerMetadata
            }
            deliver()
        }

        private fun abandon(status: Status): Nothing {
            finish(status, Metadata())
            throw ReadAbandoned()
        }

        /**
         * The trailer section's [name] if it has one, else the header section's; within a section the
         * last occurrence (a gateway that folds the trailers into the header section appends them).
         */
        private fun fieldFromTrailersFirst(name: String): String? {
            val named: (Pair<String, String>) -> Boolean = { it.first.equals(name, ignoreCase = true) }
            return (trailers.lastOrNull(named) ?: headers.lastOrNull(named))?.second
        }
    }

    private fun validateHead(
        status: Int,
        headers: List<Pair<String, String>>,
    ): Status? {
        val field: (String) -> String? = { name ->
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        }
        val contentType = field("content-type") ?: ""
        val encoding = field("grpc-encoding")
        return when {
            status != HTTP_OK -> {
                val statusMessage = "gateway/lightwalletd returned HTTP $status"
                logger("OHTTP ${method.fullMethodName}: $statusMessage")
                Status.INTERNAL.withDescription(statusMessage)
            }

            !contentType.startsWith(GRPC_CONTENT_TYPE) -> {
                Status.INTERNAL.withDescription("unexpected content-type '$contentType'")
            }

            encoding != null && encoding != "identity" -> {
                Status.INTERNAL.withDescription("unsupported grpc-encoding '$encoding'")
            }

            else -> {
                null
            }
        }
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
                                callRegistry.remove(this@OhttpClientCall)
                            }
                        }
                } else {
                    return
                }
            }
            callbackExecutor.execute(action)
        }
    }

    private fun finish(
        status: Status,
        trailers: Metadata,
    ) {
        val currentListener: Listener<ResponseT>
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            currentListener = listener ?: run {
                // Not started yet, so the counter was never incremented for this call.
                callRegistry.remove(this)
                return
            }
        }
        callbackExecutor.execute {
            try {
                currentListener.onClose(status, trailers)
            } finally {
                activeCalls.decrementAndGet()
                callRegistry.remove(this@OhttpClientCall)
            }
        }
    }

    private class ExchangeBudget(
        val timeoutMs: Int,
        val boundByDeadline: Boolean,
    )

    /** The time the OHTTP call gets: the call's own deadline, if it has one and it is shorter, bounds it. */
    private fun exchangeBudget(grpcTimeoutMs: Int): ExchangeBudget {
        val full = grpcTimeoutMs + if (grpcTimeoutMs >= DEFAULT_TIMEOUT_MS) FULL_LENGTH_SLACK_MS else 0
        val deadlineMs = callOptions.deadline?.timeRemaining(TimeUnit.MILLISECONDS)
        return if (deadlineMs != null && deadlineMs < full) {
            ExchangeBudget(deadlineMs.coerceAtLeast(1).toInt(), boundByDeadline = true)
        } else {
            ExchangeBudget(full, boundByDeadline = false)
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

    /**
     * The gRPC status of a failed exchange: an answer that does not open is INTERNAL, a request over the
     * size cap (an outer 413, or refused before sending) RESOURCE_EXHAUSTED, anything else (outer
     * 401/403/426/429/5xx, transport, a rejected key configuration) UNAVAILABLE.
     */
    private fun grpcStatusOf(e: OhttpException): Status =
        when {
            e is OhttpInternalException -> Status.INTERNAL
            e is OhttpRequestTooLargeException || e.httpStatus == HTTP_PAYLOAD_TOO_LARGE -> Status.RESOURCE_EXHAUSTED
            else -> Status.UNAVAILABLE
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
        private const val GRPC_UNCOMPRESSED_FLAG = 0
        private const val GRPC_LENGTH_FIELD_SIZE = 4
        private const val DEFAULT_TIMEOUT_MS = 75_000
        private const val MIN_TIMEOUT_MS = 1_000

        // A call at the grpc-timeout cap may be held by the gateway for its whole upstream timeout
        // (80 s) before the last chunk leaves: such an exchange gets 100 s, the ZIP's reference.
        private const val FULL_LENGTH_SLACK_MS = 25_000

        private const val PERCENT_ESCAPE_LENGTH = 3
        private const val HEX_RADIX = 16

        private const val HTTP_OK = 200
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
    }
}

/**
 * Splits a gRPC response body (length-prefixed messages, gRPC over HTTP/2) into messages as its bytes
 * arrive: [push] returns the messages they completed.
 */
internal class GrpcFrameReader {
    private var buffer = ByteArray(0)
    private var start = 0
    private var end = 0

    /** @throws IllegalArgumentException on a compressed or impossibly long frame */
    fun push(bytes: ByteArray): List<ByteArray> {
        append(bytes)
        val messages = ArrayList<ByteArray>()
        while (end - start >= FRAME_HEADER_SIZE) {
            require(buffer[start].toInt() == 0) { "compressed frames are not supported" }
            val length = ByteBuffer.wrap(buffer, start + 1, LENGTH_FIELD_SIZE).int
            require(length >= 0) { "frame longer than 2 GiB" }
            val from = start + FRAME_HEADER_SIZE
            if (end - from < length) break
            messages.add(buffer.copyOfRange(from, from + length))
            start = from + length
        }
        return messages
    }

    /** Whether bytes of a frame that has not been completed are left. */
    fun hasPartialFrame(): Boolean = end > start

    private fun append(bytes: ByteArray) {
        if (end + bytes.size > buffer.size) {
            val live = end - start
            val needed = live + bytes.size
            val target = if (needed > buffer.size) ByteArray(maxOf(needed, buffer.size * 2)) else buffer
            System.arraycopy(buffer, start, target, 0, live)
            buffer = target
            start = 0
            end = live
        }
        System.arraycopy(bytes, 0, buffer, end, bytes.size)
        end += bytes.size
    }

    private companion object {
        const val FRAME_HEADER_SIZE = 5
        const val LENGTH_FIELD_SIZE = 4
    }
}
