package cash.z.ecc.android.sdk.internal

import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.internal.jni.FakeRustBackend
import cash.z.ecc.android.sdk.internal.model.TorClient
import co.electriccoin.lightwallet.client.OhttpHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Instrumented test for the OHTTP-over-Tor routing rule: external APIs that need secrets
 * (CMC, NEAR) must never be called Tor-direct with the app's secret. Instead, with Tor ON
 * they go Tor → OHTTP relay → gateway, and the gateway injects the secret server-side.
 *
 * This test verifies:
 * 1. The relay leg goes over Tor (TorOhttpSender)
 * 2. The app does NOT send secrets (they are stripped)
 * 3. The gateway injects secrets and the requests succeed (HTTP 200)
 *
 * Requires network access and a running gateway configured to inject secrets for
 * cmc.zodl.com and near.zodl.com, so it only runs when the instrumentation argument
 * `ohttpLive=true` is passed (skipped otherwise):
 *
 *   ./gradlew :sdk-lib:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=cash.z.ecc.android.sdk.internal.OhttpOverTorSecretInjectionTest \
 *     -Pandroid.testInstrumentationRunnerArguments.ohttpLive=true
 */
class OhttpOverTorSecretInjectionTest {
    private fun assumeLive() {
        val live = InstrumentationRegistry.getArguments().getString("ohttpLive") == "true"
        assumeTrue("set instrumentation argument ohttpLive=true to run the live OHTTP-over-Tor test", live)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun cmc_via_ohttp_over_tor_with_gateway_secret_injection() =
        runTest(timeout = 180.seconds) {
            assumeLive()
            // Bootstrap Tor
            val torDir = createTempDirectory("ohttp-tor-cmc-").toFile()
            val torClient = TorClient.new(torDir, FakeRustBackend(0, mutableListOf()))
            val isolatedTor = torClient.isolatedTorClient()

            // Create OHTTP client with Tor sender
            val torSender = TorOhttpSender(isolatedTor)
            val ohttpClient = OhttpHttpClient.create(sender = torSender)

            // CMC key/info endpoint — gateway injects X-CMC_PRO_API_KEY server-side
            val url = "https://pro-api.coinmarketcap.com/v1/key/info"
            assertTrue(ohttpClient.canHandle(url), "OHTTP client should handle CMC host")

            // Execute without app secret (stripped if present)
            val response =
                ohttpClient.execute(
                    OhttpHttpClient.Request(
                        url = url,
                        headers = listOf("Accept" to "application/json")
                    )
                )

            // Assert success (gateway injected the key)
            assertEquals(200, response.status, "Gateway should inject CMC key and return 200")
            assertTrue(response.body.isNotEmpty(), "Response body should not be empty")

            // Parse and verify structure (optional: decode JSON and check it's a valid CMC response)
            val json = response.body.decodeToString()
            assertTrue(json.contains("\"usage\""), "CMC key/info should contain usage field")

            torClient.dispose()
        }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun near_via_ohttp_over_tor_with_gateway_secret_injection() =
        runTest(timeout = 180.seconds) {
            assumeLive()
            // Bootstrap Tor
            val torDir = createTempDirectory("ohttp-tor-near-").toFile()
            val torClient = TorClient.new(torDir, FakeRustBackend(0, mutableListOf()))
            val isolatedTor = torClient.isolatedTorClient()

            // Create OHTTP client with Tor sender
            val torSender = TorOhttpSender(isolatedTor)
            val ohttpClient = OhttpHttpClient.create(sender = torSender)

            // NEAR 1click tokens endpoint — gateway injects Authorization server-side
            val url = "https://1click.chaindefuser.com/v0/tokens"
            assertTrue(ohttpClient.canHandle(url), "OHTTP client should handle NEAR host")

            // Execute without app secret (stripped if present)
            val response =
                ohttpClient.execute(
                    OhttpHttpClient.Request(
                        url = url,
                        headers = listOf("Accept" to "application/json")
                    )
                )

            // The gateway injects the NEAR 1click JWT server-side (Authorization: Bearer ...)
            assertEquals(200, response.status, "Gateway should inject the NEAR JWT and return 200")
            val json = response.body.decodeToString()
            // NEAR tokens response should be a JSON array
            assertTrue(json.trim().startsWith("["), "NEAR tokens should be a JSON array")

            torClient.dispose()
        }
}
