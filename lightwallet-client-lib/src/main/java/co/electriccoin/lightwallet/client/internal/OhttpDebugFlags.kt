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

    /**
     * When true, OHTTP clients log what they talk to and what they send: gateway and relay, the
     * request's host, method and inner authority, the inner status and size. Debug builds only: a
     * release SDK puts none of it in logcat, which other apps on old Android versions and bug
     * reports can read.
     */
    val logRequests: Boolean = BuildConfig.DEBUG
}
