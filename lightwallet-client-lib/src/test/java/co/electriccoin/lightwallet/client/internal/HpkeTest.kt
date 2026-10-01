package co.electriccoin.lightwallet.client.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HpkeTest {
    @Test
    fun i2osp_12ByteLength_smallValue() {
        val result = Hpke.i2osp(1, 12)
        val expected = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01)
        assertArrayEquals(expected, result)
    }

    @Test
    fun i2osp_12ByteLength_twoByteValue() {
        val result = Hpke.i2osp(0x0102, 12)
        val expected = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0x02)
        assertArrayEquals(expected, result)
    }

    @Test
    fun i2osp_12ByteLength_zero() {
        val result = Hpke.i2osp(0, 12)
        val expected = ByteArray(12)
        assertArrayEquals(expected, result)
    }

    /**
     * RFC 9180 Appendix A.1.1: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, Base mode.
     * Same suite the OHTTP client uses. Covers the KEM, the key schedule (via the derived AEAD key and
     * base nonce), the nonce/sequence handling, and the exporter.
     */
    @Test
    fun rfc9180_appendixA1_baseMode_encapsulatedKey() {
        assertArrayEquals(hex(A1_PK_EM), a1Context().enc)
    }

    @Test
    fun rfc9180_appendixA1_baseMode_encryptions() {
        val context = a1Context()
        val plaintext = hex(A1_PT)
        for (sequence in 0..A1_MAX_SEQUENCE) {
            val ciphertext = context.seal("Count-$sequence".toByteArray(Charsets.US_ASCII), plaintext)
            val expected = A1_CIPHERTEXTS[sequence]
            if (expected != null) {
                assertArrayEquals("sequence $sequence", hex(expected), ciphertext)
            }
        }
    }

    @Test
    fun rfc9180_appendixA1_baseMode_exporter() {
        val context = a1Context()
        for ((exporterContext, exported) in A1_EXPORTS) {
            assertArrayEquals(hex(exported), context.export(hex(exporterContext), A1_EXPORT_LENGTH))
        }
    }

    private fun a1Context(): Hpke.SenderContext = Hpke.setupBaseS(hex(A1_PK_RM), hex(A1_INFO), hex(A1_SK_EM))

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val A1_INFO = "4f6465206f6e2061204772656369616e2055726e"
        const val A1_SK_EM = "52c4a758a802cd8b936eceea314432798d5baf2d7e9235dc084ab1b9cfa2f736"
        const val A1_PK_EM = "37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431"
        const val A1_PK_RM = "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d"
        const val A1_PT = "4265617574792069732074727574682c20747275746820626561757479"
        const val A1_MAX_SEQUENCE = 256
        const val A1_EXPORT_LENGTH = 32

        // sequence number -> ct (aad is "Count-<sequence>")
        val A1_CIPHERTEXTS =
            mapOf(
                0 to "f938558b5d72f1a23810b4be2ab4f84331acc02fc97babc53a52ae8218a355a96d8770ac83d07bea87e13c512a",
                1 to "af2d7e9ac9ae7e270f46ba1f975be53c09f8d875bdc8535458c2494e8a6eab251c03d0c22a56b8ca42c2063b84",
                2 to "498dfcabd92e8acedc281e85af1cb4e3e31c7dc394a1ca20e173cb72516491588d96a19ad4a683518973dcc180",
                4 to "583bd32bc67a5994bb8ceaca813d369bca7b2a42408cddef5e22f880b631215a09fc0012bc69fccaa251c0246d",
                255 to "7175db9717964058640a3a11fb9007941a5d1757fda1a6935c805c21af32505bf106deefec4a49ac38d71c9e0a",
                256 to "957f9800542b0b8891badb026d79cc54597cb2d225b54c00c5238c25d05c30e3fbeda97d2e0e1aba483a2df9f2",
            )

        // exporter_context -> exported_value (L = 32)
        val A1_EXPORTS =
            listOf(
                "" to "3853fe2b4035195a573ffc53856e77058e15d9ea064de3e59f4961d0095250ee",
                "00" to "2e8f0b54673c7029649d4eb9d5e33bf1872cf76d623ff164ac185da9e88c21a5",
                "54657374436f6e74657874" to "e9e43065102c3836401bed8c3c3c75ae46be1639869391d62c61f1ec7af54931",
            )
    }
}
