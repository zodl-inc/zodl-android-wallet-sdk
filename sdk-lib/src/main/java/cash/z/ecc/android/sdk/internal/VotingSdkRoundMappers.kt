package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingBundleSetupResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.model.voting.VotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingBundleLayout
import cash.z.ecc.android.sdk.model.voting.VotingPirPrecomputeReport
import cash.z.ecc.android.sdk.model.voting.VotingPirPrecomputeResult
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary
import cash.z.ecc.android.sdk.model.voting.VotingSnapshotBundlePrecomputeReport

// Round/hotkey mappers split out of the pre-4.0 VotingSdkMappers.kt (now VotingSdkNoteMappers.kt,
// VotingSdkVoteMappers.kt, VotingSdkDelegationMappers.kt, VotingSdkRoundPlanMappers.kt,
// VotingSdkRoundRunReportMappers.kt, and this file) to keep each file under detekt's
// TooManyFunctions threshold.
//
// Task 10 dropped this file's former `GovernancePcztResult.toPublic()` mapper -- its sole
// caller ([TypesafeVotingDb.buildGovernancePczt]/`buildGovernancePcztFromSeed`) is gone,
// superseded by `zcash_voting::DelegationPipeline`'s own PCZT construction. The remaining
// mappers here (round-level phase/state/summary, from `getRoundStateNative`/`listRoundsNative`,
// and hotkey/bundle-setup) are unrelated to the round-driver session model and unaffected by
// the port -- Task 9 kept their backing native calls unchanged.

internal fun JniRoundPhase.toPublic(): VotingRoundPhase =
    when (this) {
        JniRoundPhase.INITIALIZED -> VotingRoundPhase.INITIALIZED
        JniRoundPhase.HOTKEY_GENERATED -> VotingRoundPhase.HOTKEY_GENERATED
        JniRoundPhase.DELEGATION_CONSTRUCTED -> VotingRoundPhase.DELEGATION_CONSTRUCTED
        JniRoundPhase.DELEGATION_PROVED -> VotingRoundPhase.DELEGATION_PROVED
        JniRoundPhase.VOTE_READY -> VotingRoundPhase.VOTE_READY
    }

internal fun JniVotingHotkey.toPublic(): VotingHotkey =
    VotingHotkey(storedSecret = storedSecret, rawAddress = rawAddress, address = address)

internal fun JniBundleSetupResult.toPublic(): VotingBundleSetupResult =
    VotingBundleSetupResult(bundleCount = bundleCount, eligibleWeight = eligibleWeight, bundleWeights = bundleWeights)

internal fun JniRoundState.toPublic(): VotingRoundState =
    VotingRoundState(
        roundId = roundId,
        phase = roundPhase.toPublic(),
        snapshotHeight = snapshotHeight,
        hotkeyAddress = hotkeyAddress,
        delegatedWeight = delegatedWeight,
        proofGenerated = proofGenerated
    )

internal fun JniRoundSummary.toPublic(): VotingRoundSummary =
    VotingRoundSummary(
        roundId = roundId,
        phase = roundPhase.toPublic(),
        snapshotHeight = snapshotHeight,
        createdAt = createdAt
    )

// Moved here from the pre-4.0 VotingSdkDelegationMappers.kt (unrelated to the port, still
// unchanged; relocated only to keep that file's function count under detekt's TooManyFunctions
// threshold once it grew Keystone/round-plan-parsing responsibilities).
internal fun DelegationPirPrecomputeResult.toPublic(): VotingDelegationPirPrecomputeResult =
    VotingDelegationPirPrecomputeResult(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount
    )

internal fun PirPrecomputeResult.toPublic(): VotingPirPrecomputeResult =
    VotingPirPrecomputeResult(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount,
        servedRoot = servedRoot
    )

internal fun BundleLayout.toPublic(): VotingBundleLayout =
    VotingBundleLayout(
        bundleCount = bundleCount,
        eligibleWeightZatoshi = eligibleWeightZatoshi,
        droppedCount = droppedCount,
        privacyTrimDroppedBundles = privacyTrimDroppedBundles,
        privacyTrimDroppedNotes = privacyTrimDroppedNotes,
        privacyTrimDroppedValueZatoshi = privacyTrimDroppedValueZatoshi,
        skippedSuffixBundles = skippedSuffixBundles,
        skippedSuffixNotes = skippedSuffixNotes,
        skippedSuffixValueZatoshi = skippedSuffixValueZatoshi
    )

internal fun PirPrecomputeReport.toPublic(): VotingPirPrecomputeReport =
    VotingPirPrecomputeReport(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount
    )

internal fun SnapshotBundlePrecomputeReport.toPublic(): VotingSnapshotBundlePrecomputeReport =
    VotingSnapshotBundlePrecomputeReport(
        layout = layout.toPublic(),
        bundles = bundles.map { it.toPublic() }
    )
