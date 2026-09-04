package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FastestServersResult
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.CompactBlockUnsafe
import co.electriccoin.lightwallet.client.model.GetAddressUtxosReplyUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.LightWalletEndpointInfoUnsafe
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe
import co.electriccoin.lightwallet.client.model.ShieldedProtocolEnum
import co.electriccoin.lightwallet.client.model.SubtreeRootUnsafe
import co.electriccoin.lightwallet.client.model.TreeStateUnsafe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the benchmark stages shared by [FastestServerFetcher.evaluateServerSwitch] and the legacy
 * `getFastestServers` flow. Only kept-versus-discarded outcomes are asserted; the measured durations
 * themselves are wall-clock and would be flaky.
 */
class FastestServerFetcherTest {
    @Test
    fun `evaluateServerSwitch benchmarks every candidate without trimming`() =
        runBlocking {
            val candidates = (1..5).map { endpoint("server$it.example") }
            val clients = candidates.associateWith { FakeWalletClient() }
            val fetcher = fetcher(clients)

            fetcher.evaluateServerSwitch(
                current = candidates.first(),
                candidates = candidates,
                fetchThreshold = 5.seconds,
                blocksToFetch = 1
            )

            clients.forEach { (endpoint, client) ->
                assertEquals(1, client.blockRangeRequests.size, "Unexpected request count for $endpoint")
                assertEquals(1, client.collectedBlocks, "Unexpected collected block count for $endpoint")
            }
        }

    @Test
    fun `evaluateServerSwitch streams the requested block count ending at the tip`() =
        runBlocking {
            val only = endpoint("only.example")
            val client = FakeWalletClient()
            val fetcher = fetcher(mapOf(only to client))

            fetcher.evaluateServerSwitch(
                current = only,
                candidates = listOf(only),
                fetchThreshold = 5.seconds,
                blocksToFetch = 4
            )

            assertEquals(
                listOf(BlockHeightUnsafe(TIP - 3)..BlockHeightUnsafe(TIP)),
                client.blockRangeRequests
            )
            assertEquals(4, client.collectedBlocks)
        }

    @Test
    fun `evaluateServerSwitch leaves a current server whose stream reports a failure twice in a row`() =
        runBlocking {
            val current = endpoint("current.example")
            val healthy = endpoint("healthy.example")
            val fetcher =
                fetcher(
                    mapOf(
                        current to FakeWalletClient(blockFailureAtIndex = 0),
                        healthy to FakeWalletClient()
                    )
                )

            assertNull(
                fetcher.evaluateServerSwitch(
                    current = current,
                    candidates = listOf(current, healthy),
                    fetchThreshold = 5.seconds,
                    blocksToFetch = 1
                )
            )
            assertEquals(
                healthy,
                fetcher.evaluateServerSwitch(
                    current = current,
                    candidates = listOf(current, healthy),
                    fetchThreshold = 5.seconds,
                    blocksToFetch = 1
                )
            )
        }

    @Test
    fun `evaluateServerSwitch leaves a current server that exceeds the fetch threshold twice in a row`() =
        runBlocking {
            val current = endpoint("current.example")
            val healthy = endpoint("healthy.example")
            val fetcher =
                fetcher(
                    mapOf(
                        current to FakeWalletClient(delayPerBlock = 5.seconds),
                        healthy to FakeWalletClient()
                    )
                )

            assertNull(
                fetcher.evaluateServerSwitch(
                    current = current,
                    candidates = listOf(current, healthy),
                    fetchThreshold = 50.milliseconds,
                    blocksToFetch = 1
                )
            )
            assertEquals(
                healthy,
                fetcher.evaluateServerSwitch(
                    current = current,
                    candidates = listOf(current, healthy),
                    fetchThreshold = 50.milliseconds,
                    blocksToFetch = 1
                )
            )
        }

    @Test
    fun `evaluateServerSwitch stops benchmarking once cancelled`() =
        runBlocking {
            val clients =
                listOf("a.example", "b.example", "c.example").associate {
                    endpoint(it) to FakeWalletClient(delayPerBlock = 10.seconds)
                }
            val fetcher = fetcher(clients)

            val evaluation =
                launch {
                    fetcher.evaluateServerSwitch(
                        current = clients.keys.first(),
                        candidates = clients.keys.toList(),
                        fetchThreshold = 60.seconds,
                        blocksToFetch = 1
                    )
                }
            withTimeout(BUSY_WAIT_TIMEOUT) {
                while (clients.values.sumOf { it.blockRangeRequests.size } == 0) {
                    delay(10.milliseconds)
                }
            }
            evaluation.cancelAndJoin()

            assertEquals(1, clients.values.sumOf { it.blockRangeRequests.size })
            clients.forEach { (endpoint, client) ->
                assertTrue(client.disposed, "Client for $endpoint was not disposed")
            }
        }

