package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.TorRuntimeLease
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationInputs
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureBatchResult
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureInput
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSigningRequest
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPlan
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundRunReport
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniShareTrackingRunReport
import cash.z.ecc.android.sdk.model.voting.VotingBallotIntent
import cash.z.ecc.android.sdk.model.voting.VotingDelegationInputs
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSignatureInput
import cash.z.ecc.android.sdk.model.voting.VotingProposalRosterEntry
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import cash.z.ecc.android.sdk.model.voting.VotingRoundPlanAction
import cash.z.ecc.android.sdk.model.voting.VotingRoundQuiescence
import cash.z.ecc.android.sdk.model.voting.VotingShareTrackingQuiescence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VotingSdkImplTest {
    @Test
    fun isAvailable_returns_true_when_warmProvingCaches_succeeds() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val sdk = VotingSdkImpl(backend)

            assertTrue(sdk.isAvailable())
        }

    @Test
    fun isAvailable_returns_false_on_UnsatisfiedLinkError() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            `when`(backend.warmProvingCaches()).thenThrow(UnsatisfiedLinkError("no symbol"))
            val sdk = VotingSdkImpl(backend)

            assertFalse(sdk.isAvailable())
        }

    // NativeLibraryLoader wraps a failed System.loadLibrary in AssertionError, not
    // UnsatisfiedLinkError -- this is the case the old `it !is UnsatisfiedLinkError` check
    // silently reported as "available" for.
    @Test
    fun isAvailable_returns_false_on_AssertionError_from_a_missing_native_library() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            `when`(backend.warmProvingCaches())
                .thenThrow(AssertionError("Failed loading native library zcashwalletsdk"))
            val sdk = VotingSdkImpl(backend)

            assertFalse(sdk.isAvailable())
        }

    // Cancellation mid-probe (e.g. screen rotation, scope teardown) is not a genuine probe
    // failure -- it must propagate rather than being memoized as "unavailable", or a healthy
    // native library would be reported unavailable for the rest of the process lifetime.
    @Test
    fun isAvailable_rethrows_cancellation_and_does_not_memoize_it(): Unit =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            `when`(backend.warmProvingCaches()).thenThrow(CancellationException("probe cancelled"))
            val sdk = VotingSdkImpl(backend)

            assertFailsWith<CancellationException> { sdk.isAvailable() }
        }

    @Test
    fun isAvailable_memoizes_and_only_warms_proving_caches_once() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val sdk = VotingSdkImpl(backend)

            assertTrue(sdk.isAvailable())
            assertTrue(sdk.isAvailable())
            assertTrue(sdk.isAvailable())

            verify(backend, times(1)).warmProvingCaches()
        }

    @Test
    fun openDb_wraps_the_returned_TypesafeVotingDb() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            val sdk = VotingSdkImpl(backend)

            val session = sdk.openDb("path", "wallet-1", 0)

            session.close()
            verify(votingDb).close()
        }

    @Test
    fun getRoundState_maps_phase_and_fields() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getRoundState("round-1")).thenReturn(
                JniRoundState(
                    roundId = "round-1",
                    phase = JniRoundPhase.VOTE_READY.value,
                    snapshotHeight = 100L,
                    hotkeyAddress = "addr",
                    delegatedWeight = 5L,
                    proofGenerated = true
                )
            )
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val state = session.getRoundState("round-1")

            assertEquals(VotingRoundPhase.VOTE_READY, state?.phase)
            assertEquals("round-1", state?.roundId)
            assertEquals(5L, state?.delegatedWeight)
        }

    @Test
    fun getRoundState_returns_null_when_backend_returns_null() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getRoundState("round-1")).thenReturn(null)
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            assertEquals(null, session.getRoundState("round-1"))
        }

    @Test
    fun resetVotingSessionState_forwards_to_backend() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            session.resetVotingSessionState("round-1")

            verify(votingDb).resetVotingSessionState("round-1")
        }

    @Test
    fun configureVoting_forwards_to_backend() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val sdk = VotingSdkImpl(backend)

            sdk.configureVoting()

            verify(backend).configureVoting()
        }

    @Test
    fun storeKeystoneSignatures_maps_input_and_result() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            val signatureInput =
                JniKeystoneSignatureInput(
                    bundleIndex = 0,
                    sig = byteArrayOf(1),
                    sighash = byteArrayOf(2),
                    rk = byteArrayOf(3)
                )
            `when`(votingDb.storeKeystoneSignatures("round-1", listOf(signatureInput)))
                .thenReturn(JniKeystoneSignatureBatchResult(inserted = 1, alreadyPresent = 0))
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val result =
                session.storeKeystoneSignatures(
                    "round-1",
                    listOf(VotingKeystoneSignatureInput(0, byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
                )

            assertEquals(1, result.inserted)
            assertEquals(0, result.alreadyPresent)
        }

    @Test
    fun getKeystoneSignatures_maps_every_record() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getKeystoneSignatures("round-1")).thenReturn(
                listOf(
                    JniKeystoneSignatureRecord(
                        bundleIndex = 0,
                        sig = byteArrayOf(1),
                        sighash = byteArrayOf(2),
                        rk = byteArrayOf(3)
                    )
                )
            )
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val records = session.getKeystoneSignatures("round-1")

            assertEquals(1, records.size)
            assertEquals(0, records[0].bundleIndex)
        }

    @Test
    fun openShareTrackingSession_run_maps_report() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            val trackingSession = mock(TypesafeShareTrackingSession::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.openShareTrackingSession("round-1")).thenReturn(trackingSession)
            `when`(trackingSession.run(7L, listOf("https://helper.example"), -1L)).thenReturn(
                shareTrackingReportFixture()
            )
            val dbSession = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val session = dbSession.openShareTrackingSession("round-1")
            val report = session.run(torLease(7L), listOf("https://helper.example"), -1L)

            assertEquals(VotingShareTrackingQuiescence.AllConfirmed, report?.quiescence)
            assertEquals(2, report?.passes)
        }

    @Test
    fun shareTrackingSession_run_returns_null_when_backend_returns_null() =
        runBlocking {
            val trackingSession = mock(TypesafeShareTrackingSession::class.java)
            `when`(trackingSession.run(7L, listOf("https://helper.example"), -1L)).thenReturn(null)
            val session = VotingShareTrackingSessionImpl(trackingSession)

            assertNull(session.run(torLease(7L), listOf("https://helper.example"), -1L))
        }

    @Test
    fun shareTrackingSession_run_with_a_null_lease_passes_the_no_tor_sentinel() =
        runBlocking {
            val trackingSession = mock(TypesafeShareTrackingSession::class.java)
            `when`(trackingSession.run(0L, listOf("https://helper.example"), -1L)).thenReturn(
                shareTrackingReportFixture()
            )
            val session = VotingShareTrackingSessionImpl(trackingSession)

            val report = session.run(null, listOf("https://helper.example"), -1L)

            assertEquals(VotingShareTrackingQuiescence.AllConfirmed, report?.quiescence)
        }

    @Test
    fun shareTrackingSession_run_with_a_released_lease_fails_before_reaching_native_code() =
        runBlocking<Unit> {
            val trackingSession = mock(TypesafeShareTrackingSession::class.java)
            val released = mock(TorRuntimeLease::class.java)
            `when`(released.handle).thenThrow(IllegalStateException("TorRuntimeLease used after release"))
            val session = VotingShareTrackingSessionImpl(trackingSession)

            assertFailsWith<IllegalStateException> {
                session.run(released, listOf("https://helper.example"), -1L)
            }
            verifyNoInteractions(trackingSession)
        }

    @Test
    fun shareTrackingSession_close_and_cancel_forward_to_the_session() =
        runBlocking {
            val trackingSession = mock(TypesafeShareTrackingSession::class.java)
            val session = VotingShareTrackingSessionImpl(trackingSession)

            session.cancel()
            session.close()

            verify(trackingSession).cancel()
            verify(trackingSession).close()
        }

    @Test
    fun openRoundSession_maps_proposal_roster_and_null_timing_to_sentinel() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            val roundSession = mock(TypesafeRoundSession::class.java)
            `when`(roundSession.dbHandle).thenReturn(99L)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(
                votingDb.openRoundSession(
                    torRuntime = 7L,
                    roundId = "round-1",
                    proposalIds = intArrayOf(1, 2),
                    proposalOptionCounts = intArrayOf(2, 3),
                    hotkeySecret = null,
                    chainEndpoints = listOf("https://chain.example"),
                    operationEpoch = 0L,
                    configuredHelperUrls = emptyList(),
                    voteTreeNodeUrls = emptyList(),
                    ceremonyStartSeconds = -1L,
                    voteEndTimeSeconds = -1L
                )
            ).thenReturn(roundSession)
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val roundSessionPublic =
                session.openRoundSession(
                    torLease = torLease(7L),
                    roundId = "round-1",
                    proposals =
                        listOf(
                            VotingProposalRosterEntry(proposalId = 1, numOptions = 2),
                            VotingProposalRosterEntry(proposalId = 2, numOptions = 3)
                        ),
                    hotkeySecret = null,
                    chainEndpoints = listOf("https://chain.example"),
                    operationEpoch = 0L,
                    configuredHelperUrls = emptyList(),
                    voteTreeNodeUrls = emptyList(),
                    ceremonyStartSeconds = null,
                    voteEndTimeSeconds = null
                )

            roundSessionPublic.close()
            verify(roundSession).close()
        }

    @Test
    fun roundSession_setBallotIntents_encodes_a_null_choice_as_skip() =
        runBlocking {
            val roundSession = mock(TypesafeRoundSession::class.java)
            `when`(roundSession.dbHandle).thenReturn(1L)
            `when`(roundSession.setBallotIntents(intArrayOf(1, 2), intArrayOf(0, -1)))
                .thenReturn(roundPlanFixture())
            val session = VotingRoundSessionImpl(roundSession, torLease = torLease(1L))

            val plan =
                session.setBallotIntents(
                    listOf(
                        VotingBallotIntent(proposalId = 1, choice = 0),
                        VotingBallotIntent(proposalId = 2, choice = null)
                    )
                )

            assertEquals("round-1", plan?.roundId)
            assertEquals(VotingRoundPlanAction.VOTE, plan?.primaryAction)
        }

    @Test
    fun roundSession_run_builds_delegation_inputs_using_the_sessions_db_handle() =
        runBlocking {
            val roundSession = mock(TypesafeRoundSession::class.java)
            `when`(roundSession.dbHandle).thenReturn(42L)
            `when`(roundSession.runRound(anyLong(), any(), any())).thenReturn(roundRunReportFixture())
            val session = VotingRoundSessionImpl(roundSession, torLease = torLease(5L))

            val delegationInputs =
                VotingDelegationInputs(
                    walletDbPath = "wallet.db",
                    accountUuid = "account-uuid",
                    anchorTreeStateBytes = byteArrayOf(1),
                    hotkeySecret = null,
                    pirEndpoints = listOf("https://pir.example"),
                    pirDepth = 1,
                    pirTier0Layers = 1,
                    pirTier1Layers = 1,
                    pirPolyLen = 1,
                    keystone = false,
                    softwareSeed = byteArrayOf(2),
                    keystoneSig = null,
                    keystoneSighash = null,
                    snapshotHeight = 10,
                    eaPk = byteArrayOf(3),
                    ncRoot = byteArrayOf(4),
                    nullifierImtRoot = byteArrayOf(5)
                )

            val report = session.run(delegationInputs)

            val captor = ArgumentCaptor.forClass(JniDelegationInputs::class.java)
            verify(roundSession).runRound(eq(5L), captor.capture(), any())
            assertEquals(42L, captor.value.dbHandle)
            assertTrue(report?.quiescence is VotingRoundQuiescence.NeedsBallot)
        }

    @Test
    fun roundSession_getKeystoneSigningRequests_maps_every_request() =
        runBlocking {
            val roundSession = mock(TypesafeRoundSession::class.java)
            `when`(roundSession.dbHandle).thenReturn(1L)
            `when`(roundSession.getKeystoneSigningRequests(intArrayOf(0))).thenReturn(
                listOf(
                    JniKeystoneSigningRequest(
                        pcztBytes = byteArrayOf(1),
                        redactedPcztBytes = byteArrayOf(2),
                        pcztSighash = byteArrayOf(3),
                        rk = byteArrayOf(4),
                        actionIndex = 0,
                        displayMemo = "memo",
                        eligibleWeightZatoshi = 100L,
                        delegatedWeightZatoshi = 50L,
                        bundleCount = 1,
                        bundleIndex = 0
                    )
                )
            )
            val session = VotingRoundSessionImpl(roundSession, torLease = torLease(1L))

            val requests = session.getKeystoneSigningRequests(listOf(0))

            assertEquals(1, requests.size)
            assertEquals("memo", requests[0].displayMemo)
        }

    @Test
    fun plan_returns_null_when_backend_returns_null() =
        runBlocking {
            val roundSession = mock(TypesafeRoundSession::class.java)
            `when`(roundSession.dbHandle).thenReturn(1L)
            `when`(roundSession.getRoundPlan()).thenReturn(null)
            val session = VotingRoundSessionImpl(roundSession, torLease = torLease(1L))

            assertNull(session.plan())
        }

    private fun torLease(handle: Long): TorRuntimeLease =
        mock(TorRuntimeLease::class.java).also { `when`(it.handle).thenReturn(handle) }

    private fun roundPlanFixture(): JniRoundPlan =
        JniRoundPlan(
            roundId = "round-1",
            pendingRecovery = true,
            nextStepsJson = """[{"kind":"cast_vote","bundle_index":0,"proposal_id":1,"choice":0}]""",
            openProposals = intArrayOf(),
            unrosteredIntents = intArrayOf(),
            immediateShareKeyJson = null,
            immediateShareConfirmed = false,
            allDecided = true,
            delegationStatusesJson = "[]",
            blockingRecovery = true,
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
            delegationBundlesNeedingWork = intArrayOf(),
            delegationBundlesNeedingSigning = intArrayOf(),
            needsVotePolling = true,
            hasRemainingVoteOrShareWork = true,
            hasRecoverableVoteOrShareWork = true,
            recoveredDelegationWorkJson = "[]",
            recoveredVoteWorkJson = "[]"
        )

    private fun roundRunReportFixture(): JniRoundRunReport =
        JniRoundRunReport(
            quiescenceKind = "needs_ballot",
            quiescenceDetailJson = """{"openProposals":[1],"unrosteredIntents":[]}""",
            plan = null,
            completedProposals = 0,
            totalProposals = 1,
            remainingObligations = 1,
            failuresJson = "[]",
            skippedBundles = intArrayOf(),
            chainOutcomesJson = "[]",
            shareDeliveriesJson = "[]",
            delegationsSignedCount = 0
        )

    private fun shareTrackingReportFixture(): JniShareTrackingRunReport =
        JniShareTrackingRunReport(
            quiescenceKind = "all_confirmed",
            quiescenceDetailJson = null,
            passes = 2,
            confirmedJson = "[]",
            resubmittedJson = "[]",
            ambiguousJson = "[]",
            unrecoverableJson = "[]",
            failuresJson = "[]"
        )
}
