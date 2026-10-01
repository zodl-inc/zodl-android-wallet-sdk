package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.OhttpCodec
import co.electriccoin.lightwallet.client.OhttpCompletion
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * A sealed request waiting for its answer: owns the native opener handle of an
 * [co.electriccoin.lightwallet.client.OhttpSealedRequest].
 *
 * The handle is swapped for 0 before it is used, by [complete] or by [close], so it is spent or
 * freed exactly once even if a coroutine is cancelled between sealing and completing.
 */
internal class OhttpPending(
    private val codec: OhttpCodec,
    opener: Long,
) : Closeable {
    private val handle = AtomicLong(opener)

    /** [OhttpCodec.complete] with this request's opener, which is spent from here on. */
    fun complete(
        outerStatus: Int,
        contentType: String?,
        body: ByteArray,
        attempt: Int,
    ): OhttpCompletion {
        val spent = handle.getAndSet(0)
        check(spent != 0L) { "The OHTTP response opener is already spent" }
        return codec.complete(spent, outerStatus, contentType, body, attempt)
    }

    /** Frees the opener unless [complete] has spent it. */
    override fun close() {
        val abandoned = handle.getAndSet(0)
        if (abandoned != 0L) codec.free(abandoned)
    }
}
