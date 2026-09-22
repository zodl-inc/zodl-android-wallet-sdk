package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_RAW_ADDRESS_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_STORED_SECRET_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationInputs
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureBatchResult
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureInput
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSigningRequest
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniPirPrecomputeResult
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPlan
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundRunReport
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniShareTrackingRunReport
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.internal.model.voting.RoundDriveProgressListener
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("LargeClass", "LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions")
class TypesafeVotingBackendImplTest {
    @Test
    fun voting_note_info_maps_to_and_from_jni_shape() {
        val jniNote = jniNoteInfo().copy(scope = 1)

        val note = jniNote.toVotingNoteInfo()

        assertEquals(VotingNoteScope.INTERNAL, note.scope)
        assertEquals(jniNote, note.toJniNoteInfo())
    }

    @Test
    fun scheduled_share_submit_at_forwards_arguments_and_returns_result() =
        runTest {
            val bridge = RecordingVotingBackendBridge()
            val backend = TypesafeVotingBackendImpl { bridge }

            val submitAt =
                backend.scheduledShareSubmitAt(
                    nowSeconds = 100L,
                    ceremonyStartSeconds = 50L,
                    voteEndTimeSeconds = 200L,
                    singleShare = true
                )

            assertEquals(999L, submitAt)
            assertEquals(100L, bridge.scheduledShareSubmitAtNowSeconds)
            assertEquals(50L, bridge.scheduledShareSubmitAtCeremonyStartSeconds)
            assertEquals(200L, bridge.scheduledShareSubmitAtVoteEndTimeSeconds)
            assertEquals(true, bridge.scheduledShareSubmitAtSingleShare)
        }

    @Test
    fun configure_voting_forwards_to_bridge() =
        runTest {
            val bridge = RecordingVotingBackendBridge()
            val backend = TypesafeVotingBackendImpl { bridge }

            backend.configureVoting()

            assertEquals(1, bridge.configureVotingCalls)
        }

    @Test
    fun precompute_delegation_pir_forwards_arguments_and_maps_result() =
        runTest {
            val dbBackend =
                RecordingVotingDbBackend(
                    precomputeResult = JniDelegationPirPrecomputeResult(cachedCount = 3, fetchedCount = 4)
                )
            val backend = TypesafeVotingBackendImpl { RecordingVotingBackendBridge(dbBackend) }
            val db = backend.openVotingDb("/tmp/voting.db", "wallet-1", networkId = 1)

            val result =
                db.precomputeDelegationPir(
                    roundId = "round-1",
                    bundleIndex = 2,
                    pirServerUrl = "https://pir.example",
                    pirDepth = 1,
                    pirTier0Layers = 1,
                    pirTier1Layers = 1,
                    pirPolyLen = 2048,
                    notes = listOf(votingNoteInfo())
                )

            assertEquals(3L, result.cachedCount)
            assertEquals(4L, result.fetchedCount)
            assertEquals("round-1", dbBackend.precomputeRoundId)
            assertEquals(2, dbBackend.precomputeBundleIndex)
            assertEquals("https://pir.example", dbBackend.precomputePirServerUrl)
            assertEquals(listOf(jniNoteInfo()), dbBackend.precomputeNotes)
        }

    @Test
    fun precompute_pir_proofs_forwards_arguments_and_maps_result() =
        runTest {
            val dbBackend =
                RecordingVotingDbBackend(
                    pirPrecomputeResult =
                        JniPirPrecomputeResult(
                            cachedCount = 3,
                            fetchedCount = 4,
                            servedRoot = byteArrayOf(0x11, 0x22)
                        )
                )
            val backend = TypesafeVotingBackendImpl { RecordingVotingBackendBridge(dbBackend) }
            val db = backend.openVotingDb("/tmp/voting.db", "wallet-1", networkId = 1)

            val result =
                db.precomputePirProofs(
                    pirServerUrl = "https://pir.example",
                    pirDepth = 1,
                    pirTier0Layers = 1,
                    pirTier1Layers = 1,
                    pirPolyLen = 2048,
                    notes = listOf(votingNoteInfo())
                )

            assertEquals(3L, result.cachedCount)
            assertEquals(4L, result.fetchedCount)
            assertContentEquals(byteArrayOf(0x11, 0x22), result.servedRoot)
            assertEquals("https://pir.example", dbBackend.precomputePirProofsPirServerUrl)
            assertEquals(listOf(jniNoteInfo()), dbBackend.precomputePirProofsNotes)
        }

