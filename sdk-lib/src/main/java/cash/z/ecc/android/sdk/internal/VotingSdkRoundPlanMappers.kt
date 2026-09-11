package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPlan
import cash.z.ecc.android.sdk.model.voting.VotingCompletedVoteChoice
import cash.z.ecc.android.sdk.model.voting.VotingCompletedVoteDisplay
import cash.z.ecc.android.sdk.model.voting.VotingImmediateShareKey
import cash.z.ecc.android.sdk.model.voting.VotingRoundPlan
import cash.z.ecc.android.sdk.model.voting.VotingRoundPlanAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses Task 9's raw [JniRoundPlan] carrier into the rich [VotingRoundPlan] this port's public
 * surface exposes -- new for Task 10, not part of the pre-4.0 mapper set. Not in the original
 * brief's exact file list (which named only `VotingSdkNoteMappers.kt`/`VotingSdkRoundMappers.kt`/
 * `VotingSdkVoteMappers.kt`/`VotingSdkDelegationMappers.kt`); added following the same
 * precedent Task 9 set adding `JniVotingModels.kt` when the real work required a file the brief
 * did not anticipate -- `RoundPlan`'s 28 fields' worth of parsing does not fit cleanly into any
 * one of those four without blowing past detekt's TooManyFunctions/LongMethod thresholds.
 *
 * Every field is mapped one-to-one against `zcash_voting::session::RoundPlan`'s real field
 * list, re-verified against the crate revision this SDK is pinned to
 * (`0eccfe692068e394991c143a0bac5ecbff753bf1`) rather than trusting an older citation, per this
 * plan's standing instruction. JSON string fields (`next_steps_json`/`delegation_statuses_json`/
 * etc.) are parsed via `VotingSdkVoteMappers.kt`/`VotingSdkDelegationMappers.kt`'s per-field
 * parsers; see `encode_round_plan` in `backend-lib/src/main/rust/voting/helpers.rs` for the
 * exact JSON shape each one was built from.
 */
internal fun JniRoundPlan.toPublic(): VotingRoundPlan =
    VotingRoundPlan(
        roundId = roundId,
        pendingRecovery = pendingRecovery,
        nextSteps = parseNextSteps(nextStepsJson),
        openProposals = openProposals.toList(),
        unrosteredIntents = unrosteredIntents.toList(),
        immediateShareKey = immediateShareKeyJson?.let { parseImmediateShareKey(it) },
        immediateShareConfirmed = immediateShareConfirmed,
        allDecided = allDecided,
        delegationStatuses = parseDelegationStatuses(delegationStatusesJson),
        blockingRecovery = blockingRecovery,
        blockingShareWork = blockingShareWork,
        hasUnconfirmedShares = hasUnconfirmedShares,
        hotkeyBound = hotkeyBound,
        completedVoteArtifact = completedVoteArtifact,
        completedForDisplay = completedForDisplay,
        completedVoteDisplay = completedVoteDisplayJson?.let { parseCompletedVoteDisplay(it) },
        needsDraftSetup = needsDraftSetup,
        needsBundleSetup = needsBundleSetup,
        primaryAction = VotingRoundPlanAction.fromJniValue(primaryAction),
        needsDelegationSigning = needsDelegationSigning,
        hasInFlightDelegation = hasInFlightDelegation,
        delegationBundlesNeedingWork = delegationBundlesNeedingWork.toList(),
        delegationBundlesNeedingSigning = delegationBundlesNeedingSigning.toList(),
        needsVotePolling = needsVotePolling,
        hasRemainingVoteOrShareWork = hasRemainingVoteOrShareWork,
        hasRecoverableVoteOrShareWork = hasRecoverableVoteOrShareWork,
        recoveredDelegationWork = parseDelegationRecoveryWork(recoveredDelegationWorkJson),
        recoveredVoteWork = parseVoteRecoveryWork(recoveredVoteWorkJson)
    )

/**
 * Parses `RoundPlan::immediate_share_key`'s JSON object -- a plain crate `#[derive(Serialize)]`,
 * so field names stay Rust snake_case.
 */
private fun parseImmediateShareKey(json: String): VotingImmediateShareKey =
    JSONObject(json).let { obj ->
        VotingImmediateShareKey(
            bundleIndex = obj.optInt("bundle_index"),
            proposalId = obj.optInt("proposal_id"),
            shareIndex = obj.optInt("share_index")
        )
    }

/** Parses `RoundPlan::completed_vote_display`'s JSON object (`completed_vote_display_json`'s camelCase shape). */
private fun parseCompletedVoteDisplay(json: String): VotingCompletedVoteDisplay =
    JSONObject(json).let { obj ->
        val choices = obj.optJSONArray("choices") ?: JSONArray()
        VotingCompletedVoteDisplay(
            choices =
                List(choices.length()) { index ->
                    val choice = choices.getJSONObject(index)
                    VotingCompletedVoteChoice(
                        proposalId = choice.optInt("proposalId"),
                        choice = choice.optIntOrNull("choice")
                    )
                },
            votedAt = obj.optLongOrNull("votedAt")
        )
    }
