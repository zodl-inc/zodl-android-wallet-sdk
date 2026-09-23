package co.electriccoin.lightwallet.client.internal

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
data class SignedOhttpConfigEnvelope(
    val v: Int,
    val network: String,
    val configs: String,
    val notBefore: Long,
    val notAfter: Long,
    val keyId: String,
    val signature: String,
)

/**
 * Verified OHTTP configuration from signed envelope.
 *
 * @property configBytes RFC 9458 key configuration bytes (ready for HPKE)
 * @property keyId Key ID from the configuration (first byte)
 * @property validUntil Unix timestamp when this config expires
 */
data class VerifiedOhttpConfig(
    val configBytes: ByteArray,
    val keyId: Byte,
    val validUntil: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VerifiedOhttpConfig
        return configBytes.contentEquals(other.configBytes) &&
            keyId == other.keyId &&
            validUntil == other.validUntil
    }

    override fun hashCode(): Int {
        var result = configBytes.contentHashCode()
        result = 31 * result + keyId
        result = 31 * result + validUntil.hashCode()
        return result
    }
}

/**
 * Exception thrown when signed config verification fails.
 */
class KeyConfigVerificationFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Fetches and verifies signed OHTTP key configurations.
 *
 * Implements fail-closed security: any verification failure results in
 * OHTTP being unavailable (no fallback to unsigned configs in production).
 */
object OhttpSignedConfigFetcher {
    private const val TAG = "OhttpSignedConfig"

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

    // HTTP client with reasonable timeouts
    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    /**
     * Fetches and verifies signed OHTTP configs from the relay endpoint.
     *
     * @param relayBaseUrl Base URL of the relay (e.g. "https://relay-simulator-testnet.example")
     * @param network Network identifier ("mainnet" or "testnet")
     * @param allowUnsignedFallback If true, falls back to unsigned /ohttp-configs on verification failure (DEBUG ONLY)
     * @return Verified configuration ready for use
     * @throws KeyConfigVerificationFailedException if verification fails and no fallback allowed
     */
    fun fetchAndVerify(
        relayBaseUrl: String,
        network: String,
        allowUnsignedFallback: Boolean = false,
    ): VerifiedOhttpConfig {
        val signedUrl = deriveSignedConfigUrl(relayBaseUrl)

        try {
            Log.d(TAG, "Fetching signed configs from: $signedUrl")
            val envelope = fetchEnvelope(signedUrl)
            return verifyAndExtract(envelope, network)
        } catch (e: Exception) {
            Log.e(TAG, "Signed config verification failed", e)
            if (allowUnsignedFallback) {
                Log.w(TAG, "Falling back to unsigned configs (DEBUG MODE)")
                return fetchUnsignedFallback(relayBaseUrl)
            }
            throw KeyConfigVerificationFailedException(
                "Failed to fetch or verify signed OHTTP configs",
                e,
            )
        }
    }

    /**
     * Derives the signed config URL from the relay base URL.
     * Replaces the path with /ohttp-configs/signed.
     */
    private fun deriveSignedConfigUrl(relayBaseUrl: String): String {
        val url = relayBaseUrl.trimEnd('/')
        val baseWithoutPath = url.substringBeforeLast('/', url)
        return "$baseWithoutPath/ohttp-configs/signed"
    }

    /**
     * Fetches the signed envelope from the given URL.
     */
    private fun fetchEnvelope(url: String): SignedOhttpConfigEnvelope {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw KeyConfigVerificationFailedException(
                    "HTTP ${response.code} fetching signed configs",
                )
            }

            val body =
                response.body?.string()
                    ?: throw KeyConfigVerificationFailedException("Empty response body")

            return parseEnvelope(body)
        }
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
     * Verifies the envelope signature and extracts the config.
     *
     * Checks:
     * 1. Protocol version == 1
     * 2. Network matches expected
     * 3. Key ID matches embedded public key
     * 4. Signature is valid (Ed25519)
     * 5. Current time within [not_before - tolerance, not_after + tolerance]
     */
    private fun verifyAndExtract(
        envelope: SignedOhttpConfigEnvelope,
        expectedNetwork: String,
    ): VerifiedOhttpConfig {
        // 1. Check protocol version
        if (envelope.v != 1) {
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
        val nowSeconds = System.currentTimeMillis() / 1000
        val minValid = envelope.notBefore - TIME_TOLERANCE_SECONDS
        val maxValid = envelope.notAfter + TIME_TOLERANCE_SECONDS

        if (nowSeconds < minValid || nowSeconds > maxValid) {
            throw KeyConfigVerificationFailedException(
                "Config not valid at current time: now=$nowSeconds, valid=[$minValid, $maxValid]",
            )
        }

        // Extract key ID from first byte of configs
        val keyId = configBytes[0]

        Log.d(TAG, "Successfully verified signed config for $expectedNetwork")
        return VerifiedOhttpConfig(configBytes, keyId, envelope.notAfter)
    }

    /**
     * Builds the message that was signed: "zodl-ohttp-configs-v1" || 0x00 || network || 0x00 || not_before || not_after || configs
     */
    private fun buildSignedMessage(
        network: String,
        notBefore: Long,
        notAfter: Long,
        configBytes: ByteArray,
    ): ByteArray {
        val prefix = "zodl-ohttp-configs-v1".toByteArray(Charsets.US_ASCII)
        val networkBytes = network.toByteArray(Charsets.US_ASCII)

        val buffer =
            ByteBuffer
                .allocate(
                    prefix.size + 1 + networkBytes.size + 1 + 8 + 8 + configBytes.size,
                ).order(ByteOrder.BIG_ENDIAN)

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
        } catch (e: Exception) {
            Log.e(TAG, "Ed25519 verification error", e)
            false
        }

    /**
     * Unsigned fallback (DEBUG ONLY) - fetches raw RFC 9458 configs without signature verification.
     */
    private fun fetchUnsignedFallback(relayBaseUrl: String): VerifiedOhttpConfig {
        val url = relayBaseUrl.trimEnd('/')
        val baseWithoutPath = url.substringBeforeLast('/', url)
        val unsignedUrl = "$baseWithoutPath/ohttp-configs"

        Log.w(TAG, "Fetching UNSIGNED configs from: $unsignedUrl")

        val request =
            Request
                .Builder()
                .url(unsignedUrl)
                .get()
                .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw KeyConfigVerificationFailedException(
                    "HTTP ${response.code} fetching unsigned configs",
                )
            }

            val configBytes =
                response.body?.bytes()
                    ?: throw KeyConfigVerificationFailedException("Empty unsigned config response")

            val keyId = configBytes[0]
            // Unsigned configs don't expire (use a far future timestamp)
            return VerifiedOhttpConfig(configBytes, keyId, Long.MAX_VALUE)
        }
    }

    /**
     * Computes key ID for a public key (first 8 hex chars of SHA-256).
     */
    fun computeKeyId(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