    @Test
    fun generate_hotkey_forwards_stored_secret_and_validates_result() =
        runTest {
            val validHotkey =
                JniVotingHotkey(
                    storedSecret = ByteArray(JNI_HOTKEY_STORED_SECRET_BYTES_SIZE),
                    rawAddress = ByteArray(JNI_HOTKEY_RAW_ADDRESS_BYTES_SIZE),
                    address = "utest1fixture"
                )
            val dbBackend = RecordingVotingDbBackend(hotkeyResult = validHotkey)
            val backend = TypesafeVotingBackendImpl { RecordingVotingBackendBridge(dbBackend) }
            val db = backend.openVotingDb("/tmp/voting.db", "wallet-1", networkId = 1)
            val storedSecret = ByteArray(0)

            val hotkey = db.generateHotkey(storedSecret)

            assertEquals(validHotkey, hotkey)
            assertContentEquals(storedSecret, dbBackend.generateHotkeyStoredSecret)
        }

    @Test
    fun generate_hotkey_rejects_malformed_stored_secret_length() =
        runTest {
            val malformedHotkey =
                JniVotingHotkey(
                    storedSecret = ByteArray(JNI_HOTKEY_STORED_SECRET_BYTES_SIZE - 1),
                    rawAddress = ByteArray(JNI_HOTKEY_RAW_ADDRESS_BYTES_SIZE),
                    address = "utest1fixture"
                )
            val dbBackend = RecordingVotingDbBackend(hotkeyResult = malformedHotkey)
            val backend = TypesafeVotingBackendImpl { RecordingVotingBackendBridge(dbBackend) }
            val db = backend.openVotingDb("/tmp/voting.db", "wallet-1", networkId = 1)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    db.generateHotkey(ByteArray(0))
                }
            assertTrue(error.message.orEmpty().contains("storedSecret"))
        }

    @Test
    fun store_and_get_keystone_signatures_forward_arguments_and_results() =
        runTest {
            val batchResult = JniKeystoneSignatureBatchResult(inserted = 1, alreadyPresent = 2)
            val records =
                arrayOf(
                    JniKeystoneSignatureRecord(
                        bundleIndex = 0,
                        sig = ByteArray(64) { 0x11 },
                        sighash = ByteArray(32) { 0xAA.toByte() },
                        rk = ByteArray(32) { 0x22 }
                    )
                )
            val dbBackend =
                RecordingVotingDbBackend(
                    keystoneSignatureBatchResult = batchResult,
                    keystoneSignatureRecords = records
                )
            val backend = TypesafeVotingBackendImpl { RecordingVotingBackendBridge(dbBackend) }
            val db = backend.openVotingDb("/tmp/voting.db", "wallet-1", networkId = 1)
            val signatures =
                listOf(
                    JniKeystoneSignatureInput(
                        bundleIndex = 0,
                        sig = ByteArray(64) { 0x11 },
                        sighash = ByteArray(32) { 0xAA.toByte() },
                        rk = ByteArray(32) { 0x22 }
                    )
                )

            val storeResult = db.storeKeystoneSignatures("round-1", signatures)
            val getResult = db.getKeystoneSignatures("round-1")

            assertEquals(batchResult, storeResult)
            assertEquals("round-1", dbBackend.storeKeystoneSignaturesRoundId)
            assertEquals(signatures, dbBackend.storeKeystoneSignaturesSignatures)
            assertEquals(records.asList(), getResult)
            assertEquals("round-1", dbBackend.getKeystoneSignaturesRoundId)
        }

    @Test
    fun share_tracking_session_methods_forward_arguments_and_results() =
        runTest {
            val report =
                JniShareTrackingRunReport(
                    quiescenceKind = "all_confirmed",
                    quiescenceDetailJson = null,
                    passes = 1,
                    confirmedJson = "[]",
                    resubmittedJson = "[]",
                    ambiguousJson = "[]",
                    unrecoverableJson = "[]",
                    failuresJson = "[]"
                )
            val sessionBackend = RecordingShareTrackingSessionBackend(runReport = report)
            val dbBackend = RecordingVotingDbBackend(shareTrackingSessionBackend = sessionBackend)
            val backend = TypesafeVotingBackendImpl { RecordingVotingBackendBridge(dbBackend) }
            val db = backend.openVotingDb("/tmp/voting.db", "wallet-1", networkId = 1)

            val session = db.openShareTrackingSession("round-1")
            assertEquals("round-1", dbBackend.openShareTrackingSessionRoundId)

            val result =
                session.run(
                    torRuntime = 42L,
                    helperUrls = listOf("https://helper.example"),
                    voteEndTimeSeconds = -1
                )

            assertEquals(report, result)
            assertEquals(42L, sessionBackend.runTorRuntime)
            assertEquals(listOf("https://helper.example"), sessionBackend.runHelperUrls)
            assertEquals(-1L, sessionBackend.runVoteEndTimeSeconds)

            session.cancel()
            assertEquals(1, sessionBackend.cancelCalls)

            session.close()
            assertEquals(1, sessionBackend.closeCalls)
        }

    @Test
    fun open_round_session_forwards_arguments() =
        runTest {
            val sessionBackend = RecordingRoundSessionBackend()
            val dbBackend = RecordingVotingDbBackend(roundSessionBackend = sessionBackend)
            val backend = TypesafeVotingBackendImpl { RecordingVotingBackendBridge(dbBackend) }
            val db = backend.openVotingDb("/tmp/voting.db", "wallet-1", networkId = 1)

            db.openRoundSession(
                torRuntime = 7L,
                roundId = "round-1",
                proposalIds = intArrayOf(1, 2),
                proposalOptionCounts = intArrayOf(2, 3),
                hotkeySecret = null,
                chainEndpoints = listOf("https://chain.example"),
                operationEpoch = 5L,
                configuredHelperUrls = listOf("https://helper.example"),
                voteTreeNodeUrls = listOf("https://tree.example"),
                ceremonyStartSeconds = -1,
                voteEndTimeSeconds = -1
            )

            assertEquals(7L, dbBackend.openRoundSessionTorRuntime)
            assertEquals("round-1", dbBackend.openRoundSessionRoundId)
            assertContentEquals(intArrayOf(1, 2), dbBackend.openRoundSessionProposalIds)
            assertContentEquals(intArrayOf(2, 3), dbBackend.openRoundSessionProposalOptionCounts)
            assertEquals(5L, dbBackend.openRoundSessionOperationEpoch)
            assertEquals(listOf("https://chain.example"), dbBackend.openRoundSessionChainEndpoints)
        }

    @Test
    fun round_session_methods_forward_arguments_and_results() =
        runTest {
            val plan = jniRoundPlan()
            val report = jniRoundRunReport(plan)
            val signingRequests = arrayOf(jniKeystoneSigningRequest())
            val sessionBackend =
                RecordingRoundSessionBackend(
                    roundPlan = plan,
                    roundRunReport = report,
                    keystoneSigningRequests = signingRequests
                )
            val dbBackend = RecordingVotingDbBackend(roundSessionBackend = sessionBackend)
            val backend = TypesafeVotingBackendImpl { RecordingVotingBackendBridge(dbBackend) }
            val db = backend.openVotingDb("/tmp/voting.db", "wallet-1", networkId = 1)
            val session =
                db.openRoundSession(
                    torRuntime = 1L,
                    roundId = "round-1",
                    proposalIds = intArrayOf(1),
                    proposalOptionCounts = intArrayOf(2),
                    hotkeySecret = null,
                    chainEndpoints = emptyList(),
                    operationEpoch = 0L,
                    configuredHelperUrls = emptyList(),
                    voteTreeNodeUrls = emptyList(),
                    ceremonyStartSeconds = -1,
                    voteEndTimeSeconds = -1
                )

            assertEquals(plan, session.getRoundPlan())

            val refreshedPlan = session.setBallotIntents(intArrayOf(1), intArrayOf(0))
            assertEquals(plan, refreshedPlan)
            assertContentEquals(intArrayOf(1), sessionBackend.setBallotIntentsProposalIds)
            assertContentEquals(intArrayOf(0), sessionBackend.setBallotIntentsChoices)

            val delegationInputs =
                JniDelegationInputs(
                    dbHandle = 1L,
                    walletDbPath = "/tmp/wallet.db",
                    accountUuid = "account-1",
                    anchorTreeStateBytes = ByteArray(0),
                    hotkeySecret = null,
                    pirEndpoints = arrayOf("https://pir.example"),
                    pirDepth = 1,
                    pirTier0Layers = 1,
                    pirTier1Layers = 1,
                    pirPolyLen = 2048,
                    keystone = false,
                    softwareSeed = ByteArray(32),
                    keystoneSig = null,
                    keystoneSighash = null,
                    snapshotHeight = 10,
                    eaPk = ByteArray(32),
                    ncRoot = ByteArray(32),
                    nullifierImtRoot = ByteArray(32)
                )
            val runResult = session.runRound(torRuntime = 9L, delegationInputs = delegationInputs)
            assertEquals(report, runResult)
            assertEquals(9L, sessionBackend.runRoundTorRuntime)
            assertEquals(delegationInputs, sessionBackend.runRoundDelegationInputs)

            val requests = session.getKeystoneSigningRequests(intArrayOf(0, 1))
            assertEquals(signingRequests.asList(), requests)
            assertContentEquals(intArrayOf(0, 1), sessionBackend.keystoneSigningRequestsBundleIndices)

            session.setOperationEpoch(3L)
            assertEquals(3L, sessionBackend.setOperationEpochValue)

            session.cancel()
            assertEquals(1, sessionBackend.cancelCalls)

            session.close()
            assertEquals(1, sessionBackend.closeCalls)
        }

    private fun jniNoteInfo() =
        JniNoteInfo(
            commitment = ByteArray(32) { 1 },
            nullifier = ByteArray(32) { 2 },
            value = 100_000L,
            position = 5L,
            diversifier = ByteArray(11) { 3 },
            rho = ByteArray(32) { 4 },
            rseed = ByteArray(32) { 5 },
            scope = 0,
            ufvk = "ufvk-fixture"
        )

    private fun votingNoteInfo() = jniNoteInfo().toVotingNoteInfo()

    private fun jniRoundPlan() =
        JniRoundPlan(
            roundId = "round-1",
            pendingRecovery = false,
            nextStepsJson = "[]",
            openProposals = intArrayOf(1),
            unrosteredIntents = IntArray(0),
            immediateShareKeyJson = null,
            immediateShareConfirmed = false,
            allDecided = false,
            delegationStatusesJson = "[]",
            blockingRecovery = false,
            blockingShareWork = false,
            hasUnconfirmedShares = false,
            hotkeyBound = true,
            completedVoteArtifact = false,
            completedForDisplay = false,
            completedVoteDisplayJson = null,
            needsDraftSetup = false,
            needsBundleSetup = false,
            primaryAction = 2,
            needsDelegationSigning = false,
            hasInFlightDelegation = false,
            delegationBundlesNeedingWork = IntArray(0),
            delegationBundlesNeedingSigning = IntArray(0),
            needsVotePolling = false,
            hasRemainingVoteOrShareWork = false,
            hasRecoverableVoteOrShareWork = false,
            recoveredDelegationWorkJson = "[]",
            recoveredVoteWorkJson = "[]"
        )

    private fun jniRoundRunReport(plan: JniRoundPlan) =
        JniRoundRunReport(
            quiescenceKind = "no_work_left",
            quiescenceDetailJson = null,
            plan = plan,
            completedProposals = 1,
            totalProposals = 1,
            remainingObligations = 0,
            failuresJson = "[]",
            skippedBundles = IntArray(0),
            chainOutcomesJson = "[]",
            shareDeliveriesJson = "[]",
            delegationsSignedCount = 0
        )

    private fun jniKeystoneSigningRequest() =
        JniKeystoneSigningRequest(
            pcztBytes = ByteArray(64) { 6 },
            redactedPcztBytes = ByteArray(64) { 7 },
            pcztSighash = ByteArray(32) { 8 },
            rk = ByteArray(32) { 9 },
            actionIndex = 0,
            displayMemo = "memo",
            eligibleWeightZatoshi = 1_000L,
            delegatedWeightZatoshi = 500L,
            bundleCount = 1,
            bundleIndex = 0
        )

    private class RecordingVotingBackendBridge(
        private val dbBackend: VotingDbBackend = RecordingVotingDbBackend()
    ) : VotingBackendBridge {
        var configureVotingCalls = 0
        var scheduledShareSubmitAtNowSeconds: Long? = null
        var scheduledShareSubmitAtCeremonyStartSeconds: Long? = null
        var scheduledShareSubmitAtVoteEndTimeSeconds: Long? = null
        var scheduledShareSubmitAtSingleShare: Boolean? = null

        override suspend fun computeShareNullifier(
            voteCommitment: ByteArray,
            shareIndex: Int,
            blind: ByteArray
        ): ByteArray = unused()

        override suspend fun openVotingDb(
            dbPath: String,
            walletId: String,
            networkId: Int
        ): VotingDbBackend = dbBackend

        override suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult = unused()

        override suspend fun warmProvingCaches() = unused()

        override suspend fun configureVoting() {
            configureVotingCalls++
        }

        override suspend fun scheduledShareSubmitAt(
            nowSeconds: Long,
            ceremonyStartSeconds: Long,
            voteEndTimeSeconds: Long,
            singleShare: Boolean
        ): Long {
            scheduledShareSubmitAtNowSeconds = nowSeconds
            scheduledShareSubmitAtCeremonyStartSeconds = ceremonyStartSeconds
            scheduledShareSubmitAtVoteEndTimeSeconds = voteEndTimeSeconds
            scheduledShareSubmitAtSingleShare = singleShare
            return 999L
        }

        override suspend fun extractOrchardFvkFromUfvk(
            ufvk: String,
            networkId: Int
        ): ByteArray = unused()

        override suspend fun deriveHotkeyRawAddress(
            hotkeySeed: ByteArray,
            networkId: Int
        ): ByteArray = unused()

        override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray = unused()

        override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray = unused()

        override suspend fun extractSpendAuthSig(
            signedPcztBytes: ByteArray,
            actionIndex: Int
        ): ByteArray = unused()

        override suspend fun verifyWitness(witness: JniWitnessData): Boolean = unused()

        override suspend fun getWalletNotes(
            walletDbPath: String,
            snapshotHeight: Long,
            networkId: Int,
            accountUuid: ByteArray
        ): List<JniNoteInfo> = unused()

        private fun unused(): Nothing = error("unused")
    }

    @Suppress("LongParameterList")
    private class RecordingVotingDbBackend(
        private val precomputeResult: JniDelegationPirPrecomputeResult =
            JniDelegationPirPrecomputeResult(cachedCount = 0, fetchedCount = 0),
        private val pirPrecomputeResult: JniPirPrecomputeResult =
            JniPirPrecomputeResult(cachedCount = 0, fetchedCount = 0, servedRoot = ByteArray(0)),
        private val hotkeyResult: JniVotingHotkey? = null,
        private val keystoneSignatureBatchResult: JniKeystoneSignatureBatchResult =
            JniKeystoneSignatureBatchResult(inserted = 0, alreadyPresent = 0),
        private val keystoneSignatureRecords: Array<JniKeystoneSignatureRecord> = emptyArray(),
        private val shareTrackingSessionBackend: ShareTrackingSessionBackend =
            RecordingShareTrackingSessionBackend(),
        private val roundSessionBackend: RoundSessionBackend = RecordingRoundSessionBackend()
    ) : VotingDbBackend {
        var precomputeRoundId: String? = null
        var precomputeBundleIndex: Int? = null
        var precomputePirServerUrl: String? = null
        var precomputeNotes: List<JniNoteInfo>? = null
        var precomputePirProofsPirServerUrl: String? = null
        var precomputePirProofsNotes: List<JniNoteInfo>? = null
        var generateHotkeyStoredSecret: ByteArray = ByteArray(0)
        var storeKeystoneSignaturesRoundId: String? = null
        var storeKeystoneSignaturesSignatures: List<JniKeystoneSignatureInput>? = null
        var getKeystoneSignaturesRoundId: String? = null
        var openShareTrackingSessionRoundId: String? = null
        var openRoundSessionTorRuntime: Long? = null
        var openRoundSessionRoundId: String? = null
        var openRoundSessionProposalIds: IntArray? = null
        var openRoundSessionProposalOptionCounts: IntArray? = null
        var openRoundSessionOperationEpoch: Long? = null
        var openRoundSessionChainEndpoints: List<String>? = null

        override suspend fun close() = unused()

        override suspend fun getRoundState(roundId: String): JniRoundState? = unused()

        override suspend fun listRounds(): Array<JniRoundSummary> = unused()

        override suspend fun getBundleCount(roundId: String): Int = unused()

        override suspend fun clearRound(roundId: String) = unused()

        override suspend fun deleteSkippedBundles(
            roundId: String,
            keepCount: Int
        ): Long = unused()

        override suspend fun setupBundles(
            roundId: String,
            notes: List<JniNoteInfo>
        ): JniBundleSetupResult = unused()

        override suspend fun ensureRound(
            roundId: String,
            anchorTreeStateBytes: ByteArray,
            snapshotHeight: Long,
            eaPk: ByteArray,
            ncRoot: ByteArray,
            nullifierImtRoot: ByteArray
        ) = unused()

        override suspend fun generateHotkey(storedSecret: ByteArray): JniVotingHotkey {
            generateHotkeyStoredSecret = storedSecret
            return hotkeyResult ?: unused()
        }

        override suspend fun precomputeDelegationPir(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            pirDepth: Int,
            pirTier0Layers: Int,
            pirTier1Layers: Int,
            pirPolyLen: Int,
            notes: List<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult {
            precomputeRoundId = roundId
            precomputeBundleIndex = bundleIndex
            precomputePirServerUrl = pirServerUrl
            precomputeNotes = notes
            return precomputeResult
        }

        override suspend fun precomputePirProofs(
            pirServerUrl: String,
            pirDepth: Int,
            pirTier0Layers: Int,
            pirTier1Layers: Int,
            pirPolyLen: Int,
            notes: List<JniNoteInfo>
        ): JniPirPrecomputeResult {
            precomputePirProofsPirServerUrl = pirServerUrl
            precomputePirProofsNotes = notes
            return pirPrecomputeResult
        }

        override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long = unused()

        override suspend fun resetTreeClient(roundId: String) = unused()

        override suspend fun resetAllTreeClients() = unused()

        override suspend fun resetVotingSessionState(roundId: String) = unused()

        override suspend fun storeKeystoneSignatures(
            roundId: String,
            signatures: List<JniKeystoneSignatureInput>
        ): JniKeystoneSignatureBatchResult {
            storeKeystoneSignaturesRoundId = roundId
            storeKeystoneSignaturesSignatures = signatures
            return keystoneSignatureBatchResult
        }

        override suspend fun getKeystoneSignatures(roundId: String): Array<JniKeystoneSignatureRecord> {
            getKeystoneSignaturesRoundId = roundId
            return keystoneSignatureRecords
        }

        override suspend fun openShareTrackingSession(roundId: String): ShareTrackingSessionBackend {
            openShareTrackingSessionRoundId = roundId
            return shareTrackingSessionBackend
        }

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
        ): RoundSessionBackend {
            openRoundSessionTorRuntime = torRuntime
            openRoundSessionRoundId = roundId
            openRoundSessionProposalIds = proposalIds
            openRoundSessionProposalOptionCounts = proposalOptionCounts
            openRoundSessionOperationEpoch = operationEpoch
            openRoundSessionChainEndpoints = chainEndpoints
            return roundSessionBackend
        }

        private fun unused(): Nothing = error("unused")
    }

    private class RecordingRoundSessionBackend(
        private val roundPlan: JniRoundPlan? = null,
        private val roundRunReport: JniRoundRunReport? = null,
        private val keystoneSigningRequests: Array<JniKeystoneSigningRequest> = emptyArray()
    ) : RoundSessionBackend {
        override val dbHandle: Long = 42L

        var closeCalls = 0
        var cancelCalls = 0
        var setOperationEpochValue: Long? = null
        var setBallotIntentsProposalIds: IntArray? = null
        var setBallotIntentsChoices: IntArray? = null
        var runRoundTorRuntime: Long? = null
        var runRoundDelegationInputs: JniDelegationInputs? = null
        var runRoundProgressListener: RoundDriveProgressListener? = null
        var keystoneSigningRequestsBundleIndices: IntArray? = null

        override suspend fun close() {
            closeCalls++
        }

        override suspend fun cancel() {
            cancelCalls++
        }

        override suspend fun setOperationEpoch(operationEpoch: Long) {
            setOperationEpochValue = operationEpoch
        }

        override suspend fun getRoundPlan(): JniRoundPlan? = roundPlan

        override suspend fun setBallotIntents(
            proposalIds: IntArray,
            choices: IntArray
        ): JniRoundPlan? {
            setBallotIntentsProposalIds = proposalIds
            setBallotIntentsChoices = choices
            return roundPlan
        }

        override suspend fun runRound(
            torRuntime: Long,
            delegationInputs: JniDelegationInputs?,
            progressListener: RoundDriveProgressListener?
        ): JniRoundRunReport? {
            runRoundTorRuntime = torRuntime
            runRoundDelegationInputs = delegationInputs
            runRoundProgressListener = progressListener
            return roundRunReport
        }

        override suspend fun getKeystoneSigningRequests(bundleIndices: IntArray): Array<JniKeystoneSigningRequest> {
            keystoneSigningRequestsBundleIndices = bundleIndices
            return keystoneSigningRequests
        }
    }

    private class RecordingShareTrackingSessionBackend(
        private val runReport: JniShareTrackingRunReport? = null
    ) : ShareTrackingSessionBackend {
        var closeCalls = 0
        var cancelCalls = 0
        var runTorRuntime: Long? = null
        var runHelperUrls: List<String>? = null
        var runVoteEndTimeSeconds: Long? = null

        override suspend fun close() {
            closeCalls++
        }

        override suspend fun cancel() {
            cancelCalls++
        }

        override suspend fun run(
            torRuntime: Long,
            helperUrls: List<String>,
            voteEndTimeSeconds: Long
        ): JniShareTrackingRunReport? {
            runTorRuntime = torRuntime
            runHelperUrls = helperUrls
            runVoteEndTimeSeconds = voteEndTimeSeconds
            return runReport
        }
    }
}
