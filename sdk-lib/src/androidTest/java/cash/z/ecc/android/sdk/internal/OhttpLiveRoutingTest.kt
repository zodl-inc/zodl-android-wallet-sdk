package cash.z.ecc.android.sdk.internal

import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.util.OhttpCodecFactory
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.OhttpHttpClient
import co.electriccoin.lightwallet.client.internal.DirectOhttpSender
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Live end-to-end routing test for OHTTP: proves that lightwalletd sync RPCs, voting, and PIR
 * all go through the Fastly relay and the ZODL gateway on both mainnet and testnet.
 *
 * Coverage:
 *   - Lightwalletd via LightWalletClient (gateway mode, clearnet → Fastly → gateway → LWD)
 *   - Voting: GET to an allowlisted vote host through OhttpHttpClient
 *   - PIR: GET /root on lb-pir-primary.valargroup.org through OhttpHttpClient
 *
 * Requires network access and running gateways. Gated exactly like OhttpOverTorSecretInjectionTest:
 * only runs when the instrumentation argument `ohttpLive=true` is passed.
 *
 * Run:
 *   ./gradlew :sdk-lib:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=cash.z.ecc.android.sdk.internal.OhttpLiveRoutingTest \
 *     -Pandroid.testInstrumentationRunnerArguments.ohttpLive=true
 */
class OhttpLiveRoutingTest {
    private fun assumeLive() {
        val live = InstrumentationRegistry.getArguments().getString("ohttpLive") == "true"
        assumeTrue("set instrumentation argument ohttpLive=true to run the live OHTTP routing test", live)
    }

    @Test
    fun mainnet_lightwalletd_via_ohttp() =
        runTest(timeout = 180.seconds) {
            assumeLive()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val endpoint = LightWalletEndpoint("ohttp-gateway.example", 9067, true)

            // Create LightWalletClient with the gateway endpoint
            val client =
                LightWalletClient.new(
                    context = context,
                    lightWalletEndpoint = endpoint,
                    ohttpCodec = OhttpCodecFactory.create(),
                )

            // Fetch latest height - Response<BlockHeightUnsafe>
            when (val heightResponse = client.getLatestBlockHeight()) {
                is Response.Success -> {
                    val height = heightResponse.result
                    // Fetch a small block range below tip
                    val startHeight = BlockHeightUnsafe(height.value - 5)
                    val endHeight = BlockHeightUnsafe(height.value - 1)
                    val blocks = client.getBlockRange(startHeight..endHeight).take(5).toList()
                    assertTrue(blocks.isNotEmpty(), "getBlockRange should return blocks")
                }

                is Response.Failure -> error("Failed to get height: ${heightResponse.toThrowable().message}")
            }
        }

    @Test
    fun testnet_lightwalletd_via_ohttp() =
        runTest(timeout = 180.seconds) {
            assumeLive()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val endpoint = LightWalletEndpoint("ohttp-gateway-testnet.example", 9067, true)

            // Create LightWalletClient with the gateway endpoint
            val client =
                LightWalletClient.new(
                    context = context,
                    lightWalletEndpoint = endpoint,
                    ohttpCodec = OhttpCodecFactory.create(),
                )

            // Fetch latest height - Response<BlockHeightUnsafe>
            when (val heightResponse = client.getLatestBlockHeight()) {
                is Response.Success -> {
                    val height = heightResponse.result
                    // Fetch a small block range below tip
                    val startHeight = BlockHeightUnsafe(height.value - 5)
                    val endHeight = BlockHeightUnsafe(height.value - 1)
                    val blocks = client.getBlockRange(startHeight..endHeight).take(5).toList()
                    assertTrue(blocks.isNotEmpty(), "getBlockRange should return blocks")
                }

                is Response.Failure -> error("Failed to get height: ${heightResponse.toThrowable().message}")
            }
        }

    @Test
    fun mainnet_voting_via_ohttp() =
        runTest(timeout = 120.seconds) {
            assumeLive()
            val sender = DirectOhttpSender()
            val ohttpClient = OhttpHttpClient.create(codec = OhttpCodecFactory.create(), sender = sender)

            // Test voting through prod.vote-chain-primary.valargroup.org (allowlisted by the gateway)
            val voteUrl = "https://prod.vote-chain-primary.valargroup.org/rounds"
            assertTrue(ohttpClient.canHandle(voteUrl), "OHTTP client should handle voting host")

            val response =
                ohttpClient.execute(
                    OhttpHttpClient.Request(
                        url = voteUrl,
                        headers = listOf("Accept" to "application/json"),
                    ),
                )

            // Assert non-5xx response (gateway proxied it)
            assertTrue(response.status < 500, "Voting endpoint should return < 500, got ${response.status}")

            sender.dispose()
        }

    @Test
    fun testnet_voting_via_ohttp() =
        runTest(timeout = 120.seconds) {
            assumeLive()
            val sender = DirectOhttpSender()
            val ohttpClient = OhttpHttpClient.create(codec = OhttpCodecFactory.create(), sender = sender)

            // Test voting through vote.zodl.com (allowlisted by the gateway)
            val voteUrl = "https://vote.zodl.com/rounds"
            assertTrue(ohttpClient.canHandle(voteUrl), "OHTTP client should handle voting host")

            val response =
                ohttpClient.execute(
                    OhttpHttpClient.Request(
                        url = voteUrl,
                        headers = listOf("Accept" to "application/json"),
                    ),
                )

            // Assert non-5xx response (gateway proxied it)
            assertTrue(response.status < 500, "Voting endpoint should return < 500, got ${response.status}")

            sender.dispose()
        }

    @Test
    fun mainnet_pir_via_ohttp() =
        runTest(timeout = 120.seconds) {
            assumeLive()
            val sender = DirectOhttpSender()
            val ohttpClient = OhttpHttpClient.create(codec = OhttpCodecFactory.create(), sender = sender)

            // PIR root endpoint (allowlisted by the gateway: GET /root)
            val pirUrl = "https://lb-pir-primary.valargroup.org/root"
            assertTrue(ohttpClient.canHandle(pirUrl), "OHTTP client should handle PIR host")

            val response =
                ohttpClient.execute(
                    OhttpHttpClient.Request(
                        url = pirUrl,
                        headers = listOf("Accept" to "application/octet-stream"),
                    ),
                )

            // Assert 200 and non-empty body
            assertTrue(response.status == 200, "PIR endpoint should return 200, got ${response.status}")
            assertTrue(response.body.isNotEmpty(), "PIR response body should not be empty")

            sender.dispose()
        }
}
