package co.electriccoin.lightwallet.client.internal

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Signed OHTTP configuration envelope (RFC 9458 key configs with Ed25519 signature).
 *
 * Prevents MITM attacks on key configuration by requiring a valid signature from
 * the ZODL signing key. The envelope includes network context, validity period,
 * and key ID for crypto-agility.
 *
 * @property v Protocol version (must be 1)
 * @property network Network identifier ("mainnet" or "testnet")
 * @property configs Base64-encoded RFC 9458 key configuration bytes
 * @property notBefore Unix timestamp (seconds) - config valid from this time
 * @property notAfter Unix timestamp (seconds) - config valid until this time
 * @property keyId First 8 hex chars of SHA-256(signing public key)
 * @property signature Base64-encoded Ed25519 signature
 */
internal data class SignedOhttpConfigEnvelope(
    val v: Int,
    val network: String,
    val configs: String,
    val notBefore: Long,
    val notAfter: Long,
    val keyId: String,
    val signature: String,
)

/**
 * Exception thrown when signed config verification fails.
 */
class KeyConfigVerificationFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Pure verification of signed OHTTP key configurations.
 *
 * Implements fail-closed security: any verification failure throws [KeyConfigVerificationFailedException].
 * No network calls, no fallbacks — verification only.
 */
internal object OhttpSignedConfigVerifier {
    // Protocol constants
    private const val ENVELOPE_VERSION = 1
    private const val MILLIS_PER_SECOND = 1000L
    private const val NULL_BYTE_SIZE = 1
    private const val LONG_BYTES = 8
    private const val KEY_ID_BYTES = 4
    private const val HEX_CHARS_PER_BYTE = 2
    private const val HEX_RADIX = 16
    private const val MAX_VALIDITY_SECONDS = 86400L // 24 hours

    // Embedded signing public keys (32 bytes raw Ed25519 public keys)
    private val MAINNET_PUBLIC_KEY =
        hexToBytes(
            "REDACTED-SIGNING-PUBKEY-MAINNET",
        )
    private val TESTNET_PUBLIC_KEY =
        hexToBytes(
            "REDACTED-SIGNING-PUBKEY-TESTNET",
        )

    // Expected key IDs (first 8 hex of SHA-256(public key))
    private const val MAINNET_KEY_ID = "ba8a2dbd"
    private const val TESTNET_KEY_ID = "a3620fb6"

    // Time tolerance (±60 seconds for clock skew)
    private const val TIME_TOLERANCE_SECONDS = 60L

    /**
     * Parses and verifies a signed OHTTP config envelope.
     *
     * @param envelopeJson JSON string from `/ohttp-configs/signed`
     * @param expectedNetwork Network identifier ("mainnet" or "testnet")
     * @return Parsed [OhttpKeyConfig] ready for use, along with expiry timestamp
     * @throws KeyConfigVerificationFailedException if verification fails
     */
    fun verifyAndParse(
        envelopeJson: String,
        expectedNetwork: String,
    ): Pair<OhttpKeyConfig, Long> {
        val envelope = parseEnvelope(envelopeJson)
        verifyEnvelope(envelope, expectedNetwork)
        val configBytes = Base64.decode(envelope.configs, Base64.NO_WRAP)
        val keyConfig = OhttpKeyConfig.parseSupported(configBytes)
        return keyConfig to envelope.notAfter
    }

    /**
     * Parses JSON envelope into typed structure.
     */
    private fun parseEnvelope(json: String): SignedOhttpConfigEnvelope {
        val obj = JSONObject(json)
        return SignedOhttpConfigEnvelope(
            v = obj.getInt("v"),
            network = obj.getString("network"),
            configs = obj.getString("configs"),
            notBefore = obj.getLong("not_before"),
            notAfter = obj.getLong("not_after"),
            keyId = obj.getString("key_id"),
            signature = obj.getString("sig"),
        )
    }

