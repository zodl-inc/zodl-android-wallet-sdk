package co.electriccoin.lightwallet.client

/**
 * Maps OHTTP gateway hostnames to their relay URLs. The mapping is built into the native
 * `zodl-ohttp-client` library and can be overridden at build time via environment variables
 * `ZODL_OHTTP_RELAY_URL_MAINNET` and `ZODL_OHTTP_RELAY_URL_TESTNET`.
 *
 * This interface decouples `lightwallet-client-lib` from the native implementation; `sdk-lib`
 * wires it via [co.electriccoin.lightwallet.client.internal.OhttpEndpoints.initialize].
 */
interface RelayUrlMapper {
    /**
     * Returns the relay URL for a given gateway host, or null if the host is not a known gateway.
     */
    fun relayUrlFor(gatewayHost: String): String?
}
