package co.electriccoin.lightwallet.client.internal

import androidx.annotation.VisibleForTesting

/**
 * Debug-only flags for OHTTP transport. Production builds must not enable these.
 */
internal object OhttpDebugFlags {
    /**
     * When true, allows setting a client trace prefix that will be sent to the relay/gateway.
     * MUST remain false in production to prevent correlation across requests.
     */
    @VisibleForTesting
    var allowTracePrefix: Boolean = false
}
