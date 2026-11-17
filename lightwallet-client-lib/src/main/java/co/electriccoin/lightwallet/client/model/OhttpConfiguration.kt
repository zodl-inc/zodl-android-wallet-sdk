package co.electriccoin.lightwallet.client.model

import java.util.Locale

/**
 * The OHTTP configuration of one network, as the app has it: the gateway host, the relay URL, the DNS
 * name of the gateway's signed key configuration and the key that signs it.
 *
 * The SDK carries none of these values. The app installs them once at startup with
 * `cash.z.ecc.android.sdk.Ohttp.configure`, which checks every value against the rule its property
 * states and throws an [IllegalArgumentException] naming the field and the rule (never the value).
 *
 * The values are normalized when the configuration is created: surrounding whitespace (for example a
 * trailing newline from a secret store) is removed from all four, and the gateway host, the key DNS
 * name and the signing key are lower-cased. The relay URL keeps its letter case, since its path may
 * depend on it.
 *
 * [toString] says only whether each value is set, never the value, so a configuration can be logged.
 *
 * @param gatewayHost the gateway's host name, which is also the `:authority` of the lightwalletd
 *   requests sent through it: a bare DNS host name (no scheme, port, path or trailing dot).
 * @param relayUrl the URL sealed requests are posted to: `https://`, a host (a DNS name or an IP
 *   literal), an optional numeric port and an optional path, without userinfo, query, fragment,
 *   backslash, whitespace or control characters.
 * @param keysDnsName the DNS TXT name of the gateway's signed key configuration, its last-resort
 *   source: a bare DNS host name.
 * @param signingPublicKey the key the gateway's key configuration is signed with: a raw Ed25519 public
 *   key as 64 hex digits, a valid curve point not of small order.
 */
class OhttpNetworkConfiguration(
    gatewayHost: String,
    relayUrl: String,
    keysDnsName: String,
    signingPublicKey: String,
) {
    val gatewayHost: String = gatewayHost.trim().lowercase(Locale.ROOT)
    val relayUrl: String = relayUrl.trim()
    val keysDnsName: String = keysDnsName.trim().lowercase(Locale.ROOT)
    val signingPublicKey: String = signingPublicKey.trim().lowercase(Locale.ROOT)

    override fun equals(other: Any?): Boolean =
        other is OhttpNetworkConfiguration &&
            gatewayHost == other.gatewayHost &&
            relayUrl == other.relayUrl &&
            keysDnsName == other.keysDnsName &&
            signingPublicKey == other.signingPublicKey

    override fun hashCode(): Int = listOf(gatewayHost, relayUrl, keysDnsName, signingPublicKey).hashCode()

    override fun toString(): String =
        "OhttpNetworkConfiguration(gatewayHost=${shown(gatewayHost)}, relayUrl=${shown(relayUrl)}, " +
            "keysDnsName=${shown(keysDnsName)}, signingPublicKey=${shown(signingPublicKey)})"

    private fun shown(value: String) = if (value.isEmpty()) "<empty>" else "<set>"
}

/**
 * The OHTTP configuration of mainnet and testnet that the app installs once at startup with
 * `cash.z.ecc.android.sdk.Ohttp.configure`. A network left null has OHTTP off: its gateway host, if
 * an app still uses it as a server, is a plain lightwalletd server. The two networks must not share a
 * value (host names and URLs compared ignoring ASCII case).
 *
 * [toString] says which networks and values are set, never a value.
 */
class OhttpConfiguration(
    val mainnet: OhttpNetworkConfiguration?,
    val testnet: OhttpNetworkConfiguration?,
) {
    /** The configuration of testnet ([isTestnet] true) or mainnet, null when OHTTP is off for it. */
    fun forNetwork(isTestnet: Boolean): OhttpNetworkConfiguration? = if (isTestnet) testnet else mainnet

    override fun equals(other: Any?): Boolean =
        other is OhttpConfiguration && mainnet == other.mainnet && testnet == other.testnet

    override fun hashCode(): Int = 31 * mainnet.hashCode() + testnet.hashCode()

    override fun toString(): String = "OhttpConfiguration(mainnet=$mainnet, testnet=$testnet)"
}
