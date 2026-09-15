package cash.z.ecc.android.sdk.internal.model.voting

import androidx.annotation.Keep

@Keep
data class JniNoteInfo(
    val commitment: ByteArray,
    val nullifier: ByteArray,
    val value: Long,
    val position: Long,
    val diversifier: ByteArray,
    val rho: ByteArray,
    val rseed: ByteArray,
    val scope: Int,
    val ufvk: String
) {
    override fun toString(): String = "JniNoteInfo(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniNoteInfo) return false
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
        result = 31 * result + scope
        result = 31 * result + ufvk.hashCode()
        return result
    }
}

@Keep
data class JniWitnessData(
    val noteCommitment: ByteArray,
    val position: Long,
    val root: ByteArray,
    val authPath: List<ByteArray>
) {
    internal constructor(
        noteCommitment: ByteArray,
        position: Long,
        root: ByteArray,
        authPath: Array<ByteArray>
    ) : this(
        noteCommitment = noteCommitment,
        position = position,
        root = root,
        authPath = authPath.toList()
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniWitnessData) return false
        return noteCommitment.contentEquals(other.noteCommitment) &&
            position == other.position &&
            root.contentEquals(other.root) &&
            authPath.contentDeepEquals(other.authPath)
    }

    override fun hashCode(): Int {
        var result = noteCommitment.contentHashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + root.contentHashCode()
        result = 31 * result + authPath.contentDeepHashCode()
        return result
    }
}

@Keep
data class JniVanWitness(
    val authPath: List<ByteArray>,
    val position: Long,
    val anchorHeight: Long
) {
    internal constructor(
        authPath: Array<ByteArray>,
        position: Long,
        anchorHeight: Long
    ) : this(
        authPath = authPath.toList(),
        position = position,
        anchorHeight = anchorHeight
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVanWitness) return false
        return authPath.contentDeepEquals(other.authPath) &&
            position == other.position &&
            anchorHeight == other.anchorHeight
    }

    override fun hashCode(): Int {
        var result = authPath.contentDeepHashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + anchorHeight.hashCode()
        return result
    }
}

@Keep
data class JniWireEncryptedShare(
    val c1: ByteArray,
    val c2: ByteArray,
    val shareIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniWireEncryptedShare) return false
        return c1.contentEquals(other.c1) &&
            c2.contentEquals(other.c2) &&
            shareIndex == other.shareIndex
    }

    override fun hashCode(): Int {
        var result = c1.contentHashCode()
        result = 31 * result + c2.contentHashCode()
        result = 31 * result + shareIndex
        return result
    }
}

/**
 * Typed JNI carrier for vote commitment outputs.
 *
 * `shareBlinds`, `rVpk`, and `alphaV` are sensitive reveal/signing inputs.
 * They must not be logged or exposed outside the voting recovery path. They are
 * carried here because follow-up JNI calls consume the typed commitment result,
 * and recovery persistence needs them to resume after restart. Encrypted-share
 * plaintext and encryption randomness remain Rust-only and are not included in
 * [encShares].
 */
@Keep
data class JniVoteCommitmentResult(
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val proposalId: Int,
    val bundleIndex: Int,
    val proof: ByteArray,
    val encShares: List<JniWireEncryptedShare>,
    val anchorHeight: Long,
    val voteRoundId: String,
    val sharesHash: ByteArray,
    val shareBlinds: List<ByteArray>,
    val shareComms: List<ByteArray>,
    val rVpk: ByteArray,
    val alphaV: ByteArray
) {
    internal constructor(
        vanNullifier: ByteArray,
        voteAuthorityNoteNew: ByteArray,
        voteCommitment: ByteArray,
        proposalId: Int,
        bundleIndex: Int,
        proof: ByteArray,
        encShares: Array<JniWireEncryptedShare>,
        anchorHeight: Long,
        voteRoundId: String,
        sharesHash: ByteArray,
        shareBlinds: Array<ByteArray>,
        shareComms: Array<ByteArray>,
        rVpk: ByteArray,
        alphaV: ByteArray
    ) : this(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        proof = proof,
        encShares = encShares.toList(),
        anchorHeight = anchorHeight,
        voteRoundId = voteRoundId,
        sharesHash = sharesHash,
        shareBlinds = shareBlinds.toList(),
        shareComms = shareComms.toList(),
        rVpk = rVpk,
        alphaV = alphaV
    )

    override fun toString(): String = "JniVoteCommitmentResult(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVoteCommitmentResult) return false
        return scalarFieldsEqual(other) &&
            byteFieldsEqual(other) &&
            listFieldsEqual(other)
    }

    private fun scalarFieldsEqual(other: JniVoteCommitmentResult) =
        proposalId == other.proposalId &&
            bundleIndex == other.bundleIndex &&
            anchorHeight == other.anchorHeight &&
            voteRoundId == other.voteRoundId

    private fun byteFieldsEqual(other: JniVoteCommitmentResult) =
        vanNullifier.contentEquals(other.vanNullifier) &&
            voteAuthorityNoteNew.contentEquals(other.voteAuthorityNoteNew) &&
            voteCommitment.contentEquals(other.voteCommitment) &&
            proof.contentEquals(other.proof) &&
            sharesHash.contentEquals(other.sharesHash) &&
            rVpk.contentEquals(other.rVpk) &&
            alphaV.contentEquals(other.alphaV)

    private fun listFieldsEqual(other: JniVoteCommitmentResult) =
        encShares == other.encShares &&
            shareBlinds.contentDeepEquals(other.shareBlinds) &&
            shareComms.contentDeepEquals(other.shareComms)

    override fun hashCode(): Int {
        var result = vanNullifier.contentHashCode()
        result = 31 * result + voteAuthorityNoteNew.contentHashCode()
        result = 31 * result + voteCommitment.contentHashCode()
        result = 31 * result + proposalId
        result = 31 * result + bundleIndex
        result = 31 * result + proof.contentHashCode()
        result = 31 * result + encShares.hashCode()
        result = 31 * result + anchorHeight.hashCode()
        result = 31 * result + voteRoundId.hashCode()
        result = 31 * result + sharesHash.contentHashCode()
        result = 31 * result + shareBlinds.contentDeepHashCode()
        result = 31 * result + shareComms.contentDeepHashCode()
        result = 31 * result + rVpk.contentHashCode()
        result = 31 * result + alphaV.contentHashCode()
        return result
    }
}

