package co.electriccoin.lightwallet.client.internal

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * HPKE (RFC 9180) sender side, Base mode, for the OHTTP cipher suite
 * DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-128-GCM.
 *
 * Only what OHTTP (RFC 9458) needs is implemented: `SetupBaseS`, `Seal` and `Export`.
 * Pure JVM (BouncyCastle lightweight API) so it can be unit tested outside Android.
 */
internal object Hpke {
    const val KEM_ID: Int = 0x0020
    const val KDF_ID: Int = 0x0001
    const val AEAD_ID: Int = 0x0001

    const val N_PK: Int = 32 // X25519 public key size
    const val N_ENC: Int = 32 // Encapsulated key size
    const val N_SECRET: Int = 32 // KEM shared secret size
    const val N_H: Int = 32 // SHA-256 output size
    const val N_K: Int = 16 // AES-128-GCM key size
    const val N_N: Int = 12 // AES-GCM nonce size
    const val N_T: Int = 16 // AES-GCM tag size
    private const val N_T_BITS: Int = 128 // AES-GCM tag size in bits

    private const val MODE_BASE: Byte = 0x00
    private val VERSION_LABEL = "HPKE-v1".toByteArray(Charsets.US_ASCII)
    private val KEM_SUITE_ID = "KEM".toByteArray(Charsets.US_ASCII) + i2osp(KEM_ID, 2)
    private val HPKE_SUITE_ID =
        "HPKE".toByteArray(Charsets.US_ASCII) + i2osp(KEM_ID, 2) + i2osp(KDF_ID, 2) + i2osp(AEAD_ID, 2)

    private val random = SecureRandom()

    /**
     * Sender context returned by [setupBaseS]. Holds the AEAD key material and the exporter secret.
     */
    class SenderContext internal constructor(
        val enc: ByteArray,
        private val key: ByteArray,
        private val baseNonce: ByteArray,
        private val exporterSecret: ByteArray,
    ) {
        private var sequence: Long = 0

        /** RFC 9180 §5.2 `ContextS.Seal(aad, pt)`. */
        fun seal(
            aad: ByteArray,
            plaintext: ByteArray,
        ): ByteArray {
            val nonce = computeNonce(baseNonce, sequence)
            sequence++
            return aesGcmSeal(key, nonce, aad, plaintext)
        }

        /** RFC 9180 §5.3 `Context.Export(exporter_context, L)`. */
        fun export(
            exporterContext: ByteArray,
            length: Int,
        ): ByteArray = labeledExpand(HPKE_SUITE_ID, exporterSecret, "sec", exporterContext, length)
    }

    /** RFC 9180 §5.1.1 `SetupBaseS(pkR, info)`. */
    fun setupBaseS(
        recipientPublicKey: ByteArray,
        info: ByteArray,
    ): SenderContext {
        require(recipientPublicKey.size == N_PK) { "Recipient public key must be $N_PK bytes" }

        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(random))
        val ephemeral = generator.generateKeyPair()
        val skE = ephemeral.private as X25519PrivateKeyParameters
        return setupBaseS(recipientPublicKey, info, skE.encoded)
    }

    /**
     * [setupBaseS] with a caller-supplied ephemeral private key, so the RFC 9180 Appendix A test vectors
     * (which fix `skEm`) can be checked. Production code must use the random-ephemeral overload.
     */
    internal fun setupBaseS(
        recipientPublicKey: ByteArray,
        info: ByteArray,
        ephemeralPrivateKey: ByteArray,
    ): SenderContext {
        require(recipientPublicKey.size == N_PK) { "Recipient public key must be $N_PK bytes" }

        // DHKEM(X25519).Encap(pkR) — RFC 9180 §4.1
        val skE = X25519PrivateKeyParameters(ephemeralPrivateKey, 0)
        val pkE = skE.generatePublicKey()
        val pkR = X25519PublicKeyParameters(recipientPublicKey, 0)

        val dh = ByteArray(N_SECRET)
        X25519Agreement().apply { init(skE) }.calculateAgreement(pkR, dh, 0)

        val enc = pkE.encoded
        val kemContext = enc + recipientPublicKey
        val eaePrk = labeledExtract(KEM_SUITE_ID, ByteArray(0), "eae_prk", dh)
        val sharedSecret = labeledExpand(KEM_SUITE_ID, eaePrk, "shared_secret", kemContext, N_SECRET)

        // KeySchedule(mode_base, shared_secret, info, psk="", psk_id="") — RFC 9180 §5.1
        val pskIdHash = labeledExtract(HPKE_SUITE_ID, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(HPKE_SUITE_ID, ByteArray(0), "info_hash", info)
        val keyScheduleContext = byteArrayOf(MODE_BASE) + pskIdHash + infoHash
        val secret = labeledExtract(HPKE_SUITE_ID, sharedSecret, "secret", ByteArray(0))
        val key = labeledExpand(HPKE_SUITE_ID, secret, "key", keyScheduleContext, N_K)
        val baseNonce = labeledExpand(HPKE_SUITE_ID, secret, "base_nonce", keyScheduleContext, N_N)
        val exporterSecret = labeledExpand(HPKE_SUITE_ID, secret, "exp", keyScheduleContext, N_H)

        return SenderContext(enc, key, baseNonce, exporterSecret)
    }

    // ---- HKDF primitives (RFC 5869) -------------------------------------------------------------

    /** `Extract(salt, ikm)` = HMAC-SHA256(salt, ikm). */
    fun hkdfExtract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(if (salt.isEmpty()) ByteArray(N_H) else salt))
        mac.update(ikm, 0, ikm.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    /** `Expand(prk, info, L)`. */
    fun hkdfExpand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters.skipExtractParameters(prk, info))
        val out = ByteArray(length)
        hkdf.generateBytes(out, 0, length)
        return out
    }

    private fun labeledExtract(
        suiteId: ByteArray,
        salt: ByteArray,
        label: String,
        ikm: ByteArray,
    ): ByteArray = hkdfExtract(salt, VERSION_LABEL + suiteId + label.toByteArray(Charsets.US_ASCII) + ikm)

    private fun labeledExpand(
        suiteId: ByteArray,
        prk: ByteArray,
        label: String,
        info: ByteArray,
        length: Int,
    ): ByteArray =
        hkdfExpand(
            prk,
            i2osp(length, 2) + VERSION_LABEL + suiteId + label.toByteArray(Charsets.US_ASCII) + info,
            length,
        )

    // ---- AEAD ------------------------------------------------------------------------------------

    fun aesGcmSeal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(true, AEADParameters(KeyParameter(key), N_T_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val n = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    fun aesGcmOpen(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(false, AEADParameters(KeyParameter(key), N_T_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val n = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    private fun computeNonce(
        baseNonce: ByteArray,
        sequence: Long,
    ): ByteArray {
        val nonce = baseNonce.copyOf()
        val seq = i2osp(sequence, N_N)
        for (i in nonce.indices) {
            nonce[i] = (nonce[i].toInt() xor seq[i].toInt()).toByte()
        }
        return nonce
    }

    fun i2osp(
        value: Int,
        length: Int,
    ): ByteArray = i2osp(value.toLong(), length)

    /** Integer-to-octet-string primitive (RFC 9180 §4). */
    @Suppress("MagicNumber") // Bit shifts are part of the byte-packing algorithm
    fun i2osp(
        value: Long,
        length: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(length)
        for (shift in (length - 1) downTo 0) {
            val shiftBits = shift * 8
            val byte = if (shiftBits >= 64) 0 else ((value ushr shiftBits) and 0xFF).toInt()
            out.write(byte)
        }
        return out.toByteArray()
    }
}