    /**
     * Verifies the envelope signature and validity.
     *
     * Checks:
     * 1. Protocol version == 1
     * 2. Network matches expected
     * 3. Key ID matches embedded public key
     * 4. Signature is valid (Ed25519)
     * 5. Current time within [not_before - tolerance, not_after + tolerance]
     * 6. Validity period ≤ 24 hours
     * 7. not_after ≤ now + 24 hours
     */
    @Suppress("ThrowsCount") // sequential envelope validation
    private fun verifyEnvelope(
        envelope: SignedOhttpConfigEnvelope,
        expectedNetwork: String,
    ) {
        // 1. Check protocol version
        if (envelope.v != ENVELOPE_VERSION) {
            throw KeyConfigVerificationFailedException(
                "Unsupported protocol version: ${envelope.v}",
            )
        }

        // 2. Check network
        if (envelope.network != expectedNetwork) {
            throw KeyConfigVerificationFailedException(
                "Network mismatch: expected $expectedNetwork, got ${envelope.network}",
            )
        }

        // 3. Select public key and verify key ID
        val (publicKey, expectedKeyId) =
            when (expectedNetwork) {
                "mainnet" -> MAINNET_PUBLIC_KEY to MAINNET_KEY_ID
                "testnet" -> TESTNET_PUBLIC_KEY to TESTNET_KEY_ID
                else -> throw KeyConfigVerificationFailedException(
                    "Unknown network: $expectedNetwork",
                )
            }

        if (envelope.keyId != expectedKeyId) {
            throw KeyConfigVerificationFailedException(
                "Key ID mismatch: expected $expectedKeyId, got ${envelope.keyId}",
            )
        }

        // 6. Check validity period ≤ 24 hours
        val validityPeriod = envelope.notAfter - envelope.notBefore
        if (validityPeriod > MAX_VALIDITY_SECONDS) {
            throw KeyConfigVerificationFailedException(
                "Validity period too long: ${validityPeriod}s (max $MAX_VALIDITY_SECONDS)",
            )
        }

        val nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND

        // 7. Check not_after ≤ now + 24 hours
        if (envelope.notAfter > nowSeconds + MAX_VALIDITY_SECONDS) {
            throw KeyConfigVerificationFailedException(
                "not_after too far in future: ${envelope.notAfter} > ${nowSeconds + MAX_VALIDITY_SECONDS}",
            )
        }

        // 4. Verify Ed25519 signature
        val configBytes = Base64.decode(envelope.configs, Base64.NO_WRAP)
        val signatureBytes = Base64.decode(envelope.signature, Base64.NO_WRAP)
        val message =
            buildSignedMessage(
                envelope.network,
                envelope.notBefore,
                envelope.notAfter,
                configBytes,
            )

        if (!verifyEd25519Signature(publicKey, message, signatureBytes)) {
            throw KeyConfigVerificationFailedException("Invalid Ed25519 signature")
        }

        // 5. Check time validity
        val minValid = envelope.notBefore - TIME_TOLERANCE_SECONDS
        val maxValid = envelope.notAfter + TIME_TOLERANCE_SECONDS

        if (nowSeconds < minValid || nowSeconds > maxValid) {
            throw KeyConfigVerificationFailedException(
                "Config not valid at current time: now=$nowSeconds, valid=[$minValid, $maxValid]",
            )
        }
    }

    /**
     * Builds the message that was signed:
     * "zodl-ohttp-configs-v1" || 0x00 || network || 0x00 || not_before || not_after || configs
     */
    private fun buildSignedMessage(
        network: String,
        notBefore: Long,
        notAfter: Long,
        configBytes: ByteArray,
    ): ByteArray {
        val prefix = "zodl-ohttp-configs-v1".toByteArray(Charsets.US_ASCII)
        val networkBytes = network.toByteArray(Charsets.US_ASCII)

        val totalSize =
            prefix.size + NULL_BYTE_SIZE + networkBytes.size + NULL_BYTE_SIZE +
                LONG_BYTES + LONG_BYTES + configBytes.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(prefix)
        buffer.put(0x00.toByte())
        buffer.put(networkBytes)
        buffer.put(0x00.toByte())
        buffer.putLong(notBefore)
        buffer.putLong(notAfter)
        buffer.put(configBytes)

        return buffer.array()
    }

    /**
     * Verifies an Ed25519 signature using BouncyCastle.
     */
    private fun verifyEd25519Signature(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            val signer = Ed25519Signer()
            val publicKeyParams = Ed25519PublicKeyParameters(publicKey, 0)
            signer.init(false, publicKeyParams)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            false
        }

    /**
     * Computes key ID for a public key (first 8 hex chars of SHA-256).
     */
    fun computeKeyId(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.take(KEY_ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % HEX_CHARS_PER_BYTE == 0) { "Hex string must have even length" }
        return hex.chunked(HEX_CHARS_PER_BYTE).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
    }
}
