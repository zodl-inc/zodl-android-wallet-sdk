package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationInputs
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureBatchResult
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureInput
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSigningRequest
import cash.z.ecc.android.sdk.model.voting.VotingChainSubmissionDiagnostic
import cash.z.ecc.android.sdk.model.voting.VotingDelegationInputs
import cash.z.ecc.android.sdk.model.voting.VotingDelegationRecoveryWork
import cash.z.ecc.android.sdk.model.voting.VotingDelegationStatus
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSignatureBatchResult
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSignatureInput
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSignatureRecord
import cash.z.ecc.android.sdk.model.voting.VotingKeystoneSigningRequest
import cash.z.ecc.android.sdk.model.voting.VotingVoteRecoveryWork
import org.json.JSONArray
import org.json.JSONObject

// Delegation mappers split out of the pre-4.0 VotingSdkMappers.kt (now VotingSdkNoteMappers.kt,
// VotingSdkRoundMappers.kt, VotingSdkVoteMappers.kt, VotingSdkRoundPlanMappers.kt,
// VotingSdkRoundRunReportMappers.kt, and this file) to keep each file under detekt's
// TooManyFunctions threshold.
//
// Task 10 dropped every pre-4.0 mapper this file held (`DelegationProofResult`/
// `DelegationSubmissionResult`/`VotingTxHashLookup`/`CommitmentBundleRecord`/
// `CommittedVoteRecord`/`ShareDelegationRecord`, all deleted from `TypesafeVotingBackend.kt` by
// Task 9 alongside their sole callers) and repurposed the file for this port's real delegation
// concerns: the Keystone signing surface Task 6/7 added
// (`JniKeystoneSigningRequest`/`JniKeystoneSignatureInput`/`JniKeystoneSignatureRecord`/
// `JniKeystoneSignatureBatchResult`), and parsing `RoundPlan`'s per-bundle delegation fields
// (`delegation_statuses`/`recovered_delegation_work`/`recovered_vote_work`) out of the raw JNI
// layer's JSON-string carriers -- confirmed against `zcash_voting::session::DelegationStatus`/
// `DelegationRecoveryWork`/`VoteRecoveryWork` and `backend-lib/src/main/rust/voting/helpers.rs`'s
// `delegation_status_json`/`delegation_recovery_work_json`/`vote_recovery_work_json` at the
// crate revision this SDK is pinned to.

internal fun JniKeystoneSigningRequest.toPublic(): VotingKeystoneSigningRequest =
    VotingKeystoneSigningRequest(
        pcztBytes = pcztBytes,
        redactedPcztBytes = redactedPcztBytes,
        pcztSighash = pcztSighash,
        rk = rk,
        actionIndex = actionIndex,
        displayMemo = displayMemo,
        eligibleWeightZatoshi = eligibleWeightZatoshi,
        delegatedWeightZatoshi = delegatedWeightZatoshi,
        bundleCount = bundleCount,
        bundleIndex = bundleIndex
    )

internal fun VotingKeystoneSignatureInput.toInternal(): JniKeystoneSignatureInput =
    JniKeystoneSignatureInput(bundleIndex = bundleIndex, sig = sig, sighash = sighash, rk = rk)

internal fun JniKeystoneSignatureRecord.toPublic(): VotingKeystoneSignatureRecord =
    VotingKeystoneSignatureRecord(bundleIndex = bundleIndex, sig = sig, sighash = sighash, rk = rk)

internal fun JniKeystoneSignatureBatchResult.toPublic(): VotingKeystoneSignatureBatchResult =
    VotingKeystoneSignatureBatchResult(inserted = inserted, alreadyPresent = alreadyPresent)

/**
 * [dbHandle] is supplied by the caller ([VotingRoundSessionImpl], via
 * [TypesafeRoundSession.dbHandle]) rather than carried on [VotingDelegationInputs] itself --
 * see that public type's doc comment for why callers never need to know about JNI database
 * handles directly.
 */
internal fun VotingDelegationInputs.toInternal(dbHandle: Long): JniDelegationInputs =
    JniDelegationInputs(
        dbHandle = dbHandle,
        walletDbPath = walletDbPath,
        accountUuid = accountUuid,
        anchorTreeStateBytes = anchorTreeStateBytes,
        hotkeySecret = hotkeySecret,
        pirEndpoints = pirEndpoints.toTypedArray(),
        pirDepth = pirDepth,
        pirTier0Layers = pirTier0Layers,
        pirTier1Layers = pirTier1Layers,
        pirPolyLen = pirPolyLen,
        keystone = keystone,
        softwareSeed = softwareSeed,
        keystoneSig = keystoneSig,
        keystoneSighash = keystoneSighash
    )

/** Parses `RoundPlan::delegation_statuses`'s JSON array (`delegation_status_json`'s shape). */
internal fun parseDelegationStatuses(json: String): List<VotingDelegationStatus> =
    JSONArray(json).let { array ->
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            VotingDelegationStatus(
                bundleIndex = entry.optIntOrNull("bundleIndex") ?: 0,
                phase = entry.optString("phase"),
                txHash = entry.optStringOrNull("txHash"),
                submissionDiagnostic = entry.optObjectOrNull("submissionDiagnostic")?.toSubmissionDiagnostic(),
                terminal = entry.optBoolean("terminal")
            )
        }
    }

private fun JSONObject.toSubmissionDiagnostic(): VotingChainSubmissionDiagnostic =
    VotingChainSubmissionDiagnostic(kind = optString("kind"), message = optString("message"))

/** Parses `RoundPlan::recovered_delegation_work`'s JSON array (`delegation_recovery_work_json`'s shape). */
internal fun parseDelegationRecoveryWork(json: String): List<VotingDelegationRecoveryWork> =
    JSONArray(json).let { array ->
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            VotingDelegationRecoveryWork(
                kind = entry.optString("kind"),
                bundleIndex = entry.optIntOrNull("bundleIndex") ?: 0,
                phase = entry.optString("phase"),
                txHash = entry.optStringOrNull("txHash")
            )
        }
    }

/** Parses `RoundPlan::recovered_vote_work`'s JSON array (`vote_recovery_work_json`'s shape). */
internal fun parseVoteRecoveryWork(json: String): List<VotingVoteRecoveryWork> =
    JSONArray(json).let { array ->
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            VotingVoteRecoveryWork(
                kind = entry.optString("kind"),
                bundleIndex = entry.optIntOrNull("bundleIndex") ?: 0,
                proposalId = entry.optIntOrNull("proposalId") ?: 0,
                txHash = entry.optStringOrNull("txHash"),
                vcTreePosition = entry.optLongOrNull("vcTreePosition"),
                shareIndexes = entry.optArrayOrNull("shareIndexes")?.toIntList().orEmpty()
            )
        }
    }
