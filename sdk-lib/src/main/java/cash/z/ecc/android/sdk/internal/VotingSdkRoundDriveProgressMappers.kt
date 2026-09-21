package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.voting.VotingRoundDriveProgress
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
        VotingRoundDriveProgress(
            kind = view.optString("kind"),
            step = view.optObjectOrNull("step")?.let(::parseNextStep),
            proofProgress =
                view.optObjectOrNull("progress")?.optDoubleOrNull("proof_progress")?.toFloat()
        )
    }
