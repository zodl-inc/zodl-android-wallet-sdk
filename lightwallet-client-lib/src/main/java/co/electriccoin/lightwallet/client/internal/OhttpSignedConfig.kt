package co.electriccoin.lightwallet.client.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

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
@Serializable
internal data class SignedOhttpConfigEnvelope(
    val v: Int,
    val network: String,
    val configs: String,
    @SerialName("not_before")
    val notBefore: Long,
    @SerialName("not_after")
    val notAfter: Long,
    @SerialName("key_id")
    val keyId: String,
    @SerialName("sig")
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
    private const val MAX_CONFIGS_BYTES = 4096 // ZIP rule 5

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

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Clock interface for time dependency injection (testing).
     */
    interface Clock {
        fun currentTimeSeconds(): Long
    }

    /**
     * Default clock using system time.
     */
    private object SystemClock : Clock {
        override fun currentTimeSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND
    }

    /**
     * Parses and verifies a signed OHTTP config envelope.
     *
     * @param envelopeJson JSON string from `/ohttp-configs/signed`
     * @param expectedNetwork Network identifier ("mainnet" or "testnet")
     * @param clock Clock for time validation (defaults to system clock)
     * @param trustedPublicKey Raw 32-byte Ed25519 public key to trust instead of the embedded
     *   production key for [expectedNetwork]. Tests only; production callers leave this null.
     * @return Parsed [OhttpKeyConfig] ready for use, along with expiry timestamp
     * @throws KeyConfigVerificationFailedException if verification fails
     */
    fun verifyAndParse(
        envelopeJson: String,
        expectedNetwork: String,
        clock: Clock = SystemClock,
        trustedPublicKey: ByteArray? = null,
    ): Pair<OhttpKeyConfig, Long> {
        val envelope = parseEnvelope(envelopeJson)
        verifyEnvelope(envelope, expectedNetwork, clock, trustedPublicKey)
        val configBytes = decodeBase64("configs", envelope.configs)

        // P4: Enforce 4096-byte limit on decoded configs (ZIP rule 5)
        if (configBytes.size > MAX_CONFIGS_BYTES) {
            throw KeyConfigVerificationFailedException(
                "Decoded configs too large: ${configBytes.size} bytes (max $MAX_CONFIGS_BYTES)"
            )
        }

        val keyConfig =
            try {
                OhttpKeyConfig.parseSupported(configBytes)
            } catch (e: IllegalArgumentException) {
                throw KeyConfigVerificationFailedException("Invalid OHTTP key configuration", e)
            }
        return keyConfig to envelope.notAfter
    }

    /**
     * Decodes a base64 envelope field, converting malformed input into a verification failure.
     */
    private fun decodeBase64(
        field: String,
        value: String,
    ): ByteArray =
        try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw KeyConfigVerificationFailedException("Invalid base64 in envelope field '$field'", e)
        }

    /**
     * Parses JSON envelope into typed structure using kotlinx.serialization.
     * P6: Checks for duplicate keys (best-effort; kotlinx.serialization last-value-wins by design).
     */
    private fun parseEnvelope(jsonString: String): SignedOhttpConfigEnvelope {
        // P6: Best-effort duplicate key detection (strict parse per ZIP rule 1)
        // Note: kotlinx.serialization uses last-value-wins for duplicates by design.
        // A full fix would require a streaming JSON parser.
        checkNoDuplicateKeys(jsonString)

        return try {
            json.decodeFromString<SignedOhttpConfigEnvelope>(jsonString)
        } catch (e: SerializationException) {
            throw KeyConfigVerificationFailedException("Failed to parse envelope JSON", e)
        } catch (e: IllegalArgumentException) {
            throw KeyConfigVerificationFailedException("Failed to parse envelope JSON", e)
        }
    }

    /**
     * Best-effort check for duplicate JSON keys at top level.
     * Limitation: Only checks top-level keys due to kotlinx.serialization design.
     */
    private fun checkNoDuplicateKeys(jsonString: String) {
        val seenKeys = mutableSetOf<String>()
        // Simple regex to extract top-level keys (between quotes before colons)
        val keyPattern = """"([^"]+)"\s*:""".toRegex()
        keyPattern.findAll(jsonString).forEach { match ->
            val key = match.groupValues[1]
            if (!seenKeys.add(key)) {
                throw KeyConfigVerificationFailedException("Duplicate JSON key: $key")
            }
        }
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
        clock: Clock,
        trustedPublicKey: ByteArray?,
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
        val (publicKey, expectedKeyId) = selectSigningKey(expectedNetwork, trustedPublicKey)

        if (envelope.keyId != expectedKeyId) {
            throw KeyConfigVerificationFailedException(
                "Key ID mismatch: expected $expectedKeyId, got ${envelope.keyId}",
            )
        }

        // 5-7. Validity window: period ≤ 24 h, not_after ≤ now + 24 h, now within window
        checkValidity(envelope, clock.currentTimeSeconds())

        // 4. Verify Ed25519 signature
        val configBytes = decodeBase64("configs", envelope.configs)
        val signatureBytes = decodeBase64("sig", envelope.signature)
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
    }

    /**
     * Returns the Ed25519 public key to trust and its key ID: the injected [trustedPublicKey]
     * (tests) or the embedded production key for [expectedNetwork].
     */
    private fun selectSigningKey(
        expectedNetwork: String,
        trustedPublicKey: ByteArray?,
    ): Pair<ByteArray, String> =
        if (trustedPublicKey != null) {
            trustedPublicKey to computeKeyId(trustedPublicKey)
        } else {
            when (expectedNetwork) {
                "mainnet" -> MAINNET_PUBLIC_KEY to MAINNET_KEY_ID

                "testnet" -> TESTNET_PUBLIC_KEY to TESTNET_KEY_ID

                else -> throw KeyConfigVerificationFailedException(
                    "Unknown network: $expectedNetwork",
                )
            }
        }

    /**
     * Validity checks: period ≤ 24 hours, not_after ≤ now + 24 hours, and now within
     * [not_before - tolerance, not_after + tolerance].
     */
    private fun checkValidity(
        envelope: SignedOhttpConfigEnvelope,
        nowSeconds: Long,
    ) {
        // P5: Require not_before < not_after strictly (ZIP rule 2)
        if (envelope.notBefore >= envelope.notAfter) {
            throw KeyConfigVerificationFailedException(
                "Invalid validity window: not_before=${envelope.notBefore} >= not_after=${envelope.notAfter}"
            )
        }

        val validityPeriod = envelope.notAfter - envelope.notBefore
        val latestAllowed = nowSeconds + MAX_VALIDITY_SECONDS
        val minValid = envelope.notBefore - TIME_TOLERANCE_SECONDS
        val maxValid = envelope.notAfter + TIME_TOLERANCE_SECONDS
        val failure =
            when {
                validityPeriod > MAX_VALIDITY_SECONDS -> {
                    "Validity period too long: ${validityPeriod}s (max $MAX_VALIDITY_SECONDS)"
                }

                envelope.notAfter > latestAllowed -> {
                    "not_after too far in future: ${envelope.notAfter} > $latestAllowed"
                }

                nowSeconds < minValid || nowSeconds > maxValid -> {
                    "Config not valid at current time: now=$nowSeconds, valid=[$minValid, $maxValid]"
                }

                else -> {
                    null
                }
            }
        if (failure != null) {
            throw KeyConfigVerificationFailedException(failure)
        }
    }

    /**
     * Builds the message that was signed:
     * "zodl-ohttp-configs-v1" || 0x00 || network || 0x00 || not_before || not_after || configs
     */
    fun buildSignedMessage(
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
    @Suppress("SwallowedException") // Signature verification failure returns false, caller throws
    fun verifyEd25519Signature(
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

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % HEX_CHARS_PER_BYTE == 0) { "Hex string must have even length" }
        return hex.chunked(HEX_CHARS_PER_BYTE).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
    }
}