@Keep
data class JniCommitmentBundleRecord(
    val commitment: JniVoteCommitmentResult,
    val vcTreePosition: Long
)

/**
 * Typed JNI carrier for the one-shot `vote::commit` result: the signed commitment bundle
 * plus the vote authorization signature and share payloads it produces.
 *
 * `rVpk` and `voteAuthSig` are signing-path outputs. They are carried here because recovery
 * persistence needs them to resume after restart, not because callers should act on them
 * directly.
 */
@Keep
data class JniVoteCommitResult(
    val bundleIndex: Int,
    val proposalId: Int,
    val choice: Int,
    val voteRoundId: String,
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val proof: ByteArray,
    val encShares: List<JniWireEncryptedShare>,
    val anchorHeight: Long,
    val sharesHash: ByteArray,
    val shareComms: List<ByteArray>,
    val rVpk: ByteArray,
    val voteAuthSig: ByteArray,
    val sharePayloads: List<JniSharePayload>
) {
    internal constructor(
        bundleIndex: Int,
        proposalId: Int,
        choice: Int,
        voteRoundId: String,
        vanNullifier: ByteArray,
        voteAuthorityNoteNew: ByteArray,
        voteCommitment: ByteArray,
        proof: ByteArray,
        encShares: Array<JniWireEncryptedShare>,
        anchorHeight: Long,
        sharesHash: ByteArray,
        shareComms: Array<ByteArray>,
        rVpk: ByteArray,
        voteAuthSig: ByteArray,
        sharePayloads: Array<JniSharePayload>
    ) : this(
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        choice = choice,
        voteRoundId = voteRoundId,
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proof = proof,
        encShares = encShares.toList(),
        anchorHeight = anchorHeight,
        sharesHash = sharesHash,
        shareComms = shareComms.toList(),
        rVpk = rVpk,
        voteAuthSig = voteAuthSig,
        sharePayloads = sharePayloads.toList()
    )

    override fun toString(): String = "JniVoteCommitResult(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVoteCommitResult) return false
        return scalarFieldsEqual(other) && byteFieldsEqual(other) && listFieldsEqual(other)
    }

    private fun scalarFieldsEqual(other: JniVoteCommitResult) =
        bundleIndex == other.bundleIndex &&
            proposalId == other.proposalId &&
            choice == other.choice &&
            voteRoundId == other.voteRoundId &&
            anchorHeight == other.anchorHeight

    private fun byteFieldsEqual(other: JniVoteCommitResult) =
        vanNullifier.contentEquals(other.vanNullifier) &&
            voteAuthorityNoteNew.contentEquals(other.voteAuthorityNoteNew) &&
            voteCommitment.contentEquals(other.voteCommitment) &&
            proof.contentEquals(other.proof) &&
            sharesHash.contentEquals(other.sharesHash) &&
            rVpk.contentEquals(other.rVpk) &&
            voteAuthSig.contentEquals(other.voteAuthSig)

    private fun listFieldsEqual(other: JniVoteCommitResult) =
        encShares == other.encShares &&
            shareComms.contentDeepEquals(other.shareComms) &&
            sharePayloads == other.sharePayloads

    override fun hashCode(): Int {
        var result = bundleIndex
        result = 31 * result + proposalId
        result = 31 * result + choice
        result = 31 * result + voteRoundId.hashCode()
        result = 31 * result + vanNullifier.contentHashCode()
        result = 31 * result + voteAuthorityNoteNew.contentHashCode()
        result = 31 * result + voteCommitment.contentHashCode()
        result = 31 * result + proof.contentHashCode()
        result = 31 * result + encShares.hashCode()
        result = 31 * result + anchorHeight.hashCode()
        result = 31 * result + sharesHash.contentHashCode()
        result = 31 * result + shareComms.contentDeepHashCode()
        result = 31 * result + rVpk.contentHashCode()
        result = 31 * result + voteAuthSig.contentHashCode()
        result = 31 * result + sharePayloads.hashCode()
        return result
    }
}

