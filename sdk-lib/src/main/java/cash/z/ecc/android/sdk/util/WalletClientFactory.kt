package cash.z.ecc.android.sdk.util

import android.content.Context
import cash.z.ecc.android.sdk.internal.model.CombinedWalletClientImpl
import cash.z.ecc.android.sdk.internal.model.LazyTorClient
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.PartialTorWalletClient
import co.electriccoin.lightwallet.client.WalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/**
 * A factory responsible for creating an instance of [WalletClient].
 */
class WalletClientFactory(
    private val context: Context,
    private val torClient: LazyTorClient?
) {
    /**
     * Creates a [CombinedWalletClientImpl] which will leverage Tor for lightwalletd connection for functions specified
     * in [PartialTorWalletClient].
     * Other functions specified in [WalletClient] will use regular lightwalletd connection using [LightWalletClient].
     *
     * The base Tor client (and its isolated client used here) is not created until the first Tor-mode
     * request is actually made, keeping Tor runtime creation off the cold-start path.
     *
     * @return an instance of [WalletClient] for [endpoint]
     */
    suspend fun create(
        endpoint: LightWalletEndpoint,
        ohttpClientTraceIdPrefix: String? = null
    ): CombinedWalletClient {
        // Only create the native codec for known OHTTP gateways. This avoids loading the native
        // library in pure-JVM unit tests that use non-OHTTP endpoints (the codec is only required
        // when the endpoint is an OHTTP gateway; LightWalletClient.new throws if missing).
        val ohttpCodec = if (isKnownOhttpGateway(endpoint.host)) {
            OhttpCodecFactory.create()
        } else {
            null
        }
        return CombinedWalletClientImpl.new(
            endpoint = endpoint,
            lightWalletClient =
                LightWalletClient.new(
                    context,
                    endpoint,
                    ohttpClientTraceIdPrefix = ohttpClientTraceIdPrefix,
                    ohttpCodec = ohttpCodec
                ),
            isolatedTorClient = torClient?.let { holder -> LazyTorClient { holder.getOrCreate().isolatedTorClient() } },
        )
    }

    /**
     * Returns true for the exact ZODL OHTTP gateway hosts that require the native codec.
     * This mirrors the check in co.electriccoin.lightwallet.client.internal.OhttpEndpoints
     * (which is internal to lightwallet-client-lib).
     */
    private fun isKnownOhttpGateway(host: String): Boolean {
        return host == "ohttp-gateway.example" || host == "ohttp-gateway-testnet.example"
    }
}
