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
         * True when [lightWalletEndpoint] is a ZODL OHTTP gateway, i.e. when [new] needs an [OhttpCodec].
         * Callers can use it to create the native codec only when it is used.
         */
        fun isOhttpGateway(lightWalletEndpoint: LightWalletEndpoint): Boolean =
            OhttpEndpoints.isOhttpGateway(lightWalletEndpoint.host)

        /**
         * Creates a new LightWalletClient for the given endpoint.
         *
         * @param ohttpClientTraceIdPrefix Optional client trace ID prefix for end-to-end tracing
         *        in OHTTP requests. When set, every outer OHTTP POST carries `X-Client-Trace-Id: <prefix>-<uuid>`.
         *        **Privacy warning:** makes requests linkable. DEBUG ONLY. Off by default.
         * @param ohttpCodec the native OHTTP protocol implementation, required (and only used) when
         *        [lightWalletEndpoint] is a ZODL OHTTP gateway. `cash.z.ecc.android.sdk.util.WalletClientFactory`
         *        supplies it for the SDK's own clients.
         */
        @Suppress("LongParameterList")
        fun new(
            context: Context,
            lightWalletEndpoint: LightWalletEndpoint,
            singleRequestTimeout: Duration = 10.seconds,
            streamingRequestTimeout: Duration = 90.seconds,
            ohttpClientTraceIdPrefix: String? = null,
            ohttpCodec: OhttpCodec? = null,
        ): LightWalletClient =
            LightWalletClientImpl(
                // Endpoints that are ZODL OHTTP gateways are reached through an OHTTP relay
                // (RFC 9458) instead of a direct gRPC connection.
                channelFactory =
                    if (isOhttpGateway(lightWalletEndpoint)) {
                        val codec =
                            requireNotNull(ohttpCodec) {
                                "An OhttpCodec is required for the OHTTP gateway ${lightWalletEndpoint.host}"
                            }
                        val relayUrl =
                            codec.relayUrlFor(lightWalletEndpoint.host)
                                ?: error("No OHTTP relay configured for gateway host ${lightWalletEndpoint.host}")
                        OhttpChannelFactory(
                            relayUrl,
                            codec,
                            ohttpClientTraceIdPrefix = ohttpClientTraceIdPrefix,
                        )
                    } else {
                        AndroidChannelFactory(context)
                    },
                lightWalletEndpoint = lightWalletEndpoint,
                singleRequestTimeout = singleRequestTimeout,
                streamingRequestTimeout = streamingRequestTimeout,
            )
    }
}
