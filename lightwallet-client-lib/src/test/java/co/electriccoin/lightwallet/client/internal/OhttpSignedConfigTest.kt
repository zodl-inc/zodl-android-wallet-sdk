package co.electriccoin.lightwallet.client.internal

import android.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import kotlin.test.fail

/**
 * Unit tests for signed OHTTP config verification.
 *
 * Tests the individual components (parsing, key ID computation, message building)
 * without requiring production private keys.
 */
class OhttpSignedConfigTest {
    private class FixedClock(private val fixedTimeSeconds: Long) : OhttpSignedConfigVerifier.Clock {
        override fun currentTimeSeconds(): Long = fixedTimeSeconds
    }

    @Test
    fun `computeKeyId produces correct first 8 hex chars of SHA-256`() {
        val testKey = OhttpSignedConfigVerifier.hexToBytes("deadbeef")
        val keyId = OhttpSignedConfigVerifier.computeKeyId(testKey)
        // SHA-256("deadbeef") = 5f78c33274e43fa9de5659265c1d917e25c03722dcb0b8d27db8d5feaa813953
        assertEquals("5f78c332", keyId)
    }

    @Test
    fun `buildSignedMessage format is correct`() {
        val message = OhttpSignedConfigVerifier.buildSignedMessage(
            network = "testnet",
            notBefore = 1700000000L,
            notAfter = 2000000000L,
            configBytes = byteArrayOf(0x01, 0x02, 0x03),
        )
        // Should start with "zodl-ohttp-configs-v1\0"
        val prefix = "zodl-ohttp-configs-v1".toByteArray(Charsets.US_ASCII)
        assertTrue(message.take(prefix.size).toByteArray().contentEquals(prefix))
        assertEquals(0x00.toByte(), message[prefix.size])
    }

    @Test
    fun `verifyAndParse rejects wrong protocol version`() {
        val envelope = """{"v": 2, "network": "testnet", "configs": "AAAA", "not_before": 1700000000, "not_after": 1700021600, "key_id": "a3620fb6", "sig": "AAAA"}"""
        val clock = FixedClock(1700010000)
        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock)
        }
    }

    @Test
    fun `verifyAndParse rejects network mismatch`() {
        val envelope = """{"v": 1, "network": "testnet", "configs": "AAAA", "not_before": 1700000000, "not_after": 1700021600, "key_id": "ba8a2dbd", "sig": "AAAA"}"""
        val clock = FixedClock(1700010000)
        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(envelope, "mainnet", clock)
        }
    }

    @Test
    fun `verifyAndParse rejects expired configs`() {
        val envelope = """{"v": 1, "network": "testnet", "configs": "AAAA", "not_before": 1700000000, "not_after": 1700021600, "key_id": "a3620fb6", "sig": "AAAA"}"""
        val clock = FixedClock(1700021800) // After notAfter + tolerance
        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock)
        }
    }

    @Test
    fun `verifyAndParse rejects configs with validity period over 24 hours`() {
        val longNotAfter = 1700000000L + 90000 // 25 hours
        val envelope = """{"v": 1, "network": "testnet", "configs": "AAAA", "not_before": 1700000000, "not_after": $longNotAfter, "key_id": "a3620fb6", "sig": "AAAA"}"""
        val clock = FixedClock(1700010000)
        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock)
        }
    }

    @Test
    fun `verifyAndParse rejects invalid signature`() {
        // Valid format but wrong signature - will fail signature check
        val envelope = """{"v": 1, "network": "testnet", "configs": "AAAA", "not_before": 1700000000, "not_after": 1700021600, "key_id": "a3620fb6", "sig": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="}"""
        val clock = FixedClock(1700010000)
        assertThrows<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock)
        }
    }

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
EOF

# Create final tarball v4
tar czf /tmp/sdk-fixes-v4.tar.gz -C . \
  lightwallet-client-lib/build.gradle.kts \
  lightwallet-client-lib/src/main/java/co/electriccoin/lightwallet/client/internal/OhttpSignedConfig.kt \
  lightwallet-client-lib/src/test/java/co/electriccoin/lightwallet/client/internal/OhttpSignedConfigTest.kt \
  sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/SaplingParamTool.kt \
  lightwallet-client-lib/src/main/java/co/electriccoin/lightwallet/client/internal/LightWalletClientImpl.kt

ls -lh /tmp/sdk-fixes-v4.tar.gz
