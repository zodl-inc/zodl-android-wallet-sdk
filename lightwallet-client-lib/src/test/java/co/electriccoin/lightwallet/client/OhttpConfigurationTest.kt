package co.electriccoin.lightwallet.client

import co.electriccoin.lightwallet.client.internal.OhttpEndpoints
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.OhttpConfiguration
import co.electriccoin.lightwallet.client.model.OhttpNetworkConfiguration
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalOhttpApi::class)
class OhttpConfigurationTest {
    private val mainnet =
        OhttpNetworkConfiguration(
            gatewayHost = "ohttp-gateway.example",
            relayUrl = "https://relay-mainnet.example/relay",
            keysDnsName = "mainnet.ohttp-keys.example",
            signingPublicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        )
    private val testnet =
        OhttpNetworkConfiguration(
            gatewayHost = "ohttp-gateway-testnet.example",
            relayUrl = "https://relay-testnet.example/relay",
            keysDnsName = "testnet.ohttp-keys.example",
            signingPublicKey = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        )
    private val both = OhttpConfiguration(mainnet = mainnet, testnet = testnet)

    @Before
    fun reset() = OhttpEndpoints.resetForTest()

    @After
    fun resetAfter() = OhttpEndpoints.resetForTest()

    @Test
    fun valuesAreTrimmedAndHostNamesLowerCased() {
        val padded =
            OhttpNetworkConfiguration(
                gatewayHost = " OHTTP-Gateway.Example\n",
                relayUrl = "\thttps://relay-mainnet.example/Relay \n",
                keysDnsName = "Mainnet.OHTTP-Keys.example ",
                signingPublicKey = "D75A980182B10AB7D54BFED3C964073A0EE172F3DAA62325AF021A68F707511A\n",
            )
        assertEquals("ohttp-gateway.example", padded.gatewayHost)
        // The relay URL keeps its letter case: its path may depend on it.
        assertEquals("https://relay-mainnet.example/Relay", padded.relayUrl)
        assertEquals("mainnet.ohttp-keys.example", padded.keysDnsName)
        assertEquals(mainnet.signingPublicKey, padded.signingPublicKey)
    }

    @Test
    fun toStringNeverShowsAValue() {
        val shown = both.toString()
        listOf(mainnet, testnet).forEach { network ->
            listOf(network.gatewayHost, network.relayUrl, network.keysDnsName, network.signingPublicKey)
                .forEach { value -> assertFalse(shown.contains(value), shown) }
        }
        assertTrue(shown.contains("<set>"), shown)
        assertTrue(OhttpNetworkConfiguration("", "", "", "").toString().contains("<empty>"))
    }

