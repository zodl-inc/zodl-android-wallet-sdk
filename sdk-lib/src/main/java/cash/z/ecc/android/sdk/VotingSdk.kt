package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.voting.VotingBallotIntent
import cash.z.ecc.android.sdk.model.voting.VotingBundleSetupResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationInputs
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.model.voting.VotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSignatureBatchResult
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSignatureInput
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSignatureRecord
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSigningRequest
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingProposalRosterEntry
import cash.z.ecc.android.sdk.model.voting.VotingRoundPlan
import cash.z.ecc.android.sdk.model.voting.VotingRoundRunReport
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary
import cash.z.ecc.android.sdk.model.voting.VotingShareTrackingReport
import cash.z.ecc.android.sdk.model.voting.VotingWitness

/**
 * The public SDK entry point for shielded voting (CHP). The only sanctioned path from the app
 * into the voting Rust backend — see `VotingRustBackend`'s doc comment for the enforcement story.
 *
 * DB-independent crypto operations live directly here; anything scoped to one round's on-disk
 * state is behind [openDb]'s [VotingDbSession]; anything scoped to one round's `RoundExecutor`/
 * `RoundDriver` session (plan, ballot intents, drive-to-quiescence) is behind
 * [VotingDbSession.openRoundSession]'s [VotingRoundSession].
 *
 * This is the voting-4.0.0 SDK port's session/plan/run surface, replacing the pre-4.0
 * caller-drives-every-step interface (hand-rolled PCZT construction, per-share recovery
 * bookkeeping, ...) with one backed end-to-end by `zcash_voting`'s own `RoundExecutor`/
 * `RoundDriver`/`DelegationPipeline`/`ShareTrackingDriver` — see `VotingRoundSession.run`'s doc
 * comment for how round bootstrap works (a real, previously-empirically-confirmed gap here was
 * fixed post-Task-10; that fix's history is worth reading if you're touching this surface).
 */
@Suppress("TooManyFunctions")
interface VotingSdk {
    /**
     * True if this build's native library actually exports the voting JNI symbols. Callers must
     * check this before any other call — a mismatch between the app's runtime feature flag and
     * how this SDK artifact was compiled otherwise surfaces as an [UnsatisfiedLinkError] (or,
     * if the whole native library failed to load, an [AssertionError]) crash instead of a
     * graceful no-op. The implementation memoizes its result after the first call for the
     * lifetime of this [VotingSdk] instance, so repeated calls are cheap; only the first call
     * pays the cost of warming the native proving caches (its implementation calls
     * [warmProvingCaches]) or of a failed native-library probe. Does not open a database or
     * touch the network.
     */
    suspend fun isAvailable(): Boolean

    /** Opens (creating if needed) the round database at [dbPath], scoped to one wallet/network. */
    suspend fun openDb(dbPath: String, walletId: String, networkId: Int): VotingDbSession

    suspend fun computeShareNullifier(voteCommitment: ByteArray, shareIndex: Int, blind: ByteArray): ByteArray

    suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): VotingBundleSetupResult

    suspend fun warmProvingCaches()

    /**
     * Fixes the process-wide proving-pool policy (`max_active_heavy_jobs: 1`) once at startup,
     * before any voting round work. Independent of [VotingRoundSession.run]'s own per-dispatch
     * `max_proof_concurrency` bound — see `VotingRustBackend.configureVoting`'s doc comment in
     * `backend-lib/src/main/rust/voting/util.rs` for why both matter.
     */
    suspend fun configureVoting()

    /**
     * Computes when a delegated helper share should submit, honoring the ceremony's last-moment
     * buffer window. Returns unix seconds; `0` means "submit immediately".
     */
    suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long

    suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray

    /**
     * Derives the raw Orchard address for the voting hotkey. The hotkey account index is fixed
     * by the Rust voting backend to match the vote-signing path — do not add an `accountIndex`
     * parameter unless that path changes with it.
     */
    suspend fun deriveHotkeyRawAddress(hotkeySeed: ByteArray, networkId: Int): ByteArray

    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray

    suspend fun verifyWitness(witness: VotingWitness): Boolean

    companion object {
        /**
         * Constructs the real, Rust-backed [VotingSdk]. No Android [android.content.Context] is needed at
         * this layer.
         */
        fun new(): VotingSdk =
            cash.z.ecc.android.sdk.internal
                .VotingSdkImpl()
    }
}

