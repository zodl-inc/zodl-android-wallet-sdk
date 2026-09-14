package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationInputs
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureBatchResult
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureInput
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSigningRequest
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPlan
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundRunReport
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniShareTrackingRunReport
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Minimum entropy, in bytes, [VotingRustBackend.scheduledShareSubmitAt] sources from
 * [SecureRandom] for `scheduledShareSubmitAtNative`'s entropy parameter (which requires at
 * least 8 bytes).
 */
private const val SCHEDULED_SHARE_SUBMIT_AT_ENTROPY_BYTES = 32

/**
 * Raw JNI bindings to the native shielded-voting backend.
 *
 * The only permitted caller is `sdk-lib`'s `TypesafeVotingBackendImpl` — every other consumer,
 * including the app, must go through the public `cash.z.ecc.android.sdk.VotingSdk` API instead.
 * That boundary is enforced two ways: this `@Deprecated(ERROR)` (which forces any legitimate
 * caller to carry an explicit, grep-able `@Suppress("DEPRECATION_ERROR")`), and — once the app
 * finishes migrating off direct `VotingRustBackend` usage — dropping this module's JNI artifact
 * from the app's compile classpath entirely, per the CHP feature-module extraction design
 * (`docs/superpowers/specs/2026-08-10-chp-feature-module-extraction-design.md`).
 *
 * Kotlin `internal` cannot express this restriction on its own: `sdk-lib` is a separate Gradle
 * module from `backend-lib` and would lose access along with every other consumer, which is why
 * this class stays a public class carrying an error-level deprecation instead.
 *
 * Whether the native library actually exports these JNI symbols in a given build depends on
 * `backend-lib/build.gradle.kts`'s `RUSTFLAGS` (the `--cfg zcash_voting` gate) and
 * `backend-lib/Cargo.toml`'s `zcash_voting`/`unstable-voting-circuits` entries — independent of
 * this annotation. If they disagree with a caller's expectation, calls here throw
 * [UnsatisfiedLinkError] rather than failing gracefully; `VotingSdk` callers should use its
 * `isAvailable()` probe rather than assuming this class is safe to call just because the
 * `@Suppress` compiles.
 *
 * This class's `external fun` surface mirrors the round-driver session model the Rust `voting`
 * module (`backend-lib/src/main/rust/voting.rs` and its `voting/` submodules) exports as of
 * the voting-4.0.0 SDK port:
 * a `VotingDb` handle scopes wallet-level state (rounds, bundles, hotkeys, Keystone signatures,
 * vote-tree sync), and a [VotingDb.RoundSession] handle scopes one round's `RoundExecutor`/
 * `RoundDriver` session (plan, ballot intents, drive-to-quiescence, Keystone signing requests).
 * The old per-operation JNI surface (hand-rolled PCZT construction, per-share recovery
 * bookkeeping, ...) is gone — that responsibility now lives inside `zcash_voting` itself,
 * reached only via [VotingDb.RoundSession.runRound].
 */
@Keep
@Suppress("TooManyFunctions", "LongParameterList")
@Deprecated(
    message =
        "Direct access to VotingRustBackend is restricted to sdk-lib's TypesafeVotingBackendImpl " +
            "— use cash.z.ecc.android.sdk.VotingSdk instead. See this class's doc comment.",
    level = DeprecationLevel.ERROR
)
class VotingRustBackend private constructor() {
    @Throws(RuntimeException::class)
    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray =
        withContext(Dispatchers.IO) {
            computeShareNullifierNative(voteCommitment, shareIndex, blind)
        }