/**
 * Wraps a recovered [JniVoteCommitResult] with its confirmed vote-commitment-tree position, as
 * returned by `recoverCommittedVoteNative`.
 */
@Keep
data class JniCommittedVoteRecord(
    val commit: JniVoteCommitResult,
    val vcTreePosition: Long
)

@Keep
data class JniSharePayload(
    val sharesHash: ByteArray,
    val proposalId: Int,
    val voteDecision: Int,
    val encShare: JniWireEncryptedShare,
    val treePosition: Long,
    val allEncShares: List<JniWireEncryptedShare>,
    val shareComms: List<ByteArray>,
    val primaryBlind: ByteArray,
    /** Voting round ID as 32 bytes encoded in lowercase hex, as populated by the crate. */
    val voteRoundId: String
) {
    internal constructor(
        sharesHash: ByteArray,
        proposalId: Int,
        voteDecision: Int,
        encShare: JniWireEncryptedShare,
        treePosition: Long,
        allEncShares: Array<JniWireEncryptedShare>,
        shareComms: Array<ByteArray>,
        primaryBlind: ByteArray,
        voteRoundId: String
    ) : this(
        sharesHash = sharesHash,
        proposalId = proposalId,
        voteDecision = voteDecision,
        encShare = encShare,
        treePosition = treePosition,
        allEncShares = allEncShares.toList(),
        shareComms = shareComms.toList(),
        primaryBlind = primaryBlind,
        voteRoundId = voteRoundId
    )

    override fun toString(): String = "JniSharePayload(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniSharePayload) return false
        return sharesHash.contentEquals(other.sharesHash) &&
            proposalId == other.proposalId &&
            voteDecision == other.voteDecision &&
            encShare == other.encShare &&
            treePosition == other.treePosition &&
            allEncShares == other.allEncShares &&
            shareComms.contentDeepEquals(other.shareComms) &&
            primaryBlind.contentEquals(other.primaryBlind) &&
            voteRoundId == other.voteRoundId
    }

    override fun hashCode(): Int {
        var result = sharesHash.contentHashCode()
        result = 31 * result + proposalId
        result = 31 * result + voteDecision
        result = 31 * result + encShare.hashCode()
        result = 31 * result + treePosition.hashCode()
        result = 31 * result + allEncShares.hashCode()
        result = 31 * result + shareComms.contentDeepHashCode()
        result = 31 * result + primaryBlind.contentHashCode()
        result = 31 * result + voteRoundId.hashCode()
        return result
    }
}

@Keep
data class JniShareDelegationRecord(
    val roundId: String,
    val bundleIndex: Int,
    val proposalId: Int,
    val shareIndex: Int,
    val sentToUrls: List<String>,
    val nullifier: ByteArray,
    val confirmed: Boolean,
    val submitAt: Long,
    val createdAt: Long
) {
    internal constructor(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: Array<String>,
        nullifier: ByteArray,
        confirmed: Boolean,
        submitAt: Long,
        createdAt: Long
    ) : this(
        roundId = roundId,
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        shareIndex = shareIndex,
        sentToUrls = sentToUrls.toList(),
        nullifier = nullifier,
        confirmed = confirmed,
        submitAt = submitAt,
        createdAt = createdAt
    )

    override fun toString(): String = "JniShareDelegationRecord(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniShareDelegationRecord) return false
        return roundId == other.roundId &&
            bundleIndex == other.bundleIndex &&
            proposalId == other.proposalId &&
            shareIndex == other.shareIndex &&
            sentToUrls == other.sentToUrls &&
            nullifier.contentEquals(other.nullifier) &&
            confirmed == other.confirmed &&
            submitAt == other.submitAt &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = roundId.hashCode()
        result = 31 * result + bundleIndex
        result = 31 * result + proposalId
        result = 31 * result + shareIndex
        result = 31 * result + sentToUrls.hashCode()
        result = 31 * result + nullifier.contentHashCode()
        result = 31 * result + confirmed.hashCode()
        result = 31 * result + submitAt.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/**
 * Typed JNI carrier for a voting hotkey.
 *
 * `generateHotkeyNative` can mint a fresh app-owned hotkey identity, so [storedSecret] must
 * cross JNI here for Android to persist in secure storage; the crate never re-derives it from
 * the wallet seed. [storedSecret] is sensitive and must not be logged.
 */
@Keep
data class JniVotingHotkey(
    val storedSecret: ByteArray,
    val rawAddress: ByteArray,
    val address: String
) {
    override fun toString(): String = "JniVotingHotkey(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVotingHotkey) return false
        return storedSecret.contentEquals(other.storedSecret) &&
            rawAddress.contentEquals(other.rawAddress) &&
            address == other.address
    }

    override fun hashCode(): Int {
        var result = storedSecret.contentHashCode()
        result = 31 * result + rawAddress.contentHashCode()
        result = 31 * result + address.hashCode()
        return result
    }
}

