package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import cash.z.ecc.android.sdk.internal.jni.RustOhttpTool
import co.electriccoin.lightwallet.client.InternalOhttpApi
import co.electriccoin.lightwallet.client.internal.OhttpEndpoints
import co.electriccoin.lightwallet.client.model.OhttpConfiguration

/**
 * The OHTTP (RFC 9458) configuration of the process.
 *
 * The SDK carries no OHTTP endpoint or key, neither in its sources nor from its build: the app passes
 * the configuration of mainnet and testnet in once at startup with [configure], before it creates a
 * [Synchronizer] or uses any other OHTTP API of the SDK. Until then, and for a network the configuration
 * leaves null, OHTTP is off (fail closed): no host is that network's gateway, so a lightwalletd endpoint
 * on its gateway host is a plain server, and the OHTTP HTTP client
 * (`co.electriccoin.lightwallet.client.OhttpHttpClient`) refuses every call for that network.
 */
object Ohttp {
    private val lock = Any()

    /**
     * Installs [configuration] for the whole process, in the Kotlin layer and in the native library.
     *
     * Call it once at startup, for example in `Application.onCreate`, before anything else in the SDK.
     * It never loads the native library and never blocks on it, so it is safe on the main thread:
     * - when the library is already loaded, the native library checks and installs the values right
     *   away, and a refused value throws here;
     * - otherwise the values are installed in the Kotlin layer now and in the native library as soon as
     *   it loads (right after its initialization, before any other use of it). If the native library
     *   refuses them then, OHTTP is turned off for the whole process (every OHTTP route fails closed)
     *   and the refusal is logged; nothing is thrown, since no caller is waiting.
     *
     * The configuration is set once per process and the call is thread-safe:
     * - the first call that succeeds wins; null (OHTTP off for both networks) is a configuration too;
     * - a later call with an equal configuration succeeds and changes nothing;
     * - a later call with a different one throws [IllegalStateException] and the first stays;
     * - a first call made after the SDK already read the OHTTP state (and so ran with OHTTP off) throws
     *   [IllegalStateException] instead of turning OHTTP on for only part of the process.
     *
     * Every OHTTP route reads this configuration: the lightwalletd gateways
     * (`co.electriccoin.lightwallet.client.LightWalletClient.isOhttpGateway`, `ohttpGatewayHost`), the
     * Slipstream engine, the OHTTP HTTP client for the proxied services and voting sessions. With OHTTP
     * off for the wallet's network, a voting session reaches the predefined vote and PIR servers like
     * custom ones (directly with Tor off); with it on they ride the gateway and fail closed.
     *
     * Values are normalized by [co.electriccoin.lightwallet.client.model.OhttpNetworkConfiguration]
     * (trimmed, host names lower-cased) and checked by the native library, the only implementation of
     * the rules.
     *
     * @throws IllegalArgumentException when the library is loaded and a value breaks its rule or the two
     *   networks share a value. The message names the network, the field and the rule, never the value.
     *   Nothing is installed, so the call may be repeated with a corrected configuration.
     * @throws IllegalStateException when a different configuration is already installed, or when the SDK
     *   read the OHTTP state before this first call
     */
    @JvmStatic
    @OptIn(InternalOhttpApi::class)
    fun configure(configuration: OhttpConfiguration?) =
        synchronized(lock) {
            if (RustBackend.isLoaded) {
                OhttpEndpoints.install(configuration, ::installNative)
                return@synchronized
            }
            OhttpEndpoints.install(configuration)
            RustBackend.onLibraryLoaded = { installNativeAtLoad(configuration) }
            // The library may have finished loading after the check above but before the hook was set.
            if (RustBackend.isLoaded) installNativeAtLoad(configuration)
        }

    /** The installed configuration, or null while none is installed (or OHTTP is off for both networks). */
    @JvmStatic
    @OptIn(InternalOhttpApi::class)
    val configuration: OhttpConfiguration?
        get() = OhttpEndpoints.configuration

    /** Whether OHTTP is on for testnet ([isTestnet] true) or mainnet. */
    @JvmStatic
    fun isOn(isTestnet: Boolean): Boolean = configuration?.forNetwork(isTestnet) != null

    private fun installNative(configuration: OhttpConfiguration?) {
        val mainnet = configuration?.mainnet
        val testnet = configuration?.testnet
        RustOhttpTool.configureNative(
            mainnetGatewayHost = mainnet?.gatewayHost,
            mainnetRelayUrl = mainnet?.relayUrl,
            mainnetKeysDnsName = mainnet?.keysDnsName,
            mainnetSigningPublicKey = mainnet?.signingPublicKey,
            testnetGatewayHost = testnet?.gatewayHost,
            testnetRelayUrl = testnet?.relayUrl,
            testnetKeysDnsName = testnet?.keysDnsName,
            testnetSigningPublicKey = testnet?.signingPublicKey,
        )
    }

    /**
     * The native part of a configuration installed before the library loaded. Installing an equal
     * configuration twice is a no-op in the native library, so the load hook and [configure] may both
     * run it.
     */
    @OptIn(InternalOhttpApi::class)
    private fun installNativeAtLoad(configuration: OhttpConfiguration?) {
        try {
            installNative(configuration)
        } catch (e: IllegalArgumentException) {
            failClosed(e)
        } catch (e: IllegalStateException) {
            failClosed(e)
        }
    }

    @OptIn(InternalOhttpApi::class)
    private fun failClosed(e: RuntimeException) {
        OhttpEndpoints.failClosed()
        // The message names the network, the field and the rule, never a value.
        Twig.error { "OHTTP configuration refused by the native library, OHTTP is off: ${e.message}" }
    }
}
