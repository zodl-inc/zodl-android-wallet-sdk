package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationInputs
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureBatchResult
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureInput
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSigningRequest
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPlan
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundRunReport
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniShareTrackingRunReport
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData

@Suppress("TooManyFunctions", "LongParameterList")
internal interface TypesafeVotingBackend {
    suspend fun openVotingDb(dbPath: String, walletId: String, networkId: Int): TypesafeVotingDb

    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray

    suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): JniBundleSetupResult

    suspend fun warmProvingCaches()

    /**
     * Fixes the process-wide proving-pool policy once at startup, before any voting round
     * work. See `VotingRustBackend.configureVoting`'s doc comment.
     */
    suspend fun configureVoting()

    /**
     * Computes when a delegated helper share should submit, honoring the ceremony's
     * last-moment buffer window. A passthrough to the Rust backend, which sources its own
     * entropy. Returns unix seconds; `0` means "submit immediately".
     */
    suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long

    suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray

    /**
     * Derives the raw Orchard address for the voting hotkey.
     *
     * The hotkey account index is intentionally fixed by the Rust voting backend to match the
     * vote-signing path. Do not add an `accountIndex` parameter unless that path changes with it;
     * otherwise delegation can be built for a hotkey that later vote construction cannot sign for.
     */
    suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int
    ): ByteArray

    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray

    suspend fun verifyWitness(witness: JniWitnessData): Boolean

    /**
     * Reads [accountUuid]'s voting-eligible note plaintexts from the MAIN wallet database at
     * [walletDbPath], as of the historical [snapshotHeight]. See
     * `VotingRustBackend.getWalletNotes`'s doc comment for why this is a narrower re-addition
     * of the deleted pre-4.0 `getWalletNotesNative`, scoped to [computeBundleSetup]/
     * [TypesafeVotingDb.setupBundles]'s bundle-setup-time need only.
     */
    suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuid: ByteArray
    ): List<VotingNoteInfo>
}

@Suppress("TooManyFunctions", "LongParameterList")
internal interface TypesafeVotingDb {
    suspend fun close()

    suspend fun getRoundState(roundId: String): JniRoundState?

    suspend fun listRounds(): List<JniRoundSummary>

    suspend fun getBundleCount(roundId: String): Int

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long

    suspend fun setupBundles(
        roundId: String,
        notes: List<VotingNoteInfo>
    ): JniBundleSetupResult

    /**
     * Bootstraps (or validates) [roundId]'s `rounds` row from caller-supplied round metadata,
     * via `zcash_voting::DelegationPipeline::ensure_round`. Required before [setupBundles] or a
     * delegation-enabled [TypesafeRoundSession.runRound] call can do anything for a round that
     * has never been through this call before — see `ensureRoundNative`'s doc comment for why
     * neither of those alone can create this row for a virgin `roundId`. Idempotent: an
     * already-bootstrapped round with matching params is a no-op; one with different params
     * fails loudly.
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
     * Mints or reconstructs a voting hotkey.
     *
     * An empty [storedSecret] mints a fresh, app-owned random hotkey; a previously persisted
     * [JniVotingHotkey.storedSecret] deterministically reconstructs the same hotkey. This call
     * is not scoped to a round.
     */
    suspend fun generateHotkey(storedSecret: ByteArray): JniVotingHotkey

    suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        pirPolyLen: Int,
        notes: List<VotingNoteInfo>
    ): DelegationPirPrecomputeResult

    suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long

    suspend fun resetTreeClient(roundId: String)

    suspend fun resetAllTreeClients()

    /**
     * Clears unsigned delegation setup fields (PCZT/rk/sighash) for every bundle in [roundId]
     * that has neither a submitted delegation tx nor a persisted Keystone signature — see the
     * crate's `clear_unsigned_delegation_setup_fields` for the exact predicate — so a subsequent
     * construct call starts clean. Does not delete round-level state.
     */
    suspend fun resetVotingSessionState(roundId: String)

    /**
     * Atomically stores a batch of Keystone-signed delegation bundle signatures so a later
     * round-wide [resetVotingSessionState] preserves those bundles instead of wiping their
     * unsigned setup fields for a rebuild. Pass `rk`/`sighash` already verified by a prior
     * [TypesafeRoundSession.getKeystoneSigningRequests]-driven signing flow, not arbitrary
     * caller-supplied values — this call does not itself re-verify the signature.
     */
    suspend fun storeKeystoneSignatures(
        roundId: String,
        signatures: List<JniKeystoneSignatureInput>
    ): JniKeystoneSignatureBatchResult

    suspend fun getKeystoneSignatures(roundId: String): List<JniKeystoneSignatureRecord>

    /**
     * Drives [roundId]'s unconfirmed helper shares to confirmation, repeating passes until the
     * round's shares are quiescent. See `VotingRustBackend.VotingDb.trackShares`'s doc comment.
     */
    suspend fun trackShares(
        roundId: String,
        torRuntime: Long,
        helperUrls: List<String>,
        voteEndTimeSeconds: Long
    ): JniShareTrackingRunReport

    /**
     * Opens a round session bound to [roundId]'s roster and hotkey. See
     * `VotingRustBackend.VotingDb.openRoundSession`'s doc comment.
     */
    @Suppress("LongParameterList")
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
    ): TypesafeRoundSession
}

