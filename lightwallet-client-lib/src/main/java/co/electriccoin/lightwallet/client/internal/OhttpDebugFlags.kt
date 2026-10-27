package co.electriccoin.lightwallet.client.internal

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
    val allowTracePrefix: Boolean = BuildConfig.DEBUG

    /**
     * When true, the Tor-off OHTTP path logs per-call timings under the tag `OhttpTiming` (see
     * [OhttpTiming]): debug builds only, a release SDK installs no listener and logs nothing.
     */
    val logTiming: Boolean = BuildConfig.DEBUG
}
