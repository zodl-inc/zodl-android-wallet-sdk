package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.InternalOhttpApi
import co.electriccoin.lightwallet.client.model.OhttpConfiguration

/**
 * OHTTP (RFC 9458) gateway hosts.
 *
 * A [co.electriccoin.lightwallet.client.model.LightWalletEndpoint] whose host is one of the OHTTP
 * gateways of the installed configuration is served through an OHTTP relay instead of a direct gRPC
 * connection:
 *
 *   app ──HPKE-encrypted──▶ relay (sees IP, not content) ──▶ gateway (sees content, not IP) ──▶ lightwalletd
 *
 * The gateway's signed HPKE key configuration is fetched through the relay (see
 * [co.electriccoin.lightwallet.client.OhttpCodec.signedConfigUrl]); which network a gateway host serves,
 * and so which signing key is trusted for it, is decided by the same codec.
 *
 * Nothing here is hardcoded and nothing comes from the build: the app installs the configuration once at
 * startup (`cash.z.ecc.android.sdk.Ohttp.configure`), which puts the same values into the native library.
 * Until then, and for a network the configuration leaves null, OHTTP is off and no host is a gateway.
 *
 * The configuration is set once per process: the first successful [install] wins (`null`, OHTTP off, is a
 * configuration too); installing an equal one again changes nothing, a different one throws.
 */
object OhttpEndpoints {
    private val lock = Any()

    /** Whether [install] has succeeded; [installed] is meaningful only then. */
    @Volatile
    private var isInstalled = false

    @Volatile
    private var installed: OhttpConfiguration? = null

    /**
     * Whether SDK code read the OHTTP state before [install]: it then saw OHTTP off and may still act on
     * that, so a later [install] is refused rather than turning OHTTP on for only part of the process.
     */
    @Volatile
    private var readBeforeInstall = false

    @Volatile
    private var hosts: OhttpGatewayHosts = OhttpGatewayHosts(mainnet = null, testnet = null)

    /** The gateway hosts of the installed configuration; tests replace them with example hosts. */
    internal var gatewayHosts: OhttpGatewayHosts
        get() {
            markRead()
            return hosts
        }
        set(value) {
            hosts = value
        }

    /** The installed configuration, or null while none is installed (or the installed one is null). */
    @InternalOhttpApi
    val configuration: OhttpConfiguration?
        get() {
            markRead()
            return installed
        }

    private fun markRead() {
        if (!isInstalled) readBeforeInstall = true
    }

    /**
     * Installs [configuration] for the process, once. [installNative] runs first, under the same lock, and
     * throws when the native library refuses a value; only then does this holder change, so both layers
     * hold the same configuration. A caller whose native library is not loaded yet passes a no-op and
     * applies the native part when the library loads (see `cash.z.ecc.android.sdk.Ohttp.configure`).
     *
     * @throws IllegalArgumentException when [installNative] refuses a value; nothing is installed and a
     *   later call may try again
     * @throws IllegalStateException when a different configuration is already installed (it stays), or
     *   when the SDK already read the OHTTP state before any configuration was installed
     */
    @InternalOhttpApi
    fun install(
        configuration: OhttpConfiguration?,
        installNative: (OhttpConfiguration?) -> Unit = {},
    ) = synchronized(lock) {
        if (isInstalled) {
            check(installed == configuration) {
                "A different OHTTP configuration is already installed; it is set once per process"
            }
            return@synchronized
        }
        check(!readBeforeInstall) {
            "The SDK read the OHTTP configuration before it was installed; configure OHTTP before any other " +
                "SDK call"
        }
        installNative(configuration)
        installed = configuration
        hosts =
            OhttpGatewayHosts(
                mainnet = configuration?.mainnet?.gatewayHost,
                testnet = configuration?.testnet?.gatewayHost,
            )
        isInstalled = true
    }

    /**
     * Turns OHTTP off for the rest of the process after the native library refused the installed
     * configuration when it loaded: no host is a gateway any more and every OHTTP route fails closed. The
     * holder stays installed (with OHTTP off), so a later [install] of any configuration other than null
     * is refused.
     */
    @InternalOhttpApi
    fun failClosed() =
        synchronized(lock) {
            installed = null
            hosts = OhttpGatewayHosts(mainnet = null, testnet = null)
            isInstalled = true
        }

    /** Forgets the installed configuration, for tests that install their own. */
    internal fun resetForTest() =
        synchronized(lock) {
            isInstalled = false
            installed = null
            readBeforeInstall = false
            hosts = OhttpGatewayHosts(mainnet = null, testnet = null)
        }

    /**
     * True only for the exact gateway hosts of the installed configuration. A prefix match would route a
     * custom server such as `ohttp-gateway.example.org` into relay selection, which would fail.
     */
    internal fun isOhttpGateway(host: String): Boolean = gatewayHosts.let { host == it.mainnet || host == it.testnet }
}

/** The gateway host of each network, null when OHTTP is off for it. */
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
