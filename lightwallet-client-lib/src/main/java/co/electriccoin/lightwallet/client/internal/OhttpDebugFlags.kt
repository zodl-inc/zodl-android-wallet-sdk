package co.electriccoin.lightwallet.client.internal

import androidx.annotation.VisibleForTesting
import co.electriccoin.lightwallet.client.BuildConfig

/**
 * Debug-only flags for OHTTP transport. Production builds must not enable these.
 */
internal object OhttpDebugFlags {
    /**
     * When true, allows setting a client trace prefix that will be sent to the relay/gateway.
     * Follows this library's build type: true for debug builds only, so a release SDK never
     * sends a trace id even if the app passes a prefix.
     */
    @VisibleForTesting
    val allowTracePrefix: Boolean = BuildConfig.DEBUG
}
