package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.voting.VotingChainOutcome
import cash.z.ecc.android.sdk.model.voting.VotingNextStep
import cash.z.ecc.android.sdk.model.voting.VotingRoundStepFailure
import org.json.JSONArray
import org.json.JSONObject

// Vote/round-drive-outcome mappers split out of the pre-4.0 VotingSdkMappers.kt (now
// VotingSdkNoteMappers.kt, VotingSdkRoundMappers.kt, VotingSdkDelegationMappers.kt,
// VotingSdkRoundPlanMappers.kt, VotingSdkRoundRunReportMappers.kt, and this file) to keep each
// file under detekt's TooManyFunctions threshold.
//
// Task 10 repurposed this file entirely: every pre-4.0 vote-commit mapper it held
// (`JniVoteCommitmentResult`/`JniVoteCommitResult`/`JniSharePayload`/`JniVoteRecord`) lost its
// sole caller when the corresponding `TypesafeVotingDb` methods
// (`buildVoteCommitment`/`buildSharePayloads`/`getVotes`) were deleted -- `zcash_voting`'s own
// `RoundDriver`/`vote_work` module now owns vote construction and submission entirely, reached
// only through [VotingRoundSession.run]. What replaces them is this file's real "vote work"
// concern in the round-driver model: parsing `zcash_voting::session::NextStep` --
// `getRoundPlanNative`'s/`setBallotIntentsNative`'s `next_steps` field, and every other place a
// `NextStep` shows up (round-drive failures, chain outcomes, quiescence detail) -- out of the
// raw JNI layer's JSON-string carriers. `NextStep`'s own `Serialize` derive uses its Rust field
// names verbatim (snake_case: `bundle_index`/`proposal_id`/`choice`/`share_index`) with a
// `kind`-tagged, snake_case-renamed variant discriminator (`serde(tag = "kind", rename_all =
// "snake_case")`) -- confirmed against `zcash_voting::session::NextStep`'s definition at the
// crate revision this SDK is pinned to (`0eccfe692068e394991c143a0bac5ecbff753bf1`).

/**
 * Parses one `NextStep` JSON object. [VotingNextStep.Unknown] covers a future crate variant
 * this SDK does not recognize yet (`NextStep` is `#[non_exhaustive]` in the crate) -- read
 * defensively (`optInt` rather than a required-field getter) so an unrecognized shape still
 * yields a bundle index where present instead of throwing.
 */
internal fun parseNextStep(json: JSONObject): VotingNextStep {
    val bundleIndex = json.optInt("bundle_index")
    return when (val kind = json.optString("kind")) {
        "delegate" -> {
            VotingNextStep.Delegate(bundleIndex)
        }

        "advance_delegation" -> {
            VotingNextStep.AdvanceDelegation(bundleIndex)
        }

        "advance_imported_delegation" -> {
            VotingNextStep.AdvanceImportedDelegation(bundleIndex)
        }

        "cast_vote" -> {
            VotingNextStep.CastVote(
                bundleIndex = bundleIndex,
                proposalId = json.optInt("proposal_id"),
                choice = json.optInt("choice")
            )
        }

        "advance_vote" -> {
            VotingNextStep.AdvanceVote(bundleIndex, json.optInt("proposal_id"))
        }

        "advance_vote_batch" -> {
            VotingNextStep.AdvanceVoteBatch(bundleIndex, json.optInt("proposal_id"))
        }

        "submit_shares" -> {
            VotingNextStep.SubmitShares(
                bundleIndex = bundleIndex,
                proposalId = json.optInt("proposal_id"),
                shareIndex = json.optInt("share_index")
            )
        }

        "confirm_share" -> {
            VotingNextStep.ConfirmShare(
                bundleIndex = bundleIndex,
                proposalId = json.optInt("proposal_id"),
                shareIndex = json.optInt("share_index")
            )
        }

        else -> {
            VotingNextStep.Unknown(bundleIndex, kind, json.toString())
        }
    }
}

/** Parses `RoundPlan::next_steps`'/`RoundQuiescence::PassBudgetExhausted::remaining`'s JSON array. */
internal fun parseNextSteps(json: String): List<VotingNextStep> =
    JSONArray(json).let { array -> List(array.length()) { index -> parseNextStep(array.getJSONObject(index)) } }

/** Parses one optional embedded `NextStep` field (`RoundQuiescence::ChainTerminal`/`ChainRecoveryStalled`'s `step`). */
internal fun parseOptionalNextStep(json: JSONObject, key: String): VotingNextStep? =
    json.optObjectOrNull(key)?.let { parseNextStep(it) }

/** Parses `RoundRunReport::failures`'s JSON array (`round_step_failure_record_json`'s shape). */
internal fun parseRoundStepFailures(json: String): List<VotingRoundStepFailure> =
    JSONArray(json).let { array ->
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            VotingRoundStepFailure(
                step = parseOptionalNextStep(entry, "step"),
                bundleIndex = entry.optIntOrNull("bundleIndex"),
                kind = entry.optString("kind"),
                message = entry.optString("message")
            )
        }
    }

/** Parses `RoundRunReport::chain_outcomes`'s JSON array (`step`/`outcome` pairs). */
internal fun parseChainOutcomes(json: String): List<VotingChainOutcome> =
    JSONArray(json).let { array ->
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            VotingChainOutcome(
                step = parseOptionalNextStep(entry, "step"),
                outcome = entry.optString("outcome")
            )
        }
    }

/** Parses `RoundRunReport::share_deliveries`'s JSON array of raw crate debug strings. */
internal fun parseShareDeliveries(json: String): List<String> = JSONArray(json).toStringList()

/** Parses one JSON array of `NextStep` objects, e.g. `RoundQuiescence::PassBudgetExhausted::remaining`. */
internal fun JSONArray.toNextStepList(): List<VotingNextStep> =
    List(length()) { index -> parseNextStep(getJSONObject(index)) }
