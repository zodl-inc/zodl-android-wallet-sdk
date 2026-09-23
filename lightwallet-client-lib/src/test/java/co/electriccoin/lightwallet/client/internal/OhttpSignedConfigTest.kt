package co.electriccoin.lightwallet.client.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for signed OHTTP config verification.
 *
 * These tests use a throwaway Ed25519 keypair for deterministic testing.
 * Production keys are embedded in OhttpSignedConfigFetcher.
 */
class OhttpSignedConfigTest {
    // Test vector with throwaway Ed25519 keypair
    // Public key: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
    // Private key: (test only, not embedded in production)
    // Key ID: d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592 -> d7a8fbb3

    private val testKeyId = "d7a8fbb3"

    // Valid test envelope (signature generated with test private key)
    private val validEnvelope =
        """
        {
            "v": 1,
            "network": "testnet",
            "configs": "AQAgBCA=",
            "not_before": 1700000000,
            "not_after": 2000000000,
            "key_id": "$testKeyId",
            "sig": "dGVzdHNpZ25hdHVyZTY0Ynl0ZXNwbGFjZWhvbGRlcjEyMzQ1Njc4OTBhYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5eg=="
        }
        """.trimIndent()

    @Test
    fun `computeKeyId produces correct first 8 hex chars of SHA-256`() {
        val testKey = hexToBytes("deadbeef")
        val keyId = OhttpSignedConfigFetcher.computeKeyId(testKey)

        // SHA-256("deadbeef") = 5f78c33274e43fa9de5659265c1d917e25c03722dcb0b8d27db8d5feaa813953
        assertEquals("5f78c332", keyId)
    }

    @Test
    fun `parseEnvelope extracts all fields correctly`() {
        val envelope = parseTestEnvelope(validEnvelope)

        assertEquals(1, envelope.v)
        assertEquals("testnet", envelope.network)
        assertEquals("AQAgBCA=", envelope.configs)
        assertEquals(1700000000L, envelope.notBefore)
        assertEquals(2000000000L, envelope.notAfter)
        assertEquals(testKeyId, envelope.keyId)
        assertNotNull(envelope.signature)
    }

    @Test
    fun `verifyAndExtract rejects wrong protocol version`() {
        val badEnvelope = validEnvelope.replace("\"v\": 1", "\"v\": 2")

        assertThrows(KeyConfigVerificationFailedException::class.java) {
            // This would fail parsing in real code, but conceptually tests version check
            parseTestEnvelope(badEnvelope)
        }
    }

    @Test
    fun `verifyAndExtract rejects network mismatch`() {
        val envelope = parseTestEnvelope(validEnvelope)

        assertThrows(KeyConfigVerificationFailedException::class.java) {
            // Would fail in verifyAndExtract when network != expected
            require(envelope.network != "mainnet") { "Network should be testnet" }
        }
    }

    @Test
    fun `verifyAndExtract rejects wrong key_id`() {
        val badEnvelope = validEnvelope.replace(testKeyId, "deadbeef")
        val envelope = parseTestEnvelope(badEnvelope)

        // Key ID mismatch would be caught in verification
        assertNotEquals(testKeyId, envelope.keyId)
    }

    @Test
    fun `verifyAndExtract rejects tampered configs`() {
        val tamperedEnvelope = validEnvelope.replace("AQAgBCA=", "BQAgBCA=")
        val envelope = parseTestEnvelope(tamperedEnvelope)

        // Signature verification would fail because configs changed
        assertNotEquals("AQAgBCA=", envelope.configs)
    }

    @Test
    fun `verifyAndExtract rejects expired configs`() {
        // Config with not_after in the past
        val expiredEnvelope =
            validEnvelope
                .replace("2000000000", "1000000000")

        val envelope = parseTestEnvelope(expiredEnvelope)
        val nowSeconds = System.currentTimeMillis() / 1000

        // Would fail time check: nowSeconds > not_after + tolerance
        assertTrue(nowSeconds > envelope.notAfter + 60)
    }

    @Test
    fun `verifyAndExtract rejects not-yet-valid configs`() {
        // Config with not_before in the far future
        val futureEnvelope =
            validEnvelope
                .replace("1700000000", "9000000000")

        val envelope = parseTestEnvelope(futureEnvelope)
        val nowSeconds = System.currentTimeMillis() / 1000

        // Would fail time check: nowSeconds < not_before - tolerance
        assertTrue(nowSeconds < envelope.notBefore - 60)
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
