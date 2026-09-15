package co.electriccoin.lightwallet.client.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class OhttpSecretStrippingTest {
    @Test
    fun `stripSecretsForGateway removes Authorization for near_zodl_com`() {
        val headers = listOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer secret-token",
            "Accept" to "application/json"
        )
        val logged = mutableListOf<String>()
        val stripped = stripSecretsForGateway("near.zodl.com", headers) { logged.add(it) }

        assertEquals(2, stripped.size)
        assertEquals("Content-Type", stripped[0].first)
        assertEquals("Accept", stripped[1].first)
        assertEquals(1, logged.size)
        assert(logged[0].contains("Authorization"))
        assert(logged[0].contains("near.zodl.com"))
    }

    @Test
    fun `stripSecretsForGateway removes X-CMC_PRO_API_KEY for cmc_zodl_com`() {
        val headers = listOf(
            "Accept" to "application/json",
            "X-CMC_PRO_API_KEY" to "secret-key-12345"
        )
        val logged = mutableListOf<String>()
        val stripped = stripSecretsForGateway("cmc.zodl.com", headers) { logged.add(it) }

        assertEquals(1, stripped.size)
        assertEquals("Accept", stripped[0].first)
        assertEquals(1, logged.size)
        assert(logged[0].contains("X-CMC_PRO_API_KEY"))
        assert(logged[0].contains("cmc.zodl.com"))
    }

    @Test
    fun `stripSecretsForGateway does not touch headers for non-secret-injecting hosts`() {
        val headers = listOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer token",
            "X-CMC_PRO_API_KEY" to "key"
        )
        val logged = mutableListOf<String>()
        val stripped = stripSecretsForGateway("vote.zodl.com", headers) { logged.add(it) }

        assertEquals(3, stripped.size)
        assertEquals(0, logged.size)
    }

    @Test
    fun `stripSecretsForGateway handles headers without secrets`() {
        val headers = listOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
        val logged = mutableListOf<String>()
        val stripped = stripSecretsForGateway("near.zodl.com", headers) { logged.add(it) }

        assertEquals(2, stripped.size)
        assertEquals(0, logged.size)
    }

    @Test
    fun `stripSecretsForGateway removes both secrets when both present`() {
        val headers = listOf(
            "Authorization" to "Bearer token",
            "X-CMC_PRO_API_KEY" to "key",
            "Content-Type" to "application/json"
        )
        val logged = mutableListOf<String>()
        val stripped = stripSecretsForGateway("cmc.zodl.com", headers) { logged.add(it) }

        assertEquals(1, stripped.size)
        assertEquals("Content-Type", stripped[0].first)
        assertEquals(1, logged.size)
        assert(logged[0].contains("Authorization"))
        assert(logged[0].contains("X-CMC_PRO_API_KEY"))
    }
}
