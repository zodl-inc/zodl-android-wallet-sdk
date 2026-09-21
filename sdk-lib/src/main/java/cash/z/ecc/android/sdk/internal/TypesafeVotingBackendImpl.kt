// VotingRustBackend is deprecated at ERROR level because its native symbols are not
// built in this release. This file is the internal wrapper around that backend, so it
// necessarily references it; the suppression is scoped here rather than relaxing the
// deprecation, which is what keeps the failure a compile error for consumers.
@file:Suppress("TooManyFunctions", "DEPRECATION_ERROR")

package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_RAW_ADDRESS_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_STORED_SECRET_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.VotingRustBackend
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
import cash.z.ecc.android.sdk.internal.model.voting.RoundDriveProgressListener

@Suppress("TooManyFunctions", "LongParameterList")
internal class TypesafeVotingBackendImpl(
    private val rustBackendFactory: suspend () -> VotingBackendBridge = {
        RustVotingBackendBridge(VotingRustBackend.new())
    }
) : TypesafeVotingBackend {
    private val rustBackendLazy =
        SuspendingLazy<Unit, VotingBackendBridge> {
            rustBackendFactory()
        }

    override suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray =
        rustBackend().computeShareNullifier(voteCommitment, shareIndex, blind)

    override suspend fun openVotingDb(dbPath: String, walletId: String, networkId: Int): TypesafeVotingDb =
        TypesafeVotingDbImpl(
            rustBackend().openVotingDb(dbPath, walletId, networkId)
        )

    override suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): JniBundleSetupResult =
        rustBackend().computeBundleSetup(notes.toJniNoteInfos())

    override suspend fun warmProvingCaches() =
        rustBackend().warmProvingCaches()

    override suspend fun configureVoting() =
        rustBackend().configureVoting()

    override suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long =
        rustBackend().scheduledShareSubmitAt(
            nowSeconds,
            ceremonyStartSeconds,
            voteEndTimeSeconds,
            singleShare
        )

    override suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray =
        rustBackend().extractOrchardFvkFromUfvk(ufvk, networkId)

    override suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int
    ): ByteArray =
        rustBackend().deriveHotkeyRawAddress(hotkeySeed, networkId)

    override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray =
        rustBackend().extractNcRoot(treeStateBytes)

    override suspend fun verifyWitness(witness: JniWitnessData): Boolean =
        rustBackend().verifyWitness(witness)

    override suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuid: ByteArray
    ): List<VotingNoteInfo> =
        rustBackend()
            .getWalletNotes(walletDbPath, snapshotHeight, networkId, accountUuid)
            .map { it.toVotingNoteInfo() }

    private suspend fun rustBackend() = rustBackendLazy.getInstance(Unit)
}

internal interface VotingBackendBridge {
    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray

    suspend fun openVotingDb(dbPath: String, walletId: String, networkId: Int): VotingDbBackend

    suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult

    suspend fun warmProvingCaches()

    suspend fun configureVoting()

    suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long

    suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray

    suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int
    ): ByteArray

    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray

    suspend fun verifyWitness(witness: JniWitnessData): Boolean

    suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuid: ByteArray
    ): List<JniNoteInfo>
}

private class RustVotingBackendBridge(
    private val rustBackend: VotingRustBackend
) : VotingBackendBridge {
    override suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray =
        rustBackend.computeShareNullifier(voteCommitment, shareIndex, blind)

    override suspend fun openVotingDb(dbPath: String, walletId: String, networkId: Int): VotingDbBackend =
        RustVotingDbBackend(rustBackend.openVotingDb(dbPath, walletId, networkId))

    override suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult =
        rustBackend.computeBundleSetup(notes)

    override suspend fun warmProvingCaches() =
        rustBackend.warmProvingCaches()

    override suspend fun configureVoting() =
        rustBackend.configureVoting()

    override suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long =
        rustBackend.scheduledShareSubmitAt(
            nowSeconds,
            ceremonyStartSeconds,
            voteEndTimeSeconds,
            singleShare
        )

    override suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray =
        rustBackend.extractOrchardFvkFromUfvk(ufvk, networkId)

    override suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int
    ): ByteArray =
        rustBackend.deriveHotkeyRawAddress(hotkeySeed, networkId)

    override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray =
        rustBackend.extractNcRoot(treeStateBytes)

    override suspend fun verifyWitness(witness: JniWitnessData): Boolean =
        rustBackend.verifyWitness(witness)

    override suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuid: ByteArray
    ): List<JniNoteInfo> =
        rustBackend.getWalletNotes(walletDbPath, snapshotHeight, networkId, accountUuid).toList()
}