/** One open round database. Callers must [close] it when done. */
@Suppress("TooManyFunctions", "LongParameterList")
interface VotingDbSession {
    suspend fun close()

    suspend fun getRoundState(roundId: String): VotingRoundState?

    suspend fun listRounds(): List<VotingRoundSummary>

    suspend fun getBundleCount(roundId: String): Int

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(roundId: String, keepCount: Int): Long

    suspend fun setupBundles(roundId: String, notes: List<VotingNoteInfo>): VotingBundleSetupResult

    /**
     * Bootstraps (or validates) [roundId]'s `rounds` row from caller-supplied round metadata,
     * via `zcash_voting::DelegationPipeline::ensure_round`. Required before [setupBundles] or a
     * delegation-enabled [VotingRoundSession.run] call can do anything for a round that has
     * never been through this call before — see [VotingRoundSession.run]'s doc comment for why
     * neither of those alone can create this row for a virgin [roundId]: `RoundDriver::run`
     * never proposes delegation work until bundle rows exist, and bundle rows cannot be created
     * until the round row itself exists. Idempotent: an already-bootstrapped round with
     * matching params is a no-op; one with different params fails loudly, since the stored
     * params bind every bundle/proof already built against them.
     */
    suspend fun ensureRound(
        roundId: String,
        anchorTreeStateBytes: ByteArray,
        snapshotHeight: Long,
        eaPk: ByteArray,
        ncRoot: ByteArray,
        nullifierImtRoot: ByteArray
    )

    /**
     * Mints or reconstructs a voting hotkey. An empty [storedSecret] mints a fresh, app-owned
     * random hotkey; a previously persisted [VotingHotkey.storedSecret] deterministically
     * reconstructs the same hotkey. Not scoped to a round.
     */
    suspend fun generateHotkey(storedSecret: ByteArray): VotingHotkey

    suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        pirPolyLen: Int,
        notes: List<VotingNoteInfo>
    ): VotingDelegationPirPrecomputeResult

    suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long

    suspend fun resetTreeClient(roundId: String)

    suspend fun resetAllTreeClients()

    /**
     * Clears unsigned delegation setup fields for every bundle in [roundId] that has neither a
     * submitted delegation tx nor a persisted Keystone signature, so a subsequent construct
     * call starts clean. Does not delete round-level state.
     */
    suspend fun resetVotingSessionState(roundId: String)

    /**
     * Atomically stores a batch of Keystone-signed delegation bundle signatures so a later
     * round-wide [resetVotingSessionState] preserves those bundles instead of wiping their
     * unsigned setup fields for a rebuild. Pass `rk`/`sighash` already verified by a prior
     * [VotingRoundSession.getKeystoneSigningRequests]-driven signing flow, not arbitrary
     * caller-supplied values — this call does not itself re-verify the signature.
     */
    suspend fun storeKeystoneSignatures(
        roundId: String,
        signatures: List<VotingKeystoneSignatureInput>
    ): VotingKeystoneSignatureBatchResult

    suspend fun getKeystoneSignatures(roundId: String): List<VotingKeystoneSignatureRecord>

    /**
     * Drives [roundId]'s unconfirmed helper shares to confirmation with a `ShareTrackingDriver`,
     * repeating passes until the round's shares are quiescent. Standalone and session-less:
     * unlike [VotingRoundSession.run], a separate call cannot cancel an in-flight [trackShares]
     * run mid-pass.
     *
     * [torRuntime] is the caller's raw native Tor-runtime handle (the same one an app's
     * [cash.z.ecc.android.sdk.internal.model.TorClient] instance uses for its own HTTP
     * dispatch) — this SDK exposes no public accessor for it today; resolving that for a real
     * caller is the app-side companion plan's job, not this port's.
     *
     * [voteEndTimeSeconds] `< 0` decodes to "no vote-end boundary known yet".
     */
    suspend fun trackShares(
        roundId: String,
        torRuntime: Long,
        helperUrls: List<String>,
        voteEndTimeSeconds: Long
    ): VotingShareTrackingReport

    /**
     * Opens a round session: binds a `RoundExecutor` to [roundId]'s roster and hotkey, wires its
     * chain-submission and helper transports through [torRuntime], and registers it. Callers
     * must [VotingRoundSession.close] it when done.
     *
     * [hotkeySecret] may be `null` before a hotkey is bound. [ceremonyStartSeconds]/
     * [voteEndTimeSeconds] `null` decode to "not yet known". See [trackShares]'s doc comment for
     * [torRuntime]'s caveat.
     */
    @Suppress("LongParameterList")
    suspend fun openRoundSession(
        torRuntime: Long,
        roundId: String,
        proposals: List<VotingProposalRosterEntry>,
        hotkeySecret: ByteArray?,
        chainEndpoints: List<String>,
        operationEpoch: Long,
        configuredHelperUrls: List<String>,
        voteTreeNodeUrls: List<String>,
        ceremonyStartSeconds: Long?,
        voteEndTimeSeconds: Long?
    ): VotingRoundSession
}

