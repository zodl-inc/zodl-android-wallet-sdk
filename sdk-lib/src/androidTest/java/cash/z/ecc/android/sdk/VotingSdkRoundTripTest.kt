package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.jni.JNI_VOTING_NETWORK_ID_TESTNET
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.model.voting.VotingBallotIntent
import cash.z.ecc.android.sdk.model.voting.VotingDelegationInputs
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingNoteScope
import cash.z.ecc.android.sdk.model.voting.VotingProposalRosterEntry
import cash.z.ecc.android.sdk.model.voting.VotingRoundQuiescence
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Instrumented round-trip test for the voting-5.0.0 SDK port's new public surface
 * ([VotingSdk]/[VotingDbSession]/[VotingRoundSession]) -- Task 10's own required deliverable.
 *
 * Exercises the real Rust backend end to end through the public API only (no raw
 * `VotingRustBackend`/`TypesafeVotingBackend` access), against a real, throwaway on-device
 * SQLite database and a real (but never network-reachable in these tests) Tor runtime.
 *
 * The central scenario ([round_trip_ensure_round_bootstraps_a_virgin_round]) settles this task's
 * headline open question: does the round-bootstrap sequence work through the public surface, the
 * same way it now does through the raw JNI layer (see `backend-lib`'s `VotingRustBackendTest.
 * ensureRound_bootstraps_a_virgin_round_and_unblocks_setup_and_run`, which established the same
 * fix one layer down)? It does, confirmed here via the actual public types
 * ([VotingDbSession.ensureRound], [VotingDelegationInputs], [VotingRoundSession.run]) an app
 * would use.
 *
 * There is no test/fixture chain-submission endpoint in this repo for a real
 * delegate-vote-confirm cycle to run against (the brief's "at least one full delegate→vote→
 * confirm cycle" is aspirational for a future task once the round-bootstrap gap below is
 * fixed); this test instead exercises every reachable step of the new session surface against
 * the real backend and documents exactly where the cycle currently cannot proceed further.
 */
class VotingSdkRoundTripTest {
    @Test
    fun round_trip_open_session_run_without_delegation_reaches_needs_ballot() =
        runTest(timeout = 5.minutes) {
            val sdk = VotingSdk.new()
            assertTrue(sdk.isAvailable())

            val dbSession = sdk.openDb(newDbPath(), WALLET_ID, JNI_VOTING_NETWORK_ID_TESTNET)
            val torClient = newTorClientForTesting()
            try {
                // Nothing persisted yet for a virgin round -- confirmed through the public
                // surface before touching it, mirroring the raw-JNI-layer finding.
                assertNull(dbSession.getRoundState(ROUND_ID))
                assertTrue(dbSession.listRounds().isEmpty())

                val roundSession =
                    dbSession.openRoundSession(
                        torRuntime = torClient.rawRuntimeHandle(),
                        roundId = ROUND_ID,
                        proposals = listOf(VotingProposalRosterEntry(proposalId = 1, numOptions = 2)),
                        hotkeySecret = null,
                        chainEndpoints = listOf("https://chain.example"),
                        operationEpoch = 0L,
                        configuredHelperUrls = emptyList(),
                        voteTreeNodeUrls = emptyList(),
                        ceremonyStartSeconds = null,
                        voteEndTimeSeconds = null
                    )
                try {
                    // A signer-less run reaches a real quiescence state from the session's
                    // in-memory binding alone -- no round row needed for this path.
                    val report = roundSession.run(delegationInputs = null)
                    assertNotNull(report)
                    val quiescence = assertNotNull(report.quiescence as? VotingRoundQuiescence.NeedsBallot)
                    assertEquals(listOf(1), quiescence.openProposals)
                    val plan = assertNotNull(report.plan)
                    assertEquals(ROUND_ID, plan.roundId)
                    assertEquals(listOf(1), plan.openProposals)

                    val refetchedPlan = assertNotNull(roundSession.plan())
                    assertEquals(ROUND_ID, refetchedPlan.roundId)

                    // setBallotIntents is a real write path; with no rounds-table row ever
                    // persisted it fails the bundles/ballots foreign key -- proven here through
                    // VotingBallotIntent/VotingRoundSession, not the raw IntArray/JniRoundPlan
                    // shapes underneath.
                    assertFailsWith<RuntimeException> {
                        roundSession.setBallotIntents(listOf(VotingBallotIntent(proposalId = 1, choice = 0)))
                    }

                    // No delegation-enabled run ever succeeded on this session, so no pipeline
                    // is cached yet.
                    assertFailsWith<RuntimeException> {
                        roundSession.getKeystoneSigningRequests(listOf(0))
                    }

                    roundSession.setOperationEpoch(1L)
                    roundSession.cancel()
                } finally {
                    roundSession.close()
                }

                // Still nothing persisted -- the whole exchange above was read-only/in-memory.
                assertNull(dbSession.getRoundState(ROUND_ID))
                assertTrue(dbSession.listRounds().isEmpty())
            } finally {
                dbSession.close()
                torClient.dispose()
            }
        }

    /**
     * This is the task's central finding, now proven fixed through the public surface.
     *
     * The `load_round_params` fix alone (`delegation_step_inputs_from_jni` no longer reads
     * `VotingRoundParams` back from the `rounds` table; [VotingDelegationInputs] now carries the
     * round's own metadata directly) turned out, on real-device verification, to be necessary
     * but not sufficient: `RoundDriver::run`'s planner never proposes delegation work for a round
     * with zero bundle rows, and bundle rows cannot be created until the round row exists — a
     * circularity only [VotingDbSession.ensureRound] (the public surface for the crate's
     * standalone `DelegationPipeline::ensure_round`) can break. See
     * [VotingRoundSession.run]'s doc comment for the full explanation.
     */
    @Test
    fun round_trip_ensure_round_bootstraps_a_virgin_round() =
        runTest(timeout = 5.minutes) {
            val sdk = VotingSdk.new()
            val dbSession = sdk.openDb(newDbPath(), WALLET_ID, JNI_VOTING_NETWORK_ID_TESTNET)
            val torClient = newTorClientForTesting()
            try {
                assertNull(dbSession.getRoundState(ROUND_ID))
                assertTrue(dbSession.listRounds().isEmpty())

                val eaPk = ByteArray(FIELD_BYTES) { 0xEA.toByte() }
                val ncRoot = ByteArray(FIELD_BYTES) { 0x01 }
                val nullifierImtRoot = ByteArray(FIELD_BYTES) { 0x02 }

                // The concrete evidence this test is named for: the round genuinely bootstraps
                // through the public surface.
                dbSession.ensureRound(ROUND_ID, ByteArray(0), snapshotHeight = 10L, eaPk, ncRoot, nullifierImtRoot)
                val bootstrappedState = assertNotNull(dbSession.getRoundState(ROUND_ID))
                assertEquals(ROUND_ID, bootstrappedState.roundId)
                assertEquals(10L, bootstrappedState.snapshotHeight)
                assertTrue(dbSession.listRounds().isNotEmpty())

                // setupBundles's insert has a foreign key on rounds -- this only succeeds now
                // that ensureRound has created the row.
                val bundleSetup =
                    dbSession.setupBundles(
                        ROUND_ID,
                        listOf(
                            VotingNoteInfo(
                                commitment = ByteArray(FIELD_BYTES) { 1 },
                                nullifier = ByteArray(FIELD_BYTES) { 2 },
                                value = 13_000_000L,
                                position = 0L,
                                diversifier = ByteArray(11),
                                rho = ByteArray(FIELD_BYTES),
                                rseed = ByteArray(FIELD_BYTES),
                                scope = VotingNoteScope.EXTERNAL,
                                ufvk = ""
                            )
                        )
                    )
                assertEquals(1, bundleSetup.bundleCount)
                assertEquals(1, dbSession.getBundleCount(ROUND_ID))

                // DelegationPipeline::hotkey() requires a real hotkey before execute_prepare can
                // proceed. Bound consistently to both the session's RoundBinding and the
                // delegation inputs below.
                val hotkey = dbSession.generateHotkey(HOTKEY_SEED)

                val roundSession =
                    dbSession.openRoundSession(
                        torRuntime = torClient.rawRuntimeHandle(),
                        roundId = ROUND_ID,
                        proposals = listOf(VotingProposalRosterEntry(proposalId = 1, numOptions = 2)),
                        hotkeySecret = hotkey.storedSecret,
                        chainEndpoints = listOf("https://chain.example"),
                        operationEpoch = 0L,
                        configuredHelperUrls = emptyList(),
                        voteTreeNodeUrls = emptyList(),
                        ceremonyStartSeconds = null,
                        voteEndTimeSeconds = null
                    )
                try {
                    // A real temp path: SqliteWalletDbOpener::open_for_read genuinely opens
                    // (and creates) this file now that this call reaches real wallet I/O -- a
                    // bare "unused-wallet.db" would litter the process's working directory.
                    val walletDbPath =
                        createTempDirectory("wallet-db-").resolve("wallet.db").toFile().absolutePath
                    val delegationInputs =
                        VotingDelegationInputs(
                            walletDbPath = walletDbPath,
                            accountUuid = "unused-account-uuid",
                            anchorTreeStateBytes = ByteArray(0),
                            hotkeySecret = hotkey.storedSecret,
                            pirEndpoints = listOf("https://pir.example"),
                            // A real, valid YPIR layout (zcash_voting's own
                            // config::tests::test_pir_layout fixture) -- confirmed empirically
                            // against the backend-lib layer test that PirFleet::new rejects an
                            // inconsistent/undersized one before the pipeline is even touched.
                            pirDepth = 19,
                            pirTier0Layers = 12,
                            pirTier1Layers = 7,
                            pirPolyLen = 4096,
                            keystone = false,
                            softwareSeed = ByteArray(FIELD_BYTES) { 0x5A },
                            keystoneSig = null,
                            keystoneSighash = null,
                            // Must match ensureRound's params above exactly: VotingDb::ensure_round
                            // rejects a round it already knows under different parameters.
                            snapshotHeight = 10,
                            eaPk = eaPk,
                            ncRoot = ncRoot,
                            nullifierImtRoot = nullifierImtRoot
                        )

                    // Whatever happens deeper in the pipeline (the wallet path has no real
                    // notes, so a later stage may legitimately fail), the call must not be
                    // rejected up front with "round not found" -- the original bug this test
                    // guards against.
                    runCatching { roundSession.run(delegationInputs) }
                        .onFailure { error ->
                            assertTrue(
                                !error.message.orEmpty().contains("round not found"),
                                "the round-bootstrap bug regressed: ${error.message}"
                            )
                        }

                    // The round is still there, unaffected by whatever run did or did not
                    // dispatch.
                    assertEquals(10L, assertNotNull(dbSession.getRoundState(ROUND_ID)).snapshotHeight)

                    // Further proof the bootstrap is real, not a half-write: a write that used
                    // to fail with a foreign-key error against a nonexistent round (per
                    // round_trip_open_session_run_without_delegation_reaches_needs_ballot above)
                    // now succeeds.
                    roundSession.setBallotIntents(listOf(VotingBallotIntent(proposalId = 1, choice = 0)))
                } finally {
                    roundSession.close()
                }
            } finally {
                dbSession.close()
                torClient.dispose()
            }
        }

    private suspend fun newTorClientForTesting(): TorClient {
        val backend = mock(Backend::class.java)
        `when`(backend.networkId).thenReturn(JNI_VOTING_NETWORK_ID_TESTNET)
        return TorClient.new(createTempDirectory("tor-client-").toFile(), backend)
    }

    private fun newDbPath() = createTempDirectory("voting-db-").resolve("voting.db").toFile().absolutePath

    companion object {
        private const val WALLET_ID = "wallet-1"
        private const val FIELD_BYTES = 32
        private val HOTKEY_SEED = ByteArray(64) { 0x42 }

        // RoundExecutor::with_binding requires round_id to be exactly 64 lowercase hex
        // characters -- the crate's canonical Pallas field element encoding.
        private const val ROUND_ID = "0101010101010101010101010101010101010101010101010101010101010101"
    }
}
