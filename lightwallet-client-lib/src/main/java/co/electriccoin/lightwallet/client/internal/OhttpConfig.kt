package co.electriccoin.lightwallet.client.internal

/**
 * Static OHTTP (RFC 9458) endpoint mapping.
 *
 * A [co.electriccoin.lightwallet.client.model.LightWalletEndpoint] whose host is one of the ZODL
 * OHTTP gateways is served through an OHTTP relay instead of a direct gRPC connection:
 *
 *   app ──HPKE-encrypted──▶ relay (sees IP, not content) ──▶ gateway (sees content, not IP) ──▶ lightwalletd
 *
 * The gateway's HPKE key configuration is fetched from `https://<gateway-host>/ohttp-configs`.
 */
internal object OhttpEndpoints {
    private const val GATEWAY_HOST_PREFIX = "ohttp-gateway"

    private val relayByGatewayHost: Map<String, String> =
        mapOf(
            "ohttp-gateway-testnet.example" to "https://relay-simulator-testnet.example/relay",
            "ohttp-gateway.example" to "https://relay-simulator.example/relay"
        )

    fun isOhttpGateway(host: String): Boolean = host.startsWith(GATEWAY_HOST_PREFIX)

    fun relayUrlFor(gatewayHost: String): String =
        relayByGatewayHost[gatewayHost]
            ?: throw IllegalArgumentException("No OHTTP relay configured for gateway host $gatewayHost")

    fun keyConfigUrlFor(gatewayHost: String): String = "https://$gatewayHost/ohttp-configs"
}

/**
 * One HPKE key configuration from an `application/ohttp-keys` document (RFC 9458 §3.1).
 */
internal class OhttpKeyConfig(
    val keyId: Int,
    val kemId: Int,
    val publicKey: ByteArray,
    val kdfId: Int,
    val aeadId: Int
) {
    /** `hdr = key_id || kem_id || kdf_id || aead_id` (RFC 9458 §4.3). */
    fun header(): ByteArray =
        byteArrayOf(keyId.toByte()) + Hpke.i2osp(kemId, 2) + Hpke.i2osp(kdfId, 2) + Hpke.i2osp(aeadId, 2)

    companion object {
        /**
         * Parses an `application/ohttp-keys` body and returns the first key config that uses the
         * suite this client implements (X25519 / HKDF-SHA256 / AES-128-GCM).
         */
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
