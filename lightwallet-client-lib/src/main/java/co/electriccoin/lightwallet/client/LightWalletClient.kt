package co.electriccoin.lightwallet.client

import android.content.Context
import co.electriccoin.lightwallet.client.internal.AndroidChannelFactory
import co.electriccoin.lightwallet.client.internal.LightWalletClientImpl
import co.electriccoin.lightwallet.client.internal.OhttpChannelFactory
import co.electriccoin.lightwallet.client.internal.OhttpEndpoints
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface LightWalletClient : WalletClient {
    companion object {
        /**
         * Creates a new LightWalletClient for the given endpoint.
         *
         * @param ohttpClientTraceIdPrefix Optional client trace ID prefix for end-to-end tracing
         *        in OHTTP requests. When set, every outer OHTTP POST carries `X-Client-Trace-Id: <prefix>-<uuid>`.
         *        **Privacy warning:** makes requests linkable. DEBUG ONLY. Off by default.
         */
        fun new(
            context: Context,
            lightWalletEndpoint: LightWalletEndpoint,
            singleRequestTimeout: Duration = 10.seconds,
            streamingRequestTimeout: Duration = 90.seconds,
            ohttpClientTraceIdPrefix: String? = null
        ): LightWalletClient =
            LightWalletClientImpl(
                // Endpoints that are ZODL OHTTP gateways are reached through an OHTTP relay
                // (RFC 9458) instead of a direct gRPC connection.
                channelFactory =
                    if (OhttpEndpoints.isOhttpGateway(lightWalletEndpoint.host)) {
                        OhttpChannelFactory(
                            OhttpEndpoints.relayUrlFor(lightWalletEndpoint.host),
                            ohttpClientTraceIdPrefix = ohttpClientTraceIdPrefix
                        )
                    } else {
                        AndroidChannelFactory(context)
                    },
                lightWalletEndpoint = lightWalletEndpoint,
                singleRequestTimeout = singleRequestTimeout,
                streamingRequestTimeout = streamingRequestTimeout
            )
    }
}
