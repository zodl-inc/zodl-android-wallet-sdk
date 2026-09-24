package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.VotingDbSession
import cash.z.ecc.android.sdk.VotingRoundSession
import cash.z.ecc.android.sdk.VotingSdk
import cash.z.ecc.android.sdk.VotingShareTrackingSession
import cash.z.ecc.android.sdk.internal.model.voting.RoundDriveProgressListener
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
import cash.z.ecc.android.sdk.model.voting.VotingPirPrecomputeResult
import cash.z.ecc.android.sdk.model.voting.VotingProposalRosterEntry
import cash.z.ecc.android.sdk.model.voting.VotingRoundDriveProgressListener
import cash.z.ecc.android.sdk.model.voting.VotingRoundPlan
import cash.z.ecc.android.sdk.model.voting.VotingRoundRunReport
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary
import cash.z.ecc.android.sdk.model.voting.VotingShareTrackingReport
import cash.z.ecc.android.sdk.model.voting.VotingSnapshotBundlePrecomputeReport
import cash.z.ecc.android.sdk.model.voting.VotingWitness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The raw JNI layer's sentinel for "not yet known" on a nullable-in-the-crate timing field
 * (`ceremonyStartSeconds`/`voteEndTimeSeconds`) -- this public API models those as a nullable
 * `Long` instead, translated here at the boundary.
 */
private const val UNKNOWN_TIME_SECONDS = -1L

/**
 * The raw JNI layer's sentinel for a skipped ballot decision on `setBallotIntentsNative`'s
 * `choices` parameter -- this public API models a skip as `VotingBallotIntent.choice == null`
 * instead, translated here at the boundary.
 */
private const val SKIPPED_BALLOT_CHOICE = -1

@Suppress("TooManyFunctions")
internal class VotingSdkImpl(
    private val backend: TypesafeVotingBackend = TypesafeVotingBackendImpl()
) : VotingSdk {
    private val isAvailableMutex = Mutex()

    @Volatile
    private var cachedIsAvailable: Boolean? = null

    // Probing availability starts the crate's background Halo2 proving-cache warm-up as a side
    // effect (fire-and-forget: `warmProvingCaches` returns as soon as the crate has spawned its
    // own warm-up thread, or immediately if a warm-up was already started elsewhere in the
    // process -- the crate deduplicates internally). The result is still cached here rather than
    // re-probed on every call: once the native boundary is known to resolve it will keep
    // resolving for the rest of the process, so there is no reason to keep paying a JNI round
    // trip for `isAvailable()`'s own documented memoization contract. Any failure -- not just
    // UnsatisfiedLinkError -- means unavailable: NativeLibraryLoader wraps a failed
    // System.loadLibrary in AssertionError, not UnsatisfiedLinkError, so a
    // `!is UnsatisfiedLinkError` check would previously report "available" for exactly the
    // missing-native-library case this gate exists to catch.
    override suspend fun isAvailable(): Boolean =
        cachedIsAvailable ?: isAvailableMutex.withLock {
            cachedIsAvailable ?: runCatching { backend.warmProvingCaches() }
                .onFailure { if (it is CancellationException) throw it }
                .isSuccess
                .also { cachedIsAvailable = it }
        }

    override suspend fun openDb(dbPath: String, walletId: String, networkId: Int): VotingDbSession =
        VotingDbSessionImpl(backend.openVotingDb(dbPath, walletId, networkId))

    override suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray = backend.computeShareNullifier(voteCommitment, shareIndex, blind)

    override suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): VotingBundleSetupResult =
        backend.computeBundleSetup(notes.map { it.toInternal() }).toPublic()

    override suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: BlockHeight,
        networkId: Int,
        accountUuid: AccountUuid
    ): List<VotingNoteInfo> =
        backend
            .getWalletNotes(walletDbPath, snapshotHeight.value, networkId, accountUuid.value)
            .map { it.toPublic() }

    override suspend fun warmProvingCaches() = backend.warmProvingCaches()

    override suspend fun configureVoting() = backend.configureVoting()

    override suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long = backend.scheduledShareSubmitAt(nowSeconds, ceremonyStartSeconds, voteEndTimeSeconds, singleShare)

    override suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray =
        backend.extractOrchardFvkFromUfvk(ufvk, networkId)

    override suspend fun deriveHotkeyRawAddress(hotkeySeed: ByteArray, networkId: Int): ByteArray =
        backend.deriveHotkeyRawAddress(hotkeySeed, networkId)

    override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray = backend.extractNcRoot(treeStateBytes)

    override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray = backend.extractPcztSighash(pcztBytes)

    override suspend fun extractSpendAuthSig(signedPcztBytes: ByteArray, actionIndex: Int): ByteArray =
        backend.extractSpendAuthSig(signedPcztBytes, actionIndex)

    override suspend fun verifyWitness(witness: VotingWitness): Boolean = backend.verifyWitness(witness.toInternal())
}

