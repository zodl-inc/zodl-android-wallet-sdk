package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.BuildConfig

/**
 * OHTTP (RFC 9458) gateway hosts.
 *
 * A [co.electriccoin.lightwallet.client.model.LightWalletEndpoint] whose host is one of the OHTTP
 * gateways of this build is served through an OHTTP relay instead of a direct gRPC connection:
 *
 *   app ──HPKE-encrypted──▶ relay (sees IP, not content) ──▶ gateway (sees content, not IP) ──▶ lightwalletd
 *
 * The gateway's signed HPKE key configuration is fetched through the relay (see
 * [co.electriccoin.lightwallet.client.OhttpCodec.signedConfigUrl]); which network a gateway host serves,
 * and so which signing key is trusted for it, is decided by the same codec.
 *
 * Nothing here is hardcoded: the gateway hosts come from the build environment
 * (`ZODL_OHTTP_GATEWAY_HOST_MAINNET` / `_TESTNET`, see build.gradle.kts), the same values the native
 * `zodl-ohttp-client` compiles in together with the relay URLs, key DNS names and signing keys. A build
 * without them has no gateway: OHTTP is off and no host is treated as one.
 */
internal object OhttpEndpoints {
    /** This build's gateway hosts; tests replace them with example hosts. */
    @Volatile
    internal var gatewayHosts: OhttpGatewayHosts =
        OhttpGatewayHosts(
            mainnet = BuildConfig.OHTTP_GATEWAY_HOST_MAINNET.ifEmpty { null },
            testnet = BuildConfig.OHTTP_GATEWAY_HOST_TESTNET.ifEmpty { null },
        )

    /**
     * True only for the exact gateway hosts of this build. A prefix match would route a custom server
     * such as `ohttp-gateway.example.org` into relay selection, which would fail.
     */
    fun isOhttpGateway(host: String): Boolean = gatewayHosts.let { host == it.mainnet || host == it.testnet }
}

/** The gateway host of each network, null when the build configured none. */
internal data class OhttpGatewayHosts(
    val mainnet: String?,
    val testnet: String?,
) {
    fun forNetwork(testnet: Boolean): String? = if (testnet) this.testnet else mainnet
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
// Sapling params are downloaded directly via HTTPS (Tor OFF) or through the SDK's Tor client
// (Tor ON), never through OHTTP: raw.githubusercontent.com is listed below for another user.

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
        // Two validators of the production vote chain, reached by their sslip.io names: the published
        // dynamic voting config lists them as vote servers (as `OHTTP_VOTE_HOSTS` in route.rs).
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
        // The app's voting config mirror (token-holder-voting-config on GitHub, checksum-pinned by the
        // app); the gateway allows only that repository's paths on this host.
        "raw.githubusercontent.com",
        "flexa.zodl.com",
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
        "api.flexa.co" -> "flexa.zodl.com"
        "voting.valargroup.dev" -> "vote.zodl.com"
        "near.zodl.com" -> "near.zodl.com"
        "cmc.zodl.com" -> "cmc.zodl.com"
        "vote.zodl.com" -> "vote.zodl.com"
        "flexa.zodl.com" -> "flexa.zodl.com"
        else -> null
    }

/** Returns true if [host] is one of [OHTTP_PROXY_ALLOWED_HOSTS] (case-insensitive, exact match). */
internal fun isOhttpProxyAllowedHost(host: String): Boolean = OHTTP_PROXY_ALLOWED_HOSTS.contains(host.lowercase())
