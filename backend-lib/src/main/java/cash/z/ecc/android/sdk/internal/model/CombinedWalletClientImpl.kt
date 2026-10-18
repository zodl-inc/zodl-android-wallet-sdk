package cash.z.ecc.android.sdk.internal.model

import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.PartialTorWalletClient
import co.electriccoin.lightwallet.client.PartialWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.CompactBlockUnsafe
import co.electriccoin.lightwallet.client.model.GetAddressUtxosReplyUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.ShieldedProtocolEnum
import co.electriccoin.lightwallet.client.model.SubtreeRootUnsafe
import co.electriccoin.lightwallet.client.model.UninitializedTorClientException
import co.electriccoin.lightwallet.client.util.use
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class CombinedWalletClientImpl private constructor(
    private val lightWalletClient: LightWalletClient,
    private val isolatedTorClient: LazyTorClient?,
    private val endpoint: LightWalletEndpoint,
    private val torStreamingClientFactory: (suspend () -> LightWalletClient)?,
) : CombinedWalletClient {
    private val cache = mutableMapOf<ServiceMode, PartialTorWalletClient>()

    private val streamingCache = mutableMapOf<ServiceMode, LightWalletClient>()

    private val semaphore = Mutex()

    override suspend fun dispose() {
        semaphore.withLock {
            lightWalletClient.dispose()
            isolatedTorClient?.dispose()
            cache.forEach { (_, client) -> client.dispose() }
            cache.clear()
            streamingCache.forEach { (_, client) -> client.dispose() }
            streamingCache.clear()
        }
    }

    override suspend fun fetchTransaction(
        txId: ByteArray,
        serviceMode: ServiceMode
    ) = executeAsResponse(serviceMode) { fetchTransaction(txId) }

    override suspend fun getServerInfo(
        serviceMode: ServiceMode
    ) = executeAsResponse(serviceMode) { getServerInfo() }

    override suspend fun getLatestBlockHeight(
        serviceMode: ServiceMode
    ) = executeAsResponse(serviceMode) { getLatestBlockHeight() }

    override suspend fun submitTransaction(
        tx: ByteArray,
        serviceMode: ServiceMode
    ) = executeAsResponse(serviceMode) { submitTransaction(tx) }

    override suspend fun getTreeState(
        height: BlockHeightUnsafe,
        serviceMode: ServiceMode
    ) = executeAsResponse(serviceMode) { getTreeState(height) }

    override suspend fun checkSingleUseTransparentAddress(
        accountUuid: ByteArray,
        serviceMode: ServiceMode
    ): Response<String?> {
        require(serviceMode == ServiceMode.UniqueTor)
        return executeAsResponse(serviceMode) {
            require(this is PartialTorWalletClient)
            checkSingleUseTransparentAddress(accountUuid)
        }
    }

    override suspend fun fetchUtxosByAddress(
        accountUuid: ByteArray,
        address: String,
        serviceMode: ServiceMode
    ): Response<String?> {
        require(serviceMode == ServiceMode.UniqueTor)
        return executeAsResponse(serviceMode) {
            require(this is PartialTorWalletClient)
            fetchUtxosByAddress(accountUuid, address)
        }
    }

    override suspend fun fetchUtxos(
        tAddresses: List<String>,
        startHeight: BlockHeightUnsafe,
        serviceMode: ServiceMode
    ): Flow<Response<GetAddressUtxosReplyUnsafe>> =
        executeAsStream(serviceMode) { fetchUtxos(tAddresses, startHeight) }

    override suspend fun getBlockRange(
        heightRange: ClosedRange<BlockHeightUnsafe>,
        serviceMode: ServiceMode
    ): Flow<Response<CompactBlockUnsafe>> =
        executeAsStream(serviceMode) { getBlockRange(heightRange) }

    override suspend fun getTAddressTransactions(
        tAddress: String,
        blockHeightRange: ClosedRange<BlockHeightUnsafe>,
        serviceMode: ServiceMode
    ): Flow<Response<RawTransactionUnsafe>> =
        executeAsStream(serviceMode) { getTAddressTransactions(tAddress, blockHeightRange) }

    override suspend fun getSubtreeRoots(
        startIndex: UInt,
        shieldedProtocol: ShieldedProtocolEnum,
        maxEntries: UInt,
        serviceMode: ServiceMode
    ): Flow<Response<SubtreeRootUnsafe>> =
        executeAsStream(serviceMode) { getSubtreeRoots(startIndex, shieldedProtocol, maxEntries) }

    override suspend fun observeMempool(
        serviceMode: ServiceMode
    ): Flow<Response<RawTransactionUnsafe>> =
        executeAsStream(serviceMode) { observeMempool() }

    override fun reconnect() = lightWalletClient.reconnect()

    /**
     * Streaming calls exist only on [LightWalletClient]. In a Tor mode they go through
     * [torStreamingClientFactory] (OHTTP with the relay leg over Tor); an endpoint without one has no
     * Tor transport for streams, so the call fails instead of falling back to a direct connection.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun <T> executeAsStream(
        serviceMode: ServiceMode,
        block: LightWalletClient.() -> Flow<Response<T>>
    ): Flow<Response<T>> =
        try {
            when (serviceMode) {
                ServiceMode.Direct -> {
                    semaphore.withLock { block(lightWalletClient) }
                }

                ServiceMode.UniqueTor -> {
                    val client = createTorStreamingClient()
                    try {
                        block(client).onCompletion { client.dispose() }
                    } catch (e: Exception) {
                        client.dispose()
                        throw e
                    }
                }

                ServiceMode.DefaultTor,
                is ServiceMode.Group -> {
                    semaphore.withLock {
                        val client = streamingCache[serviceMode] ?: createTorStreamingClient()
                        streamingCache[serviceMode] = client
                        block(client)
                    }
                }
            }
        } catch (e: UninitializedTorClientException) {
            flowOf(Response.Failure.OverTor(cause = e))
        }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun createTorStreamingClient(): LightWalletClient {
        val factory =
            torStreamingClientFactory
                ?: throw UninitializedTorClientException(
                    IllegalStateException("No Tor transport for streaming calls to ${endpoint.host}")
                )
        return try {
            factory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UninitializedTorClientException(e)
        }
    }

    private suspend inline fun <T> executeAsResponse(
        serviceMode: ServiceMode,
        block: PartialWalletClient.() -> Response<T>
    ): Response<T> =
        try {
            execute(serviceMode, block)
        } catch (e: UninitializedTorClientException) {
            Response.Failure.OverTor(cause = e)
        }

    /**
     * Executes [block] against the client appropriate for [serviceMode] under [semaphore]. For
     * [ServiceMode.Direct], the shared [lightWalletClient] is used directly. For Tor-mode requests, the
     * Tor client is resolved lazily inside the Tor branches, backed by [LazyTorClient]'s cache.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun <T> execute(
        serviceMode: ServiceMode,
        block: PartialWalletClient.() -> T
    ): T =
        semaphore.withLock {
            when (serviceMode) {
                ServiceMode.Direct -> {
                    block(lightWalletClient)
                }

                ServiceMode.UniqueTor -> {
                    createTorWalletClient().use { block(it) }
                }

                ServiceMode.DefaultTor,
                is ServiceMode.Group -> {
                    try {
                        block(getOrCreate(serviceMode))
                    } catch (e: Exception) {
                        remove(serviceMode)
                        throw e
                    }
                }
            }
        }

    private suspend fun remove(serviceMode: ServiceMode) =
        withContext(Dispatchers.Default) { cache.remove(serviceMode) }

    private suspend fun getOrCreate(serviceMode: ServiceMode) =
        withContext(Dispatchers.Default) { cache.getOrPut(serviceMode) { createTorWalletClient() } }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun createTorWalletClient(): PartialTorWalletClient {
        val tor =
            try {
                isolatedTorClient?.getOrCreate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw UninitializedTorClientException(e)
            } ?: throw UninitializedTorClientException(NullPointerException("torClient is null"))

        return try {
            tor.createWalletClient("https://${endpoint.host}:${endpoint.port}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UninitializedTorClientException(e)
        }
    }

    companion object Factory {
        /**
         * @param torStreamingClientFactory creates a [LightWalletClient] for [endpoint] whose traffic
         * goes over Tor, used by the streaming calls in a Tor mode. Null when there is none (the
         * endpoint is not an OHTTP gateway, or Tor is unavailable): such calls then fail.
         */
        suspend fun new(
            endpoint: LightWalletEndpoint,
            lightWalletClient: LightWalletClient,
            isolatedTorClient: LazyTorClient?,
            torStreamingClientFactory: (suspend () -> LightWalletClient)? = null
        ) = CombinedWalletClientImpl(
            endpoint = endpoint,
            lightWalletClient = lightWalletClient,
            isolatedTorClient = isolatedTorClient,
            torStreamingClientFactory = torStreamingClientFactory
        )
    }
}