@Suppress("TooManyFunctions", "LongParameterList")
internal interface VotingDbBackend {
    suspend fun close()

    suspend fun getRoundState(roundId: String): JniRoundState?

    suspend fun listRounds(): Array<JniRoundSummary>

    suspend fun getBundleCount(roundId: String): Int

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long

    suspend fun setupBundles(
        roundId: String,
        notes: List<JniNoteInfo>
    ): JniBundleSetupResult

    suspend fun ensureRound(
        roundId: String,
        anchorTreeStateBytes: ByteArray,
        snapshotHeight: Long,
        eaPk: ByteArray,
        ncRoot: ByteArray,
        nullifierImtRoot: ByteArray
    )

    suspend fun generateHotkey(storedSecret: ByteArray): JniVotingHotkey

    suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        pirPolyLen: Int,
        notes: List<JniNoteInfo>
    ): JniDelegationPirPrecomputeResult

    suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long

    suspend fun resetTreeClient(roundId: String)

    suspend fun resetAllTreeClients()

    suspend fun resetVotingSessionState(roundId: String)

    suspend fun storeKeystoneSignatures(
        roundId: String,
        signatures: List<JniKeystoneSignatureInput>
    ): JniKeystoneSignatureBatchResult

    suspend fun getKeystoneSignatures(roundId: String): Array<JniKeystoneSignatureRecord>

    suspend fun openShareTrackingSession(roundId: String): ShareTrackingSessionBackend

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
    ): RoundSessionBackend
}

@Suppress("TooManyFunctions", "LongParameterList")
private class RustVotingDbBackend(
    private val votingDb: VotingRustBackend.VotingDb
) : VotingDbBackend {
    override suspend fun close() = votingDb.close()

    override suspend fun getRoundState(roundId: String): JniRoundState? =
        votingDb.getRoundState(roundId)

    override suspend fun listRounds(): Array<JniRoundSummary> =
        votingDb.listRounds()

    override suspend fun getBundleCount(roundId: String): Int =
        votingDb.getBundleCount(roundId)

    override suspend fun clearRound(roundId: String) =
        votingDb.clearRound(roundId)

    override suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long = votingDb.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(
        roundId: String,
        notes: List<JniNoteInfo>
    ): JniBundleSetupResult = votingDb.setupBundles(roundId, notes)

    override suspend fun ensureRound(
        roundId: String,
        anchorTreeStateBytes: ByteArray,
        snapshotHeight: Long,
        eaPk: ByteArray,
        ncRoot: ByteArray,
        nullifierImtRoot: ByteArray
    ) = votingDb.ensureRound(roundId, anchorTreeStateBytes, snapshotHeight, eaPk, ncRoot, nullifierImtRoot)

    override suspend fun generateHotkey(storedSecret: ByteArray): JniVotingHotkey =
        votingDb.generateHotkey(storedSecret)

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        pirPolyLen: Int,
        notes: List<JniNoteInfo>
    ): JniDelegationPirPrecomputeResult =
        votingDb.precomputeDelegationPir(
            roundId,
            bundleIndex,
            pirServerUrl,
            pirDepth,
            pirTier0Layers,
            pirTier1Layers,
            pirPolyLen,
            notes
        )

    override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long =
        votingDb.syncVoteTree(roundId, nodeUrl)

    override suspend fun resetTreeClient(roundId: String) =
        votingDb.resetTreeClient(roundId)

    override suspend fun resetAllTreeClients() =
        votingDb.resetTreeClient("")

    override suspend fun resetVotingSessionState(roundId: String) =
        votingDb.resetVotingSessionState(roundId)

    override suspend fun storeKeystoneSignatures(
        roundId: String,
        signatures: List<JniKeystoneSignatureInput>
    ): JniKeystoneSignatureBatchResult =
        votingDb.storeKeystoneSignatures(roundId, signatures)

    override suspend fun getKeystoneSignatures(roundId: String): Array<JniKeystoneSignatureRecord> =
        votingDb.getKeystoneSignatures(roundId)

    override suspend fun openShareTrackingSession(roundId: String): ShareTrackingSessionBackend =
        RustShareTrackingSessionBackend(votingDb.openShareTrackingSession(roundId))

    override suspend fun openRoundSession(
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
    ): RoundSessionBackend =
        RustRoundSessionBackend(
            votingDb.openRoundSession(
                torRuntime,
                roundId,
                proposalIds,
                proposalOptionCounts,
                hotkeySecret,
                chainEndpoints,
                operationEpoch,
                configuredHelperUrls,
                voteTreeNodeUrls,
                ceremonyStartSeconds,
                voteEndTimeSeconds
            )
        )
}