// Must match PHASE_* constants in backend-lib/src/main/rust/voting/helpers.rs.
internal const val JNI_ROUND_PHASE_INITIALIZED = 0
internal const val JNI_ROUND_PHASE_HOTKEY_GENERATED = 1
internal const val JNI_ROUND_PHASE_DELEGATION_CONSTRUCTED = 2
internal const val JNI_ROUND_PHASE_DELEGATION_PROVED = 3
internal const val JNI_ROUND_PHASE_VOTE_READY = 4

@Keep
data class JniBundleSetupResult(
    val bundleCount: Int,
    val eligibleWeight: Long,
    val bundleWeights: List<Long>
) {
    internal constructor(bundleCount: Int, eligibleWeight: Long, bundleWeights: LongArray) :
        this(bundleCount, eligibleWeight, bundleWeights.toList())
}

@Keep
data class JniGovernancePczt(
    val pcztBytes: ByteArray,
    val rk: ByteArray,
    val sighash: ByteArray,
    val actionIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniGovernancePczt) return false
        return pcztBytes.contentEquals(other.pcztBytes) &&
            rk.contentEquals(other.rk) &&
            sighash.contentEquals(other.sighash) &&
            actionIndex == other.actionIndex
    }

    override fun hashCode(): Int {
        var result = pcztBytes.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + actionIndex
        return result
    }
}

@Keep
data class JniRoundState(
    val roundId: String,
    val phase: Int,
    val snapshotHeight: Long,
    val hotkeyAddress: String?,
    val delegatedWeight: Long?,
    val proofGenerated: Boolean
) {
    val roundPhase = JniRoundPhase.fromInt(phase)
}

@Keep
enum class JniRoundPhase(
    val value: Int
) {
    INITIALIZED(JNI_ROUND_PHASE_INITIALIZED),
    HOTKEY_GENERATED(JNI_ROUND_PHASE_HOTKEY_GENERATED),
    DELEGATION_CONSTRUCTED(JNI_ROUND_PHASE_DELEGATION_CONSTRUCTED),
    DELEGATION_PROVED(JNI_ROUND_PHASE_DELEGATION_PROVED),
    VOTE_READY(JNI_ROUND_PHASE_VOTE_READY);

    companion object {
        fun fromInt(value: Int) =
            entries.firstOrNull { it.value == value }
                ?: error("Unknown round phase: $value")
    }
}

@Keep
data class JniRoundSummary(
    val roundId: String,
    val phase: Int,
    val snapshotHeight: Long,
    val createdAt: Long
) {
    val roundPhase = JniRoundPhase.fromInt(phase)
}

@Keep
data class JniVoteRecord(
    val proposalId: Int,
    val bundleIndex: Int,
    val choice: Int,
    val submitted: Boolean
)

@Keep
data class JniDelegationPirPrecomputeResult(
    val cachedCount: Long,
    val fetchedCount: Long
)

@Keep
data class JniDelegationProofResult(
    val proof: ByteArray,
    val publicInputs: List<ByteArray>,
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govNullifiers: List<ByteArray>,
    val vanComm: ByteArray,
    val rk: ByteArray
) {
    internal constructor(
        proof: ByteArray,
        publicInputs: Array<ByteArray>,
        nfSigned: ByteArray,
        cmxNew: ByteArray,
        govNullifiers: Array<ByteArray>,
        vanComm: ByteArray,
        rk: ByteArray
    ) : this(
        proof = proof,
        publicInputs = publicInputs.toList(),
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govNullifiers = govNullifiers.toList(),
        vanComm = vanComm,
        rk = rk
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniDelegationProofResult) return false
        return proof.contentEquals(other.proof) &&
            publicInputs.contentDeepEquals(other.publicInputs) &&
            nfSigned.contentEquals(other.nfSigned) &&
            cmxNew.contentEquals(other.cmxNew) &&
            govNullifiers.contentDeepEquals(other.govNullifiers) &&
            vanComm.contentEquals(other.vanComm) &&
            rk.contentEquals(other.rk)
    }

    override fun hashCode(): Int {
        var result = proof.contentHashCode()
        result = 31 * result + publicInputs.contentDeepHashCode()
        result = 31 * result + nfSigned.contentHashCode()
        result = 31 * result + cmxNew.contentHashCode()
        result = 31 * result + govNullifiers.contentDeepHashCode()
        result = 31 * result + vanComm.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        return result
    }
}

/**
 * @property sighash The PCZT sighash. Verification-only (local `spend_auth_sig`/Keystone
 * signature verification against [rk]) — do **not** submit this to the vote-chain server.
 * @property tx1Effects The versioned effects blob the vote-chain server requires on submission;
 * the sole field that goes over the wire for the delegation transaction.
 */