/**
 * A round's `RoundExecutor`/`RoundDriver` session — plan, ballot intents, drive-to-quiescence,
 * and the Keystone signing requests a delegation-enabled drive produces. See
 * `VotingRustBackend.RoundSession`'s doc comment.
 */
@Suppress("TooManyFunctions")
internal interface TypesafeRoundSession {
    /**
     * The raw JNI database handle of the [TypesafeVotingDb] this session was opened against.
     *
     * Added by Task 10 (voting-4.0.0 SDK port) so a delegation-enabled [runRound] call's caller
     * can build [JniDelegationInputs.dbHandle] without a second, independently-tracked
     * reference to the same database — `RoundSessionHandle` on the Rust side does not retain a
     * database reference of its own (see [JniDelegationInputs]'s doc comment), so the session's
     * originating handle, captured once at [TypesafeVotingDb.openRoundSession] time, is the only
     * place this value is available.
     */
    val dbHandle: Long

    suspend fun close()

    suspend fun cancel()

    suspend fun setOperationEpoch(operationEpoch: Long)

    suspend fun getRoundPlan(): JniRoundPlan?

    /**
     * Records ballot decisions and returns the refreshed plan. `choices[i] < 0` decodes to a
     * skipped decision for `proposalIds[i]`.
     */
    suspend fun setBallotIntents(
        proposalIds: IntArray,
        choices: IntArray
    ): JniRoundPlan?

    /**
     * Drives this session's round to quiescence. [delegationInputs] must be non-null for a pass
     * that needs to advance delegation signing; every other step tolerates `null`.
     */
    suspend fun runRound(
        torRuntime: Long,
        delegationInputs: JniDelegationInputs?
    ): JniRoundRunReport?

    /**
     * The Keystone signing requests for [bundleIndices], from the delegation pipeline a prior
     * delegation-enabled [runRound] call built and cached on this session. Fails if no
     * delegation pipeline is cached yet.
     */
    suspend fun getKeystoneSigningRequests(bundleIndices: IntArray): List<JniKeystoneSigningRequest>
}

/**
 * The typesafe view of a spendable note the voting backend may draw voting weight from.
 *
 * [toString] is redacted: [rseed] and [rho] reconstruct the note's spending randomness,
 * [nullifier] links the note to its spend, and [ufvk] is a full viewing key that discloses
 * the entire account's transaction history. The generated `data class` rendering would print
 * all four into any log line that interpolates a note.
 */
internal data class VotingNoteInfo(
    val commitment: ByteArray,
    val nullifier: ByteArray,
    val value: Long,
    val position: Long,
    val diversifier: ByteArray,
    val rho: ByteArray,
    val rseed: ByteArray,
    val scope: VotingNoteScope,
    val ufvk: String
) {
    override fun toString(): String = "VotingNoteInfo(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingNoteInfo) return false
        return commitment.contentEquals(other.commitment) &&
            nullifier.contentEquals(other.nullifier) &&
            value == other.value &&
            position == other.position &&
            diversifier.contentEquals(other.diversifier) &&
            rho.contentEquals(other.rho) &&
            rseed.contentEquals(other.rseed) &&
            scope == other.scope &&
            ufvk == other.ufvk
    }

    override fun hashCode(): Int {
        var result = commitment.contentHashCode()
        result = 31 * result + nullifier.contentHashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + diversifier.contentHashCode()
        result = 31 * result + rho.contentHashCode()
        result = 31 * result + rseed.contentHashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + ufvk.hashCode()
        return result
    }
}

internal enum class VotingNoteScope(
    val jniValue: Int
) {
    EXTERNAL(0),
    INTERNAL(1);

    companion object {
        fun fromJniValue(value: Int) =
            entries.firstOrNull { it.jniValue == value }
                ?: error("Unknown voting note scope: $value")
    }
}

internal data class DelegationPirPrecomputeResult(
    val cachedCount: Long,
    val fetchedCount: Long
)
