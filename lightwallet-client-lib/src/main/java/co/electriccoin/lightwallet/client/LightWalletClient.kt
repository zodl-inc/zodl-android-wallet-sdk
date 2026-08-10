package co.electriccoin.lightwallet.client

import android.content.Context
import co.electriccoin.lightwallet.client.internal.AndroidChannelFactory
import co.electriccoin.lightwallet.client.internal.LightWalletClientImpl
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// OHTTP relay hostname prefix — if the endpoint host starts with "ohttp-",
// requests are routed through the OHTTP relay instead of connecting directly.
private const val OHTTP_HOST_PREFIX = "ohttp-"
private const val OHTTP_DEFAULT_RELAY = "https://ohttp-lwd-testnet.zodl.com"

interface LightWalletClient : WalletClient {
    companion object {
        fun new(
            context: Context,
            lightWalletEndpoint: LightWalletEndpoint,
            singleRequestTimeout: Duration = 10.seconds,
            streamingRequestTimeout: Duration = 90.seconds
        ): LightWalletClient =
            LightWalletClientImpl(
                // Use OHTTP channel factory when endpoint is an OHTTP relay hostname
                channelFactory = if (lightWalletEndpoint.host.startsWith(OHTTP_HOST_PREFIX)) {
                    co.electriccoin.lightwallet.client.internal.OhttpChannelFactory(
                        relayUrl = "https://${lightWalletEndpoint.host}"
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
