package cash.z.ecc.android.sdk.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small `org.json` helpers shared by the round-driver JSON-string carriers
 * (`VotingSdkVoteMappers.kt`/`VotingSdkDelegationMappers.kt`/`VotingSdkRoundPlanMappers.kt`/
 * `VotingSdkRoundRunReportMappers.kt`/`VotingSdkRoundDriveProgressMappers.kt`) that parse
 * Task 9's raw `Jni*` types' JSON-encoded fields into rich
 * [cash.z.ecc.android.sdk.model.voting.VotingRoundPlan]/
 * [cash.z.ecc.android.sdk.model.voting.VotingRoundRunReport] model types. `org.json` (not a
 * third-party JSON library) matches this codebase's existing convention -- see
 * `cash.z.ecc.android.sdk.internal.model.ext.CheckpointExt`'s use of `org.json.JSONObject` for
 * checkpoint parsing.
 *
 * Kept in one small file rather than duplicated per mapper file, since every one of them needs
 * the same handful of "optional field" idioms `org.json` does not provide directly.
 */

internal fun JSONArray.toIntList(): List<Int> = List(length()) { index -> getInt(index) }

internal fun JSONArray.toStringList(): List<String> = List(length()) { index -> getString(index) }

internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

internal fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

internal fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

internal fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null

internal fun JSONObject.optObjectOrNull(key: String): JSONObject? =
    if (has(key) && !isNull(key)) getJSONObject(key) else null

internal fun JSONObject.optArrayOrNull(key: String): JSONArray? =
    if (has(key) && !isNull(key)) getJSONArray(key) else null
