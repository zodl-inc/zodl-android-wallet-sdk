package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.voting.VotingNextStep
import cash.z.ecc.android.sdk.model.voting.VotingRoundWorkTally
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression coverage for the gap Milan's review of PR #16 flagged: [parseRoundDriveProgress]
 * parses the crate's serde JSON with `optInt`/`optIntOrNull`-style defaults and had no tests, so
 * a field rename in a future `zcash_voting` bump could quietly turn live round-drive progress
 * into all-zero/null values with nothing to catch it.
 *
 * androidTest, not a JVM unit test: `org.json.JSONObject`/`JSONArray` resolve to Android's
 * unimplemented stub on a plain JVM test run (every method throws), matching every other
 * JSON-string-carrier mapper test in this module (`TypesafeVotingBackendImplTest`,
 * `VotingSdkRoundTripTest`, ...).
 */
class VotingSdkRoundDriveProgressMappersTest {
    @Test
    fun a_fully_populated_round_drive_event_view_parses_every_field() {
        val json =
            """
            {
                "kind": "step_progress",
                "step": {"kind": "cast_vote", "bundle_index": 2, "proposal_id": 7, "choice": 1},
                "progress": {"proof_progress": 0.5, "proposal_id": 9, "vote_commit_stage": "proving"},
                "tally": {"completed_proposals": 3, "total_proposals": 10, "remaining_obligations": 4},
                "plan": {"next_steps": [], "recovered_vote_work": []}
            }
            """.trimIndent()

        val progress = parseRoundDriveProgress(json)

        assertEquals("step_progress", progress.kind)
        assertEquals(VotingNextStep.CastVote(bundleIndex = 2, proposalId = 7, choice = 1), progress.step)
        assertEquals(0.5f, progress.proofProgress)
        assertEquals(
            VotingRoundWorkTally(completedProposals = 3, totalProposals = 10, remainingObligations = 4),
            progress.tally
        )
        assertEquals(9, progress.voteCommitProposalId)
        assertEquals("proving", progress.voteCommitStage)
        assertEquals(emptyList(), progress.voteCarryingBundleIndexes)
    }

    @Test
    fun an_event_with_no_optional_fields_at_all_parses_to_nulls_not_a_crash_or_defaulted_zeros() {
        val progress = parseRoundDriveProgress("""{"kind": "plan_refreshed"}""")

        assertEquals("plan_refreshed", progress.kind)
        assertNull(progress.step)
        assertNull(progress.proofProgress)
        assertNull(progress.tally)
        assertNull(progress.voteCommitProposalId)
        assertNull(progress.voteCommitStage)
        assertNull(progress.voteCarryingBundleIndexes)
    }

    @Test
    fun vote_carrying_bundle_indexes_are_vote_family_steps_plus_recovered_work_sorted_deduped() {
        val json =
            """
            {
                "kind": "plan_refreshed",
                "plan": {
                    "next_steps": [
                        {"kind": "cast_vote", "bundle_index": 5, "proposal_id": 1, "choice": 0},
                        {"kind": "delegate", "bundle_index": 3},
                        {"kind": "advance_vote_batch", "bundle_index": 1, "proposal_id": 2},
                        {"kind": "submit_shares", "bundle_index": 5}
                    ],
                    "recovered_vote_work": [
                        {"bundle_index": 8},
                        {"bundle_index": 1}
                    ]
                }
            }
            """.trimIndent()

        val progress = parseRoundDriveProgress(json)

        // bundle 3 (delegate) is excluded -- not a vote-family step. bundle 5 appears twice in
        // next_steps (cast_vote and submit_shares) and bundle 1 appears in both next_steps and
        // recovered_vote_work -- both collapse to one entry each, sorted ascending.
        assertEquals(listOf(1, 5, 8), progress.voteCarryingBundleIndexes)
    }

    @Test
    fun an_empty_plan_with_no_next_steps_or_recovered_vote_work_is_an_empty_list_not_null() {
        val progress = parseRoundDriveProgress("""{"kind": "plan_refreshed", "plan": {}}""")

        assertEquals(emptyList(), progress.voteCarryingBundleIndexes)
    }
}
