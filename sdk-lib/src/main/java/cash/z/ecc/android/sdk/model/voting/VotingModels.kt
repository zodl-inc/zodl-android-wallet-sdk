package cash.z.ecc.android.sdk.model.voting

/** Public mirror of the internal `VotingNoteScope` — which shielded-address scope a note came from. */
enum class VotingNoteScope {
    EXTERNAL,
    INTERNAL
}

/**
 * A shielded note eligible for voting weight, as read from wallet state. `rho`/`rseed` are
 * note secrets and must not be logged.
 */
data class VotingNoteInfo(
    val commitment: ByteArray,
    val nullifier: ByteArray,
    val value: Long,
    val position: Long,
    val diversifier: ByteArray,
    val rho: ByteArray,
    val rseed: ByteArray,
    val scope: VotingNoteScope,
    val ufvk: String
) {
    override fun toString(): String = "VotingNoteInfo(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingNoteInfo) return false
        return commitment.contentEquals(other.commitment) &&
            nullifier.contentEquals(other.nullifier) &&
            value == other.value &&
            position == other.position &&
            diversifier.contentEquals(other.diversifier) &&
            rho.contentEquals(other.rho) &&
            rseed.contentEquals(other.rseed) &&
            scope == other.scope &&
            ufvk == other.ufvk
    }

    override fun hashCode(): Int {
        var result = commitment.contentHashCode()
        result = 31 * result + nullifier.contentHashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + diversifier.contentHashCode()
        result = 31 * result + rho.contentHashCode()
        result = 31 * result + rseed.contentHashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + ufvk.hashCode()
        return result
    }
}

/** A merkle authentication path + root for one note, used for PCZT witness construction. */
data class VotingWitness(
    val noteCommitment: ByteArray,
    val position: Long,
    val root: ByteArray,
    val authPath: List<ByteArray>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingWitness) return false
        return noteCommitment.contentEquals(other.noteCommitment) &&
            position == other.position &&
            root.contentEquals(other.root) &&
            authPath.size == other.authPath.size &&
            authPath.zip(other.authPath).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int {
        var result = noteCommitment.contentHashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + root.contentHashCode()
        result = 31 * result + authPath.size
        return result
    }
}

/** A stored voting hotkey identity. `storedSecret` is sensitive and must not be logged. */
data class VotingHotkey(
    val storedSecret: ByteArray,
    val rawAddress: ByteArray,
    val address: String
) {
    override fun toString(): String = "VotingHotkey(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingHotkey) return false
        return storedSecret.contentEquals(other.storedSecret) &&
            rawAddress.contentEquals(other.rawAddress) &&
            address == other.address
    }

    override fun hashCode(): Int = address.hashCode()
}

/** The count/weight summary produced when a round's bundles are first laid out. */
data class VotingBundleSetupResult(
    val bundleCount: Int,
    val eligibleWeight: Long,
    val bundleWeights: List<Long>
)

/**
 * The canonical per-round phase, matching `zcash_voting::phases`' round-level model. Read-only
 * via [cash.z.ecc.android.sdk.VotingDbSession.getRoundState]/[cash.z.ecc.android.sdk.VotingDbSession.listRounds]
 * -- unrelated to [VotingRoundPlan]'s richer, round-driver-derived resume state.
 */
enum class VotingRoundPhase {
    INITIALIZED,
    HOTKEY_GENERATED,
    DELEGATION_CONSTRUCTED,
    DELEGATION_PROVED,
    VOTE_READY
}

data class VotingRoundState(
    val roundId: String,
    val phase: VotingRoundPhase,
    val snapshotHeight: Long,
    val hotkeyAddress: String?,
    val delegatedWeight: Long?,
    val proofGenerated: Boolean
)

data class VotingRoundSummary(
    val roundId: String,
    val phase: VotingRoundPhase,
    val snapshotHeight: Long,
    val createdAt: Long
)

data class VotingDelegationPirPrecomputeResult(
    val cachedCount: Long,
    val fetchedCount: Long
)

// ---------------------------------------------------------------------------------------------
// Round-driver session model (voting-4.0.0 SDK port). Everything below mirrors
// `zcash_voting::session`/`zcash_voting::round_drive`/`zcash_voting::share_tracking` types,
// parsed from Task 9's raw `Jni*` carriers (see `VotingSdkRoundPlanMappers.kt`/
// `VotingSdkRoundRunReportMappers.kt`). Re-verified against the pinned crate revision
// (`0eccfe692068e394991c143a0bac5ecbff753bf1`, `zcash_voting::session::RoundPlan`/`NextStep`) at
// the time this was written, per this plan's standing instruction not to trust an old citation.
// ---------------------------------------------------------------------------------------------

