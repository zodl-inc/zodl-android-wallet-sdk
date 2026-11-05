package cash.z.ecc.android.sdk.internal.model

import co.electriccoin.lightwallet.client.LightWalletClient
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CombinedWalletClientImplStreamingTest {
    private val endpoint = LightWalletEndpoint("ohttp-gateway.example", 443, true)

    @Test
    fun direct_streams_use_the_direct_client() =
        runTest {
            val direct = RecordingClient()
            val combined = CombinedWalletClientImpl.new(endpoint, direct, null) { error("no Tor client expected") }

            combined.observeMempool(ServiceMode.Direct).toList()

            assertEquals(1, direct.streams)
        }

    @Test
    fun tor_streams_without_a_tor_transport_fail_instead_of_going_direct() =
        runTest {
            val direct = RecordingClient()
            val combined = CombinedWalletClientImpl.new(endpoint, direct, null, torStreamingClientFactory = null)

            for (mode in listOf(ServiceMode.DefaultTor, ServiceMode.UniqueTor, ServiceMode.Group("g"))) {
                val responses = combined.observeMempool(mode).toList()
                assertIs<Response.Failure.OverTor<*>>(responses.single())
            }
            assertEquals(0, direct.streams)
        }

    @Test
    fun shared_tor_modes_reuse_one_tor_client_until_disposed() =
        runTest {
            val direct = RecordingClient()
            val created = mutableListOf<RecordingClient>()
            val combined =
                CombinedWalletClientImpl.new(endpoint, direct, null) { RecordingClient().also { created += it } }

            combined.observeMempool(ServiceMode.DefaultTor).toList()
            combined.observeMempool(ServiceMode.DefaultTor).toList()

            assertEquals(0, direct.streams)
            assertEquals(1, created.size)
            assertEquals(2, created.single().streams)

            combined.dispose()
            assertTrue(created.single().disposed)
        }

    @Test
    fun unique_tor_streams_get_their_own_client_disposed_after_the_stream() =
        runTest {
            val created = mutableListOf<RecordingClient>()
            val combined =
                CombinedWalletClientImpl.new(endpoint, RecordingClient(), null) {
                    RecordingClient().also { created += it }
                }

            combined.observeMempool(ServiceMode.UniqueTor).toList()
            combined.observeMempool(ServiceMode.UniqueTor).toList()

            assertEquals(2, created.size)
            assertTrue(created.all { it.disposed && it.streams == 1 })
        }

    @Test
    fun a_unique_tor_stream_that_is_never_collected_makes_no_client() =
        runTest {
            val created = mutableListOf<RecordingClient>()
            val combined =
                CombinedWalletClientImpl.new(endpoint, RecordingClient(), null) {
                    RecordingClient().also { created += it }
                }

            combined.observeMempool(ServiceMode.UniqueTor)
            combined.getBlockRange(BlockHeightUnsafe(1)..BlockHeightUnsafe(2), ServiceMode.UniqueTor)

            assertTrue(created.isEmpty())
        }

    @Test
    fun a_unique_tor_stream_whose_collection_fails_or_stops_early_disposes_its_client() =
        runTest {
            val created = mutableListOf<RecordingClient>()
            val combined =
                CombinedWalletClientImpl.new(endpoint, RecordingClient(), null) {
                    RecordingClient(items = 3).also { created += it }
                }

            // Stopped after the first item (the collector cancels the rest of the flow).
            val firstOnly =
                combined
                    .observeMempool(ServiceMode.UniqueTor)
                    .take(1)
                    .toList()
            assertEquals(1, firstOnly.size)
            // A collector that throws.
            assertFailsWith<IllegalStateException> {
                combined.observeMempool(ServiceMode.UniqueTor).collect { error("collector failed") }
            }

            assertEquals(2, created.size)
            assertTrue(created.all { it.disposed })
        }

    @Test
    fun a_unique_tor_stream_whose_collecting_job_is_cancelled_disposes_its_client() =
        runTest {
            val created = mutableListOf<SuspendingDisposalClient>()
            val combined =
                CombinedWalletClientImpl.new(endpoint, RecordingClient(), null) {
                    SuspendingDisposalClient().also { created += it }
                }

            // Launch a Job that collects the stream, then cancel it mid-collection.
            val job =
                launch {
                    combined.observeMempool(ServiceMode.UniqueTor).collect {
                        // Never completes — the Job will be cancelled.
                        delay(Long.MAX_VALUE)
                    }
                }
            // Give the collection a chance to start.
            delay(10)
            job.cancel()
            job.join()

            // The client should have been disposed exactly once, even though the Job was cancelled.
            assertEquals(1, created.size)
            assertEquals(1, created.single().disposeCallCount)
        }

    private class RecordingClient(
        private val items: Int = 0
    ) : LightWalletClient {
        var streams = 0
            private set

        var disposed = false
            private set

        private fun <T> stream(): Flow<Response<T>> {
            streams++
            @Suppress("UNCHECKED_CAST")
            return List(items) { Response.Success(Unit) as Response<T> }.asFlow()
        }

        override suspend fun fetchUtxos(
            tAddresses: List<String>,
            startHeight: BlockHeightUnsafe
        ): Flow<Response<GetAddressUtxosReplyUnsafe>> = stream()

        override fun getBlockRange(heightRange: ClosedRange<BlockHeightUnsafe>): Flow<Response<CompactBlockUnsafe>> =
            stream()

        override fun getTAddressTransactions(
            tAddress: String,
            blockHeightRange: ClosedRange<BlockHeightUnsafe>
        ): Flow<Response<RawTransactionUnsafe>> = stream()

        override fun getSubtreeRoots(
            startIndex: UInt,
            shieldedProtocol: ShieldedProtocolEnum,
            maxEntries: UInt
        ): Flow<Response<SubtreeRootUnsafe>> = stream()

        override fun observeMempool(): Flow<Response<RawTransactionUnsafe>> = stream()

        override fun reconnect() = Unit

        override suspend fun getServerInfo(): Response<LightWalletEndpointInfoUnsafe> = TODO()

        override suspend fun getLatestBlockHeight(): Response<BlockHeightUnsafe> = TODO()

        override suspend fun fetchTransaction(txId: ByteArray): Response<RawTransactionUnsafe> = TODO()

        override suspend fun submitTransaction(tx: ByteArray): Response<SendResponseUnsafe> = TODO()

        override suspend fun getTreeState(height: BlockHeightUnsafe): Response<TreeStateUnsafe> = TODO()

        override suspend fun dispose() {
            disposed = true
        }
    }

    private class SuspendingDisposalClient : LightWalletClient {
        var disposeCallCount = 0
            private set

        private fun <T> infiniteStream(): Flow<Response<T>> =
            flow {
                @Suppress("UNCHECKED_CAST")
                while (true) {
                    emit(Response.Success(Unit) as Response<T>)
                }
            }

        override fun observeMempool(): Flow<Response<RawTransactionUnsafe>> = infiniteStream()

        override suspend fun dispose() {
            disposeCallCount++
            // Suspend briefly so that cancellation can race with disposal.
            delay(1)
        }

        override suspend fun fetchUtxos(
            tAddresses: List<String>,
            startHeight: BlockHeightUnsafe
        ): Flow<Response<GetAddressUtxosReplyUnsafe>> = TODO()

        override fun getBlockRange(heightRange: ClosedRange<BlockHeightUnsafe>): Flow<Response<CompactBlockUnsafe>> =
            TODO()

        override fun getTAddressTransactions(
            tAddress: String,
            blockHeightRange: ClosedRange<BlockHeightUnsafe>
        ): Flow<Response<RawTransactionUnsafe>> = TODO()

        override fun getSubtreeRoots(
            startIndex: UInt,
            shieldedProtocol: ShieldedProtocolEnum,
            maxEntries: UInt
        ): Flow<Response<SubtreeRootUnsafe>> = TODO()

        override fun reconnect() = Unit

        override suspend fun getServerInfo(): Response<LightWalletEndpointInfoUnsafe> = TODO()

        override suspend fun getLatestBlockHeight(): Response<BlockHeightUnsafe> = TODO()

        override suspend fun fetchTransaction(txId: ByteArray): Response<RawTransactionUnsafe> = TODO()

        override suspend fun submitTransaction(tx: ByteArray): Response<SendResponseUnsafe> = TODO()

        override suspend fun getTreeState(height: BlockHeightUnsafe): Response<TreeStateUnsafe> = TODO()
    }
}
