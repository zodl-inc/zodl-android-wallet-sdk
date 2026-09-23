package co.electriccoin.lightwallet.client.internal

import android.util.Log
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import io.grpc.ManagedChannel

/**
 * [ChannelFactory] that serves a lightwalletd endpoint through Oblivious HTTP (RFC 9458).
 *
 * The endpoint host is the ZODL OHTTP *gateway*; every gRPC call is HPKE-encrypted to the gateway's
 * published key and sent via the configured *relay*, so:
 *
 *   - the relay sees the client IP but only ciphertext,
 *   - the gateway (and lightwalletd behind it) sees the request but only the relay's IP.
 *
 * See [OhttpManagedChannel] for the gRPC transport and [OhttpTransport] for the OHTTP framing.
 */
internal class OhttpChannelFactory(
    private val relayUrl: String,
    /**
     * Optional client trace ID prefix. When set, every outer OHTTP POST to the relay carries
     * `X-Client-Trace-Id: <prefix>-<uuid>`. See [OhttpTransport.ohttpClientTraceIdPrefix] for details.
     */
    private val ohttpClientTraceIdPrefix: String? = null,
) : ChannelFactory {
    override fun newChannel(endpoint: LightWalletEndpoint): ManagedChannel {
        require(endpoint.isSecure) { "OHTTP endpoints must be secure (https)" }
        val logger: (String) -> Unit = { Log.i(Constants.LOG_TAG, it) }
        val network = OhttpEndpoints.networkFor(endpoint.host)
        Log.i(Constants.LOG_TAG, "OHTTP channel: gateway=${endpoint.host} relay=$relayUrl network=$network")
        val transport =
            OhttpTransport(
                relayUrl = relayUrl,
                network = network,
                logger = logger,
                ohttpClientTraceIdPrefix = ohttpClientTraceIdPrefix,
            )
        return OhttpManagedChannel(authority = endpoint.host, transport = transport, logger = logger)
    }
}
