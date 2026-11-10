package cash.z.ecc.android.sdk.util

import cash.z.ecc.android.sdk.internal.jni.RustOhttpTool
import co.electriccoin.lightwallet.client.OhttpCodec

/**
 * Provides the native [OhttpCodec] (the shared `zodl-ohttp-client` core, Android profile) that
 * `co.electriccoin.lightwallet.client.OhttpHttpClient.create` and
 * `co.electriccoin.lightwallet.client.LightWalletClient.new` need to talk Oblivious HTTP. The
 * lightwallet-client module does not depend on the native library, so the SDK wires it here.
 */
object OhttpCodecFactory {
    /** Loads the native library if needed and returns the (shared, thread-safe) codec. */
    suspend fun create(): OhttpCodec = RustOhttpTool.new()
}
