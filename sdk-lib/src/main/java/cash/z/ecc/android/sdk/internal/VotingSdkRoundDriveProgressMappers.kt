package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.voting.VotingRoundDriveProgress
import cash.z.ecc.android.sdk.model.voting.VotingRoundWorkTally
import org.json.JSONObject

/**
 * Parses `round_drive_event_step_and_json`'s `detail` JSON (see that function's doc comment in
 * `backend-lib/src/main/rust/voting/round_session.rs`) -- a plain serde encoding of
 * `zcash_voting::wire::RoundDriveEventView` -- into the public [VotingRoundDriveProgress]
 * [cash.z.ecc.android.sdk.model.voting.VotingRoundDriveProgressListener] carries. Reuses
 * [parseNextStep] (already used for `RoundPlan::next_steps`, the same `NextStepView` JSON
 * shape) for the embedded `step` field rather than re-deriving that mapping.
 */
internal fun parseRoundDriveProgress(json: String): VotingRoundDriveProgress =
    JSONObject(json).let { view ->
        val progress = view.optObjectOrNull("progress")
        VotingRoundDriveProgress(
            kind = view.optString("kind"),
            step = view.optObjectOrNull("step")?.let(::parseNextStep),
            proofProgress = progress?.optDoubleOrNull("proof_progress")?.toFloat(),
            tally = view.optObjectOrNull("tally")?.let(::parseRoundWorkTally),
            // Only populated for a `VoteCommit` progress payload -- the real, currently-proving
            // draft's own identity, distinct from the outer `step` field's fixed id. See
            // VotingRoundDriveProgress's own doc comment.
            voteCommitProposalId = progress?.optIntOrNull("proposal_id"),
            voteCommitStage = progress?.optStringOrNull("vote_commit_stage"),
            voteCarryingBundleIndexes = view.optObjectOrNull("plan")?.let(::parseVoteCarryingBundleIndexes)
        )
    }

/** Parses `RoundDriveEventView::tally`'s JSON object (a plain `RoundWorkTally` serde encoding). */
private fun parseRoundWorkTally(json: JSONObject): VotingRoundWorkTally =
    VotingRoundWorkTally(
        completedProposals = json.optInt("completed_proposals"),
        totalProposals = json.optInt("total_proposals")
    )

private val VOTE_NEXT_STEP_KINDS =
    setOf(
        "cast_vote",
        "advance_vote",
        "advance_vote_batch",
        "submit_shares"
    )

/**
 * Every bundle index `RoundPlanView::next_steps` still owes a vote-family step for, unioned with
 * every bundle index `RoundPlanView::recovered_vote_work` names -- mirrors Vizor Wallet's
 * `voteCarryingBundleIndexes` (`voting_resume_plan.dart`). Reuses [parseNextStep] for
 * `next_steps` rather than re-deriving its own `kind` classification, matching
 * `VotingNextStep.bundleIndex`'s own already-established mapping.
 */
private fun parseVoteCarryingBundleIndexes(plan: JSONObject): List<Int> {
    val indexes = sortedSetOf<Int>()
    plan.optArrayOrNull("next_steps")?.let { steps ->
        for (index in 0 until steps.length()) {
            val stepJson = steps.getJSONObject(index)
            if (stepJson.optString("kind") in VOTE_NEXT_STEP_KINDS) {
                indexes += parseNextStep(stepJson).bundleIndex
            }
        }
    }
    plan.optArrayOrNull("recovered_vote_work")?.let { works ->
        for (index in 0 until works.length()) {
            indexes += works.getJSONObject(index).optInt("bundle_index")
        }
    }
    return indexes.toList()
}
