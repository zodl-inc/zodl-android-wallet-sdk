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
 *
 * Relay URLs come from the native `zodl-ohttp-client` library and can be overridden at build time
 * via environment variables `ZODL_OHTTP_RELAY_URL_MAINNET` and `ZODL_OHTTP_RELAY_URL_TESTNET`.
 */
internal object OhttpEndpoints {
    /** The ZODL OHTTP gateway hosts (exact match only). */
    private val GATEWAY_HOSTS =
        setOf(
            "ohttp-gateway.example",
            "ohttp-gateway-testnet.example",
        )

    /**
     * True only for the exact gateway hosts we have a relay for. A prefix match would route a custom
     * server such as `ohttp-gateway.example.org` into relay selection, which would fail.
     */
    fun isOhttpGateway(host: String): Boolean = GATEWAY_HOSTS.contains(host)
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
 * The hosts the gateway proxies (exact match only, as the gateway does).
 * Requests to these hosts are sent to the gateway with their real `:authority`
 * (no hostname rewrite), and the gateway proxies them to its backend for that host.
 * MUST match the gateway's BACKENDS (infra repo, kubernetes/apps/ohttp-gateway/deployment.yaml),
 * like `OHTTP_VOTE_HOSTS` in `backend-lib/src/main/rust/voting/route.rs`.
 */
internal val OHTTP_PROXY_ALLOWED_HOSTS =
    setOf(
        "cmc.zodl.com",
        "near.zodl.com",
        "vote.zodl.com",
        "prod.vote-chain-primary.valargroup.org",
        "voting.valargroup.dev",
        "ru.zcash.space",
        "147-182-192-212.sslip.io",
        "134-209-215-69.sslip.io",
        "mnval01.zcash.legends.sh",
        "zvote-1.stardust.rest",
        "zvote.zodl.com",
        "zvote.keplr.app",
        "zvote.zfnd.org",
        "v.ombie.cash",
        "lb-pir-primary.valargroup.org",
        "lb-pir-backup.valargroup.org",
        "zpir-1.stardust.rest",
        "zelay.example",
        "raw.githubusercontent.com",
    )

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

/** Returns true if [host] is one of [OHTTP_PROXY_ALLOWED_HOSTS] (case-insensitive, exact match). */
internal fun isOhttpProxyAllowedHost(host: String): Boolean = OHTTP_PROXY_ALLOWED_HOSTS.contains(host.lowercase())

/**
 * The gateway hosts whose signed key configuration the proxy requests are sealed to, by the wallet's
 * network (both gateways proxy the same BACKENDS).
 */
internal const val PROXY_GATEWAY_HOST = "ohttp-gateway.example"

internal const val PROXY_GATEWAY_HOST_TESTNET = "ohttp-gateway-testnet.example"
