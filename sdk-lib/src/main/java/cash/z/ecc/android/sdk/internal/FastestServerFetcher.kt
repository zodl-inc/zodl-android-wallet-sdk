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
import co.electriccoin.lightwallet.client.model.CompactBlockUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.LightWalletEndpointInfoUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.util.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

internal class FastestServerFetcher(
    private val backend: TypesafeBackend,
    private val network: ZcashNetwork,
    private val walletClientFactory: WalletClientFactory,
    private val sdkFlags: SdkFlags,
    private val switchEvaluator: ServerSwitchEvaluator = ServerSwitchEvaluator.PROCESS_WIDE
) {
    operator fun invoke(servers: List<LightWalletEndpoint>): Flow<FastestServersResult> =
        flow {
            emit(FastestServersResult.Measuring)

            val serversByRpcMeanLatency =
                measureRpcLatency(servers)
                    .mapIndexedNotNull { index, result ->
                        if (index <= K - 1 || result.meanDuration <= LATENCY_THRESHOLD) {
                            Twig.debug { "Fastest Server: '${result.endpoint}' VALIDATED by SORTING by RPC latency" }
                            result
                        } else {
                            Twig.debug { "Fastest Server: '${result.endpoint}' RULED OUT by SORTING by RPC latency" }
                            result.disposeQuietly()
                            null
                        }
                    }

            Twig.debug {
                "Fastest Server: '${serversByRpcMeanLatency.map { it.endpoint }}' VALIDATED by MEASURING RPC latency"
            }

            emit(FastestServersResult.Validating(serversByRpcMeanLatency.map { it.endpoint }.take(K)))

            val serversByGetBlockRangeTimeout =
                measureBlockFetches(
                    survivors = serversByRpcMeanLatency,
                    blocksToFetch = N,
                    fetchThreshold = FETCH_THRESHOLD,
                    logPrefix = FASTEST_SERVER_LOG_PREFIX,
                    limit = K
                ).map { it.endpoint }

            Twig.debug { "Fastest Server: '$serversByGetBlockRangeTimeout' VALIDATED by getBlockRange timeout" }

            emit(FastestServersResult.Done(serversByGetBlockRangeTimeout))
        }.flowOn(Dispatchers.Default)

    /**
     * Benchmarks [current] together with every endpoint in [candidates] and decides whether the wallet
     * should move away from [current].
     *
     * [current] is measured whether or not the caller offered it: a host dropped from the caller's list by
     * an app update must be given the chance to keep the wallet, rather than losing it to a decision made
     * without a single measurement of it.
     *
     * The decision is a recommendation, not a record: an evaluation counts a failed measurement of
     * [current] but never clears that count and never starts the switch cooldown. [confirmServerSwitch] is
     * what does both, so a recommendation the caller declines cannot silence a genuinely dead server.
     *
     * @param current the endpoint the wallet is connected to right now
     * @param candidates the endpoints to benchmark alongside [current]
     * @param fetchThreshold per-candidate cap for the block-fetch stage
     * @param blocksToFetch how many blocks ending at the lowest tip the survivors report to stream
     *
     * @return the endpoint to switch to, or null when the wallet should stay on [current]
     */
    suspend fun evaluateServerSwitch(
        current: LightWalletEndpoint,
        candidates: List<LightWalletEndpoint>,
        fetchThreshold: Duration,
        blocksToFetch: Int
    ): LightWalletEndpoint? {
        require(blocksToFetch >= 1) { "blocksToFetch must be at least 1, was $blocksToFetch" }
        require(fetchThreshold.isPositive()) { "fetchThreshold must be positive, was $fetchThreshold" }

        val isCurrentOffered = candidates.any { it.isSameServer(current) }

        return withContext(Dispatchers.Default) {
            val ranked =
                measureBlockFetches(
                    survivors = measureRpcLatency(if (isCurrentOffered) candidates else candidates + current),
                    blocksToFetch = blocksToFetch,
                    fetchThreshold = fetchThreshold,
                    logPrefix = SERVER_SWITCH_LOG_PREFIX,
                    limit = Int.MAX_VALUE
                ).sortedBy { it.score }

            val outcome =
                switchEvaluator.evaluate(
                    current = current,
                    ranked = ranked,
                    isCurrentOffered = isCurrentOffered
                )

            Twig.info { ServerSwitchPolicy.describe(current = current, ranked = ranked, outcome = outcome) }

            outcome.endpointToSwitchTo
        }
    }

    /**
     * Records that the wallet was actually moved to [endpoint] after [evaluateServerSwitch] recommended it.
     */
    suspend fun confirmServerSwitch(endpoint: LightWalletEndpoint) = switchEvaluator.onSwitchApplied(endpoint)

    private suspend fun measureRpcLatency(servers: List<LightWalletEndpoint>): List<ValidateServerResult> =
        servers
            .parallelMapNotNull {
                validateServerEndpointAndMeasure(it)
            }.sortedBy {
                it.meanDuration
            }

    /**
     * Runs the block-fetch stage sequentially over [survivors] in their given order, keeping at most [limit]
     * measured endpoints. Survivors that are never reached, because [limit] was hit or the caller was
     * cancelled, are disposed before returning.
     *
     * Every survivor is timed on one common range: [blocksToFetch] blocks ending at the lowest tip any of
     * them reported. Anchoring the range on each server's own tip instead would time downloads of different
     * blocks, and compact block sizes differ by orders of magnitude, so the scores would say more about the
     * payload each server happened to serve than about the server itself.
     */
    private suspend fun measureBlockFetches(
        survivors: List<ValidateServerResult>,
        blocksToFetch: Int,
        fetchThreshold: Duration,
        logPrefix: String,
        limit: Int
    ): List<MeasuredEndpoint> {
        val commonTip = survivors.minOfOrNull { it.remoteInfo.blockHeightUnsafe.value } ?: return emptyList()
        val heightRange =
            BlockHeightUnsafe((commonTip - (blocksToFetch - 1)).coerceAtLeast(0))..BlockHeightUnsafe(commonTip)
        val pending = ArrayDeque(survivors)
        val measured = mutableListOf<MeasuredEndpoint>()
        try {
            while (pending.isNotEmpty() && measured.size < limit) {
                val result = pending.removeFirst()
                measureBlockFetch(
                    result = result,
                    heightRange = heightRange,
                    fetchThreshold = fetchThreshold,
                    logPrefix = logPrefix
                )?.let { measured += MeasuredEndpoint(endpoint = result.endpoint, score = it) }
            }
        } finally {
            pending.forEach { it.disposeQuietly() }
        }
        return measured
    }

    /**
     * Streams the blocks of [heightRange] and times the stream. Always disposes [result].
     *
     * @return the stream duration, or null when the stream failed or exceeded [fetchThreshold]
     */
    private suspend fun measureBlockFetch(
        result: ValidateServerResult,
        heightRange: ClosedRange<BlockHeightUnsafe>,
        fetchThreshold: Duration,
        logPrefix: String
    ): Duration? {
        val outcome =
            try {
                withTimeoutOrNull(fetchThreshold) {
                    streamBlocks(result = result, heightRange = heightRange)
                } ?: BlockFetchOutcome.TimedOut
            } finally {
                result.disposeQuietly()
            }

        return when (outcome) {
            BlockFetchOutcome.TimedOut -> {
                Twig.debug { "$logPrefix: '${result.endpoint}' RULED OUT by getBlockRange timeout" }
                null
            }

            is BlockFetchOutcome.Threw -> {
                Twig.debug(outcome.throwable) {
                    "$logPrefix: '${result.endpoint}' RULED OUT by getBlockRange exception"
                }
                null
            }

            is BlockFetchOutcome.Streamed -> {
                val failure = outcome.failure
                if (failure != null) {
                    Twig.debug {
                        "$logPrefix: '${result.endpoint}' RULED OUT by getBlockRange failure " +
                            "${failure.code}: ${failure.description}"
                    }
                    null
                } else {
                    Twig.debug {
                        "$logPrefix: '${result.endpoint}' VALIDATED by getBlockRange in ${outcome.duration}"
                    }
                    outcome.duration
                }
            }
        }
    }

    /**
     * Streams the blocks of [heightRange] the same way `downloadBatchOfBlocks()` does, except that the
     * stream honours the Tor flag: benchmarking every bundled host over a direct connection would expose
     * the user's address to operators they are not a customer of, while Tor is on.
     */
    private suspend fun streamBlocks(
        result: ValidateServerResult,
        heightRange: ClosedRange<BlockHeightUnsafe>
    ): BlockFetchOutcome =
        runCatching {
            measureTimedValue {
                result.lightWalletClient
                    .getBlockRange(
                        heightRange = heightRange,
                        serviceMode =
                            sdkFlags ifTor
                                ServiceMode.Group(
                                    "measureBlockFetch(${result.endpoint.host}:${result.endpoint.port})"
                                )
                    ).firstOrNull { it is Response.Failure }
            }
        }.fold(
            onSuccess = {
                BlockFetchOutcome.Streamed(
                    duration = it.duration,
                    failure = it.value as? Response.Failure<CompactBlockUnsafe>
                )
            },
            onFailure = {
                if (it is CancellationException) throw it
                BlockFetchOutcome.Threw(it)
            }
        )

    /**
     * Creates a wallet client for [endpoint] and measures it. The client is disposed unless it is handed
     * over to the returned result, so neither a cancellation nor a throw mid-validation can leak its gRPC
     * channel.
     */
    private suspend fun validateServerEndpointAndMeasure(endpoint: LightWalletEndpoint): ValidateServerResult? {
        val lightWalletClient =
            runCatching { walletClientFactory.create(endpoint) }
                .getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Twig.debug(throwable) { "Fastest Server: Server '$endpoint' RULED OUT, client creation failed" }
                    return null
                }
        var validated: ValidateServerResult? = null
        try {
            validated = measureValidatedServer(endpoint = endpoint, lightWalletClient = lightWalletClient)
            return validated
        } finally {
            if (validated == null) {
                lightWalletClient.disposeQuietly()
            }
        }
    }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    private suspend fun measureValidatedServer(
        endpoint: LightWalletEndpoint,
        lightWalletClient: CombinedWalletClient
    ): ValidateServerResult? {
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

        val serviceMode =
            sdkFlags ifTor ServiceMode.Group("validateServerEndpointAndMeasure(${endpoint.host}:${endpoint.port})")

        val remoteInfo: LightWalletEndpointInfoUnsafe?
        val getServerInfoDuration =
            measureTime {
                remoteInfo =
                    withTimeoutOrNull(RPC_TIMEOUT) {
                        when (val response = lightWalletClient.getServerInfo(serviceMode)) {
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
            return null
        }

        // Check network type
        if (!remoteInfo.matchingNetwork(network.networkName)) {
            logRuledOut("matchingNetwork failed")
            return null
        }

        // Check sapling activation height
        runCatching {
            val remoteSaplingActivationHeight = remoteInfo.saplingActivationHeightUnsafe.toBlockHeight()
            if (network.saplingActivationHeight != remoteSaplingActivationHeight) {
                logRuledOut("invalid saplingActivationHeight")
                return null
            }
        }.getOrElse {
            logRuledOut("saplingActivationHeight failed", it)
            return null
        }

        val currentChainTip: BlockHeight?
        val getLatestBlockHeightDuration =
            measureTime {
                currentChainTip =
                    withTimeoutOrNull(RPC_TIMEOUT) {
                        when (val response = lightWalletClient.getLatestBlockHeight(serviceMode = serviceMode)) {
                            is Response.Success -> {
                                runCatching { response.result.toBlockHeight() }.getOrElse {
                                    logRuledOut("toBlockHeight failed", it)
                                    null
                                }
                            }

                            is Response.Failure -> {
                                logRuledOut("getLatestBlockHeight failed", response.toThrowable())
                                null
                            }
                        }
                    }
            }

        if (currentChainTip == null) {
            return null
        }

        val sdkBranchId =
            runCatching {
                "%x".format(
                    Locale.ROOT,
                    backend.getBranchIdForHeight(currentChainTip)
                )
            }.getOrElse {
                logRuledOut("getBranchIdForHeight failed", it)
                return null
            }

        if (!remoteInfo.consensusBranchId.equals(sdkBranchId, true)) {
            logRuledOut("consensusBranchId does not match")
            return null
        }

        if (remoteInfo.estimatedHeight >= remoteInfo.blockHeightUnsafe.value + SYNCED_THRESHOLD_BLOCKS) {
            logRuledOut("estimatedHeight does not match")
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
}

/**
 * Disposes the receiver outside cancellation and swallows any failure. The real wallet clients suspend
 * while shutting their gRPC channel down, so disposing from an already cancelled coroutine would abandon
 * the channel at the first suspension point instead of closing it.
 *
 * The disposal is capped: it runs uncancellably, so a gRPC shutdown that hangs rather than throws would
 * otherwise be unstoppable, and the caller joins the cancelled evaluation before starting the next one -
 * one wedged channel would wedge automatic selection for the lifetime of the process. A
 * [withTimeoutOrNull] child still cancels itself on its own timeout inside [NonCancellable].
 */
private suspend fun Disposable.disposeQuietly() {
    withContext(NonCancellable) {
        runCatching {
            withTimeoutOrNull(DISPOSE_TIMEOUT) { dispose() } ?: Twig.debug {
                "Fastest Server: gave up disposing a benchmarked wallet client after $DISPOSE_TIMEOUT"
            }
        }.onFailure { Twig.debug(it) { "Fastest Server: failed to dispose a benchmarked wallet client" } }
    }
}

private suspend fun <T, R> Iterable<T>.parallelMapNotNull(block: suspend (T) -> R?): List<R> =
    coroutineScope {
        map { async { block(it) } }
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
 * Why one candidate's block-fetch stage ended, kept apart so a stream that threw and a stream that ran out
 * of time are not both reported as a timeout.
 */
private sealed interface BlockFetchOutcome {
    data class Streamed(
        val duration: Duration,
        val failure: Response.Failure<CompactBlockUnsafe>?
    ) : BlockFetchOutcome

    data class Threw(
        val throwable: Throwable
    ) : BlockFetchOutcome

    data object TimedOut : BlockFetchOutcome
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

/**
 * Cap for a single benchmarking RPC call, in case a server accepts the connection and then never answers.
 */
private val RPC_TIMEOUT = 5.seconds

/**
 * Cap for shutting one benchmarked wallet client down, in case its gRPC channel never finishes closing.
 */
private val DISPOSE_TIMEOUT = 5.seconds

private const val SYNCED_THRESHOLD_BLOCKS = 288

private const val FASTEST_SERVER_LOG_PREFIX = "Fastest Server"

private const val SERVER_SWITCH_LOG_PREFIX = "Server Switch"