@Keep
data class JniDelegationSubmissionResult(
    val proof: ByteArray,
    val rk: ByteArray,
    val spendAuthSig: ByteArray,
    val sighash: ByteArray,
    val tx1Effects: ByteArray,
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govComm: ByteArray,
    val govNullifiers: List<ByteArray>,
    val voteRoundId: String
) {
    internal constructor(
        proof: ByteArray,
        rk: ByteArray,
        spendAuthSig: ByteArray,
        sighash: ByteArray,
        tx1Effects: ByteArray,
        nfSigned: ByteArray,
        cmxNew: ByteArray,
        govComm: ByteArray,
        govNullifiers: Array<ByteArray>,
        voteRoundId: String
    ) : this(
        proof = proof,
        rk = rk,
        spendAuthSig = spendAuthSig,
        sighash = sighash,
        tx1Effects = tx1Effects,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govComm = govComm,
        govNullifiers = govNullifiers.toList(),
        voteRoundId = voteRoundId
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniDelegationSubmissionResult) return false
        return proof.contentEquals(other.proof) &&
            rk.contentEquals(other.rk) &&
            spendAuthSig.contentEquals(other.spendAuthSig) &&
            sighash.contentEquals(other.sighash) &&
            tx1Effects.contentEquals(other.tx1Effects) &&
            nfSigned.contentEquals(other.nfSigned) &&
            cmxNew.contentEquals(other.cmxNew) &&
            govComm.contentEquals(other.govComm) &&
            govNullifiers.contentDeepEquals(other.govNullifiers) &&
            voteRoundId == other.voteRoundId
    }

    override fun hashCode(): Int {
        var result = proof.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + spendAuthSig.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + tx1Effects.contentHashCode()
        result = 31 * result + nfSigned.contentHashCode()
        result = 31 * result + cmxNew.contentHashCode()
        result = 31 * result + govComm.contentHashCode()
        result = 31 * result + govNullifiers.contentDeepHashCode()
        result = 31 * result + voteRoundId.hashCode()
        return result
    }
}

/**
 * The canonical, per-bundle delegation phase (`prepared`, `pczt_built`, `proved`, `submitted`,
 * `confirmed` — matches `zcash_voting::phases::DelegationPhase::as_str`), derived on read from
 * persisted artifacts rather than the coarse round-level phase on [JniRoundState].
 */
@Keep
data class JniDelegationPhase(
    val bundleIndex: Int,
    val phase: String
)

/**
 * Typed JNI carrier for `zcash_voting::session::RoundPlan`, `getRoundPlanNative`'s/
 * `setBallotIntentsNative`'s return value and the embedded field of [JniRoundRunReport].
 *
 * Every `RoundPlan` field is carried, one property per field in declaration order. Nested
 * record types the crate does not derive `Serialize` for ([delegationStatusesJson],
 * [completedVoteDisplayJson], [recoveredDelegationWorkJson], [recoveredVoteWorkJson],
 * [immediateShareKeyJson]) are JSON-encoded strings rather than first-class Kotlin models —
 * see `encode_round_plan`'s own doc comment in `backend-lib/src/main/rust/voting/helpers.rs`
 * for why. [primaryAction] is `RoundPlanAction` encoded as an int (0=Idle, 1=Delegate,
 * 2=Vote, 3=SubmitShares, 4=Done, -1=unknown/future variant).
 */
