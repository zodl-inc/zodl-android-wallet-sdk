package co.electriccoin.lightwallet.client.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Generates client trace IDs for OHTTP requests in the format: `<prefix>-<sessionUuid>-<seq>`.
 * The session UUID is generated once per process; seq is a monotonic counter (zero-padded 6 digits).
 * Prefix is sanitized to `[A-Za-z0-9._:-]` with first char alphanumeric, and the total ID is
 * truncated to ≤ 64 chars.
 */
internal class OhttpClientTrace(
    prefix: String,
) {
    private val sanitizedPrefix: String
    private val sessionUuid: String = UUID.randomUUID().toString()
    private val sequenceCounter = AtomicLong(0)

    init {
        // Sanitize prefix: keep only [A-Za-z0-9._:-], ensure first char is alphanumeric
        val cleaned = prefix.replace(Regex("[^A-Za-z0-9._:-]"), "")
        sanitizedPrefix =
            when {
                cleaned.isEmpty() -> {
                    "trace"
                }

                cleaned.first().isLetterOrDigit() -> {
                    cleaned
                }

                else -> {
                    // Drop leading non-alphanumeric chars until we find one
                    val alphaNumIndex = cleaned.indexOfFirst { it.isLetterOrDigit() }
                    if (alphaNumIndex >= 0) cleaned.substring(alphaNumIndex) else "trace"
                }
            }
    }

    /**
     * Generates the next trace ID: `<prefix>-<sessionUuid>-<seq>`, truncated to 64 chars.
     */
    fun next(): String {
        val seq = sequenceCounter.getAndIncrement()
        val seqStr = seq.toString().padStart(SEQ_PAD_LENGTH, '0')
        val full = "$sanitizedPrefix-$sessionUuid-$seqStr"
        return if (full.length <= MAX_LENGTH) full else full.take(MAX_LENGTH)
    }

    companion object {
        private const val MAX_LENGTH = 64
        private const val SEQ_PAD_LENGTH = 6
    }
}
