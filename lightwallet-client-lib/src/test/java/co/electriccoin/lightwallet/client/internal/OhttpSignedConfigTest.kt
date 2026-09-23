package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.internal.SignedEnvelopeTestVectors.ONE_DAY_SECONDS
import co.electriccoin.lightwallet.client.internal.SignedEnvelopeTestVectors.ONE_HOUR_SECONDS
import co.electriccoin.lightwallet.client.internal.SignedEnvelopeTestVectors.signedEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [OhttpSignedConfigVerifier]: a pure function over (envelope, network, clock,
 * trusted key). Envelopes are generated inside each test with a fresh Ed25519 key and verified
 * against a fixed clock, so no production private key is needed.
 */
class OhttpSignedConfigTest {
    private class FixedClock(
        private val fixedTimeSeconds: Long,
    ) : OhttpSignedConfigVerifier.Clock {
        override fun currentTimeSeconds(): Long = fixedTimeSeconds
    }

    private val key = TestSigningKey()
    private val notBefore = 1_700_000_000L
    private val notAfter = notBefore + 6 * ONE_HOUR_SECONDS
    private val clock = FixedClock(notBefore + ONE_HOUR_SECONDS)

    @Test
    fun `computeKeyId produces correct first 8 hex chars of SHA-256`() {
        val testKey = OhttpSignedConfigVerifier.hexToBytes("deadbeef")
        val keyId = OhttpSignedConfigVerifier.computeKeyId(testKey)
        // SHA-256(deadbeef) = 5f78c33274e43fa9de5659265c1d917e25c03722dcb0b8d27db8d5feaa813953
        assertEquals("5f78c332", keyId)
    }

    @Test
    fun `buildSignedMessage format is correct`() {
        val message =
            OhttpSignedConfigVerifier.buildSignedMessage(
                network = "testnet",
                notBefore = notBefore,
                notAfter = notAfter,
                configBytes = byteArrayOf(0x01, 0x02, 0x03),
            )
        val prefix = "zodl-ohttp-configs-v1".toByteArray(Charsets.US_ASCII)
        assertTrue(message.take(prefix.size).toByteArray().contentEquals(prefix))
        assertEquals(0x00.toByte(), message[prefix.size])
    }

    @Test
    fun `verifyAndParse accepts valid signed envelope`() {
        val envelope = signedEnvelope(key, notBefore, notAfter)
        val (keyConfig, expiry) =
            OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock, key.publicKey)
        assertEquals(1, keyConfig.keyId)
        assertEquals(Hpke.KEM_ID, keyConfig.kemId)
        assertEquals(notAfter, expiry)
    }

    @Test
    fun `verifyAndParse rejects tampered signature`() {
        val envelope = signedEnvelope(key, notBefore, notAfter, tamperSignature = true)
        val e =
            assertFailsWith<KeyConfigVerificationFailedException> {
                OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock, key.publicKey)
            }
        assertTrue(e.message!!.contains("signature"))
    }

    @Test
    fun `verifyAndParse rejects wrong network`() {
        val envelope = signedEnvelope(key, notBefore, notAfter, network = "mainnet")
        val e =
            assertFailsWith<KeyConfigVerificationFailedException> {
                OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock, key.publicKey)
            }
        assertTrue(e.message!!.contains("Network mismatch"))
    }

    @Test
    fun `verifyAndParse rejects expired configs`() {
        val envelope = signedEnvelope(key, notBefore, notAfter)
        val afterExpiry = FixedClock(notAfter + 200) // beyond not_after + 60 s tolerance
        val e =
            assertFailsWith<KeyConfigVerificationFailedException> {
                OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", afterExpiry, key.publicKey)
            }
        assertTrue(e.message!!.contains("not valid at current time"))
    }

    @Test
    fun `verifyAndParse rejects configs with validity period over 24 hours`() {
        val envelope = signedEnvelope(key, notBefore, notBefore + ONE_DAY_SECONDS + ONE_HOUR_SECONDS)
        val e =
            assertFailsWith<KeyConfigVerificationFailedException> {
                OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock, key.publicKey)
            }
        assertTrue(e.message!!.contains("Validity period too long"))
    }

    @Test
    fun `verifyAndParse rejects not_after more than 24 hours ahead of now`() {
        val farNotAfter = clock.currentTimeSeconds() + ONE_DAY_SECONDS + ONE_HOUR_SECONDS
        val envelope = signedEnvelope(key, farNotAfter - ONE_HOUR_SECONDS, farNotAfter)
        val e =
            assertFailsWith<KeyConfigVerificationFailedException> {
                OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock, key.publicKey)
            }
        assertTrue(e.message!!.contains("too far in future"))
    }

    @Test
    fun `verifyAndParse rejects wrong protocol version`() {
        val envelope = signedEnvelope(key, notBefore, notAfter, version = 2)
        assertFailsWith<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock, key.publicKey)
        }
    }

    @Test
    fun `verifyAndParse rejects key_id that does not match the trusted key`() {
        val envelope = signedEnvelope(key, notBefore, notAfter, keyId = "00000000")
        val e =
            assertFailsWith<KeyConfigVerificationFailedException> {
                OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock, key.publicKey)
            }
        assertTrue(e.message!!.contains("Key ID mismatch"))
    }

    @Test
    fun `verifyAndParse defaults to the embedded production key`() {
        // Without an injected key the verifier must only trust the embedded ZODL testnet key.
        val envelope = signedEnvelope(key, notBefore, notAfter)
        val e =
            assertFailsWith<KeyConfigVerificationFailedException> {
                OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock)
            }
        assertTrue(e.message!!.contains("Key ID mismatch: expected a3620fb6"))
    }

    @Test
    fun `verifyAndParse rejects malformed base64 as a verification failure`() {
        val envelope = signedEnvelope(key, notBefore, notAfter, signatureOverride = "!!not-base64!!")
        assertFailsWith<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse(envelope, "testnet", clock, key.publicKey)
        }
    }

    @Test
    fun `verifyAndParse rejects malformed JSON as a verification failure`() {
        assertFailsWith<KeyConfigVerificationFailedException> {
            OhttpSignedConfigVerifier.verifyAndParse("{not json", "testnet", clock, key.publicKey)
        }
    }
}