/**
 * A round's `RoundExecutor`/`RoundDriver` session — plan, ballot intents, drive-to-quiescence,
 * and the Keystone signing requests a delegation-enabled drive produces. Callers must [close]
 * it when done.
 */
interface VotingRoundSession {
    suspend fun close()

    /**
     * Cancels an in-flight [run]. Has no implicit effect on its own — a caller with a [run] call
     * in flight must call this first if it wants that run to stop early; [close] alone does not
     * cancel one.
     */
    suspend fun cancel()

    suspend fun setOperationEpoch(operationEpoch: Long)

    suspend fun plan(): VotingRoundPlan?

    /**
     * Records ballot decisions and returns the refreshed plan.
     */
    suspend fun setBallotIntents(intents: List<VotingBallotIntent>): VotingRoundPlan?

    /**
     * Drives this session's round to quiescence with a `RoundDriver`.
     *
     * [delegationInputs] must be non-null for a pass that needs to advance delegation signing;
     * every other step tolerates `null`.
     *
     * **A virgin round needs [VotingDbSession.ensureRound] + [VotingDbSession.setupBundles]
     * called first — [run] alone, even with real [delegationInputs], cannot bootstrap one.**
     * This was empirically verified on-device while fixing the round-bootstrap bug below: the
     * first fix (removing a premature `rounds`-table read from the native side) turned out to
     * be necessary but not sufficient. Reading `zcash_voting::round_planning::classify` directly
     * shows why: `RoundDriver::run`'s planner derives delegation obligations from a
     * `DelegationPhase` snapshot over *persisted* `bundles` rows — with zero bundle rows (a
     * virgin round), it proposes zero `Delegate`/`AdvanceDelegation` steps, no matter what
     * [delegationInputs] carries, so `DelegationPipeline::execute_prepare`'s own internal
     * bootstrap call is never reached from here. And bundle rows themselves cannot be created
     * (`setupBundles`'s insert has a foreign key on `rounds`) until the round row exists. The
     * correct sequence for a virgin round is: [VotingDbSession.ensureRound] (creates the round
     * row from caller-supplied metadata, bypassing that circularity via the crate's own
     * standalone `DelegationPipeline::ensure_round`) → [VotingDbSession.setupBundles] (creates
     * bundle rows now that the round exists) → [run] (which can now genuinely plan and dispatch
     * delegation work).
     *
     * **The `load_round_params` bug itself, fixed (voting-4.0.0 SDK port):** the native side
     * (`backend-lib/src/main/rust/voting/delegation_driver.rs`'s `delegation_step_inputs_from_jni`)
     * used to read the round's `VotingRoundParams` back from the `rounds` table via
     * `load_round_params` immediately when [delegationInputs] was non-null, unconditionally
     * before ever constructing a `DelegationPipeline` — so a virgin round was rejected with
     * "round not found" before anything else ran. [VotingDelegationInputs] now carries the
     * round's `snapshotHeight`/`eaPk`/`ncRoot`/`nullifierImtRoot` directly (the caller already
     * has these from the same authenticated round config used to fetch `anchorTreeStateBytes`
     * and to call [VotingDbSession.ensureRound]), and the native side builds `VotingRoundParams`
     * from them instead of reading a row. `VotingDb::ensure_round` still validates a
     * pre-existing round's stored params against these on every call, so passing them is safe
     * whether the round is new or already bootstrapped.
     */
    suspend fun run(delegationInputs: VotingDelegationInputs? = null): VotingRoundRunReport?

    /**
     * The Keystone signing requests for [bundleIndices], from the delegation pipeline a prior
     * delegation-enabled [run] call built and cached on this session. Fails if no delegation
     * pipeline is cached yet.
     */
    suspend fun getKeystoneSigningRequests(bundleIndices: List<Int>): List<VotingKeystoneSigningRequest>
}
