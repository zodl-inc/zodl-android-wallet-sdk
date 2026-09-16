package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.isOhttpProxyAllowedHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OhttpHttpClientTest {
    @Test
    fun `canHandle returns true for static proxy mappings`() {
        val client = OhttpHttpClient.create()
        assertTrue(client.canHandle("https://1click.chaindefuser.com/v0/tokens"))
        assertTrue(client.canHandle("https://voting.valargroup.dev/prod/dynamic-voting-config.json"))
        assertTrue(client.canHandle("https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest?symbol=ZEC"))
    }

    @Test
    fun `canHandle returns true for allowlisted suffixes`() {
        val client = OhttpHttpClient.create()
        assertTrue(client.canHandle("https://voting-prod.valargroup.org/shielded-vote/v1/rounds"))
        assertTrue(client.canHandle("https://voting-prod-2.valargroup.org/shielded-vote/v1/rounds/active"))
        assertTrue(client.canHandle("https://api.valargroup.dev/config"))
        assertTrue(client.canHandle("https://subdomain.chaindefuser.com/path"))
    }

    @Test
    fun `canHandle returns false for non-allowlisted hosts`() {
        val client = OhttpHttpClient.create()
        assertFalse(client.canHandle("https://example.com/path"))
        assertFalse(client.canHandle("https://valargroup.com/path"))
        assertFalse(client.canHandle("https://malicious-valargroup.org/path"))
        assertFalse(client.canHandle("https://notchaindefuser.com/path"))
    }

    @Test
    fun `isOhttpProxyAllowedHost matches dot-prefixed suffixes correctly`() {
        assertTrue(isOhttpProxyAllowedHost("voting-prod.valargroup.org"))
        assertTrue(isOhttpProxyAllowedHost("api.valargroup.dev"))
        assertTrue(isOhttpProxyAllowedHost("valargroup.org"))
        assertTrue(isOhttpProxyAllowedHost("valargroup.dev"))
        assertFalse(isOhttpProxyAllowedHost("valargroup.com"))
        assertFalse(isOhttpProxyAllowedHost("evil-valargroup.org"))
        assertFalse(isOhttpProxyAllowedHost("evilorg"))
    }

    @Test
    fun `isOhttpProxyAllowedHost matches bare domain suffixes correctly`() {
        assertTrue(isOhttpProxyAllowedHost("chaindefuser.com"))
        assertTrue(isOhttpProxyAllowedHost("subdomain.chaindefuser.com"))
        assertTrue(isOhttpProxyAllowedHost("a.b.chaindefuser.com"))
        assertFalse(isOhttpProxyAllowedHost("chaindefuser.net"))
        assertFalse(isOhttpProxyAllowedHost("notchaindefuser.com"))
    }

    @Test
    fun `isOhttpProxyAllowedHost is case-insensitive`() {
        assertTrue(isOhttpProxyAllowedHost("VOTING-PROD.VALARGROUP.ORG"))
        assertTrue(isOhttpProxyAllowedHost("ChainDefUser.COM"))
        assertTrue(isOhttpProxyAllowedHost("API.ValarGroup.Dev"))
    }

    @Test
    fun `canHandle handles invalid URLs gracefully`() {
        val client = OhttpHttpClient.create()
        assertFalse(client.canHandle("not a url"))
        assertFalse(client.canHandle(""))
        assertFalse(client.canHandle("://missing-scheme"))
    }
}