@Keep
data class JniRoundPlan(
    val roundId: String,
    val pendingRecovery: Boolean,
    val nextStepsJson: String,
    val openProposals: IntArray,
    val unrosteredIntents: IntArray,
    val immediateShareKeyJson: String?,
    val immediateShareConfirmed: Boolean,
    val allDecided: Boolean,
    val delegationStatusesJson: String,
    val blockingRecovery: Boolean,
    val blockingShareWork: Boolean,
    val hasUnconfirmedShares: Boolean,
    val hotkeyBound: Boolean,
    val completedVoteArtifact: Boolean,
    val completedForDisplay: Boolean,
    val completedVoteDisplayJson: String?,
    val needsDraftSetup: Boolean,
    val needsBundleSetup: Boolean,
    val primaryAction: Int,
    val needsDelegationSigning: Boolean,
    val hasInFlightDelegation: Boolean,
    val delegationBundlesNeedingWork: IntArray,
    val delegationBundlesNeedingSigning: IntArray,
    val needsVotePolling: Boolean,
    val hasRemainingVoteOrShareWork: Boolean,
    val hasRecoverableVoteOrShareWork: Boolean,
    val recoveredDelegationWorkJson: String,
    val recoveredVoteWorkJson: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniRoundPlan) return false
        return scalarFieldsEqual(other) &&
            flagFieldsEqual(other) &&
            jsonFieldsEqual(other) &&
            arrayFieldsEqual(other)
    }

    private fun scalarFieldsEqual(other: JniRoundPlan) =
        roundId == other.roundId &&
            pendingRecovery == other.pendingRecovery &&
            immediateShareConfirmed == other.immediateShareConfirmed &&
            allDecided == other.allDecided &&
            blockingRecovery == other.blockingRecovery &&
            blockingShareWork == other.blockingShareWork &&
            hasUnconfirmedShares == other.hasUnconfirmedShares &&
            hotkeyBound == other.hotkeyBound &&
            completedVoteArtifact == other.completedVoteArtifact

    private fun flagFieldsEqual(other: JniRoundPlan) =
        completedForDisplay == other.completedForDisplay &&
            needsDraftSetup == other.needsDraftSetup &&
            needsBundleSetup == other.needsBundleSetup &&
            primaryAction == other.primaryAction &&
            needsDelegationSigning == other.needsDelegationSigning &&
            hasInFlightDelegation == other.hasInFlightDelegation &&
            needsVotePolling == other.needsVotePolling &&
            hasRemainingVoteOrShareWork == other.hasRemainingVoteOrShareWork &&
            hasRecoverableVoteOrShareWork == other.hasRecoverableVoteOrShareWork

    private fun jsonFieldsEqual(other: JniRoundPlan) =
        nextStepsJson == other.nextStepsJson &&
            immediateShareKeyJson == other.immediateShareKeyJson &&
            delegationStatusesJson == other.delegationStatusesJson &&
            completedVoteDisplayJson == other.completedVoteDisplayJson &&
            recoveredDelegationWorkJson == other.recoveredDelegationWorkJson &&
            recoveredVoteWorkJson == other.recoveredVoteWorkJson

    private fun arrayFieldsEqual(other: JniRoundPlan) =
        openProposals.contentEquals(other.openProposals) &&
            unrosteredIntents.contentEquals(other.unrosteredIntents) &&
            delegationBundlesNeedingWork.contentEquals(other.delegationBundlesNeedingWork) &&
            delegationBundlesNeedingSigning.contentEquals(other.delegationBundlesNeedingSigning)

    override fun hashCode(): Int {
        var result = roundId.hashCode()
        result = 31 * result + pendingRecovery.hashCode()
        result = 31 * result + nextStepsJson.hashCode()
        result = 31 * result + openProposals.contentHashCode()
        result = 31 * result + unrosteredIntents.contentHashCode()
        result = 31 * result + (immediateShareKeyJson?.hashCode() ?: 0)
        result = 31 * result + immediateShareConfirmed.hashCode()
        result = 31 * result + allDecided.hashCode()
        result = 31 * result + delegationStatusesJson.hashCode()
        result = 31 * result + blockingRecovery.hashCode()
        result = 31 * result + blockingShareWork.hashCode()
        result = 31 * result + hasUnconfirmedShares.hashCode()
        result = 31 * result + hotkeyBound.hashCode()
        result = 31 * result + completedVoteArtifact.hashCode()
        result = 31 * result + completedForDisplay.hashCode()
        result = 31 * result + (completedVoteDisplayJson?.hashCode() ?: 0)
        result = 31 * result + needsDraftSetup.hashCode()
        result = 31 * result + needsBundleSetup.hashCode()
        result = 31 * result + primaryAction
        result = 31 * result + needsDelegationSigning.hashCode()
        result = 31 * result + hasInFlightDelegation.hashCode()
        result = 31 * result + delegationBundlesNeedingWork.contentHashCode()
        result = 31 * result + delegationBundlesNeedingSigning.contentHashCode()
        result = 31 * result + needsVotePolling.hashCode()
        result = 31 * result + hasRemainingVoteOrShareWork.hashCode()
        result = 31 * result + hasRecoverableVoteOrShareWork.hashCode()
        result = 31 * result + recoveredDelegationWorkJson.hashCode()
        result = 31 * result + recoveredVoteWorkJson.hashCode()
        return result
    }
}

/**
 * Callback for `runRoundNative`'s progress parameter, bridged from the crate's own
 * `RoundDriveEvent`s by `round_drive_reporter_from_callback` in `round_session.rs` (see its doc
 * comment). A `RoundDriver::run` pass can take on the order of a minute or more, so this exists
 * to give the UI something to show while it runs rather than looking stuck.
 *
 * [step] is a short label (e.g. `"StepSelected"`, `"StepFinished"`) naming the
 * [zcash_voting]'s `RoundDriveEvent` variant that fired; [detail] is that event's full
 * `{:?}` debug dump (the exact `NextStep`, bundle index, disposition, etc.). Called from
 * whichever native thread the round-driver's concurrent bundle tasks happen to be running on —
 * an implementation must be safe to call from any thread and must not block.
 */
fun interface RoundDriveProgressListener {
    fun onRoundDriveProgress(
        step: String,
        detail: String
    )
}

/**
 * Typed JNI carrier for `zcash_voting::RoundRunReport`, `runRoundNative`'s return value: the
 * terminal outcome of one `RoundDriver::run` pass.
 *
 * [quiescenceKind] is a stable discriminator string for the crate's (non-exhaustive)
 * `RoundQuiescence` enum; [quiescenceDetailJson] carries the variant-specific payload where one
 * exists, `null` otherwise. [plan] reuses [JniRoundPlan] for the report's embedded
 * `Option<RoundPlan>`. The remaining complex fields (failures, chain outcomes, share
 * deliveries) are JSON-encoded rather than first-class models — see `encode_round_run_report`'s
 * doc comment in `backend-lib/src/main/rust/voting/helpers.rs`. [delegationsSignedCount] is a
 * count only: signed delegation bundles themselves are not surfaced here.
 */
