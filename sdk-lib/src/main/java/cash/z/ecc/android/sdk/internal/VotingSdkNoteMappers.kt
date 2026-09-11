package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingNoteScope
import cash.z.ecc.android.sdk.model.voting.VotingWitness

// Note/witness mappers split out of the pre-4.0 VotingSdkMappers.kt (now VotingSdkRoundMappers.kt,
// VotingSdkVoteMappers.kt, VotingSdkDelegationMappers.kt, VotingSdkRoundPlanMappers.kt,
// VotingSdkRoundRunReportMappers.kt, and this file) to keep each file under detekt's
// TooManyFunctions threshold.
//
// Task 10 trimmed this file down to [VotingNoteInfo]/[VotingWitness] -- the only two of this
// file's pre-4.0 mappers whose backing native call ([TypesafeVotingDb.computeBundleSetup]/
// [TypesafeVotingDb.setupBundles]/[TypesafeVotingBackend.verifyWitness]) survived the port.
// [cash.z.ecc.android.sdk.model.voting.VotingVanWitness]/`VotingEncryptedShare` and this file's
// former `cash.z.ecc.android.sdk.internal.VotingNoteInfo.toPublic()` mapper (for the now-deleted
// `getWalletNotes`) are gone with their sole callers ([TypesafeVotingBackend.buildVoteCommitment]/
// `getWalletNotes`), superseded by `zcash_voting`'s own vote/witness construction inside
// `DelegationPipeline`/`RoundDriver`.

internal fun JniNoteInfo.toPublic(): VotingNoteInfo =
    VotingNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope = if (scope == 0) VotingNoteScope.EXTERNAL else VotingNoteScope.INTERNAL,
        ufvk = ufvk
    )

internal fun VotingNoteInfo.toInternal(): cash.z.ecc.android.sdk.internal.VotingNoteInfo =
    cash.z.ecc.android.sdk.internal.VotingNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope =
            if (scope == VotingNoteScope.EXTERNAL) {
                cash.z.ecc.android.sdk.internal.VotingNoteScope.EXTERNAL
            } else {
                cash.z.ecc.android.sdk.internal.VotingNoteScope.INTERNAL
            },
        ufvk = ufvk
    )

internal fun JniWitnessData.toPublic(): VotingWitness =
    VotingWitness(noteCommitment = noteCommitment, position = position, root = root, authPath = authPath)

internal fun VotingWitness.toInternal(): JniWitnessData =
    JniWitnessData(noteCommitment = noteCommitment, position = position, root = root, authPath = authPath)
