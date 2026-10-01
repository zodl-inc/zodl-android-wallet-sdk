package co.electriccoin.lightwallet.client

/**
 * Exception thrown when signed config verification fails.
 */
class KeyConfigVerificationFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
