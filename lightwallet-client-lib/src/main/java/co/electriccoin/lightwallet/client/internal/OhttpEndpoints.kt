package co.electriccoin.lightwallet.client.internal

/**
 * Static OHTTP (RFC 9458) endpoint mapping.
 *
 * A [co.electriccoin.lightwallet.client.model.LightWalletEndpoint] whose host is one of the ZODL
 * OHTTP gateways is served through an OHTTP relay instead of a direct gRPC connection:
 *
 *   app ──HPKE-encrypted──▶ relay (sees IP, not content) ──▶ gateway (sees content, not IP) ──▶ lightwalletd
 *
 * The gateway's signed HPKE key configuration is fetched through the relay (see
 * [co.electriccoin.lightwallet.client.OhttpCodec.signedConfigUrl]); which network a gateway host serves,
 * and so which signing key is trusted for it, is decided by the same codec.
 */
internal object OhttpEndpoints {
    private val relayByGatewayHost: Map<String, String> =
        mapOf(
            "ohttp-gateway-testnet.example" to "https://ohttp-relay-testnet.example/relay",
            "ohttp-gateway.example" to "https://ohttp-relay.example/relay",
        )

    /**
     * True only for the exact gateway hosts we have a relay for. A prefix match would route a custom
     * server such as `ohttp-gateway.example.org` into [relayUrlFor], which throws.
     */
    fun isOhttpGateway(host: String): Boolean = relayByGatewayHost.containsKey(host)

    fun relayUrlFor(gatewayHost: String): String =
        relayByGatewayHost[gatewayHost]
            ?: throw IllegalArgumentException("No OHTTP relay configured for gateway host $gatewayHost")
}

// =============================================================================
// External API proxy mappings
// =============================================================================
// Non-LWD calls (NEAR intents, voting config) can also be routed
// through the OHTTP gateway. The gateway routes by `authority` header and proxies
// to the real backend; external services only ever see the gateway's IP.
//
// Privacy routing matrix:
//   Tor OFF: use OHTTP for all external calls (gateway hides client IP)
//   Tor ON:  LWD → Tor→OHTTP; other external calls → Tor direct (no double-wrap)
//
// Note: Sapling params are now hosted on GitHub (raw.githubusercontent.com) and downloaded
// directly via HTTPS (Tor OFF) or through the SDK's Tor client (Tor ON); no OHTTP proxying.

/**
 * Allowlisted host suffixes for direct OHTTP proxying (gateway v1.4.5+).
 * Hosts matching these suffixes are sent to the gateway with their real `:authority`
 * (no hostname rewrite), and the gateway proxies them to `https://<authority>` directly.
 */
internal val OHTTP_PROXY_ALLOWED_SUFFIXES = listOf(".valargroup.org", ".valargroup.dev", "chaindefuser.com")

/**
 * Maps a real external-service hostname to the zodl OHTTP proxy hostname.
 * Pass-through entries (near.zodl.com, cmc.zodl.com, vote.zodl.com) allow the app to use
 * the zodl aliases directly instead of the original hostnames.
 */
internal fun proxyGatewayHostFor(originalHost: String): String? =
    when (originalHost) {
        "1click.chaindefuser.com" -> "near.zodl.com"
        "pro-api.coinmarketcap.com" -> "cmc.zodl.com"
        "voting.valargroup.dev" -> "vote.zodl.com"
        "near.zodl.com" -> "near.zodl.com"
        "cmc.zodl.com" -> "cmc.zodl.com"
        "vote.zodl.com" -> "vote.zodl.com"
        else -> null
    }

/**
 * Returns true if [host] matches one of [OHTTP_PROXY_ALLOWED_SUFFIXES].
 * Match rule: a suffix starting with `.` matches `x.suffix`; a bare domain matches itself or `*.domain`.
 */
internal fun isOhttpProxyAllowedHost(host: String): Boolean {
    val lowerHost = host.lowercase()
    return OHTTP_PROXY_ALLOWED_SUFFIXES.any { suffix ->
        val lowerSuffix = suffix.lowercase()
        when {
            lowerSuffix.startsWith(".") -> lowerHost.endsWith(lowerSuffix) || lowerHost == lowerSuffix.drop(1)
            else -> lowerHost == lowerSuffix || lowerHost.endsWith(".$lowerSuffix")
        }
    }
}

/**
 * Relay URL for proxy gateway hosts — all share the mainnet relay.
 */
internal const val PROXY_RELAY_URL = "https://ohttp-relay.example/relay"

/**
 * The gateway host whose signed key configuration the proxy requests are sealed to: the mainnet
 * gateway (every proxy host uses the mainnet relay).
 */
internal const val PROXY_GATEWAY_HOST = "ohttp-gateway.example"
