package co.electriccoin.lightwallet.client.internal

import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpSender
import co.electriccoin.lightwallet.client.internal.SignedEnvelopeTestVectors.ONE_DAY_SECONDS
import co.electriccoin.lightwallet.client.internal.SignedEnvelopeTestVectors.ONE_HOUR_SECONDS
import co.electriccoin.lightwallet.client.internal.SignedEnvelopeTestVectors.signedEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Integration tests for signed OHTTP config verification through the production
 * [OhttpTransport.keyConfig] path: fetch from `/ohttp-configs/signed` → verify → parse → cache.
 *
 * A fake [OhttpSender] injects envelopes signed in-test with a fresh Ed25519 key, and that key is
 * passed to the transport as the trusted signing key.
 */
class OhttpSignedConfigIntegrationTest {
    private val key = TestSigningKey()
    private val now = System.currentTimeMillis() / 1000
    private val notBefore = now - ONE_HOUR_SECONDS
    private val notAfter = now + ONE_HOUR_SECONDS

    private fun transport(
        sender: FakeSender,
        network: String = "testnet",
    ) = OhttpTransport(
        relayUrl = "https://relay.test/relay",
        network = network,
        sender = sender,
        trustedSigningKey = key.publicKey,
    )

    private fun sender(envelope: String) = FakeSender(getResponses = listOf(envelope.toByteArray(Charsets.UTF_8)))

    @Test
    fun `keyConfig with valid envelope succeeds`() {
        val sender = sender(signedEnvelope(key, notBefore, notAfter))

        val keyConfig = runBlocking { transport(sender).keyConfig() }

        assertEquals(1, keyConfig.keyId)
        assertEquals(1, sender.getCalls.size)
        assertEquals("https://relay.test/ohttp-configs/signed", sender.getCalls[0].first)
    }

    @Test
    fun `keyConfig rejects tampered signature`() {
        val sender = sender(signedEnvelope(key, notBefore, notAfter, tamperSignature = true))

        assertFailsWith<KeyConfigVerificationFailedException> {
            runBlocking { transport(sender).keyConfig() }
        }
    }

    @Test
    fun `keyConfig rejects wrong network`() {
        val sender = sender(signedEnvelope(key, notBefore, notAfter, network = "mainnet"))

        assertFailsWith<KeyConfigVerificationFailedException> {
            runBlocking { transport(sender, network = "testnet").keyConfig() }
        }
    }

    @Test
    fun `keyConfig rejects expired envelope`() {
        val sender = sender(signedEnvelope(key, now - 2 * ONE_HOUR_SECONDS, now - ONE_HOUR_SECONDS))

        assertFailsWith<KeyConfigVerificationFailedException> {
            runBlocking { transport(sender).keyConfig() }
        }
    }

    @Test
    fun `keyConfig rejects validity period exceeding 24 hours`() {
        val sender = sender(signedEnvelope(key, notBefore, notBefore + ONE_DAY_SECONDS + ONE_HOUR_SECONDS))

        assertFailsWith<KeyConfigVerificationFailedException> {
            runBlocking { transport(sender).keyConfig() }
        }
    }

    @Test
    fun `keyConfig rejects not_after too far in future`() {
        val farNotAfter = now + ONE_DAY_SECONDS + ONE_HOUR_SECONDS
        val sender = sender(signedEnvelope(key, farNotAfter - ONE_HOUR_SECONDS, farNotAfter))

        assertFailsWith<KeyConfigVerificationFailedException> {
            runBlocking { transport(sender).keyConfig() }
        }
    }

    @Test
    fun `keyConfig fetches from signed endpoint not unsigned`() {
        val sender = sender(signedEnvelope(key, notBefore, notAfter))

        runBlocking { transport(sender).keyConfig() }

        // Exactly one GET, to /ohttp-configs/signed; none to the unsigned /ohttp-configs.
        assertEquals(1, sender.getCalls.size)
        assertEquals("https://relay.test/ohttp-configs/signed", sender.getCalls[0].first)
        assertEquals(0, sender.getCalls.count { it.first.endsWith("/ohttp-configs") })
        assertEquals(0, sender.postCalls.size)
    }

    @Test
    fun `keyConfig caches verified config until expiry`() {
        val sender = sender(signedEnvelope(key, notBefore, notAfter))
        val transport = transport(sender)

        runBlocking {
            transport.keyConfig()
            transport.keyConfig()
        }

        assertEquals(1, sender.getCalls.size)
    }

    /**
     * Fake sender that returns canned responses and records calls.
     */
    private class FakeSender(
        private val getResponses: List<ByteArray> = emptyList(),
        private val postResponses: List<ByteArray> = emptyList(),
    ) : OhttpSender {
        val getCalls = mutableListOf<Triple<String, List<Pair<String, String>>, Int>>()
        val postCalls = mutableListOf<Triple<String, ByteArray, List<Pair<String, String>>>>()

        private var getIndex = 0
        private var postIndex = 0

        override suspend fun dispose() = Unit

        override suspend fun get(
            url: String,
            headers: List<Pair<String, String>>,
            timeoutMs: Int,
        ): ByteArray {
            getCalls.add(Triple(url, headers, timeoutMs))
            if (getIndex >= getResponses.size) {
                throw OhttpException("No more GET responses")
            }
            return getResponses[getIndex++]
        }

        override suspend fun post(
            url: String,
            body: ByteArray,
            headers: List<Pair<String, String>>,
            timeoutMs: Int,
        ): ByteArray {
            postCalls.add(Triple(url, body, headers))
            if (postIndex >= postResponses.size) {
                throw OhttpException("No more POST responses")
            }
            return postResponses[postIndex++]
        }
    }
}
