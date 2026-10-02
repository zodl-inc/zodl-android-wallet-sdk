package cash.z.ecc.android.sdk.util

import cash.z.ecc.android.sdk.internal.jni.RustOhttpTool
import co.electriccoin.lightwallet.client.RelayUrlMapper

/**
 * Provides the native [RelayUrlMapper] implementation from `zodl-ohttp-client`. The
 * lightwallet-client module does not depend on the native library, so the SDK wires it here.
 */
object RelayUrlMapperFactory {
    /** Returns the (shared, thread-safe) relay URL mapper. */
    fun create(): RelayUrlMapper = NativeRelayUrlMapper

    private object NativeRelayUrlMapper : RelayUrlMapper {
        override fun relayUrlFor(gatewayHost: String): String? = RustOhttpTool.relayUrlFor(gatewayHost)
    }
}
