package co.electriccoin.lightwallet.client

import java.io.IOException

/**
 * OHTTP transport exception: wraps network errors, HTTP errors from the relay/gateway, and
 * encapsulation/decapsulation failures.
 *
 * @property retryAfterSeconds the delay a 429 answer asked for (`Retry-After`, delay-seconds form)
 */
open class OhttpException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
    val retryAfterSeconds: Long? = null,
) : IOException(message, cause)

/** The gateway's answer did not open: AEAD, chunk framing or Binary HTTP failure (gRPC `INTERNAL`). */
class OhttpInternalException(
    message: String,
    cause: Throwable? = null,
) : OhttpException(message, null, cause)

/**
 * The sealed request is larger than the relay and the gateway accept, so it was not sent; handled
 * like an outer 413 (gRPC `RESOURCE_EXHAUSTED`).
 */
class OhttpRequestTooLargeException(
    message: String,
    cause: Throwable? = null,
) : OhttpException(message, null, cause)