internal interface RoundSessionBackend {
    /** See [TypesafeRoundSession.dbHandle]'s doc comment. */
    val dbHandle: Long

    suspend fun close()

    suspend fun cancel()

    suspend fun setOperationEpoch(operationEpoch: Long)

    suspend fun getRoundPlan(): JniRoundPlan?

    suspend fun setBallotIntents(
        proposalIds: IntArray,
        choices: IntArray
    ): JniRoundPlan?

    suspend fun runRound(
        torRuntime: Long,
        delegationInputs: JniDelegationInputs?,
        progressListener: RoundDriveProgressListener? = null
    ): JniRoundRunReport?

    suspend fun getKeystoneSigningRequests(bundleIndices: IntArray): Array<JniKeystoneSigningRequest>
}

private class RustRoundSessionBackend(
    private val roundSession: VotingRustBackend.RoundSession
) : RoundSessionBackend {
    override val dbHandle: Long = roundSession.dbHandle

    override suspend fun close() = roundSession.close()

    override suspend fun cancel() = roundSession.cancel()

    override suspend fun setOperationEpoch(operationEpoch: Long) =
        roundSession.setOperationEpoch(operationEpoch)

    override suspend fun getRoundPlan(): JniRoundPlan? = roundSession.getRoundPlan()

    override suspend fun setBallotIntents(
        proposalIds: IntArray,
        choices: IntArray
    ): JniRoundPlan? = roundSession.setBallotIntents(proposalIds, choices)

    override suspend fun runRound(
        torRuntime: Long,
        delegationInputs: JniDelegationInputs?,
        progressListener: RoundDriveProgressListener?
    ): JniRoundRunReport? = roundSession.runRound(torRuntime, delegationInputs, progressListener)

    override suspend fun getKeystoneSigningRequests(bundleIndices: IntArray): Array<JniKeystoneSigningRequest> =
        roundSession.getKeystoneSigningRequests(bundleIndices)
}

internal interface ShareTrackingSessionBackend {
    suspend fun close()

    suspend fun cancel()

    suspend fun run(
        torRuntime: Long,
        helperUrls: List<String>,
        voteEndTimeSeconds: Long
    ): JniShareTrackingRunReport?
}

private class RustShareTrackingSessionBackend(
    private val session: VotingRustBackend.ShareTrackingSession
) : ShareTrackingSessionBackend {
    override suspend fun close() = session.close()

    override suspend fun cancel() = session.cancel()

    override suspend fun run(
        torRuntime: Long,
        helperUrls: List<String>,
        voteEndTimeSeconds: Long
    ): JniShareTrackingRunReport? = session.run(torRuntime, helperUrls, voteEndTimeSeconds)
}

