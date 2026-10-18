package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep

// Cross layer (Kotlin, Rust) classes of the OHTTP JNI surface (`ohttp.rs`). Constructed from native
// code, so the class names and constructor signatures are part of the JNI contract and must be kept.

/** A verified gateway key: the 33 public bytes, the end of its validity (Unix seconds) and its network. */
@Keep
class JniOhttpKey(
    val key: ByteArray,
    val notAfter: Long,
    val network: String,
)

/** A sealed request and the native handle of what opens its answer (0 when there is none). */
@Keep
class JniOhttpSealed(
    val body: ByteArray,
    val opener: Long,
)

/**
 * What the relay's answer came to. [kind] is 0 for a response (then [headers] is a packed field
 * list containing both headers and any trailers, and [trailers] is always an empty packed field
 * list; see `OhttpFields`), 1 to refetch the key configuration, 2 for a refusal ([status] and
 * [detail]), 3 for a success not labelled `message/ohttp-chunked-res` ([detail] is the content type).
 */
@Keep
@Suppress("LongParameterList")
class JniOhttpOutcome(
    val kind: Int,
    val status: Int,
    val headers: ByteArray,
    val trailers: ByteArray,
    val body: ByteArray,
    val detail: String?,
)

/**
 * What one read of a streamed answer completed (`streamPush`, `streamFinish` of `ohttp.rs`): the
 * head ([status] and the packed [headers], or -1 and null when it is not complete yet), the body bytes
 * that came out, the packed trailer section (null until it is read) and whether the response ended.
 */
@Keep
class JniOhttpStreamEvents(
    val status: Int,
    val headers: ByteArray?,
    val body: ByteArray,
    val trailers: ByteArray?,
    val end: Boolean,
)

/** The preset's headers (packed field lists), size caps (-1 for none) and key cache rules. */
@Keep
@Suppress("LongParameterList")
class JniOhttpProfile(
    val postHeaders: ByteArray,
    val keyFetchHeaders: ByteArray,
    val maxSealedResponse: Long,
    val maxKeyConfig: Long,
    val cacheRefreshMarginSecs: Long,
    val dropBeforeRefetch: Boolean,
    val traceOnKeyFetch: Boolean,
    val bodyWindow: Int,
)

/**
 * A failure of the native OHTTP core. [kind] is one of the `KIND_*` constants of `ohttp.rs`
 * (mirrored in `RustOhttpTool`); [message] is the fixed text of the core's error, never request or
 * response content.
 */
@Keep
class JniOhttpException(
    val kind: Int,
    message: String,
) : RuntimeException(message)
