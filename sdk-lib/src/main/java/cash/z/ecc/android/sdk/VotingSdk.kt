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
 * comment for the one open architectural gap this port left (round bootstrap).
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
     * **Known gap, confirmed empirically (Task 10 of the voting-4.0.0 SDK port):** a
     * delegation-enabled call (real, non-null [delegationInputs]) does **not** bootstrap a
     * virgin round, even though `getKeystoneSigningRequestsNative`'s own doc comment describes
     * this as the intended main-line first step for a round. `zcash_voting`'s own bootstrap
     * mechanism (`DelegationPipeline`'s `prepare_delegation_bundle_inner` calling
     * `observe_ensure_round_context`, which calls `VotingDb::ensure_round_state`) genuinely
     * exists in the crate, but the current JNI wiring
     * (`backend-lib/src/main/rust/voting/delegation_driver.rs`'s
     * `delegation_step_inputs_from_jni`) calls `load_round_params` — a hard `SELECT` against the
     * `rounds` table — immediately after decoding `delegation_inputs` and unconditionally before
     * ever constructing the `DelegationPipeline`, so an unknown `round_id` is rejected with
     * "round not found" before `RoundDriver::run` is even entered. There is currently no
     * JNI-exposed way to create a round's row for a brand-new `round_id` — the pre-4.0
     * `initRoundNative` that used to do this was removed, and neither this call nor
     * [VotingDbSession.openRoundSession]'s `RoundBinding` supplies the round's
     * `snapshot_height`/`ea_pk`/`nc_root`/`nullifier_imt_root` fields the crate's bootstrap path
     * needs. Callers must ensure a round row already exists through some other means before
     * calling this with non-null [delegationInputs]; this SDK does not yet expose one.
     */
    suspend fun run(delegationInputs: VotingDelegationInputs? = null): VotingRoundRunReport?

    /**
     * The Keystone signing requests for [bundleIndices], from the delegation pipeline a prior
     * delegation-enabled [run] call built and cached on this session. Fails if no delegation
     * pipeline is cached yet.
     */
    suspend fun getKeystoneSigningRequests(bundleIndices: List<Int>): List<VotingKeystoneSigningRequest>
}