/**
 * One entry of a round's authenticated proposal roster -- mirrors
 * `zcash_voting::vote_work::ProposalRosterEntry` (the crate's own type), passed to
 * [cash.z.ecc.android.sdk.VotingDbSession.openRoundSession] in place of the raw JNI layer's
 * two parallel `IntArray`s.
 */
data class VotingProposalRosterEntry(
    val proposalId: Int,
    val numOptions: Int
)

/**
 * One ballot decision for [cash.z.ecc.android.sdk.VotingRoundSession.setBallotIntents]. `choice
 * == null` decodes to a skipped decision, matching `zcash_voting::session::Decision::Skipped`
 * (the raw JNI layer's `choices[i] < 0` sentinel, translated at this boundary).
 */
data class VotingBallotIntent(
    val proposalId: Int,
    val choice: Int?
)

/**
 * Software/Keystone delegation-signing inputs for [cash.z.ecc.android.sdk.VotingRoundSession.run].
 *
 * Mirrors the raw JNI layer's `JniDelegationInputs` minus `dbHandle`: the [VotingRoundSession]
 * that accepts this supplies the underlying database handle automatically from the
 * [cash.z.ecc.android.sdk.VotingDbSession] it was opened against, so callers never need to know
 * about JNI database handles.
 *
 * [keystone] selects the signer: `true` uses a Keystone-signed delegation ([keystoneSig]/
 * [keystoneSighash] both present replay a previously-obtained signature; both absent resumes
 * from a previously *persisted* Keystone signature; exactly one present is rejected by the
 * native side). `false` requires [softwareSeed] and signs with the wallet's own seed.
 *
 * [softwareSeed] and [keystoneSig]/[keystoneSighash] are sensitive signing-path inputs and must
 * not be logged.
 *
 * [snapshotHeight]/[eaPk]/[ncRoot]/[nullifierImtRoot] are the round's own metadata -- together
 * with the `roundId` already passed to [cash.z.ecc.android.sdk.VotingDbSession.openRoundSession],
 * this is the complete `VotingRoundParams` the native side needs. Callers must supply these
 * directly from the same authenticated round config used to fetch [anchorTreeStateBytes] and to
 * call [cash.z.ecc.android.sdk.VotingDbSession.ensureRound]; the native side never reads them
 * back from a database row, since a brand-new round has no row there yet. This removes the
 * premature-read bug that used to reject a virgin round outright -- but, per
 * [VotingRoundSession.run]'s doc comment, [VotingDbSession.ensureRound] (plus
 * [cash.z.ecc.android.sdk.VotingDbSession.setupBundles]) must still be called before [run] for a
 * round that has never been bootstrapped; passing these fields to [run] alone is not sufficient.
 */
data class VotingDelegationInputs(
    val walletDbPath: String,
    val accountUuid: String,
    val anchorTreeStateBytes: ByteArray,
    val hotkeySecret: ByteArray?,
    val pirEndpoints: List<String>,
    val pirDepth: Int,
    val pirTier0Layers: Int,
    val pirTier1Layers: Int,
    val pirPolyLen: Int,
    val keystone: Boolean,
    val softwareSeed: ByteArray?,
    val keystoneSig: ByteArray?,
    val keystoneSighash: ByteArray?,
    val snapshotHeight: Long,
    val eaPk: ByteArray,
    val ncRoot: ByteArray,
    val nullifierImtRoot: ByteArray
) {
    override fun toString(): String = "VotingDelegationInputs(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingDelegationInputs) return false
        return scalarFieldsEqual(other) && byteFieldsEqual(other)
    }

    private fun scalarFieldsEqual(other: VotingDelegationInputs) =
        walletDbPath == other.walletDbPath &&
            accountUuid == other.accountUuid &&
            pirEndpoints == other.pirEndpoints &&
            pirDepth == other.pirDepth &&
            pirTier0Layers == other.pirTier0Layers &&
            pirTier1Layers == other.pirTier1Layers &&
            pirPolyLen == other.pirPolyLen &&
            keystone == other.keystone &&
            snapshotHeight == other.snapshotHeight

    private fun byteFieldsEqual(other: VotingDelegationInputs) =
        anchorTreeStateBytes.contentEquals(other.anchorTreeStateBytes) &&
            hotkeySecret.nullableContentEquals(other.hotkeySecret) &&
            softwareSeed.nullableContentEquals(other.softwareSeed) &&
            keystoneSig.nullableContentEquals(other.keystoneSig) &&
            keystoneSighash.nullableContentEquals(other.keystoneSighash) &&
            eaPk.contentEquals(other.eaPk) &&
            ncRoot.contentEquals(other.ncRoot) &&
            nullifierImtRoot.contentEquals(other.nullifierImtRoot)

    override fun hashCode(): Int {
        var result = walletDbPath.hashCode()
        result = 31 * result + accountUuid.hashCode()
        result = 31 * result + anchorTreeStateBytes.contentHashCode()
        result = 31 * result + pirEndpoints.hashCode()
        result = 31 * result + keystone.hashCode()
        result = 31 * result + snapshotHeight.hashCode()
        result = 31 * result + eaPk.contentHashCode()
        result = 31 * result + ncRoot.contentHashCode()
        result = 31 * result + nullifierImtRoot.contentHashCode()
        return result
    }
}

