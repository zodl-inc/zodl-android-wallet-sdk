package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.jni.JNI_VOTING_NETWORK_ID_TESTNET
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.model.voting.VotingBallotIntent
import cash.z.ecc.android.sdk.model.voting.VotingDelegationInputs
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
 * Instrumented round-trip test for the voting-4.0.0 SDK port's new public surface
 * ([VotingSdk]/[VotingDbSession]/[VotingRoundSession]) -- Task 10's own required deliverable.
 *
 * Exercises the real Rust backend end to end through the public API only (no raw
 * `VotingRustBackend`/`TypesafeVotingBackend` access), against a real, throwaway on-device
 * SQLite database and a real (but never network-reachable in these tests) Tor runtime.
 *
 * The central scenario ([round_trip_delegation_enabled_run_cannot_bootstrap_a_virgin_round])
 * settles this task's headline open question: does a delegation-enabled [VotingRoundSession.run]
 * call bootstrap a virgin round through the public surface, the same way it does not through
 * the raw JNI layer (see `backend-lib`'s `VotingRustBackendTest.
 * runRound_with_delegation_inputs_cannot_bootstrap_a_virgin_round`, which established the same
 * finding one layer down)? It does not, for the same underlying reason -- confirmed here via
 * the actual public types ([VotingDelegationInputs], [VotingRoundSession.run]) an app would use.
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
                        torRuntime = torClient.torRuntimeHandleForTesting(),
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
     * This is the task's central finding, proven through the public surface: see this class's
     * doc comment and [VotingRoundSession.run]'s own doc comment for the full explanation
     * (`delegation_step_inputs_from_jni`'s eager `load_round_params` guard rejects an unknown
     * `round_id` before `RoundDriver::run` is ever entered).
     */
    @Test
    fun round_trip_delegation_enabled_run_cannot_bootstrap_a_virgin_round() =
        runTest(timeout = 5.minutes) {
            val sdk = VotingSdk.new()
            val dbSession = sdk.openDb(newDbPath(), WALLET_ID, JNI_VOTING_NETWORK_ID_TESTNET)
            val torClient = newTorClientForTesting()
            try {
                assertNull(dbSession.getRoundState(ROUND_ID))

                val roundSession =
                    dbSession.openRoundSession(
                        torRuntime = torClient.torRuntimeHandleForTesting(),
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
                    val delegationInputs =
                        VotingDelegationInputs(
                            walletDbPath = "unused-wallet.db",
                            accountUuid = "unused-account-uuid",
                            anchorTreeStateBytes = ByteArray(0),
                            hotkeySecret = null,
                            pirEndpoints = listOf("https://pir.example"),
                            pirDepth = 1,
                            pirTier0Layers = 1,
                            pirTier1Layers = 1,
                            pirPolyLen = 1,
                            keystone = false,
                            softwareSeed = ByteArray(FIELD_BYTES) { 0x5A },
                            keystoneSig = null,
                            keystoneSighash = null
                        )

                    val error =
                        assertFailsWith<RuntimeException> {
                            roundSession.run(delegationInputs)
                        }
                    assertTrue(
                        error.message.orEmpty().contains("round not found"),
                        "expected a round-not-found rejection from load_round_params, got: ${error.message}"
                    )

                    // The failed attempt genuinely never created a rounds row.
                    assertNull(dbSession.getRoundState(ROUND_ID))
                    assertTrue(dbSession.listRounds().isEmpty())
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

    private fun TorClient.torRuntimeHandleForTesting(): Long {
        val field = TorClient::class.java.getDeclaredField("nativeHandle")
        field.isAccessible = true
        return field.get(this) as Long
    }

    private fun newDbPath() = createTempDirectory("voting-db-").resolve("voting.db").toFile().absolutePath

    companion object {
        private const val WALLET_ID = "wallet-1"
        private const val FIELD_BYTES = 32

        // RoundExecutor::with_binding requires round_id to be exactly 64 lowercase hex
        // characters -- the crate's canonical Pallas field element encoding.
        private const val ROUND_ID = "0101010101010101010101010101010101010101010101010101010101010101"
    }
}
