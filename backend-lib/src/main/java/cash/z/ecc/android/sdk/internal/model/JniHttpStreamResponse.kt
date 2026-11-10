package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep

/**
 * Serves as cross layer (Kotlin, Rust) communication class: the head of a response over Tor whose
 * body is read as it arrives (`httpPostStream` of `lib.rs`).
 *
 * @param status the HTTP status code
 * @param headers the response headers
 * @param body the native handle of the body, owned by [TorClient.HttpBody]
 */
@Keep
class JniHttpStreamResponse(
    val status: Int,
    val headers: Array<JniHttpHeader>,
    val body: Long,
)