@Suppress("TooManyFunctions", "LongParameterList")
internal class VotingDbSessionImpl(
    private val db: TypesafeVotingDb
) : VotingDbSession {
    override suspend fun close() = db.close()

    override suspend fun getRoundState(roundId: String): VotingRoundState? = db.getRoundState(roundId)?.toPublic()

    override suspend fun listRounds(): List<VotingRoundSummary> = db.listRounds().map { it.toPublic() }

    override suspend fun getBundleCount(roundId: String): Int = db.getBundleCount(roundId)

    override suspend fun clearRound(roundId: String) = db.clearRound(roundId)

    override suspend fun deleteSkippedBundles(roundId: String, keepCount: Int): Long =
        db.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(roundId: String, notes: List<VotingNoteInfo>): VotingBundleSetupResult =
        db.setupBundles(roundId, notes.map { it.toInternal() }).toPublic()

    override suspend fun ensureRound(
        roundId: String,
        anchorTreeStateBytes: ByteArray,
        snapshotHeight: Long,
        eaPk: ByteArray,
        ncRoot: ByteArray,
        nullifierImtRoot: ByteArray
    ) = db.ensureRound(roundId, anchorTreeStateBytes, snapshotHeight, eaPk, ncRoot, nullifierImtRoot)

    override suspend fun generateHotkey(storedSecret: ByteArray): VotingHotkey =
        db.generateHotkey(storedSecret).toPublic()

    override suspend fun precomputeDelegationPir(
        torRuntime: Long,
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        pirPolyLen: Int,
        notes: List<VotingNoteInfo>
    ): VotingDelegationPirPrecomputeResult =
        db
            .precomputeDelegationPir(
                torRuntime,
                roundId,
                bundleIndex,
                pirServerUrl,
                pirDepth,
                pirTier0Layers,
                pirTier1Layers,
                pirPolyLen,
                notes.map { it.toInternal() }
            ).toPublic()

    override suspend fun precomputePirProofs(
        torRuntime: Long,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        pirPolyLen: Int,
        notes: List<VotingNoteInfo>
    ): VotingPirPrecomputeResult =
        db
            .precomputePirProofs(
                torRuntime,
                pirServerUrl,
                pirDepth,
                pirTier0Layers,
                pirTier1Layers,
                pirPolyLen,
                notes.map { it.toInternal() }
            ).toPublic()

    override suspend fun precomputeSnapshotBundles(
        torRuntime: Long,
        roundId: String,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        pirPolyLen: Int,
        notes: List<VotingNoteInfo>
    ): VotingSnapshotBundlePrecomputeReport =
        db
            .precomputeSnapshotBundles(
                torRuntime,
                roundId,
                pirServerUrl,
                pirDepth,
                pirTier0Layers,
                pirTier1Layers,
                pirPolyLen,
                notes.map { it.toInternal() }
            ).toPublic()

    override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long = db.syncVoteTree(roundId, nodeUrl)

    override suspend fun resetTreeClient(roundId: String) = db.resetTreeClient(roundId)

    override suspend fun resetAllTreeClients() = db.resetAllTreeClients()

    override suspend fun resetVotingSessionState(roundId: String) = db.resetVotingSessionState(roundId)

    override suspend fun storeKeystoneSignatures(
        roundId: String,
        signatures: List<VotingKeystoneSignatureInput>
    ): VotingKeystoneSignatureBatchResult =
        db.storeKeystoneSignatures(roundId, signatures.map { it.toInternal() }).toPublic()

    override suspend fun getKeystoneSignatures(roundId: String): List<VotingKeystoneSignatureRecord> =
        db.getKeystoneSignatures(roundId).map { it.toPublic() }

    override suspend fun openShareTrackingSession(roundId: String): VotingShareTrackingSession =
        VotingShareTrackingSessionImpl(db.openShareTrackingSession(roundId))

    override suspend fun openRoundSession(
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
    ): VotingRoundSession =
        VotingRoundSessionImpl(
            session =
                db.openRoundSession(
                    torRuntime = torRuntime,
                    roundId = roundId,
                    proposalIds = proposals.map { it.proposalId }.toIntArray(),
                    proposalOptionCounts = proposals.map { it.numOptions }.toIntArray(),
                    hotkeySecret = hotkeySecret,
                    chainEndpoints = chainEndpoints,
                    operationEpoch = operationEpoch,
                    configuredHelperUrls = configuredHelperUrls,
                    voteTreeNodeUrls = voteTreeNodeUrls,
                    ceremonyStartSeconds = ceremonyStartSeconds ?: UNKNOWN_TIME_SECONDS,
                    voteEndTimeSeconds = voteEndTimeSeconds ?: UNKNOWN_TIME_SECONDS
                ),
            torRuntime = torRuntime
        )
}

