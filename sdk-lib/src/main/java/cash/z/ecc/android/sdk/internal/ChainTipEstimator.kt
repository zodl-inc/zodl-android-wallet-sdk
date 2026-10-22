package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.jni.MigrationRustBackend
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.BlockSpacing
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.time.Clock

internal interface ChainTipEstimator {
    suspend fun estimatedTip(): Long

    /**
     * Measured average seconds per block over the recent scanned window (header timestamps),
     * clamped to [MIN_SECONDS_PER_BLOCK, MAX_SECONDS_PER_BLOCK]. Falls back to the height-aware
     * protocol target (75 s before NU7, 25 s from NU7) when fewer than two blocks are available or the
     * window spans the NU7 activation height. Testnet's minimum-difficulty rule makes
     * real spacing bursty (seconds-per-block during bursts) — a constant 75s there turns every
     * height-to-time projection into an overestimate.
     */
    suspend fun estimatedSecondsPerBlock(): Long
}

internal const val FALLBACK_SECONDS_PER_BLOCK = 75L

/** Default when no estimator is wired: no estimate, protocol-constant rate. */
internal object NoOpChainTipEstimator : ChainTipEstimator {
    override suspend fun estimatedTip(): Long = -1L

    override suspend fun estimatedSecondsPerBlock(): Long = FALLBACK_SECONDS_PER_BLOCK
}

internal const val MIN_SECONDS_PER_BLOCK = 5L
internal const val MAX_SECONDS_PER_BLOCK = 150L

/** Blocks of history used for the rate measurement. */
internal const val RATE_WINDOW_BLOCKS = 100L

/**
 * Entries in a `blockRateSamples` array that carry a full `(height, time)` pair for both the newer
 * and the older sample: `[latestHeight, latestTime, olderHeight, olderTime]`.
 */
internal const val SAMPLE_PAIR_SIZE = 4

/** Pure rate computation: clamped average spacing between two scanned blocks. */
internal fun measuredSecondsPerBlock(
    olderHeight: Long,
    olderTimeEpochSeconds: Long,
    newerHeight: Long,
    newerTimeEpochSeconds: Long,
): Long {
    val blocks = newerHeight - olderHeight
    if (blocks <= 0) return FALLBACK_SECONDS_PER_BLOCK
    val span = (newerTimeEpochSeconds - olderTimeEpochSeconds).coerceAtLeast(0)
    return (span / blocks).coerceIn(MIN_SECONDS_PER_BLOCK, MAX_SECONDS_PER_BLOCK)
}

/**
 * Estimates the current chain tip from the latest scanned block plus elapsed wall-clock time at a
 * 75-second average block interval. Returns -1 when no block has been scanned yet.
 */
internal fun estimateTip(
    scannedHeight: Long,
    scannedBlockTimeEpochSeconds: Long,
    nowEpochSeconds: Long,
    secondsPerBlock: Long = FALLBACK_SECONDS_PER_BLOCK,
): Long =
    scannedHeight +
        ((nowEpochSeconds - scannedBlockTimeEpochSeconds).coerceAtLeast(0L) / secondsPerBlock.coerceAtLeast(1L))

/**
 * Pure derivation of the measured rate from a `blockRateSamples` array
 * (`[latestHeight, latestTime, olderHeight, olderTime]`): the clamped average spacing between the
 * two samples, or the target spacing when the older sample is absent (array shorter than 4).
 *
 * With a [network], the target spacing is the height-aware one (ZIP 218), and a window that mixes
 * blocks from before and after NU7 activation is not measured at all: its average is meaningless
 * for either era, so the target spacing at the latest block is used until the whole window lies after
 * activation. Without a [network] the pre-NU7 75 s target is used and the window is taken as is.
 */
internal fun secondsPerBlockFromSamples(
    samples: LongArray,
    network: ZcashNetwork? = null
): Long {
    val latestHeight = samples.firstOrNull()
    val fallback =
        if (network != null && latestHeight != null) {
            BlockSpacing.targetSeconds(network, BlockHeight(latestHeight))
        } else {
            FALLBACK_SECONDS_PER_BLOCK
        }
    if (samples.size < SAMPLE_PAIR_SIZE) return fallback
    val nu7 = network?.let { BlockSpacing.nu7ActivationHeight(it)?.value }
    val windowMixesEras = nu7 != null && samples[2] < nu7 && samples[0] >= nu7
    if (windowMixesEras) return fallback
    return measuredSecondsPerBlock(
        olderHeight = samples[2],
        olderTimeEpochSeconds = samples[3],
        newerHeight = samples[0],
        newerTimeEpochSeconds = samples[1],
    )
}

/**
 * A [ChainTipEstimator] that reads its block samples through the engine's BUNDLED SQLite (a JNI
 * call), never through a second Android-framework SQLite instance on the engine-owned
 * `data.sqlite3`.
 *
 * This deliberately does NOT use `ReadOnlySupportSqliteOpenHelper`/`BlockTable` (framework
 * `android.database.sqlite.SQLiteDatabase`): running a second SQLite library instance against the
 * engine's DB in one process makes a framework `close()` drop the engine's fcntl/WAL locks and
 * truncate the `-shm` index under the engine's live mmap → deterministic SIGBUS (Milan's
 * dual-SQLite-instance incident; the production host reads moved to bundled rusqlite for exactly
 * this reason). An earlier version of this class opened framework SQLite here and both leaked the
 * handle (StrictMode `LeakedClosableViolation`) and reintroduced that hazard.
 */
internal class LazyDataDbChainTipEstimator(
    private val context: Context,
    private val network: ZcashNetwork,
    private val alias: String,
    private val clock: Clock = Clock.System,
    private val rustBackendProvider: suspend () -> MigrationRustBackend = { MigrationRustBackend.new() },
) : ChainTipEstimator {
    private suspend fun samples(): LongArray {
        val dbFile = DatabaseCoordinator.getInstance(context).dataDbFile(network, alias)
        // blockRateSamples dispatches its own JNI read onto SdkDispatchers.DATABASE_IO.
        return rustBackendProvider().blockRateSamples(dbFile.absolutePath, RATE_WINDOW_BLOCKS)
    }

    // Deliberately broad: the estimate is advisory, and every failure mode of the JNI read (a
    // missing or half-migrated database, a JNI error, an I/O fault) must degrade to the sentinel
    // rather than propagate into a caller that only wanted a hint about the tip.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun estimatedTip(): Long =
        try {
            val s = samples()
            if (s.size < 2) {
                -1L
            } else {
                estimateTip(
                    scannedHeight = s[0],
                    scannedBlockTimeEpochSeconds = s[1],
                    nowEpochSeconds = clock.now().epochSeconds,
                    secondsPerBlock = secondsPerBlockFromSamples(s, network),
                )
            }
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not read latest block — returning -1" }
            -1L
        }

    // Deliberately broad, for the same reason as [estimatedTip]: an unmeasurable rate falls back to
    // the protocol constant instead of failing the caller.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun estimatedSecondsPerBlock(): Long =
        try {
            secondsPerBlockFromSamples(samples(), network)
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not measure block rate — falling back" }
            FALLBACK_SECONDS_PER_BLOCK
        }
}
