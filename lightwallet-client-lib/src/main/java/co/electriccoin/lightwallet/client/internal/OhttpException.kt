package co.electriccoin.lightwallet.client.internal

import java.io.IOException

/**
 * OHTTP transport exception: wraps network errors, HTTP errors from the relay/gateway, and
 * encapsulation/decapsulation failures.
 */
class OhttpException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : IOException(message, cause)
