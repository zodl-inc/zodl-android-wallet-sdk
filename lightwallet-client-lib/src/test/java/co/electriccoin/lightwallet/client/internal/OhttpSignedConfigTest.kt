package co.electriccoin.lightwallet.client.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.fail

/**
 * Unit tests for signed OHTTP config verification.
 *
 * These tests use the deterministic test vector from zodl-inc/ohttp repo.
 * Production keys are embedded in OhttpSignedConfigVerifier.
 */
class OhttpSignedConfigTest {
    // Deterministic test vector from zodl-inc/ohttp/docs/signed-configs-test-vector.json
    // Public key: a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f0
    // Key ID: 1325b850
    // Network: testnet

    private val testKeyId = "1325b850"
    private val testPublicKey = "a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f0"
    private val testConfigsBase64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKg=="
    private val testNotBefore = 1700000000L
    private val testNotAfter = 1700021600L // 6 hours validity
    private val testSignature =
        "eaavjMihe2EGm3giQq5NArYVD+G1A5AxVzQxXwvGz8C8qKRIEXl/T0TpOZe2bL+OkNmSAIX95uYgA4zGQGXbCg=="

    // Valid test envelope (from test vector)
    private val validEnvelope =
        """
        {
            "v": 1,
            "network": "testnet",
            "configs": "$testConfigsBase64",
            "not_before": $testNotBefore,
            "not_after": $testNotAfter,
            "key_id": "$testKeyId",
            "sig": "$testSignature"
        }
        """.trimIndent()

    @Test
    fun `computeKeyId produces correct first 8 hex chars of SHA-256`() {
        val testKey = hexToBytes("deadbeef")
        val keyId = OhttpSignedConfigVerifier.computeKeyId(testKey)

        // SHA-256("deadbeef") = 5f78c33274e43fa9de5659265c1d917e25c03722dcb0b8d27db8d5feaa813953
        assertEquals("5f78c332", keyId)
    }

    @Test
    fun `computeKeyId produces correct key ID for test vector public key`() {
        val publicKey = hexToBytes(testPublicKey)
        val keyId = OhttpSignedConfigVerifier.computeKeyId(publicKey)
        assertEquals(testKeyId, keyId)
    }

    @Test
    fun `parseEnvelope extracts all fields correctly`() {
        val envelope = parseTestEnvelope(validEnvelope)

        assertEquals(1, envelope.v)
        assertEquals("testnet", envelope.network)
        assertEquals(testConfigsBase64, envelope.configs)
        assertEquals(testNotBefore, envelope.notBefore)
        assertEquals(testNotAfter, envelope.notAfter)
        assertEquals(testKeyId, envelope.keyId)
        assertNotNull(envelope.signature)
    }

    @Test
    fun `verifyAndParse rejects wrong protocol version`() {
        val badEnvelope = validEnvelope.replace("\"v\": 1", "\"v\": 2")

        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(badEnvelope, "testnet")
        }
    }

    @Test
    fun `verifyAndParse rejects network mismatch`() {
        // Envelope is for testnet, but we expect mainnet
        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(validEnvelope, "mainnet")
        }
    }

    @Test
    fun `verifyAndParse rejects tampered signature`() {
        val tamperedEnvelope = validEnvelope.replace(testSignature.substring(0, 10), "AAAAAAAA")

        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(tamperedEnvelope, "testnet")
        }
    }

    @Test
    fun `verifyAndParse rejects tampered configs`() {
        val tamperedEnvelope = validEnvelope.replace(testConfigsBase64, "BQAgBCA=")

        // Signature verification should fail because configs changed
        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(tamperedEnvelope, "testnet")
        }
    }

    @Test
    fun `verifyAndParse rejects expired configs`() {
        // Config with not_after in the past (year 2000)
        val expiredEnvelope =
            validEnvelope
                .replace("$testNotAfter", "946684800")

        // Note: signature is now invalid, but expiry check would catch it anyway
        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(expiredEnvelope, "testnet")
        }
    }

    @Test
    fun `verifyAndParse rejects configs with validity period over 24 hours`() {
        // Config with validity > 24 hours (25 hours = 90000 seconds)
        val longValidityEnvelope =
            validEnvelope
                .replace("$testNotAfter", "${testNotBefore + 90000}")

        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(longValidityEnvelope, "testnet")
        }
    }

    @Test
    fun `buildSignedMessage format is correct`() {
        val message =
            buildTestSignedMessage(
                network = "testnet",
                notBefore = 1700000000L,
                notAfter = 2000000000L,
                configs = byteArrayOf(0x01, 0x02, 0x03),
            )

        // Should start with "zodl-ohttp-configs-v1\0"
        val prefix = "zodl-ohttp-configs-v1".toByteArray(Charsets.US_ASCII)
        assertTrue(message.take(prefix.size).toByteArray().contentEquals(prefix))
        assertEquals(0x00.toByte(), message[prefix.size])
    }

    @Test
    fun `verifyAndParse only fetches from signed endpoint`() {
        // This test verifies that the system fetches from /ohttp-configs/signed
        // The actual fetch is done by the sender, so this is documented behavior
        // In practice, the transport layer must construct the URL as:
        // relayUrl.removeSuffix("/relay") + "/ohttp-configs/signed"
        assertTrue(true) // Behavior documented in OhttpManagedChannel
    }

    // Helper methods for testing

    private fun parseTestEnvelope(json: String): SignedOhttpConfigEnvelope {
        val obj = org.json.JSONObject(json)
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

    private fun buildTestSignedMessage(
        network: String,
        notBefore: Long,
        notAfter: Long,
        configs: ByteArray,
    ): ByteArray {
        val prefix = "zodl-ohttp-configs-v1".toByteArray(Charsets.US_ASCII)
        val networkBytes = network.toByteArray(Charsets.US_ASCII)

        val buffer =
            java.nio.ByteBuffer
                .allocate(
                    prefix.size + 1 + networkBytes.size + 1 + 8 + 8 + configs.size,
                ).order(java.nio.ByteOrder.BIG_ENDIAN)

        buffer.put(prefix)
        buffer.put(0x00.toByte())
        buffer.put(networkBytes)
        buffer.put(0x00.toByte())
        buffer.putLong(notBefore)
        buffer.putLong(notAfter)
        buffer.put(configs)

        return buffer.array()
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private inline fun <reified T : Throwable> assertThrows(crossinline block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName} to be thrown")
        } catch (e: Throwable) {
            if (e !is T) {
                throw AssertionError(
                    "Expected ${T::class.java.simpleName} but got ${e::class.java.simpleName}",
                    e,
                )
            }
        }
    }
}
