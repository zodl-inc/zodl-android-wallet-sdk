package cash.z.ecc.android.sdk.util

import android.content.Context
import cash.z.ecc.android.sdk.internal.TorOhttpSender
import cash.z.ecc.android.sdk.internal.model.CombinedWalletClientImpl
import cash.z.ecc.android.sdk.internal.model.LazyTorClient
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.OhttpSender
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
     * Other functions specified in [WalletClient] will use regular lightwalletd connection using [LightWalletClient];
     * in a Tor mode, those of an OHTTP gateway [endpoint] go through OHTTP with the relay leg over Tor.
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
        val ohttpCodec =
            if (LightWalletClient.isOhttpGateway(endpoint)) {
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
            torStreamingClientFactory =
                if (ohttpCodec != null && torClient != null) {
                    {
                        val sender = TorOhttpSender(torClient.getOrCreate().isolatedTorClient())
                        val client =
                            LightWalletClient.new(
                                context,
                                endpoint,
                                ohttpClientTraceIdPrefix = ohttpClientTraceIdPrefix,
                                ohttpCodec = ohttpCodec,
                                ohttpSender = sender
                            )
                        SenderOwningLightWalletClient(client, sender)
                    }
                } else {
                    null
                }
        )
    }
}

/** A [LightWalletClient] that also releases the [OhttpSender] (and so its Tor client) it was built with. */
private class SenderOwningLightWalletClient(
    private val client: LightWalletClient,
    private val sender: OhttpSender
) : LightWalletClient by client {
    override suspend fun dispose() {
        client.dispose()
        sender.dispose()
    }
}