private fun ByteArray?.nullableContentEquals(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

/**
 * One unit of remaining round work -- mirrors `zcash_voting::session::NextStep`. [bundleIndex]
 * is common to every known variant. [Unknown] covers a future crate variant this SDK does not
 * recognize yet (`NextStep` is `#[non_exhaustive]` in the crate).
 */
sealed interface VotingNextStep {
    val bundleIndex: Int

    /** This bundle still needs a signed delegation before anything can be dispatched. */
    data class Delegate(
        override val bundleIndex: Int
    ) : VotingNextStep

    /** Advance one delegation that is already durably in flight. */
    data class AdvanceDelegation(
        override val bundleIndex: Int
    ) : VotingNextStep

    /** Poll one already-broadcast delegation imported from a capability. */
    data class AdvanceImportedDelegation(
        override val bundleIndex: Int
    ) : VotingNextStep

    /** Cast a vote using the recorded ballot intent choice. */
    data class CastVote(
        override val bundleIndex: Int,
        val proposalId: Int,
        val choice: Int
    ) : VotingNextStep

    /** Advance one singleton vote's chain submission by one bounded pass. */
    data class AdvanceVote(
        override val bundleIndex: Int,
        val proposalId: Int
    ) : VotingNextStep

    /** Advance one atomic vote batch's chain submission by one bounded pass. */
    data class AdvanceVoteBatch(
        override val bundleIndex: Int,
        val proposalId: Int
    ) : VotingNextStep

    /** Resume helper-share submission for a committed vote. */
    data class SubmitShares(
        override val bundleIndex: Int,
        val proposalId: Int,
        val shareIndex: Int
    ) : VotingNextStep

    data class ConfirmShare(
        override val bundleIndex: Int,
        val proposalId: Int,
        val shareIndex: Int
    ) : VotingNextStep

    /** A future crate variant this SDK does not recognize yet. [rawJson] preserves the original entry. */
    data class Unknown(
        override val bundleIndex: Int,
        val kind: String,
        val rawJson: String
    ) : VotingNextStep
}

/** High-level work area for a round -- mirrors `zcash_voting::session::RoundPlanAction`. */
enum class VotingRoundPlanAction {
    IDLE,
    DELEGATE,
    VOTE,
    SUBMIT_SHARES,
    DONE,

    /** A future crate variant this SDK does not recognize yet (`RoundPlanAction` is `#[non_exhaustive]`). */
    UNKNOWN;

    companion object {
        @Suppress("MagicNumber")
        fun fromJniValue(value: Int): VotingRoundPlanAction =
            when (value) {
                0 -> IDLE
                1 -> DELEGATE
                2 -> VOTE
                3 -> SUBMIT_SHARES
                4 -> DONE
                else -> UNKNOWN
            }
    }
}

/**
 * A bounded, redacted lifecycle diagnostic -- mirrors `zcash_voting::chain_submission::
 * ChainSubmissionDiagnostic`. [kind] is the stable `ChainSubmissionDiagnosticKind::as_str()`
 * discriminator (e.g. `"ambiguous_dispatch"`, `"nullifier_already_spent"`); kept as a raw wire
 * string rather than an enum here, since the set of valid strings is owned by the Rust crate,
 * not this SDK -- matching this file's own established convention for other crate-owned phase
 * strings.
 */
data class VotingChainSubmissionDiagnostic(
    val kind: String,
    val message: String
)

/** Durable delegation state for one eligible bundle -- mirrors `zcash_voting::session::DelegationStatus`. */
data class VotingDelegationStatus(
    val bundleIndex: Int,
    /** Raw wire string per `DelegationPhase::as_str` -- see [VotingChainSubmissionDiagnostic]'s doc comment for why. */
    val phase: String,
    val txHash: String?,
    val submissionDiagnostic: VotingChainSubmissionDiagnostic?,
    val terminal: Boolean
)

/** Grouped delegation recovery work for one bundle -- mirrors `zcash_voting::session::DelegationRecoveryWork`. */
data class VotingDelegationRecoveryWork(
    /** `"delegate"`, `"advance_delegation"`, or `"advance_imported_delegation"`. */
    val kind: String,
    val bundleIndex: Int,
    val phase: String,
    val txHash: String?
)

/**
 * Grouped vote recovery work keyed by one singleton action or batch anchor -- mirrors
 * `zcash_voting::session::VoteRecoveryWork`.
 */
data class VotingVoteRecoveryWork(
    /** `"advance_vote"`, `"advance_vote_batch"`, or `"submit_shares"`. */
    val kind: String,
    val bundleIndex: Int,
    val proposalId: Int,
    val txHash: String?,
    val vcTreePosition: Long?,
    val shareIndexes: List<Int>
)

/** Display choice for one proposal in a completed round -- mirrors `zcash_voting::session::CompletedVoteChoice`. */
data class VotingCompletedVoteChoice(
    val proposalId: Int,
    /** `null` when the proposal was skipped or bundles disagree. */
    val choice: Int?
)

/** Read-only display summary for a locally completed vote -- mirrors `zcash_voting::session::CompletedVoteDisplay`. */
data class VotingCompletedVoteDisplay(
    val choices: List<VotingCompletedVoteChoice>,
    val votedAt: Long?
)

/**
 * Identifies a round's single designated immediate helper share -- mirrors
 * `zcash_voting::share_policy::ImmediateShareKey`.
 */
data class VotingImmediateShareKey(
    val bundleIndex: Int,
    val proposalId: Int,
    val shareIndex: Int
)

/**
 * Derived resume state for one round -- mirrors `zcash_voting::session::RoundPlan` field for
 * field (28 fields, re-verified against the crate revision this SDK is pinned to). See
 * `VotingSdkRoundPlanMappers.kt` for how each field is parsed out of the raw JNI carrier.
 */
data class VotingRoundPlan(
    val roundId: String,
    val pendingRecovery: Boolean,
    val nextSteps: List<VotingNextStep>,
    val openProposals: List<Int>,
    val unrosteredIntents: List<Int>,
    val immediateShareKey: VotingImmediateShareKey?,
    val immediateShareConfirmed: Boolean,
    val allDecided: Boolean,
    val delegationStatuses: List<VotingDelegationStatus>,
    val blockingRecovery: Boolean,
    val blockingShareWork: Boolean,
    val hasUnconfirmedShares: Boolean,
    val hotkeyBound: Boolean,
    val completedVoteArtifact: Boolean,
    val completedForDisplay: Boolean,
    val completedVoteDisplay: VotingCompletedVoteDisplay?,
    val needsDraftSetup: Boolean,
    val needsBundleSetup: Boolean,
    val primaryAction: VotingRoundPlanAction,
    val needsDelegationSigning: Boolean,
    val hasInFlightDelegation: Boolean,
    val delegationBundlesNeedingWork: List<Int>,
    val delegationBundlesNeedingSigning: List<Int>,
    val needsVotePolling: Boolean,
    val hasRemainingVoteOrShareWork: Boolean,
    val hasRecoverableVoteOrShareWork: Boolean,
    val recoveredDelegationWork: List<VotingDelegationRecoveryWork>,
    val recoveredVoteWork: List<VotingVoteRecoveryWork>
)

/**
 * Why a round-drive pass stopped with nothing dispatchable -- mirrors
 * `zcash_voting::round_drive::RoundQuiescence`. [Unknown] covers a future crate variant this
 * SDK does not recognize yet (the crate enum is `#[non_exhaustive]`).
 */
sealed interface VotingRoundQuiescence {
    /** The plan lists no actionable obligation. Nothing is owed. */
    data object NoWorkLeft : VotingRoundQuiescence

    /** Ballot choices exist, but no bundle plan has been persisted yet. */
    data object NeedsBundleSetup : VotingRoundQuiescence

    /** Durable chain state the run cannot advance: a rejected or hashless terminal submission. */
    data object PersistedChainTerminal : VotingRoundQuiescence

    /** A cast is due but withheld until the ballot is terminal. */
    data class NeedsBallot(
        val openProposals: List<Int>,
        val unrosteredIntents: List<Int>
    ) : VotingRoundQuiescence

    /** Delegation is owed for these bundles but no signature is available. */
    data class NeedsDelegationSignatures(
        val bundles: List<Int>
    ) : VotingRoundQuiescence

    /** Only helper shares that require background tracking remain. [shares] are raw crate debug strings. */
    data class BackgroundShareWorkOnly(
        val shares: List<String>
    ) : VotingRoundQuiescence

    /** The host cancelled, or moved to another operation epoch. */
    data object Cancelled : VotingRoundQuiescence

    /** A chain submission ended without a confirmation. [outcome] is a raw crate debug string. */
    data class ChainTerminal(
        val step: VotingNextStep?,
        val outcome: String
    ) : VotingRoundQuiescence

    /** An advancement episode ended outside `Tracking`, so recovery is exhausted for now. */
    data class ChainRecoveryStalled(
        val step: VotingNextStep?,
        val outcome: String
    ) : VotingRoundQuiescence

    /** Every remaining obligation belongs to a bundle a failure skipped, or the run stopped on a failure. */
    data object Failures : VotingRoundQuiescence

    /** The per-dispatch budget was reached with work still planned. */
    data class PassBudgetExhausted(
        val remaining: List<VotingNextStep>
    ) : VotingRoundQuiescence

    data class Unknown(
        val kind: String,
        val detailJson: String?
    ) : VotingRoundQuiescence
}

/**
 * One failure a round-drive pass kept, with the bundle it isolated -- mirrors
 * `zcash_voting::round_drive::RoundStepFailureRecord`.
 */
data class VotingRoundStepFailure(
    val step: VotingNextStep?,
    val bundleIndex: Int?,
    /** Raw crate debug string for `RoundStepFailureKind` -- not stable; the crate has no `as_str` for it. */
    val kind: String,
    val message: String
)

/**
 * One chain outcome a round-drive pass observed, bound to the step that produced it. [outcome]
 * is a raw crate debug string.
 */
data class VotingChainOutcome(
    val step: VotingNextStep?,
    val outcome: String
)

/**
 * Everything one run of a round did -- mirrors `zcash_voting::round_drive::RoundRunReport`, the
 * terminal output of [cash.z.ecc.android.sdk.VotingRoundSession.run].
 *
 * [failures]/[chainOutcomes]/[shareDeliveries] are the report's "thinly encoded" fields: several
 * of the crate types behind them ([VotingRoundStepFailure.kind], the outcome/delivery text) are
 * large, non-exhaustive enums without a stable string form the crate exposes, so they carry raw
 * `Debug`-formatted text rather than a further-typed model -- see `encode_round_run_report`'s
 * doc comment in `backend-lib/src/main/rust/voting/helpers.rs`. [delegationsSignedCount] is a
 * count only: signed delegation bundles themselves carry proving/signing material this report
 * does not surface.
 */
data class VotingRoundRunReport(
    val quiescence: VotingRoundQuiescence,
    val plan: VotingRoundPlan?,
    val completedProposals: Int,
    val totalProposals: Int,
    val remainingObligations: Int,
    val failures: List<VotingRoundStepFailure>,
    val skippedBundles: List<Int>,
    val chainOutcomes: List<VotingChainOutcome>,
    val shareDeliveries: List<String>,
    val delegationsSignedCount: Int
)

/** Identifies one delegated helper share -- mirrors `zcash_voting::share_tracking::ShareKey`. */
data class VotingShareKey(
    val bundleIndex: Int,
    val proposalId: Int,
    val shareIndex: Int
)

/** One share resubmitted (or ambiguously attempted) during share tracking. */
data class VotingResubmittedShare(
    val share: VotingShareKey,
    val serverUrl: String
)

/**
 * Why a share-tracking pass stopped -- mirrors `zcash_voting::share_tracking::
 * ShareTrackingQuiescence`. [Unknown] covers a future crate variant this SDK does not recognize
 * yet (the crate enum is `#[non_exhaustive]`).
 */
sealed interface VotingShareTrackingQuiescence {
    data object NothingToTrack : VotingShareTrackingQuiescence

    data object AllConfirmed : VotingShareTrackingQuiescence

    data object VoteEndReached : VotingShareTrackingQuiescence

    data object Cancelled : VotingShareTrackingQuiescence

    data object AlreadyDriving : VotingShareTrackingQuiescence

    data class Failing(
        val messages: List<String>
    ) : VotingShareTrackingQuiescence

    data class PassBudgetExhausted(
        val unrecoverable: List<VotingShareKey>
    ) : VotingShareTrackingQuiescence

    data class Unknown(
        val kind: String,
        val detailJson: String?
    ) : VotingShareTrackingQuiescence
}

/**
 * The terminal outcome of one [cash.z.ecc.android.sdk.VotingDbSession.trackShares] pass --
 * mirrors `zcash_voting::share_tracking::ShareTrackingRunReport`. [failures] is a raw JSON
 * array string passthrough (`ShareTrackingRunReport::failures` has no crate `Serialize` this
 * SDK can rely on beyond what the JNI layer already serialized).
 */
data class VotingShareTrackingReport(
    val quiescence: VotingShareTrackingQuiescence,
    val passes: Int,
    val confirmed: List<VotingShareKey>,
    val resubmitted: List<VotingResubmittedShare>,
    val ambiguous: List<VotingResubmittedShare>,
    val unrecoverable: List<VotingShareKey>,
    val failures: List<String>
)

/**
 * One bundle's Keystone signing request -- mirrors `zcash_voting::delegate::
 * KeystoneSigningRequest`. [pcztBytes] is the full PCZT for local sighash/spend-auth
 * verification; [redactedPcztBytes] is the memo-redacted variant Keystone itself signs.
 */
data class VotingKeystoneSigningRequest(
    val pcztBytes: ByteArray,
    val redactedPcztBytes: ByteArray,
    val pcztSighash: ByteArray,
    val rk: ByteArray,
    val actionIndex: Int,
    val displayMemo: String,
    val eligibleWeightZatoshi: Long,
    val delegatedWeightZatoshi: Long,
    val bundleCount: Int,
    val bundleIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingKeystoneSigningRequest) return false
        return pcztBytes.contentEquals(other.pcztBytes) &&
            redactedPcztBytes.contentEquals(other.redactedPcztBytes) &&
            pcztSighash.contentEquals(other.pcztSighash) &&
            rk.contentEquals(other.rk) &&
            actionIndex == other.actionIndex &&
            displayMemo == other.displayMemo &&
            eligibleWeightZatoshi == other.eligibleWeightZatoshi &&
            delegatedWeightZatoshi == other.delegatedWeightZatoshi &&
            bundleCount == other.bundleCount &&
            bundleIndex == other.bundleIndex
    }

    override fun hashCode(): Int {
        var result = pcztBytes.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + actionIndex
        result = 31 * result + bundleIndex
        return result
    }
}

