package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.ExampleGatewaysRule
import co.electriccoin.lightwallet.client.internal.OhttpEndpoints
import co.electriccoin.lightwallet.client.internal.OhttpGatewayHosts
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OhttpGatewayHostTest {
    @get:Rule
    val gateways = ExampleGatewaysRule()

    @Test
    fun theConfiguredGatewayOfEachNetworkIsTheOneTheSdkRecognizes() {
        assertEquals("ohttp-gateway.example", LightWalletClient.ohttpGatewayHost(isTestnet = false))
        assertEquals("ohttp-gateway-testnet.example", LightWalletClient.ohttpGatewayHost(isTestnet = true))
        listOf(false, true).forEach { isTestnet ->
            val host = LightWalletClient.ohttpGatewayHost(isTestnet)!!
            assertTrue(LightWalletClient.isOhttpGateway(LightWalletEndpoint(host, 443, true)))
        }
    }

    @Test
    fun aBuildWithoutOhttpHasNoGatewayHost() {
        OhttpEndpoints.gatewayHosts = OhttpGatewayHosts(mainnet = null, testnet = null)

        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = false))
        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = true))
        assertFalse(LightWalletClient.isOhttpGateway(LightWalletEndpoint("ohttp-gateway.example", 443, true)))
    }
}