@Keep
data class JniRoundRunReport(
    val quiescenceKind: String,
    val quiescenceDetailJson: String?,
    val plan: JniRoundPlan?,
    val completedProposals: Int,
    val totalProposals: Int,
    val remainingObligations: Int,
    val failuresJson: String,
    val skippedBundles: IntArray,
    val chainOutcomesJson: String,
    val shareDeliveriesJson: String,
    val delegationsSignedCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniRoundRunReport) return false
        return quiescenceKind == other.quiescenceKind &&
            quiescenceDetailJson == other.quiescenceDetailJson &&
            plan == other.plan &&
            completedProposals == other.completedProposals &&
            totalProposals == other.totalProposals &&
            remainingObligations == other.remainingObligations &&
            failuresJson == other.failuresJson &&
            skippedBundles.contentEquals(other.skippedBundles) &&
            chainOutcomesJson == other.chainOutcomesJson &&
            shareDeliveriesJson == other.shareDeliveriesJson &&
            delegationsSignedCount == other.delegationsSignedCount
    }

    override fun hashCode(): Int {
        var result = quiescenceKind.hashCode()
        result = 31 * result + (quiescenceDetailJson?.hashCode() ?: 0)
        result = 31 * result + (plan?.hashCode() ?: 0)
        result = 31 * result + completedProposals
        result = 31 * result + totalProposals
        result = 31 * result + remainingObligations
        result = 31 * result + failuresJson.hashCode()
        result = 31 * result + skippedBundles.contentHashCode()
        result = 31 * result + chainOutcomesJson.hashCode()
        result = 31 * result + shareDeliveriesJson.hashCode()
        result = 31 * result + delegationsSignedCount
        return result
    }
}

/**
 * Typed JNI carrier for `zcash_voting::ShareTrackingRunReport`, `trackSharesNative`'s return
 * value: the terminal outcome of one `ShareTrackingDriver::run` pass.
 *
 * [quiescenceKind] is a stable discriminator string for the crate's (non-exhaustive)
 * `ShareTrackingQuiescence` enum; [quiescenceDetailJson] carries the variant-specific payload
 * where one exists, `null` otherwise. `ShareKey`/`ResubmittedShare` do not derive `Serialize` in
 * the crate, so [confirmedJson]/[resubmittedJson]/[ambiguousJson]/[unrecoverableJson] are
 * JSON-encoded arrays rather than first-class models — see `encode_share_tracking_report`'s doc
 * comment in `backend-lib/src/main/rust/voting/helpers.rs`.
 */
@Keep
data class JniShareTrackingRunReport(
    val quiescenceKind: String,
    val quiescenceDetailJson: String?,
    val passes: Int,
    val confirmedJson: String,
    val resubmittedJson: String,
    val ambiguousJson: String,
    val unrecoverableJson: String,
    val failuresJson: String
)

/**
 * Typed JNI carrier for `zcash_voting::delegate::KeystoneSigningRequest`, one entry per bundle
 * from `getKeystoneSigningRequestsNative`. [pcztBytes] is the full PCZT for local
 * sighash/spend-auth verification; [redactedPcztBytes] is the memo-redacted variant Keystone
 * itself signs. [displayMemo] is the human-readable delegation memo shown to the signer.
 */
@Keep
data class JniKeystoneSigningRequest(
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
        if (other !is JniKeystoneSigningRequest) return false
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
        result = 31 * result + redactedPcztBytes.contentHashCode()
        result = 31 * result + pcztSighash.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + actionIndex
        result = 31 * result + displayMemo.hashCode()
        result = 31 * result + eligibleWeightZatoshi.hashCode()
        result = 31 * result + delegatedWeightZatoshi.hashCode()
        result = 31 * result + bundleCount
        result = 31 * result + bundleIndex
        return result
    }
}

/**
 * Typed JNI carrier for `zcash_voting::storage::KeystoneSignatureBatchResult`,
 * `storeKeystoneSignaturesNative`'s return value.
 */
@Keep
data class JniKeystoneSignatureBatchResult(
    val inserted: Int,
    val alreadyPresent: Int
)

/**
 * Typed JNI carrier for `zcash_voting::storage::KeystoneSignatureRecord`, one entry per bundle
 * from `getKeystoneSignaturesNative`.
 */
@Keep
data class JniKeystoneSignatureRecord(
    val bundleIndex: Int,
    val sig: ByteArray,
    val sighash: ByteArray,
    val rk: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniKeystoneSignatureRecord) return false
        return bundleIndex == other.bundleIndex &&
            sig.contentEquals(other.sig) &&
            sighash.contentEquals(other.sighash) &&
            rk.contentEquals(other.rk)
    }

    override fun hashCode(): Int {
        var result = bundleIndex
        result = 31 * result + sig.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        return result
    }
}

/**
 * Typed JNI carrier for `zcash_voting::storage::KeystoneSignatureInput`, one entry per bundle
 * passed into `storeKeystoneSignaturesNative`. Construct with the `rk`/`sighash` already
 * verified by a prior [JniKeystoneSigningRequest]-driven Keystone signing flow, not arbitrary
 * caller-supplied values — the native side's `matches_bundle` guard compares against them but
 * does not itself re-verify the signature.
 */
@Keep
data class JniKeystoneSignatureInput(
    val bundleIndex: Int,
    val sig: ByteArray,
    val sighash: ByteArray,
    val rk: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniKeystoneSignatureInput) return false
        return bundleIndex == other.bundleIndex &&
            sig.contentEquals(other.sig) &&
            sighash.contentEquals(other.sighash) &&
            rk.contentEquals(other.rk)
    }

    override fun hashCode(): Int {
        var result = bundleIndex
        result = 31 * result + sig.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        return result
    }
}