/**
 * One bundle's Keystone signature to persist via
 * [cash.z.ecc.android.sdk.VotingDbSession.storeKeystoneSignatures]. Construct with the
 * `rk`/`sighash` already verified by a prior [VotingKeystoneSigningRequest]-driven signing
 * flow, not arbitrary caller-supplied values -- the native side's `matches_bundle` guard
 * compares against them but does not itself re-verify the signature.
 */
data class VotingKeystoneSignatureInput(
    val bundleIndex: Int,
    val sig: ByteArray,
    val sighash: ByteArray,
    val rk: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingKeystoneSignatureInput) return false
        return bundleIndex == other.bundleIndex &&
            sig.contentEquals(other.sig) &&
            sighash.contentEquals(other.sighash) &&
            rk.contentEquals(other.rk)
    }

    override fun hashCode(): Int {
        var result = bundleIndex
        result = 31 * result + sig.contentHashCode()
        return result
    }
}

/** One bundle's persisted Keystone signature, from [cash.z.ecc.android.sdk.VotingDbSession.getKeystoneSignatures]. */
data class VotingKeystoneSignatureRecord(
    val bundleIndex: Int,
    val sig: ByteArray,
    val sighash: ByteArray,
    val rk: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingKeystoneSignatureRecord) return false
        return bundleIndex == other.bundleIndex &&
            sig.contentEquals(other.sig) &&
            sighash.contentEquals(other.sighash) &&
            rk.contentEquals(other.rk)
    }

    override fun hashCode(): Int {
        var result = bundleIndex
        result = 31 * result + sig.contentHashCode()
        return result
    }
}

/** The result of [cash.z.ecc.android.sdk.VotingDbSession.storeKeystoneSignatures]'s atomic batch write. */
data class VotingKeystoneSignatureBatchResult(
    val inserted: Int,
    val alreadyPresent: Int
)
