package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.ext.toBlockHeight
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FastestServersResult
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.LightWalletEndpointInfoUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.util.Disposable
import co.electriccoin.lightwallet.client.util.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

internal class FastestServerFetcher(
    private val backend: TypesafeBackend,
    private val network: ZcashNetwork,
    private val walletClientFactory: WalletClientFactory,
    private val sdkFlags: SdkFlags
) {
    operator fun invoke(servers: List<LightWalletEndpoint>): Flow<FastestServersResult> =
        flow {
            emit(FastestServersResult.Measuring)

            val serversByRpcMeanLatency =
                servers
                    .parallelMapNotNull {
                        validateServerEndpointAndMeasure(it)
                    }.sortedBy {
                        it.meanDuration
                    }.mapIndexedNotNull { index, result ->
                        if (index <= K - 1 || result.meanDuration <= LATENCY_THRESHOLD) {
                            Twig.debug { "Fastest Server: '${result.endpoint}' VALIDATED by SORTING by RPC latency" }
                            result
                        } else {
                            Twig.debug { "Fastest Server: '${result.endpoint}' RULED OUT by SORTING by RPC latency" }
                            null
                        }
                    }

            Twig.debug {
                "Fastest Server: '${serversByRpcMeanLatency.map { it.endpoint }}' VALIDATED by MEASURING RPC latency"
            }

            emit(FastestServersResult.Validating(serversByRpcMeanLatency.map { it.endpoint }.take(K)))

            val serversByGetBlockRangeTimeout =
                serversByRpcMeanLatency
                    .asFlow()
                    .mapNotNull { result ->
                        measureBlockFetch(
                            result = result,
                            blocksToFetch = N,
                            fetchThreshold = FETCH_THRESHOLD,
                            logPrefix = "Fastest Server"
                        )?.let { result.endpoint }
                    }.take(K)
                    .toList()

            Twig.debug { "Fastest Server: '$serversByGetBlockRangeTimeout' VALIDATED by getBlockRange timeout" }

            emit(FastestServersResult.Done(serversByGetBlockRangeTimeout))
        }.flowOn(Dispatchers.Default)

    /**
     * Streams [blocksToFetch] blocks ending at the server's tip and times the stream. Fetched the same way as in
     * `downloadBatchOfBlocks()`. Always disposes [result].
     *
     * @return the stream duration, or null when the stream failed or exceeded [fetchThreshold]
     */
    private suspend fun measureBlockFetch(
        result: ValidateServerResult,
        blocksToFetch: Int,
        fetchThreshold: Duration,
        logPrefix: String
    ): Duration? =
        result.use {
            val to = result.remoteInfo.blockHeightUnsafe
            val from = BlockHeightUnsafe((to.value - (blocksToFetch - 1)).coerceAtLeast(0))

            val timed =
                withTimeoutOrNull(fetchThreshold) {
                    runCatching {
                        measureTimedValue {
                            result.lightWalletClient
                                .getBlockRange(
                                    heightRange = from..to,
                                    serviceMode = ServiceMode.Direct
                                ).firstOrNull { it is Response.Failure }
                        }
                    }.getOrElse {
                        Twig.debug(it) { "$logPrefix: '${result.endpoint}' RULED OUT by getBlockRange exception" }
                        null
                    }
                }

            val failure = timed?.value as? Response.Failure

            when {
                timed == null -> {
                    Twig.debug { "$logPrefix: '${result.endpoint}' RULED OUT by getBlockRange timeout" }
                    null
                }

                failure != null -> {
                    Twig.debug {
                        "$logPrefix: '${result.endpoint}' RULED OUT by getBlockRange failure " +
                            "${failure.code}: ${failure.description}"
                    }
                    null
                }

                else -> {
                    Twig.debug { "$logPrefix: '${result.endpoint}' VALIDATED by getBlockRange in ${timed.duration}" }
                    timed.duration
                }
            }
        }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    private suspend fun validateServerEndpointAndMeasure(endpoint: LightWalletEndpoint): ValidateServerResult? {
        fun logRuledOut(
            reason: String,
            throwable: Throwable? = null
        ) {
            val message =
                "Fastest Server: Server '$endpoint' RULED OUT during validating and measuring RPC " +
                    "latency. Reason: $reason"

            if (throwable != null) {
                Twig.debug(throwable) { message }
            } else {
                Twig.debug { message }
            }
        }

        val lightWalletClient = kotlin.runCatching { walletClientFactory.create(endpoint) }.getOrNull() ?: return null

        val remoteInfo: LightWalletEndpointInfoUnsafe?
        val getServerInfoDuration =
            measureTime {
                // 5 seconds timeout in case server is very unresponsive
                remoteInfo =
                    withTimeoutOrNull(5.seconds) {
                        when (
                            val response =
                                lightWalletClient.getServerInfo(
                                    sdkFlags ifTor
                                        ServiceMode.Group(
                                            "validateServerEndpointAndMeasure(${endpoint.host}:${endpoint.port})"
                                        )
                                )
                        ) {
                            is Response.Success -> {
                                response.result
                            }

                            is Response.Failure -> {
                                logRuledOut("getServerInfo failed", response.toThrowable())
                                null
                            }
                        }
                    }
            }

        if (remoteInfo == null) {
            lightWalletClient.dispose()
            return null
        }

        // Check network type
        if (!remoteInfo.matchingNetwork(network.networkName)) {
            logRuledOut("matchingNetwork failed")
            lightWalletClient.dispose()
            return null
        }

        // Check sapling activation height
        runCatching {
            val remoteSaplingActivationHeight = remoteInfo.saplingActivationHeightUnsafe.toBlockHeight()
            if (network.saplingActivationHeight != remoteSaplingActivationHeight) {
                logRuledOut("invalid saplingActivationHeight")
                lightWalletClient.dispose()
                return null
            }
        }.getOrElse {
            logRuledOut("saplingActivationHeight failed", it)
            lightWalletClient.dispose()
            return null
        }

        val currentChainTip: BlockHeight
        val getLatestBlockHeightDuration =
            measureTime {
                currentChainTip =
                    when (
                        val response =
                            lightWalletClient.getLatestBlockHeight(
                                serviceMode =
                                    sdkFlags ifTor
                                        ServiceMode.Group(
                                            "validateServerEndpointAndMeasure(${endpoint.host}:${endpoint.port})"
                                        )
                            )
                    ) {
                        is Response.Success -> {
                            runCatching { response.result.toBlockHeight() }.getOrElse {
                                logRuledOut("toBlockHeight failed", it)
                                lightWalletClient.dispose()
                                return null
                            }
                        }

                        is Response.Failure -> {
                            logRuledOut("getLatestBlockHeight failed", response.toThrowable())
                            lightWalletClient.dispose()
                            return null
                        }
                    }
            }

        val sdkBranchId =
            runCatching {
                "%x".format(
                    Locale.ROOT,
                    backend.getBranchIdForHeight(currentChainTip)
                )
            }.getOrElse {
                logRuledOut("getBranchIdForHeight failed", it)
                lightWalletClient.dispose()
                return null
            }

        if (!remoteInfo.consensusBranchId.equals(sdkBranchId, true)) {
            logRuledOut("consensusBranchId does not match")
            lightWalletClient.dispose()
            return null
        }

        if (remoteInfo.estimatedHeight >= remoteInfo.blockHeightUnsafe.value + SYNCED_THRESHOLD_BLOCKS) {
            logRuledOut("estimatedHeight does not match")
            lightWalletClient.dispose()
            return null
        }

        Twig.debug { "Fastest Server: Server '$endpoint' VALIDATED during validating and measuring RPC latency" }

        return ValidateServerResult(
            remoteInfo = remoteInfo,
            lightWalletClient = lightWalletClient,
            endpoint = endpoint,
            getServerInfoDuration = getServerInfoDuration,
            getLatestBlockHeightDuration = getLatestBlockHeightDuration
        )
    }

    private suspend inline fun <T, R> Iterable<T>.parallelMapNotNull(crossinline block: suspend (T) -> R?): List<R> =
        map { coroutineScope { async { block(it) } } }
            .awaitAll()
            .filterNotNull()
}

private data class ValidateServerResult(
    val remoteInfo: LightWalletEndpointInfoUnsafe,
    val lightWalletClient: CombinedWalletClient,
    val endpoint: LightWalletEndpoint,
    val getServerInfoDuration: Duration,
    val getLatestBlockHeightDuration: Duration,
) : Disposable {
    val meanDuration = (getServerInfoDuration + getLatestBlockHeightDuration) / 2

    override suspend fun dispose() {
        lightWalletClient.dispose()
    }
}

/**
 * Amount of fastest servers to return.
 */
private const val K = 3

/**
 * Latest N amount of blocks.
 */
private const val N = 100

/**
 * Threshold for mean RPC call latency.
 */
private val LATENCY_THRESHOLD = 300.milliseconds

/**
 * Threshold for getBlockRange RPC call latency of latest [N] blocks.
 */
private val FETCH_THRESHOLD = 60.seconds

private const val SYNCED_THRESHOLD_BLOCKS = 288
