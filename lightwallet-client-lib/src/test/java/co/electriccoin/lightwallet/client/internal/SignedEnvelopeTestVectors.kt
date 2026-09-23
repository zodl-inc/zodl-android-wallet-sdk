package co.electriccoin.lightwallet.client.internal

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/**
 * Fresh Ed25519 signing key generated inside the test process. Never a production key: tests
 * inject [publicKey] into the verifier instead of relying on the embedded ZODL keys.
 */
internal class TestSigningKey {
    val privateKey: ByteArray
    val publicKey: ByteArray

    init {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        privateKey = (pair.private as Ed25519PrivateKeyParameters).encoded
        publicKey = (pair.public as Ed25519PublicKeyParameters).encoded
    }

    val keyId: String
        get() = OhttpSignedConfigVerifier.computeKeyId(publicKey)
}

/**
 * Builds signed `/ohttp-configs/signed` envelopes for tests. Every field can be overridden to
 * produce a negative case while the signature stays valid for the (possibly bogus) content.
 */
internal object SignedEnvelopeTestVectors {
    const val ONE_HOUR_SECONDS = 3600L
    const val ONE_DAY_SECONDS = 86400L

    /**
     * RFC 9458 key configuration list containing one config: `key_id`, X25519 KEM, a dummy
     * 32-byte public key and the single HKDF-SHA256 / AES-128-GCM symmetric suite.
     */
    fun keyConfigBytes(keyId: Int = 1): ByteArray {
        val body =
            byteArrayOf(keyId.toByte(), 0x00, 0x20) +
                ByteArray(32) { 1 } +
                byteArrayOf(0x00, 0x04, 0x00, 0x01, 0x00, 0x01)
        return byteArrayOf(0x00, body.size.toByte()) + body
    }

    /** Signs [message] with a raw 32-byte Ed25519 private key (test vector generation). */
    fun signMessage(
        privateKey: ByteArray,
        message: ByteArray,
    ): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    @Suppress("LongParameterList") // Every envelope field is overridable for negative cases
    fun signedEnvelope(
        key: TestSigningKey,
        notBefore: Long,
        notAfter: Long,
        network: String = "testnet",
        configBytes: ByteArray = keyConfigBytes(),
        version: Int = 1,
        keyId: String = key.keyId,
        tamperSignature: Boolean = false,
        signatureOverride: String? = null,
    ): String {
        val message = OhttpSignedConfigVerifier.buildSignedMessage(network, notBefore, notAfter, configBytes)
        val signature = signMessage(key.privateKey, message)
        if (tamperSignature) {
            signature[0] = (signature[0].toInt() xor 0x01).toByte()
        }
        val encoder = Base64.getEncoder()
        val sig = signatureOverride ?: encoder.encodeToString(signature)
        val configs = encoder.encodeToString(configBytes)
        return """{"v": $version, "network": "$network", "configs": "$configs",""" +
            """ "not_before": $notBefore, "not_after": $notAfter, "key_id": "$keyId", "sig": "$sig"}"""
    }
}