    @Throws(RuntimeException::class)
    suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult =
        withContext(Dispatchers.IO) {
            computeBundleSetupNative(notes.toTypedArray())
                ?: error("computeBundleSetup returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun warmProvingCaches() =
        withContext(Dispatchers.IO) {
            warmProvingCachesNative()
        }

    /**
     * Fixes the process-wide proving-pool policy (`max_active_heavy_jobs: 1`) once at startup,
     * before any voting round work. See `configureVotingNative`'s doc comment in
     * `backend-lib/src/main/rust/voting/util.rs` for why this matters independently of
     * [VotingDb.RoundSession.runRound]'s own per-dispatch `max_proof_concurrency` bound.
     */
    @Throws(RuntimeException::class)
    suspend fun configureVoting() =
        withContext(Dispatchers.IO) {
            configureVotingNative()
        }

    /**
     * Computes when a delegated helper share should submit, honoring the ceremony's
     * last-moment buffer window.
     *
     * Sources its own entropy from [SecureRandom] so callers cannot forget to supply it.
     * Returns unix seconds; `0` means "submit immediately".
     */
    @Throws(RuntimeException::class)
    suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long =
        withContext(Dispatchers.IO) {
            scheduledShareSubmitAtNative(
                nowSeconds,
                ceremonyStartSeconds,
                voteEndTimeSeconds,
                singleShare,
                SecureRandom().generateSeed(SCHEDULED_SHARE_SUBMIT_AT_ENTROPY_BYTES)
            )
        }

    @Throws(RuntimeException::class)
    suspend fun extractOrchardFvkFromUfvk(
        ufvk: String,
        networkId: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            extractOrchardFvkFromUfvkNative(ufvk, networkId)
                ?: error("extractOrchardFvkFromUfvk returned null")
        }

    /**
     * Derives the raw Orchard address for the voting hotkey.
     *
     * The hotkey account index is intentionally fixed by the Rust voting backend to match the
     * vote-signing path. Do not add an `accountIndex` parameter unless that path changes with it.
     */
    @Throws(RuntimeException::class)
    suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            deriveHotkeyRawAddressNative(hotkeySeed, networkId)
                ?: error("deriveHotkeyRawAddress returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun deriveHotkeyRawAddressForAccountFixture(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            deriveHotkeyRawAddressForAccountFixtureNative(hotkeySeed, networkId, accountIndex)
                ?: error("deriveHotkeyRawAddressForAccountFixture returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            extractNcRootNative(treeStateBytes)
                ?: error("extractNcRoot returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun verifyWitness(witness: JniWitnessData): Boolean =
        withContext(Dispatchers.IO) {
            verifyWitnessNative(witness)
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun noteInfoArrayFixtureForTesting(): Array<JniNoteInfo> =
        withContext(Dispatchers.IO) {
            noteInfoArrayFixtureNative()
                ?: error("noteInfoArrayFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun witnessDataArrayFixtureForTesting(): Array<JniWitnessData> =
        withContext(Dispatchers.IO) {
            witnessDataArrayFixtureNative()
                ?: error("witnessDataArrayFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun treeStateFixtureForTesting(): ByteArray =
        withContext(Dispatchers.IO) {
            treeStateFixtureNative()
                ?: error("treeStateFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun nonEmptyTreeStateFixtureForTesting(): ByteArray =
        withContext(Dispatchers.IO) {
            nonEmptyTreeStateFixtureNative()
                ?: error("nonEmptyTreeStateFixture returned null")
        }

    suspend fun openVotingDb(dbPath: String, walletId: String, networkId: Int): VotingDb =
        withContext(SdkDispatchers.DATABASE_IO) {
            openVotingDbNative(dbPath, walletId, networkId).let { dbHandle ->
                check(dbHandle != 0L) {
                    "openVotingDb failed for dbPath=$dbPath"
                }
                VotingDb(dbHandle)
            }
        }

    @Suppress("TooManyFunctions", "LongParameterList")
    class VotingDb internal constructor(
        private var dbHandle: Long?
    ) {
        private val accessMutex = Mutex()

        suspend fun close() {
            accessMutex.withLock {
                dbHandle?.let { handle ->
                    withContext(SdkDispatchers.DATABASE_IO) {
                        closeVotingDbNative(handle)
                    }
                    dbHandle = null
                }
            }
        }

        @Throws(RuntimeException::class)
        suspend fun getRoundState(roundId: String): JniRoundState? =
            withHandle { handle -> getRoundStateNative(handle, roundId) }

        /**
         * Clears unsigned/unproved delegation setup fields for one round (preserving submitted
         * bundles and bundles with persisted Keystone signatures) so an interrupted or corrupted
         * per-bundle setup can be safely rebuilt from scratch. See
         * `resetVotingSessionStateNative`'s doc comment in
         * `backend-lib/src/main/rust/voting/rounds.rs`.
         */
        @Throws(RuntimeException::class)
        suspend fun resetVotingSessionState(roundId: String) =
            withHandle { handle -> resetVotingSessionStateNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun listRounds(): Array<JniRoundSummary> =
            withHandle { handle -> listRoundsNative(handle) }

        @Throws(RuntimeException::class)
        suspend fun getBundleCount(roundId: String): Int =
            withHandle { handle -> getBundleCountNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun clearRound(roundId: String) =
            withHandle { handle -> clearRoundNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun deleteSkippedBundles(
            roundId: String,
            keepCount: Int
        ): Long =
            withHandle { handle -> deleteSkippedBundlesNative(handle, roundId, keepCount) }

        /**
         * Bootstraps (or validates) [roundId]'s `rounds` row from caller-supplied round
         * metadata, via `zcash_voting::DelegationPipeline::ensure_round`.
         *
         * Required before [setupBundles] or a delegation-enabled
         * [cash.z.ecc.android.sdk.internal.jni.VotingRustBackend.RoundSession.runRound] call can
         * do anything for a round that has never been through this call before. See
         * `ensure_round_from_jni`'s doc comment in `delegation_driver.rs` for why
         * `RoundDriver::run()` alone cannot reach this bootstrap on its own for a virgin round:
         * it never proposes a `Delegate` step until bundle rows already exist, and bundle rows
         * cannot be created (`setupBundlesNative`'s bundle insert has a foreign key on
         * `rounds`) until the round row itself exists -- a circularity only a standalone call
         * to the crate's `ensure_round` (not part of the object-safe `DelegationDriver` trait
         * `RoundHostContext::delegation` carries) can break.
         *
         * Idempotent and safe to call every time before [setupBundles]/[runRound]: an
         * already-bootstrapped round with matching params is a no-op; one with different
         * params fails loudly, since the stored params bind every bundle/proof already built
         * against them.
         */
        @Throws(RuntimeException::class)
        suspend fun ensureRound(
            roundId: String,
            anchorTreeStateBytes: ByteArray,
            snapshotHeight: Long,
            eaPk: ByteArray,
            ncRoot: ByteArray,
            nullifierImtRoot: ByteArray
        ) = withHandle { handle ->
            ensureRoundNative(handle, roundId, anchorTreeStateBytes, snapshotHeight, eaPk, ncRoot, nullifierImtRoot)
        }

        @Throws(RuntimeException::class)
        suspend fun setupBundles(
            roundId: String,
            notes: List<JniNoteInfo>
        ): JniBundleSetupResult =
            withHandle { handle ->
                setupBundlesNative(handle, roundId, notes.toTypedArray())
                    ?: error("setupBundles returned null for roundId=$roundId")
            }

        /**
         * Mints or reconstructs a voting hotkey.
         *
         * An empty [storedSecret] mints a fresh, app-owned random hotkey; a 64-byte
         * [storedSecret] (previously persisted from a prior call's returned
         * [JniVotingHotkey.storedSecret]) deterministically reconstructs the same hotkey. This
         * call is not scoped to a round.
         */
        @Throws(RuntimeException::class)
        suspend fun generateHotkey(storedSecret: ByteArray): JniVotingHotkey =
            withHandle { handle ->
                generateHotkeyNative(handle, storedSecret)
                    ?: error("generateHotkey returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun precomputeDelegationPir(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            pirDepth: Int,
            pirTier0Layers: Int,
            pirTier1Layers: Int,
            pirPolyLen: Int,
            notes: List<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult =
            withHandle { handle ->
                precomputeDelegationPirNative(
                    handle,
                    roundId,
                    bundleIndex,
                    pirServerUrl,
                    pirDepth,
                    pirTier0Layers,
                    pirTier1Layers,
                    pirPolyLen,
                    notes.toTypedArray()
                ) ?: error("precomputeDelegationPir returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long =
            withHandle { handle ->
                syncVoteTreeNative(handle, roundId, nodeUrl).also { height ->
                    check(height >= 0) {
                        "syncVoteTree failed for roundId=$roundId"
                    }
                }
            }

        @Throws(RuntimeException::class)
        suspend fun resetTreeClient(roundId: String) =
            withHandle { handle ->
                check(resetTreeClientNative(handle, roundId)) {
                    "resetTreeClient failed for roundId=$roundId"
                }
            }

        /**
         * Atomically stores a batch of Keystone delegation signatures, replacing the old
         * per-bundle store/reconstruct pair. See `storeKeystoneSignaturesNative`'s doc comment
         * in `backend-lib/src/main/rust/voting/delegation.rs` for idempotent-replay and
         * signing-context-conflict semantics.
         */
        @Throws(RuntimeException::class)
        suspend fun storeKeystoneSignatures(
            roundId: String,
            signatures: List<JniKeystoneSignatureInput>
        ): JniKeystoneSignatureBatchResult =
            withHandle { handle ->
                storeKeystoneSignaturesNative(handle, roundId, signatures.toTypedArray())
                    ?: error("storeKeystoneSignatures returned null for roundId=$roundId")
            }

        @Throws(RuntimeException::class)
        suspend fun getKeystoneSignatures(roundId: String): Array<JniKeystoneSignatureRecord> =
            withHandle { handle ->
                getKeystoneSignaturesNative(handle, roundId)
                    ?: error("getKeystoneSignatures returned null for roundId=$roundId")
            }

        /**
         * Drives `roundId`'s unconfirmed helper shares to confirmation with a
         * `ShareTrackingDriver`, repeating passes on the cadence each pass itself computes until
         * the round's shares are quiescent. Standalone and session-less: unlike
         * [RoundSession.runRound], a separate call cannot cancel an in-flight
         * [trackShares] run mid-pass — see `trackSharesNative`'s doc comment in
         * `backend-lib/src/main/rust/voting/share_tracking_driver.rs`.
         *
         * [voteEndTimeSeconds] `< 0` decodes to "no vote-end boundary known yet".
         */
        @Throws(RuntimeException::class)
        suspend fun trackShares(
            roundId: String,
            torRuntime: Long,
            helperUrls: List<String>,
            voteEndTimeSeconds: Long
        ): JniShareTrackingRunReport =
            withHandle { handle ->
                trackSharesNative(handle, roundId, torRuntime, helperUrls.toTypedArray(), voteEndTimeSeconds)
                    ?: error("trackShares returned null for roundId=$roundId")
            }

        /**
         * Opens a round session: binds a `RoundExecutor` to [roundId]'s roster and hotkey, wires
         * its chain-submission and helper transports through [torRuntime], and registers it.
         * See `openRoundSessionNative`'s doc comment in
         * `backend-lib/src/main/rust/voting/round_session.rs`.
         *
         * [hotkeySecret] may be `null` before a hotkey is bound. [ceremonyStartSeconds]/
         * [voteEndTimeSeconds] `< 0` decode to "not yet known".
         */
        @Suppress("LongParameterList")
        @Throws(RuntimeException::class)
        suspend fun openRoundSession(
            torRuntime: Long,
            roundId: String,
            proposalIds: IntArray,
            proposalOptionCounts: IntArray,
            hotkeySecret: ByteArray?,
            chainEndpoints: List<String>,
            operationEpoch: Long,
            configuredHelperUrls: List<String>,
            voteTreeNodeUrls: List<String>,
            ceremonyStartSeconds: Long,
            voteEndTimeSeconds: Long
        ): RoundSession =
            withHandle { handle ->
                openRoundSessionNative(
                    handle,
                    torRuntime,
                    roundId,
                    proposalIds,
                    proposalOptionCounts,
                    hotkeySecret,
                    chainEndpoints.toTypedArray(),
                    operationEpoch,
                    configuredHelperUrls.toTypedArray(),
                    voteTreeNodeUrls.toTypedArray(),
                    ceremonyStartSeconds,
                    voteEndTimeSeconds
                ).let { sessionHandle ->
                    check(sessionHandle != 0L) {
                        "openRoundSession failed for roundId=$roundId"
                    }
                    RoundSession(sessionHandle, dbHandle = handle)
                }
            }

        private suspend fun <T> withHandle(block: (Long) -> T): T {
            val handle =
                checkNotNull(dbHandle) {
                    "Voting DB handle is closed"
                }
            return withContext(SdkDispatchers.DATABASE_IO) {
                block(handle)
            }
        }
    }

    /**
     * One open round session: a bound `RoundExecutor` plus the per-round host inputs
     * `runRound` needs to drive the round to quiescence. See [VotingDb.openRoundSession]'s doc
     * comment.
     *
     * [dbHandle] is the raw JNI database handle of the [VotingDb] this session was opened
     * against, retained (Task 10) so a delegation-enabled [runRound] call's caller can fill in
     * [cash.z.ecc.android.sdk.internal.model.voting.JniDelegationInputs.dbHandle] without
     * needing a second, independently-tracked reference to the same database -- see that
     * class's doc comment for why `RoundSessionHandle` on the Rust side does not already carry
     * one itself.
     */
    @Suppress("TooManyFunctions")
    class RoundSession internal constructor(
        private var sessionHandle: Long?,
        val dbHandle: Long
    ) {
        private val accessMutex = Mutex()

        /**
         * Removes this round session from the registry. No implicit chain cancellation: a
         * caller with a [runRound] call in flight must [cancel] first if it wants that run to
         * stop early.
         */
        suspend fun close() {
            accessMutex.withLock {
                sessionHandle?.let { handle ->
                    withContext(Dispatchers.IO) {
                        closeRoundSessionNative(handle)
                    }
                    sessionHandle = null
                }
            }
        }

        @Throws(RuntimeException::class)
        suspend fun cancel() =
            withHandle { handle -> cancelRoundSessionNative(handle) }

        @Throws(RuntimeException::class)
        suspend fun setOperationEpoch(operationEpoch: Long) =
            withHandle { handle -> setOperationEpochNative(handle, operationEpoch) }

        @Throws(RuntimeException::class)
        suspend fun getRoundPlan(): JniRoundPlan? =
            withHandle { handle -> getRoundPlanNative(handle) }

        /**
         * Records ballot decisions and returns the refreshed plan. `choices[i] < 0` decodes to
         * a skipped decision for `proposalIds[i]`.
         */
        @Throws(RuntimeException::class)
        suspend fun setBallotIntents(
            proposalIds: IntArray,
            choices: IntArray
        ): JniRoundPlan? =
            withHandle { handle -> setBallotIntentsNative(handle, proposalIds, choices) }

        /**
         * Drives this session's round to quiescence with a `RoundDriver`.
         *
         * [delegationInputs] must be non-null for a pass that needs to advance delegation
         * signing; every other step tolerates `null`. See [JniDelegationInputs]'s doc comment.
         */
        @Throws(RuntimeException::class)
        suspend fun runRound(
            torRuntime: Long,
            delegationInputs: JniDelegationInputs?
        ): JniRoundRunReport? =
            withHandle { handle -> runRoundNative(handle, torRuntime, delegationInputs) }

        /**
         * Loops `DelegationPipeline::keystone_request` over [bundleIndices] against the
         * pipeline a prior delegation-enabled [runRound] call built and cached on this session.
         * Fails if no delegation pipeline is cached yet.
         */
        @Throws(RuntimeException::class)
        suspend fun getKeystoneSigningRequests(bundleIndices: IntArray): Array<JniKeystoneSigningRequest> =
            withHandle { handle ->
                getKeystoneSigningRequestsNative(handle, bundleIndices)
                    ?: error("getKeystoneSigningRequests returned null")
            }

        private suspend fun <T> withHandle(block: (Long) -> T): T {
            val handle =
                checkNotNull(sessionHandle) {
                    "Round session handle is closed"
                }
            return withContext(Dispatchers.IO) {
                block(handle)
            }
        }
    }

    companion object {
        // The factory is part of the deprecated surface; suppressing here only lets the
        // class construct itself, and does not reopen it to callers.
        @Suppress("DEPRECATION_ERROR")
        suspend fun new(): VotingRustBackend {
            RustBackend.loadLibrary()

            return VotingRustBackend()
        }

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun computeShareNullifierNative(
            voteCommitment: ByteArray,
            shareIndex: Int,
            blind: ByteArray
        ): ByteArray

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun warmProvingCachesNative()

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun configureVotingNative()

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun scheduledShareSubmitAtNative(
            nowSeconds: Long,
            ceremonyStartSeconds: Long,
            voteEndTimeSeconds: Long,
            singleShare: Boolean,
            entropy: ByteArray
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractOrchardFvkFromUfvkNative(
            ufvk: String,
            networkId: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deriveHotkeyRawAddressNative(
            hotkeySeed: ByteArray,
            networkId: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deriveHotkeyRawAddressForAccountFixtureNative(
            hotkeySeed: ByteArray,
            networkId: Int,
            accountIndex: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractNcRootNative(treeStateBytes: ByteArray): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun verifyWitnessNative(witness: JniWitnessData): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun openVotingDbNative(dbPath: String, walletId: String, networkId: Int): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun closeVotingDbNative(dbHandle: Long)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getRoundStateNative(dbHandle: Long, roundId: String): JniRoundState?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun resetVotingSessionStateNative(dbHandle: Long, roundId: String)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun listRoundsNative(dbHandle: Long): Array<JniRoundSummary>

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getBundleCountNative(dbHandle: Long, roundId: String): Int

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun clearRoundNative(dbHandle: Long, roundId: String)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deleteSkippedBundlesNative(
            dbHandle: Long,
            roundId: String,
            keepCount: Int
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun computeBundleSetupNative(notes: Array<JniNoteInfo>): JniBundleSetupResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun setupBundlesNative(
            dbHandle: Long,
            roundId: String,
            notes: Array<JniNoteInfo>
        ): JniBundleSetupResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun ensureRoundNative(
            dbHandle: Long,
            roundId: String,
            anchorTreeStateBytes: ByteArray,
            snapshotHeight: Long,
            eaPk: ByteArray,
            ncRoot: ByteArray,
            nullifierImtRoot: ByteArray
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun generateHotkeyNative(
            dbHandle: Long,
            storedSecret: ByteArray
        ): JniVotingHotkey?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun noteInfoArrayFixtureNative(): Array<JniNoteInfo>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun witnessDataArrayFixtureNative(): Array<JniWitnessData>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun treeStateFixtureNative(): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun nonEmptyTreeStateFixtureNative(): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun precomputeDelegationPirNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            pirDepth: Int,
            pirTier0Layers: Int,
            pirTier1Layers: Int,
            pirPolyLen: Int,
            notes: Array<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun syncVoteTreeNative(
            dbHandle: Long,
            roundId: String,
            nodeUrl: String
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun resetTreeClientNative(
            dbHandle: Long,
            roundId: String
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeKeystoneSignaturesNative(
            dbHandle: Long,
            roundId: String,
            signatures: Array<JniKeystoneSignatureInput>
        ): JniKeystoneSignatureBatchResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getKeystoneSignaturesNative(
            dbHandle: Long,
            roundId: String
        ): Array<JniKeystoneSignatureRecord>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun trackSharesNative(
            dbHandle: Long,
            roundId: String,
            torRuntime: Long,
            helperUrls: Array<String>,
            voteEndTimeSeconds: Long
        ): JniShareTrackingRunReport?

        @JvmStatic
        @Throws(RuntimeException::class)
        @Suppress("LongParameterList")
        private external fun openRoundSessionNative(
            dbHandle: Long,
            torRuntime: Long,
            roundId: String,
            proposalIds: IntArray,
            proposalOptionCounts: IntArray,
            hotkeySecret: ByteArray?,
            chainEndpoints: Array<String>,
            operationEpoch: Long,
            configuredHelperUrls: Array<String>,
            voteTreeNodeUrls: Array<String>,
            ceremonyStartSeconds: Long,
            voteEndTimeSeconds: Long
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun closeRoundSessionNative(sessionHandle: Long)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun cancelRoundSessionNative(sessionHandle: Long)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun setOperationEpochNative(sessionHandle: Long, operationEpoch: Long)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getRoundPlanNative(sessionHandle: Long): JniRoundPlan?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun setBallotIntentsNative(
            sessionHandle: Long,
            proposalIds: IntArray,
            choices: IntArray
        ): JniRoundPlan?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun runRoundNative(
            sessionHandle: Long,
            torRuntime: Long,
            delegationInputs: JniDelegationInputs?
        ): JniRoundRunReport?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getKeystoneSigningRequestsNative(
            sessionHandle: Long,
            bundleIndices: IntArray
        ): Array<JniKeystoneSigningRequest>?
    }
}
