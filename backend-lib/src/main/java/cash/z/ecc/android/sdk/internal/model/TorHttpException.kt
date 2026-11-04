package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep

/**
 * Base type of the typed failures raised by [TorClient.httpGet] / [TorClient.httpPost].
 *
 * Only the subclasses below are thrown across the JNI boundary; any other native failure (bad URL,
 * malformed request, ...) still surfaces as a plain [RuntimeException] and is not meant to be
 * retried.
 */
open class TorHttpException(
    message: String
) : RuntimeException(message)

/**
 * The request failed at the network/transport level over Tor: a circuit or stream error, a
 * connection or TLS failure, or one of the Tor HTTP client's own per-phase timeouts. Such failures
 * are transient, so callers may retry.
 *
 * Constructed from native code (`tor_http.rs`), so the class name and the `(String)` constructor
 * are part of the JNI contract and must be kept.
 */
@Keep
class TorHttpTransportException(
    message: String
) : TorHttpException(message)

/**
 * The caller-supplied `timeoutMs` elapsed before the request completed. It is enforced inside the
 * native call, so the call has actually been abandoned (and the [TorClient] mutex released) when
 * this is thrown.
 *
 * It is deliberately NOT a [TorHttpTransportException]: the caller's whole time budget is spent, so
 * retrying would at least double the latency the caller asked for.
 *
 * Constructed from native code (`tor_http.rs`), so the class name and the `(String)` constructor
 * are part of the JNI contract and must be kept.
 */
@Keep
class TorHttpTimeoutException(
    message: String
) : TorHttpException(message)

/**
 * The caller cancelled the exchange (the coroutine of [TorClient.httpPostStreaming] was cancelled)
 * while it waited for the response head or in an [TorClient.HttpBody.read]. Nothing failed on the
 * network, so it is deliberately NOT a [TorHttpTransportException]: retrying would send again a
 * request the caller has abandoned.
 *
 * Constructed from native code (`tor_http.rs`), so the class name and the `(String)` constructor
 * are part of the JNI contract and must be kept.
 */
@Keep
class TorHttpCancelledException(
    message: String
) : TorHttpException(message)
