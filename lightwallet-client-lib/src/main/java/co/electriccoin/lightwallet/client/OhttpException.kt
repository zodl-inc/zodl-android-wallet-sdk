package co.electriccoin.lightwallet.client

import java.io.IOException

/**
 * OHTTP transport exception: wraps network errors, HTTP errors from the relay/gateway, and
 * encapsulation/decapsulation failures.
 */
open class OhttpException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * OHTTP protocol exception for AEAD or Binary HTTP format failures that should
 * be mapped to gRPC INTERNAL status.
 */
class OhttpInternalException(
    message: String,
    cause: Throwable? = null,
) : OhttpException(message, null, cause)
