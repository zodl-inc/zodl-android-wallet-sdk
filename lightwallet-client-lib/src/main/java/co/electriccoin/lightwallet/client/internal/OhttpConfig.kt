package co.electriccoin.lightwallet.client.internal

/**
 * Static OHTTP (RFC 9458) endpoint mapping.
 *
 * A [co.electriccoin.lightwallet.client.model.LightWalletEndpoint] whose host is one of the ZODL
 * OHTTP gateways is served through an OHTTP relay instead of a direct gRPC connection:
 *
 *   app ──HPKE-encrypted──▶ relay (sees IP, not content) ──▶ gateway (sees content, not IP) ──▶ lightwalletd
 *
 * The gateway's HPKE key configuration is fetched through the relay at `https://<relay-host>/ohttp-configs`
 * (the relay passes it through from the gateway).
 */
internal object OhttpEndpoints {
    private const val GATEWAY_HOST_PREFIX = "ohttp-gateway"

    fun networkFor(gatewayHost: String): String =
        when (gatewayHost) {
            "ohttp-gateway-testnet.example" -> "testnet"
            "ohttp-gateway.example" -> "mainnet"
            else -> "mainnet"
        }

    private val relayByGatewayHost: Map<String, String> =
        mapOf(
            "ohttp-gateway-testnet.example" to "https://ohttp-relay-testnet.example/relay",
            // COR-1830: Switch to ohttp-relay.example/relay once Fastly prod
            // serves /ohttp-configs (currently 501; only POST /relay works). Mainnet clients fetch signed
            // key config via the relay.
            "ohttp-gateway.example" to "https://relay-simulator.example/relay",
        )

    fun isOhttpGateway(host: String): Boolean = host.startsWith(GATEWAY_HOST_PREFIX)

    fun relayUrlFor(gatewayHost: String): String =
        relayByGatewayHost[gatewayHost]
            ?: throw IllegalArgumentException("No OHTTP relay configured for gateway host $gatewayHost")

    /**
     * Returns the key config URL for a gateway, derived from its relay URL.
     * The relay proxies `/ohttp-configs` to the gateway's key endpoint.
     */
    fun keyConfigUrlFor(gatewayHost: String): String {
        val relayUrl = relayUrlFor(gatewayHost)
        // Replace only the path suffix, not the hostname (relay-simulator stays relay-simulator)
        return if (relayUrl.endsWith("/relay")) {
            relayUrl.removeSuffix("/relay") + "/ohttp-configs"
        } else {
            relayUrl.replace("/relay", "/ohttp-configs")
        }
    }
}

/**
 * One HPKE key configuration from an `application/ohttp-keys` document (RFC 9458 §3.1).
 */
internal class OhttpKeyConfig(
    val keyId: Int,
    val kemId: Int,
    val publicKey: ByteArray,
    val kdfId: Int,
    val aeadId: Int,
) {
    /** `hdr = key_id || kem_id || kdf_id || aead_id` (RFC 9458 §4.3). */
    fun header(): ByteArray =
        byteArrayOf(keyId.toByte()) + Hpke.i2osp(kemId, 2) +
            Hpke.i2osp(kdfId, 2) + Hpke.i2osp(aeadId, 2)

    companion object {
        /**
         * Parses an `application/ohttp-keys` body and returns the first key config that uses the
         * suite this client implements (X25519 / HKDF-SHA256 / AES-128-GCM).
         * Binary format parsing per RFC 9458 §3.1 — bit shifts are mandated by the spec.
         */
        @Suppress("MagicNumber")
        fun parseSupported(bytes: ByteArray): OhttpKeyConfig {
            var pos = 0

            fun u8(): Int = bytes[pos++].toInt() and 0xFF

            fun u16(): Int = (u8() shl 8) or u8()

            while (pos + 2 <= bytes.size) {
                val configLength = u16()
                val end = pos + configLength
                require(end <= bytes.size) { "Truncated OHTTP key config" }

                val keyId = u8()
                val kemId = u16()
                require(kemId == Hpke.KEM_ID) { "Unsupported KEM 0x${kemId.toString(16)}" }
                val publicKey = bytes.copyOfRange(pos, pos + Hpke.N_PK)
                pos += Hpke.N_PK
                val suitesLength = u16()
                val suitesEnd = pos + suitesLength
                require(suitesEnd <= end) { "Truncated OHTTP symmetric suites" }
                while (pos + 4 <= suitesEnd) {
                    val kdfId = u16()
                    val aeadId = u16()
                    if (kdfId == Hpke.KDF_ID && aeadId == Hpke.AEAD_ID) {
                        return OhttpKeyConfig(keyId, kemId, publicKey, kdfId, aeadId)
                    }
                }
                pos = end
            }
            throw IllegalArgumentException("No supported HPKE suite in OHTTP key configuration")
        }
    }
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
 * COR-1830: Switch to ohttp-relay once it serves /ohttp-configs
 */
internal const val PROXY_RELAY_URL = "https://relay-simulator.example/relay"