    @Test
    fun withoutAConfigurationOhttpIsOff() {
        assertNull(OhttpEndpoints.configuration)
        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = false))
        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = true))
        assertFalse(LightWalletClient.isOhttpGateway(LightWalletEndpoint(mainnet.gatewayHost, 443, true)))
    }

    @Test
    fun anInstalledConfigurationNamesTheGatewayOfEachNetwork() {
        OhttpEndpoints.install(both) {}
        assertEquals(both, OhttpEndpoints.configuration)
        assertEquals(mainnet.gatewayHost, LightWalletClient.ohttpGatewayHost(isTestnet = false))
        assertEquals(testnet.gatewayHost, LightWalletClient.ohttpGatewayHost(isTestnet = true))
        assertTrue(LightWalletClient.isOhttpGateway(LightWalletEndpoint(testnet.gatewayHost, 443, true)))
        assertFalse(LightWalletClient.isOhttpGateway(LightWalletEndpoint("lightwalletd.example", 443, true)))
    }

    @Test
    fun oneNetworkOnlyLeavesTheOtherOff() {
        OhttpEndpoints.install(OhttpConfiguration(mainnet = null, testnet = testnet)) {}
        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = false))
        assertEquals(testnet.gatewayHost, LightWalletClient.ohttpGatewayHost(isTestnet = true))
    }

    @Test
    fun theConfigurationIsSetOnce() {
        val nativeCalls = AtomicInteger()
        OhttpEndpoints.install(both) { nativeCalls.incrementAndGet() }
        // An equal configuration again (equal after normalization): nothing changes, native is not called.
        val same =
            OhttpConfiguration(
                mainnet =
                    OhttpNetworkConfiguration(
                        gatewayHost = " OHTTP-GATEWAY.example",
                        relayUrl = mainnet.relayUrl,
                        keysDnsName = mainnet.keysDnsName,
                        signingPublicKey = mainnet.signingPublicKey,
                    ),
                testnet = testnet,
            )
        OhttpEndpoints.install(same) { nativeCalls.incrementAndGet() }
        assertEquals(1, nativeCalls.get())
        // A different one is refused and the first stays.
        assertFailsWith<IllegalStateException> {
            OhttpEndpoints.install(OhttpConfiguration(mainnet = mainnet, testnet = null)) {
                nativeCalls.incrementAndGet()
            }
        }
        assertEquals(1, nativeCalls.get())
        assertEquals(testnet.gatewayHost, LightWalletClient.ohttpGatewayHost(isTestnet = true))
    }

    @Test
    fun ohttpOffIsAConfigurationToo() {
        OhttpEndpoints.install(null) {}
        assertFailsWith<IllegalStateException> { OhttpEndpoints.install(both) {} }
        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = false))
    }

    @Test
    fun aRefusedConfigurationInstallsNothingAndMayBeRetried() {
        assertFailsWith<IllegalArgumentException> {
            OhttpEndpoints.install(both) { throw IllegalArgumentException("mainnet: gateway host: invalid") }
        }
        // Nothing was installed, so the corrected call is a first install (no read in between: a read
        // with nothing installed would block it, see aFirstInstallAfterTheSdkReadTheStateIsRefused).
        val testnetOnly = OhttpConfiguration(mainnet = null, testnet = testnet)
        OhttpEndpoints.install(testnetOnly) {}
        assertEquals(testnetOnly, OhttpEndpoints.configuration)
        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = false))
    }

    @Test
    fun concurrentInstallsReachTheNativeLibraryOnce() {
        val nativeCalls = AtomicInteger()
        val threads = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results =
                (1..threads).map {
                    pool.submit {
                        start.await()
                        OhttpEndpoints.install(both) { nativeCalls.incrementAndGet() }
                    }
                }
            start.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, nativeCalls.get())
        assertEquals(both, OhttpEndpoints.configuration)
    }

    @Test
    fun aFirstInstallAfterTheSdkReadTheStateIsRefused() {
        // Something read the OHTTP state while none was installed, so it ran with OHTTP off.
        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = false))
        val failure = assertFailsWith<IllegalStateException> { OhttpEndpoints.install(both) {} }
        assertTrue(failure.message.orEmpty().contains("before it was installed"), failure.message)
        assertNull(OhttpEndpoints.configuration)
    }

    @Test
    fun readsAfterTheInstallDoNotBlockAnEqualInstall() {
        OhttpEndpoints.install(both) {}
        assertEquals(mainnet.gatewayHost, LightWalletClient.ohttpGatewayHost(isTestnet = false))
        OhttpEndpoints.install(both) {}
        assertEquals(both, OhttpEndpoints.configuration)
    }

    @Test
    fun failingClosedTurnsOhttpOffForTheProcess() {
        OhttpEndpoints.install(both)
        OhttpEndpoints.failClosed()
        assertNull(OhttpEndpoints.configuration)
        assertNull(LightWalletClient.ohttpGatewayHost(isTestnet = false))
        assertFalse(LightWalletClient.isOhttpGateway(LightWalletEndpoint(mainnet.gatewayHost, 443, true)))
        assertFailsWith<IllegalStateException> { OhttpEndpoints.install(both) {} }
    }
}
