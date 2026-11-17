package cash.z.ecc.android.sdk.internal.transaction

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A stored terminal rejection: the server's code and its reason, as it reported them. */
internal data class StoredRejection(
    val code: Int,
    val reason: String
)

@Suppress("TooManyFunctions")
internal object PendingSubmitPlanCodec {
    fun decode(storedPlans: String): Map<String, List<LightWalletEndpoint>> {
        val lines =
            storedPlans
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()

        if (!lines.hasSupportedVersion()) {
            return emptyMap()
        }

        val plans = mutableMapOf<String, List<LightWalletEndpoint>>()
        lines.forEach { line ->
            val transactionId = line.substringBefore(KEY_VALUE_SEPARATOR)
            if (transactionId != FIELD_VERSION &&
                transactionId.isNotBlank() &&
                line.contains(KEY_VALUE_SEPARATOR)
            ) {
                plans[transactionId] = line.substringAfter(KEY_VALUE_SEPARATOR).toEndpoints()
            }
        }
        return plans
    }

    fun encode(plans: Map<String, List<LightWalletEndpoint>>): String =
        buildString {
            append(FIELD_VERSION)
            append(KEY_VALUE_SEPARATOR)
            append(VERSION)
            plans.toSortedMap().forEach { (transactionId, endpoints) ->
                appendLine()
                append(transactionId)
                append(KEY_VALUE_SEPARATOR)
                append(endpoints.toStorageText())
            }
        }

    private fun String.toEndpoints(): List<LightWalletEndpoint> =
        if (isBlank()) {
            emptyList()
        } else {
            split(ENDPOINT_SEPARATOR)
                .mapNotNull { it.toEndpoint() }
                .distinct()
        }

    private fun List<LightWalletEndpoint>.toStorageText(): String =
        joinToString(ENDPOINT_SEPARATOR) { endpoint ->
            listOf(
                endpoint.host.encode(),
                endpoint.port.toString(),
                endpoint.isSecure.toString()
            ).joinToString(FIELD_SEPARATOR)
        }

    private fun String.toEndpoint(): LightWalletEndpoint? {
        val fields = split(FIELD_SEPARATOR)
        val endpoint =
            if (fields.size == ENDPOINT_FIELD_COUNT) {
                val host = fields[ENDPOINT_HOST_INDEX].decode()
                val port = fields[ENDPOINT_PORT_INDEX].toIntOrNull()
                val isSecure = fields[ENDPOINT_IS_SECURE_INDEX].toBooleanStrictOrNull()
                if (host.isBlank() || port == null || isSecure == null) {
                    null
                } else {
                    LightWalletEndpoint(host, port, isSecure)
                }
            } else {
                null
            }
        return endpoint
    }

    /** Reads the rejections [encodeRejections] wrote; anything malformed is dropped. */
    fun decodeRejections(stored: String): Map<String, StoredRejection> {
        val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
        if (!lines.hasSupportedVersion()) {
            return emptyMap()
        }
        return lines
            .filter { it.contains(KEY_VALUE_SEPARATOR) && it.substringBefore(KEY_VALUE_SEPARATOR) != FIELD_VERSION }
            .mapNotNull { line ->
                val fields =
                    line
                        .substringAfter(KEY_VALUE_SEPARATOR)
                        .split(FIELD_SEPARATOR, limit = REJECTION_FIELD_COUNT)
                val code = fields.firstOrNull()?.toIntOrNull()
                if (fields.size != REJECTION_FIELD_COUNT || code == null) {
                    null
                } else {
                    line.substringBefore(KEY_VALUE_SEPARATOR) to StoredRejection(code, fields[1].decode())
                }
            }.toMap()
    }

    fun encodeRejections(rejections: Map<String, StoredRejection>): String =
        buildString {
            append(FIELD_VERSION)
            append(KEY_VALUE_SEPARATOR)
            append(VERSION)
            rejections.toSortedMap().forEach { (transactionId, rejection) ->
                appendLine()
                append(transactionId)
                append(KEY_VALUE_SEPARATOR)
                append(rejection.code)
                append(FIELD_SEPARATOR)
                append(rejection.reason.encode())
            }
        }

    /**
     * Reads the marks [encodeMarks] wrote, each mapped to when it was made, in seconds since the
     * epoch; a mark without a readable time (such as one written as a flag, `true`) maps to
     * [unknownTime].
     */
    fun decodeMarks(
        stored: String,
        unknownTime: Long
    ): Map<String, Long> {
        val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
        if (!lines.hasSupportedVersion()) {
            return emptyMap()
        }
        return lines
            .filter { it.contains(KEY_VALUE_SEPARATOR) && it.substringBefore(KEY_VALUE_SEPARATOR) != FIELD_VERSION }
            .associate {
                it.substringBefore(KEY_VALUE_SEPARATOR) to
                    (it.substringAfter(KEY_VALUE_SEPARATOR).toLongOrNull() ?: unknownTime)
            }
    }

    /** Writes [marks], each with when it was made, in seconds since the epoch. */
    fun encodeMarks(marks: Map<String, Long>): String =
        buildString {
            append(FIELD_VERSION)
            append(KEY_VALUE_SEPARATOR)
            append(VERSION)
            marks.toSortedMap().forEach { (key, markedAt) ->
                appendLine()
                append(key)
                append(KEY_VALUE_SEPARATOR)
                append(markedAt)
            }
        }

    private fun List<String>.hasSupportedVersion(): Boolean =
        firstOrNull { line ->
            line.contains(KEY_VALUE_SEPARATOR) &&
                line.substringBefore(KEY_VALUE_SEPARATOR) == FIELD_VERSION
        }?.substringAfter(KEY_VALUE_SEPARATOR)?.toIntOrNull() == VERSION

    private fun String.encode() = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.decode() = URLDecoder.decode(this, StandardCharsets.UTF_8.name())

    private const val VERSION = 1
    private const val FIELD_VERSION = "version"
    private const val KEY_VALUE_SEPARATOR = "="
    private const val ENDPOINT_SEPARATOR = "|"
    private const val FIELD_SEPARATOR = ","
    private const val ENDPOINT_FIELD_COUNT = 3
    private const val ENDPOINT_HOST_INDEX = 0
    private const val ENDPOINT_PORT_INDEX = 1
    private const val ENDPOINT_IS_SECURE_INDEX = 2
    private const val REJECTION_FIELD_COUNT = 2
}