    @Test
    fun `evaluateServerSwitch disposes every wallet client`() =
        runBlocking {
            val current = endpoint("current.example")
            val healthy = endpoint("healthy.example")
            val broken = endpoint("broken.example")
            val clients =
                mapOf(
                    current to FakeWalletClient(),
                    healthy to FakeWalletClient(),
                    broken to FakeWalletClient(serverInfoFails = true)
                )
            val fetcher = fetcher(clients)

            fetcher.evaluateServerSwitch(
                current = current,
                candidates = listOf(current, healthy, broken),
                fetchThreshold = 5.seconds,
                blocksToFetch = 1
            )

            clients.forEach { (endpoint, client) ->
                assertTrue(client.disposed, "Client for $endpoint was not disposed")
            }
        }

    @Test
    fun `evaluateServerSwitch stays when nothing survives`() =
        runBlocking {
            val current = endpoint("current.example")
            val fetcher = fetcher(mapOf(current to FakeWalletClient(serverInfoFails = true)))

            assertNull(
                fetcher.evaluateServerSwitch(
                    current = current,
                    candidates = listOf(current),
                    fetchThreshold = 5.seconds,
                    blocksToFetch = 1
                )
            )
        }

    @Test
    fun `evaluateServerSwitch benchmarks a current server the caller did not offer`() =
        runBlocking {
            val dropped = endpoint("dropped.example")
            val offered = endpoint("offered.example")
            val droppedClient = FakeWalletClient()
            val fetcher =
                fetcher(
                    mapOf(
                        dropped to droppedClient,
                        offered to FakeWalletClient(delayPerBlock = 20.milliseconds)
                    )
                )

            val decision =
                fetcher.evaluateServerSwitch(
                    current = dropped,
                    candidates = listOf(offered),
                    fetchThreshold = 5.seconds,
                    blocksToFetch = 1
                )

            assertEquals(1, droppedClient.blockRangeRequests.size)
            assertNull(decision)
        }

    @Test
    fun `evaluateServerSwitch times every candidate on the same block range`() =
        runBlocking {
            val ahead = endpoint("ahead.example")
            val behind = endpoint("behind.example")
            val clients = mapOf(ahead to FakeWalletClient(), behind to FakeWalletClient(tip = TIP - 5))
            val fetcher = fetcher(clients)

            fetcher.evaluateServerSwitch(
                current = ahead,
                candidates = listOf(ahead, behind),
                fetchThreshold = 5.seconds,
                blocksToFetch = 3
            )

            val expected = listOf(BlockHeightUnsafe(TIP - 7)..BlockHeightUnsafe(TIP - 5))
            clients.forEach { (endpoint, client) ->
                assertEquals(expected, client.blockRangeRequests, "Unexpected range for $endpoint")
            }
        }

    @Test
    fun `getFastestServers collects the block stream`() =
        runBlocking {
            val only = endpoint("only.example")
            val client = FakeWalletClient()
            val fetcher = fetcher(mapOf(only to client))

            val results = fetcher(listOf(only)).toList()

            assertEquals(
                listOf(BlockHeightUnsafe(TIP - (LEGACY_BLOCK_COUNT - 1))..BlockHeightUnsafe(TIP)),
                client.blockRangeRequests
            )
            assertEquals(LEGACY_BLOCK_COUNT, client.collectedBlocks)
            assertEquals(listOf(only), (results.last() as FastestServersResult.Done).servers)
        }

    @Test
    fun `getFastestServers disposes the survivors it never streams`() =
        runBlocking {
            val clients =
                listOf("a.example", "b.example", "c.example", "d.example", "e.example").associate {
                    endpoint(it) to FakeWalletClient()
                }
            val fetcher = fetcher(clients)

            val done = fetcher(clients.keys.toList()).toList().last() as FastestServersResult.Done

            assertEquals(3, done.servers.size)
            assertEquals(3, clients.values.sumOf { it.blockRangeRequests.size })
            clients.forEach { (endpoint, client) ->
                assertTrue(client.disposed, "Client for $endpoint was not disposed")
            }
        }

    private fun fetcher(clients: Map<LightWalletEndpoint, FakeWalletClient>): FastestServerFetcher {
        val backend = mock(TypesafeBackend::class.java)
        clients.values.map { it.tip }.distinct().forEach {
            `when`(backend.getBranchIdForHeight(BlockHeight.new(it))).thenReturn(BRANCH_ID)
        }

        val walletClientFactory = mock(WalletClientFactory::class.java)
        runBlocking {
            clients.forEach { (endpoint, client) ->
                `when`(walletClientFactory.create(endpoint)).thenReturn(client)
            }
        }

        return FastestServerFetcher(
            backend = backend,
            network = ZcashNetwork.Mainnet,
            walletClientFactory = walletClientFactory,
            sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
            switchEvaluator = ServerSwitchEvaluator()
        )
    }

    private fun endpoint(host: String) =
        LightWalletEndpoint(
            host = host,
            port = 443,
            isSecure = true
        )

