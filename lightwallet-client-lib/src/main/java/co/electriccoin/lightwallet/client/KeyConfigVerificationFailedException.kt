package co.electriccoin.lightwallet.client

/**
 * Exception thrown when signed config verification fails. An [OhttpException], so every caller of the
 * transport fails the call closed (the gateway is treated as unreachable) instead of letting it escape.
 */
class KeyConfigVerificationFailedException(
    message: String,
    cause: Throwable? = null,
) : OhttpException(message, null, cause)