/**
 * Kotlin-constructed carrier for `runRoundNative`'s `delegation_inputs` parameter, read back by
 * the Rust side via JNI field reflection (`decode_delegation_inputs` in
 * `backend-lib/src/main/rust/voting/delegation_driver.rs`) rather than a generated constructor —
 * every property name/type below must match that function's `env.get_field` calls exactly.
 *
 * Pass `null` for a signer-less precompute-only pass or a share-tracking-only pass; every
 * `RoundHostContext` step other than `Delegate`/`AdvanceDelegation` tolerates that.
 *
 * [keystone] selects the signer: `true` uses a Keystone-signed delegation ([keystoneSig]/
 * [keystoneSighash] both present replay a previously-obtained signature; both absent resumes
 * from a previously *persisted* Keystone signature; exactly one present is rejected).
 * `false` requires [softwareSeed] and signs with the wallet's own seed.
 *
 * [softwareSeed] and [keystoneSig]/[keystoneSighash] are sensitive signing-path inputs and must
 * not be logged.
 *
 * [snapshotHeight]/[eaPk]/[ncRoot]/[nullifierImtRoot] are the round's own metadata (together with
 * the `round_id` the Rust side's `runRoundNative` already has from the session, this is the
 * complete `VotingRoundParams`). The caller must supply these directly from the same authenticated
 * round config it used to fetch [anchorTreeStateBytes] -- the Rust side must never read them back
 * from the `rounds` table, since a brand-new round has no row there yet; that row is exactly what
 * `DelegationPipeline`'s own bootstrap path creates from these values. See
 * `delegation_step_inputs_from_jni`'s doc comment in `delegation_driver.rs` for the bug this fixed
 * (a delegation-enabled `runRound` could not bootstrap a virgin round at all).
 */
@Keep
data class JniDelegationInputs(
    val dbHandle: Long,
    val walletDbPath: String,
    val accountUuid: String,
    val anchorTreeStateBytes: ByteArray,
    val hotkeySecret: ByteArray?,
    val pirEndpoints: Array<String>,
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
    override fun toString(): String = "JniDelegationInputs(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniDelegationInputs) return false
        return scalarFieldsEqual(other) && byteFieldsEqual(other)
    }

    private fun scalarFieldsEqual(other: JniDelegationInputs) =
        dbHandle == other.dbHandle &&
            walletDbPath == other.walletDbPath &&
            accountUuid == other.accountUuid &&
            pirEndpoints.contentEquals(other.pirEndpoints) &&
            pirDepth == other.pirDepth &&
            pirTier0Layers == other.pirTier0Layers &&
            pirTier1Layers == other.pirTier1Layers &&
            pirPolyLen == other.pirPolyLen &&
            keystone == other.keystone &&
            snapshotHeight == other.snapshotHeight

    private fun byteFieldsEqual(other: JniDelegationInputs) =
        anchorTreeStateBytes.contentEquals(other.anchorTreeStateBytes) &&
            hotkeySecret.nullableContentEquals(other.hotkeySecret) &&
            softwareSeed.nullableContentEquals(other.softwareSeed) &&
            keystoneSig.nullableContentEquals(other.keystoneSig) &&
            keystoneSighash.nullableContentEquals(other.keystoneSighash) &&
            eaPk.contentEquals(other.eaPk) &&
            ncRoot.contentEquals(other.ncRoot) &&
            nullifierImtRoot.contentEquals(other.nullifierImtRoot)

    override fun hashCode(): Int {
        var result = dbHandle.hashCode()
        result = 31 * result + walletDbPath.hashCode()
        result = 31 * result + accountUuid.hashCode()
        result = 31 * result + anchorTreeStateBytes.contentHashCode()
        result = 31 * result + (hotkeySecret?.contentHashCode() ?: 0)
        result = 31 * result + pirEndpoints.contentHashCode()
        result = 31 * result + pirDepth
        result = 31 * result + pirTier0Layers
        result = 31 * result + pirTier1Layers
        result = 31 * result + pirPolyLen
        result = 31 * result + keystone.hashCode()
        result = 31 * result + (softwareSeed?.contentHashCode() ?: 0)
        result = 31 * result + (keystoneSig?.contentHashCode() ?: 0)
        result = 31 * result + (keystoneSighash?.contentHashCode() ?: 0)
        result = 31 * result + snapshotHeight.hashCode()
        result = 31 * result + eaPk.contentHashCode()
        result = 31 * result + ncRoot.contentHashCode()
        result = 31 * result + nullifierImtRoot.contentHashCode()
        return result
    }
}

private fun ByteArray?.nullableContentEquals(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

private fun List<ByteArray>.contentDeepEquals(other: List<ByteArray>): Boolean =
    size == other.size && zip(other).all { (left, right) -> left.contentEquals(right) }

private fun List<ByteArray>.contentDeepHashCode(): Int =
    toTypedArray().contentDeepHashCode()