    private class FakeWalletClient(
        val tip: Long = TIP,
        private val serverInfoFails: Boolean = false,
        private val blockFailureAtIndex: Int? = null,
        private val delayPerBlock: Duration = Duration.ZERO
    ) : CombinedWalletClient {
        val blockRangeRequests = mutableListOf<ClosedRange<BlockHeightUnsafe>>()

        var collectedBlocks = 0
            private set

        var disposed = false
            private set

        private val disposeMutex = Mutex()

        override suspend fun getServerInfo(serviceMode: ServiceMode): Response<LightWalletEndpointInfoUnsafe> =
            if (serverInfoFails) {
                Response.Failure.Connection(IOException("getServerInfo unavailable"))
            } else {
                Response.Success(remoteInfo(tip))
            }

        override suspend fun getLatestBlockHeight(serviceMode: ServiceMode): Response<BlockHeightUnsafe> =
            Response.Success(BlockHeightUnsafe(tip))

        override suspend fun getBlockRange(
            heightRange: ClosedRange<BlockHeightUnsafe>,
            serviceMode: ServiceMode
        ): Flow<Response<CompactBlockUnsafe>> {
            blockRangeRequests += heightRange
            val count = (heightRange.endInclusive.value - heightRange.start.value + 1).toInt()
            return flow {
                repeat(count) { index ->
                    if (delayPerBlock > Duration.ZERO) {
                        delay(delayPerBlock)
                    }
                    collectedBlocks++
                    if (index == blockFailureAtIndex) {
                        emit(Response.Failure.Connection<CompactBlockUnsafe>(IOException("stream broke")))
                    } else {
                        emit(Response.Success(block(heightRange.start.value + index)))
                    }
                }
            }
        }

        /**
         * The real clients suspend before releasing anything - `CombinedWalletClientImpl` takes a
         * semaphore, `TorWalletClient` hops to `Dispatchers.IO` - so a disposal issued from an already
         * cancelled coroutine reaches its first suspension point and throws before it shuts the channel
         * down. The fake reproduces that, otherwise the cancellation tests would pass without the
         * production code ever disposing anything.
         */
        override suspend fun dispose() {
            yield()
            disposeMutex.withLock { disposed = true }
        }

        override fun reconnect() = Unit

        override suspend fun checkSingleUseTransparentAddress(
            accountUuid: ByteArray,
            serviceMode: ServiceMode
        ): Response<String?> = error("Unused")

        override suspend fun fetchTransaction(
            txId: ByteArray,
            serviceMode: ServiceMode
        ): Response<RawTransactionUnsafe> = error("Unused")

        override suspend fun fetchUtxos(
            tAddresses: List<String>,
            startHeight: BlockHeightUnsafe,
            serviceMode: ServiceMode
        ): Flow<Response<GetAddressUtxosReplyUnsafe>> = error("Unused")

        override suspend fun fetchUtxosByAddress(
            accountUuid: ByteArray,
            address: String,
            serviceMode: ServiceMode
        ): Response<String?> = error("Unused")

        override suspend fun getSubtreeRoots(
            startIndex: UInt,
            shieldedProtocol: ShieldedProtocolEnum,
            maxEntries: UInt,
            serviceMode: ServiceMode
        ): Flow<Response<SubtreeRootUnsafe>> = error("Unused")

        override suspend fun getTAddressTransactions(
            tAddress: String,
            blockHeightRange: ClosedRange<BlockHeightUnsafe>,
            serviceMode: ServiceMode
        ): Flow<Response<RawTransactionUnsafe>> = error("Unused")

        override suspend fun getTreeState(
            height: BlockHeightUnsafe,
            serviceMode: ServiceMode
        ): Response<TreeStateUnsafe> = error("Unused")

        override suspend fun observeMempool(serviceMode: ServiceMode): Flow<Response<RawTransactionUnsafe>> =
            error("Unused")

        override suspend fun submitTransaction(
            tx: ByteArray,
            serviceMode: ServiceMode
        ): Response<SendResponseUnsafe> = error("Unused")

        private fun block(height: Long) =
            CompactBlockUnsafe(
                height = height,
                hash = byteArrayOf(),
                time = 0,
                saplingOutputsCount = 0u,
                orchardOutputsCount = 0u,
                ironwoodOutputsCount = 0u,
                compactBlockBytes = byteArrayOf()
            )
    }

    private companion object {
        const val TIP = 2_000_000L
        const val BRANCH_ID = 0xC2D6D0B4L
        const val LEGACY_BLOCK_COUNT = 100
        val BUSY_WAIT_TIMEOUT = 10.seconds

        /**
         * [LightWalletEndpointInfoUnsafe] wraps a generated protobuf message whose supertypes are not on
         * the sdk-lib test classpath, so the remote info is stubbed rather than built from a real
         * `LightdInfo`.
         */
        fun remoteInfo(tip: Long): LightWalletEndpointInfoUnsafe =
            mock(LightWalletEndpointInfoUnsafe::class.java).also {
                `when`(it.matchingNetwork(ZcashNetwork.Mainnet.networkName)).thenReturn(true)
                `when`(it.saplingActivationHeightUnsafe)
                    .thenReturn(BlockHeightUnsafe(ZcashNetwork.Mainnet.saplingActivationHeight.value))
                `when`(it.blockHeightUnsafe).thenReturn(BlockHeightUnsafe(tip))
                `when`(it.estimatedHeight).thenReturn(tip)
                `when`(it.consensusBranchId).thenReturn("c2d6d0b4")
            }
    }
}