/**
 * [torRuntime] is captured once, from the same value [VotingDbSession.openRoundSession] was
 * called with, and reused for every [run] call on this session -- [run]'s own `torRuntime`
 * parameter (`runRoundNative`'s) exists only to drive `RoundDriver::run`'s future synchronously
 * from the JNI call (see that native function's doc comment); the session's own Tor-backed
 * transport, wired once at session-open time, is what actually dispatches chain/helper traffic.
 * There is no known reason for a caller to want a different handle for the two, so this public
 * API does not ask for one twice.
 */
internal class VotingRoundSessionImpl(
    private val session: TypesafeRoundSession,
    private val torRuntime: Long
) : VotingRoundSession {
    override suspend fun close() = session.close()

    override suspend fun cancel() = session.cancel()

    override suspend fun setOperationEpoch(operationEpoch: Long) = session.setOperationEpoch(operationEpoch)

    override suspend fun plan(): VotingRoundPlan? = session.getRoundPlan()?.toPublic()

    override suspend fun setBallotIntents(intents: List<VotingBallotIntent>): VotingRoundPlan? {
        val proposalIds = intents.map { it.proposalId }.toIntArray()
        val choices = intents.map { it.choice ?: SKIPPED_BALLOT_CHOICE }.toIntArray()
        return session.setBallotIntents(proposalIds, choices)?.toPublic()
    }

    override suspend fun run(
        delegationInputs: VotingDelegationInputs?,
        progressListener: VotingRoundDriveProgressListener?
    ): VotingRoundRunReport? =
        session
            .runRound(
                torRuntime,
                delegationInputs?.toInternal(session.dbHandle),
                progressListener?.let { listener ->
                    RoundDriveProgressListener { _, detail -> listener.onProgress(parseRoundDriveProgress(detail)) }
                }
            )?.toPublic()

    override suspend fun getKeystoneSigningRequests(bundleIndices: List<Int>): List<VotingKeystoneSigningRequest> =
        session.getKeystoneSigningRequests(bundleIndices.toIntArray()).map { it.toPublic() }
}

internal class VotingShareTrackingSessionImpl(
    private val session: TypesafeShareTrackingSession
) : VotingShareTrackingSession {
    override suspend fun close() = session.close()

    override suspend fun cancel() = session.cancel()

    override suspend fun run(
        torRuntime: Long,
        helperUrls: List<String>,
        voteEndTimeSeconds: Long
    ): VotingShareTrackingReport? = session.run(torRuntime, helperUrls, voteEndTimeSeconds)?.toPublic()
}