@Suppress("TooManyFunctions", "LongParameterList")
internal class TypesafeVotingDbImpl(
    private val votingDb: VotingDbBackend
) : TypesafeVotingDb {
    override suspend fun close() = votingDb.close()

    override suspend fun getRoundState(roundId: String): JniRoundState? =
        votingDb.getRoundState(roundId)

    override suspend fun listRounds(): List<JniRoundSummary> =
        votingDb.listRounds().asList()

    override suspend fun getBundleCount(roundId: String): Int =
        votingDb.getBundleCount(roundId)

    override suspend fun clearRound(roundId: String) =
        votingDb.clearRound(roundId)

    override suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long = votingDb.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(
        roundId: String,
        notes: List<VotingNoteInfo>
    ): JniBundleSetupResult =
        votingDb.setupBundles(roundId, notes.toJniNoteInfos())

    override suspend fun ensureRound(
        roundId: String,
        anchorTreeStateBytes: ByteArray,
        snapshotHeight: Long,
        eaPk: ByteArray,
        ncRoot: ByteArray,
        nullifierImtRoot: ByteArray
    ) = votingDb.ensureRound(roundId, anchorTreeStateBytes, snapshotHeight, eaPk, ncRoot, nullifierImtRoot)

    override suspend fun generateHotkey(storedSecret: ByteArray): JniVotingHotkey =
        votingDb.generateHotkey(storedSecret).also { hotkey ->
            hotkey.requireValid()
        }

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        pirPolyLen: Int,
        notes: List<VotingNoteInfo>
    ): DelegationPirPrecomputeResult =
        votingDb
            .precomputeDelegationPir(
                roundId,
                bundleIndex,
                pirServerUrl,
                pirDepth,
                pirTier0Layers,
                pirTier1Layers,
                pirPolyLen,
                notes.toJniNoteInfos()
            ).toDelegationPirPrecomputeResult()

    override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long =
        votingDb.syncVoteTree(roundId, nodeUrl)

    override suspend fun resetTreeClient(roundId: String) =
        votingDb.resetTreeClient(roundId)

    override suspend fun resetAllTreeClients() =
        votingDb.resetAllTreeClients()

    override suspend fun resetVotingSessionState(roundId: String) =
        votingDb.resetVotingSessionState(roundId)

    override suspend fun storeKeystoneSignatures(
        roundId: String,
        signatures: List<JniKeystoneSignatureInput>
    ): JniKeystoneSignatureBatchResult =
        votingDb.storeKeystoneSignatures(roundId, signatures)

    override suspend fun getKeystoneSignatures(roundId: String): List<JniKeystoneSignatureRecord> =
        votingDb.getKeystoneSignatures(roundId).asList()

    override suspend fun openShareTrackingSession(roundId: String): TypesafeShareTrackingSession =
        TypesafeShareTrackingSessionImpl(votingDb.openShareTrackingSession(roundId))

    override suspend fun openRoundSession(
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
    ): TypesafeRoundSession =
        TypesafeRoundSessionImpl(
            votingDb.openRoundSession(
                torRuntime,
                roundId,
                proposalIds,
                proposalOptionCounts,
                hotkeySecret,
                chainEndpoints,
                operationEpoch,
                configuredHelperUrls,
                voteTreeNodeUrls,
                ceremonyStartSeconds,
                voteEndTimeSeconds
            )
        )
}

internal class TypesafeRoundSessionImpl(
    private val roundSession: RoundSessionBackend
) : TypesafeRoundSession {
    override val dbHandle: Long = roundSession.dbHandle

    override suspend fun close() = roundSession.close()

    override suspend fun cancel() = roundSession.cancel()

    override suspend fun setOperationEpoch(operationEpoch: Long) =
        roundSession.setOperationEpoch(operationEpoch)

    override suspend fun getRoundPlan(): JniRoundPlan? = roundSession.getRoundPlan()

    override suspend fun setBallotIntents(
        proposalIds: IntArray,
        choices: IntArray
    ): JniRoundPlan? = roundSession.setBallotIntents(proposalIds, choices)

    override suspend fun runRound(
        torRuntime: Long,
        delegationInputs: JniDelegationInputs?,
        progressListener: RoundDriveProgressListener?
    ): JniRoundRunReport? = roundSession.runRound(torRuntime, delegationInputs, progressListener)

    override suspend fun getKeystoneSigningRequests(bundleIndices: IntArray): List<JniKeystoneSigningRequest> =
        roundSession.getKeystoneSigningRequests(bundleIndices).asList()
}

internal class TypesafeShareTrackingSessionImpl(
    private val session: ShareTrackingSessionBackend
) : TypesafeShareTrackingSession {
    override suspend fun close() = session.close()

    override suspend fun cancel() = session.cancel()

    override suspend fun run(
        torRuntime: Long,
        helperUrls: List<String>,
        voteEndTimeSeconds: Long
    ): JniShareTrackingRunReport? = session.run(torRuntime, helperUrls, voteEndTimeSeconds)
}

internal fun JniDelegationPirPrecomputeResult.toDelegationPirPrecomputeResult() =
    DelegationPirPrecomputeResult(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount
    )

private fun JniVotingHotkey.requireValid() {
    storedSecret.requireByteArraySize("storedSecret", JNI_HOTKEY_STORED_SECRET_BYTES_SIZE)
    rawAddress.requireByteArraySize("rawAddress", JNI_HOTKEY_RAW_ADDRESS_BYTES_SIZE)
}

private fun ByteArray.requireByteArraySize(name: String, expectedSize: Int) =
    require(size == expectedSize) {
        "$name must be $expectedSize bytes, got $size"
    }
