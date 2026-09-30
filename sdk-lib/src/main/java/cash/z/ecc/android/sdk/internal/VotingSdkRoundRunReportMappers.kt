package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniRoundRunReport
import cash.z.ecc.android.sdk.internal.model.voting.JniShareTrackingRunReport
import cash.z.ecc.android.sdk.model.voting.VotingResubmittedShare
import cash.z.ecc.android.sdk.model.voting.VotingRoundQuiescence
import cash.z.ecc.android.sdk.model.voting.VotingRoundRunReport
import cash.z.ecc.android.sdk.model.voting.VotingShareKey
import cash.z.ecc.android.sdk.model.voting.VotingShareTrackingQuiescence
import cash.z.ecc.android.sdk.model.voting.VotingShareTrackingReport
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses Task 9's raw [JniRoundRunReport]/[JniShareTrackingRunReport] carriers into this port's
 * public [VotingRoundRunReport]/[VotingShareTrackingReport] -- new for Task 10, not part of the
 * pre-4.0 mapper set; added alongside `VotingSdkRoundPlanMappers.kt` for the same reason (see
 * that file's doc comment for the precedent this follows).
 *
 * The quiescence discriminator strings and their per-variant detail JSON shapes are re-verified
 * against `round_quiescence_kind`/`round_quiescence_detail_json`/`share_tracking_quiescence_kind`/
 * `share_tracking_quiescence_detail_json` in `backend-lib/src/main/rust/voting/helpers.rs`, and
 * against `zcash_voting::round_drive::RoundQuiescence`/`zcash_voting::share_tracking::
 * ShareTrackingQuiescence` at the crate revision this SDK is pinned to
 * (`0eccfe692068e394991c143a0bac5ecbff753bf1`).
 */
internal fun JniRoundRunReport.toPublic(): VotingRoundRunReport =
    VotingRoundRunReport(
        quiescence = parseRoundQuiescence(quiescenceKind, quiescenceDetailJson),
        plan = plan?.toPublic(),
        completedProposals = completedProposals,
        totalProposals = totalProposals,
        remainingObligations = remainingObligations,
        failures = parseRoundStepFailures(failuresJson),
        skippedBundles = skippedBundles.toList(),
        chainOutcomes = parseChainOutcomes(chainOutcomesJson),
        shareDeliveries = parseShareDeliveries(shareDeliveriesJson),
        delegationsSignedCount = delegationsSignedCount
    )

// Split into `noDetailRoundQuiescence`/`detailedRoundQuiescence` (rather than one `when` over
// all 11 kinds) to keep this function's own cyclomatic complexity under detekt's threshold --
// matching this codebase's established "split into per-group helpers" convention (see e.g.
// `VotingCommitmentResult`'s pre-4.0 `equals()` in this file's git history).
private fun parseRoundQuiescence(
    kind: String,
    detailJson: String?
): VotingRoundQuiescence {
    noDetailRoundQuiescence(kind)?.let { return it }
    val detail = detailJson?.let { JSONObject(it) }
    return detailedRoundQuiescence(kind, detail) ?: VotingRoundQuiescence.Unknown(kind, detailJson)
}

private fun noDetailRoundQuiescence(kind: String): VotingRoundQuiescence? =
    when (kind) {
        "no_work_left" -> VotingRoundQuiescence.NoWorkLeft
        "needs_bundle_setup" -> VotingRoundQuiescence.NeedsBundleSetup
        "persisted_chain_terminal" -> VotingRoundQuiescence.PersistedChainTerminal
        "cancelled" -> VotingRoundQuiescence.Cancelled
        "failures" -> VotingRoundQuiescence.Failures
        else -> null
    }

private fun detailedRoundQuiescence(
    kind: String,
    detail: JSONObject?
): VotingRoundQuiescence? =
    when (kind) {
        "needs_ballot" -> {
            VotingRoundQuiescence.NeedsBallot(
                openProposals = detail?.optArrayOrNull("openProposals")?.toIntList().orEmpty(),
                unrosteredIntents = detail?.optArrayOrNull("unrosteredIntents")?.toIntList().orEmpty()
            )
        }

        "needs_delegation_signatures" -> {
            VotingRoundQuiescence.NeedsDelegationSignatures(
                bundles = detail?.optArrayOrNull("bundles")?.toIntList().orEmpty()
            )
        }

        "background_share_work_only" -> {
            VotingRoundQuiescence.BackgroundShareWorkOnly(
                shares = detail?.optArrayOrNull("shares")?.toStringList().orEmpty()
            )
        }

        "chain_terminal" -> {
            VotingRoundQuiescence.ChainTerminal(
                step = detail?.let { parseOptionalNextStep(it, "step") },
                outcome = detail?.optString("outcome").orEmpty()
            )
        }

        "chain_recovery_stalled" -> {
            VotingRoundQuiescence.ChainRecoveryStalled(
                step = detail?.let { parseOptionalNextStep(it, "step") },
                outcome = detail?.optString("outcome").orEmpty()
            )
        }

        "pass_budget_exhausted" -> {
            VotingRoundQuiescence.PassBudgetExhausted(
                remaining = detail?.optArrayOrNull("remaining")?.toNextStepList().orEmpty()
            )
        }

        else -> {
            null
        }
    }

internal fun JniShareTrackingRunReport.toPublic(): VotingShareTrackingReport =
    VotingShareTrackingReport(
        quiescence = parseShareTrackingQuiescence(quiescenceKind, quiescenceDetailJson),
        passes = passes,
        confirmed = parseShareKeys(confirmedJson),
        resubmitted = parseResubmittedShares(resubmittedJson),
        ambiguous = parseResubmittedShares(ambiguousJson),
        unrecoverable = parseShareKeys(unrecoverableJson),
        failures = JSONArray(failuresJson).toStringList()
    )

private fun parseShareTrackingQuiescence(
    kind: String,
    detailJson: String?
): VotingShareTrackingQuiescence {
    val detail = detailJson?.let { JSONObject(it) }
    return when (kind) {
        "nothing_to_track" -> {
            VotingShareTrackingQuiescence.NothingToTrack
        }

        "all_confirmed" -> {
            VotingShareTrackingQuiescence.AllConfirmed
        }

        "vote_end_reached" -> {
            VotingShareTrackingQuiescence.VoteEndReached
        }

        "cancelled" -> {
            VotingShareTrackingQuiescence.Cancelled
        }

        "already_driving" -> {
            VotingShareTrackingQuiescence.AlreadyDriving
        }

        "failing" -> {
            VotingShareTrackingQuiescence.Failing(
                messages = detail?.optArrayOrNull("messages")?.toStringList().orEmpty()
            )
        }

        "pass_budget_exhausted" -> {
            VotingShareTrackingQuiescence.PassBudgetExhausted(
                unrecoverable = detail?.optArrayOrNull("unrecoverable")?.toShareKeyList().orEmpty()
            )
        }

        else -> {
            VotingShareTrackingQuiescence.Unknown(kind, detailJson)
        }
    }
}

private fun parseShareKeys(json: String): List<VotingShareKey> = JSONArray(json).toShareKeyList()

private fun JSONArray.toShareKeyList(): List<VotingShareKey> =
    List(length()) { index -> getJSONObject(index).toShareKey() }

private fun JSONObject.toShareKey(): VotingShareKey =
    VotingShareKey(
        bundleIndex = optInt("bundleIndex"),
        proposalId = optInt("proposalId"),
        shareIndex = optInt("shareIndex")
    )

private fun parseResubmittedShares(json: String): List<VotingResubmittedShare> =
    JSONArray(json).let { array ->
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            VotingResubmittedShare(
                share = entry.getJSONObject("share").toShareKey(),
                serverUrl = entry.optString("serverUrl")
            )
        }
    }
