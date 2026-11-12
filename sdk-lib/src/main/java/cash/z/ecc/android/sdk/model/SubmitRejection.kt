package cash.z.ecc.android.sdk.model

/**
 * Why a server refused a transaction for good: submitting the same transaction again, to the same
 * server, cannot succeed. The SDK's verdict carries it: see [TransactionSubmitResult.Failure.rejection].
 *
 * The principle: a refusal is final only when its text is on an explicit allow-list of refusals that
 * can never become valid ([TOO_LARGE], [EXPIRED], [INVALID]; see [classify]). Every other failure is
 * inconclusive and never stops resubmission: the server could not be reached, an anchor or input is
 * not yet known to it, a nullifier or input conflicts with another transaction, the relay fee was not
 * met, a mempool chain limit was hit, the transaction is not final yet, a node lagging behind a
 * network upgrade refused its version or branch, or a reason the SDK does not recognize. An answer
 * that the server already holds the transaction ([isAlreadyHeld]) is an acceptance, not a refusal.
 *
 * The lists are grounded in zcashd's and Zebra's sources, are the same on every SDK, and still
 * need confirmation of the real message set by the node teams.
 */
enum class SubmitRejection {
    /**
     * The transaction is larger than the server accepts: it exceeds the server's configured
     * mempool size limit, the ZIP 218 shielded action budget of the next block, or the maximum
     * transaction size. Propose fewer notes per transaction (see [TransactionSizeBound]).
     */
    TOO_LARGE,

    /** The transaction's expiry height has passed, or is about to. */
    EXPIRED,

    /**
     * The server found the transaction invalid for good: a refusal that can never become valid,
     * such as a value out of range, a transaction with no source or sink of funds, an invalid
     * signature or proof, or a failed mandatory script check.
     */
    INVALID;

    internal companion object {
        /** The legacy RPC code for a transaction that failed verification (`RPC_VERIFY_ERROR`). */
        const val CODE_VERIFY_ERROR = -25

        /** The legacy RPC code for a transaction that was rejected (`RPC_VERIFY_REJECTED`). */
        const val CODE_VERIFY_REJECTED = -26

        // Note: Do NOT change these texts: they match the nodes' own messages, and every SDK uses the
        // same lists. Matching ignores case. Sources: "zcashd" is src/main.cpp and "zcashd RPC" is
        // src/rpc/rawtransaction.cpp, both at 558f686599586f55def3db86955d74d3be44605e; "Zebra" is
        // zebrad's mempool errors (zebrad/src/components/mempool/error.rs) and the consensus errors
        // they carry. The lists need confirmation of the real message set by the node teams.

        /**
         * Answers that the node already holds the transaction: an acceptance, whatever the code.
         * - `already-in`: zcashd `txn-already-in-mempool`.
         * - `txn-already-known`: zcashd `txn-already-known`.
         * - `already in block chain`: zcashd RPC `transaction already in block chain` (code -27).
         * - `already exists in mempool`: Zebra `transaction already exists in mempool`.
         */
        private val ALREADY_HELD_ANSWERS =
            listOf(
                "already-in", // $NON-NLS
                "txn-already-known", // $NON-NLS
                "already in block chain", // $NON-NLS
                "already exists in mempool" // $NON-NLS
            )

        /**
         * Refusals that can become valid later, or that say nothing about the transaction itself:
         * checked before the allow-lists, so that they never stop resubmission. Anything on no list
         * is inconclusive too.
         * - `unknown-anchor`: zcashd `bad-txns-{sprout,sapling,orchard}-unknown-anchor`; the anchor
         *   may be in a block the node has not seen yet.
         * - `duplicate-nullifier`: zcashd `bad-txns-{sprout,sapling,orchard}-duplicate-nullifier`; a
         *   conflicting spend may be dropped.
         * - `inputs-spent`: zcashd `bad-txns-inputs-spent`.
         * - `inputs-missingorspent`: zcashd `bad-txns-inputs-missingorspent`.
         * - `txn-mempool-conflict`: zcashd `txn-mempool-conflict`.
         * - `missing inputs`: zcashd RPC `Missing inputs` (code -25).
         * - `min relay fee`: zcashd `min relay fee not met`.
         * - `too-long-mempool-chain`: zcashd `too-long-mempool-chain`.
         * - `non-final`: zcashd `non-final`; the transaction becomes final later.
         * - `bad-tx-`: zcashd `bad-tx-*`, such as `bad-tx-consensus-branch-id-mismatch` and
         *   `bad-tx-zip225-version-too-low`: a node lagging behind a network upgrade refuses them.
         * - `tx-version`: zcashd `bad-tx-version-group-id`, for the same reason.
         */
        private val INCONCLUSIVE_REFUSALS =
            listOf(
                "unknown-anchor", // $NON-NLS
                "duplicate-nullifier", // $NON-NLS
                "inputs-spent", // $NON-NLS
                "inputs-missingorspent", // $NON-NLS
                "txn-mempool-conflict", // $NON-NLS
                "missing inputs", // $NON-NLS
                "min relay fee", // $NON-NLS
                "too-long-mempool-chain", // $NON-NLS
                "non-final", // $NON-NLS
                "bad-tx-", // $NON-NLS
                "tx-version" // $NON-NLS
            )

        /**
         * A transaction larger than the node accepts:
         * - `exceeding the configured mempool maximum`: a relay's configured mempool size limit.
         * - `ZIP 218`: Zebra's next-block ZIP 218 shielded action budget.
         * - `oversize`: zcashd `bad-txns-oversize`, the maximum transaction size.
         */
        private val TOO_LARGE_REFUSALS =
            listOf(
                "exceeding the configured mempool maximum", // $NON-NLS
                "ZIP 218", // $NON-NLS
                "oversize" // $NON-NLS
            )

        /**
         * A transaction past, or about to pass, its expiry height:
         * - `tx-overwinter-expired`: zcashd `tx-overwinter-expired`.
         * - `tx-expiring-soon`: zcashd `tx-expiring-soon`.
         * - `expired`: Zebra's expired-transaction errors.
         * - `greater than its expiry`: Zebra `... mined at a block height greater than its expiry
         *   height`.
         */
        private val EXPIRED_REFUSALS =
            listOf(
                "tx-overwinter-expired", // $NON-NLS
                "tx-expiring-soon", // $NON-NLS
                "expired", // $NON-NLS
                "greater than its expiry" // $NON-NLS
            )

        /**
         * Refusals of a transaction that can never become valid, under code -25 as well as -26:
         * - zcashd `bad-txns-invalid-joinsplit-signature`.
         * - zcashd `bad-txns-joinsplit-`: `bad-txns-joinsplit-verification-failed`.
         * - zcashd `bad-txns-sapling-bundle-invalid`, `bad-sapling-bundle-authorization`,
         *   `bad-orchard-bundle-authorization`.
         * - zcashd `bad-txns-vout-`: `bad-txns-vout-negative`, `bad-txns-vout-toolarge`.
         * - zcashd `bad-txns-txouttotal-toolarge`, `bad-txns-txintotal-toolarge`.
         * - zcashd `bad-txns-oversize` (also [TOO_LARGE], which takes precedence).
         * - zcashd `mandatory-script-verify-flag-failed (...)`.
         * - zcashd `bad-txns-no-`: `bad-txns-no-source-of-funds`, `bad-txns-no-sink-of-funds`.
         * - Zebra `MUST represent a valid signature`, `proof MUST be valid`, `proof is malformed`,
         *   `proof or signature verification failed`, `proof verification failed`,
         *   `proof has a non-canonical size`: its signature and proof verification errors.
         */
        private val INVALID_REFUSALS =
            listOf(
                "bad-txns-invalid-joinsplit-signature", // $NON-NLS
                "bad-txns-joinsplit-", // $NON-NLS
                "bad-txns-sapling-bundle-invalid", // $NON-NLS
                "bad-sapling-bundle-authorization", // $NON-NLS
                "bad-orchard-bundle-authorization", // $NON-NLS
                "bad-txns-vout-", // $NON-NLS
                "bad-txns-txouttotal-toolarge", // $NON-NLS
                "bad-txns-txintotal-toolarge", // $NON-NLS
                "bad-txns-oversize", // $NON-NLS
                "mandatory-script-verify-flag-failed", // $NON-NLS
                "bad-txns-no-", // $NON-NLS
                "MUST represent a valid signature", // $NON-NLS
                "proof MUST be valid", // $NON-NLS
                "proof is malformed", // $NON-NLS
                "proof or signature verification failed", // $NON-NLS
                "proof verification failed", // $NON-NLS
                "proof has a non-canonical size" // $NON-NLS
            )

        /**
         * Whether a server's answer ([description]) says it already holds the transaction: an
         * acceptance of it, whatever the code, never a refusal ([ALREADY_HELD_ANSWERS]).
         */
        fun isAlreadyHeld(description: String?): Boolean {
            val text = description.orEmpty()
            return ALREADY_HELD_ANSWERS.any { text.contains(it, ignoreCase = true) }
        }

        /**
         * The rejection a server's response describes, or `null` when the failure is not a
         * refusal for good: any failure to reach the server ([grpcError]), any code but
         * [CODE_VERIFY_ERROR] or [CODE_VERIFY_REJECTED], an answer that the server already holds the
         * transaction ([isAlreadyHeld]), any [INCONCLUSIVE_REFUSALS] text, and any text on none of
         * the allow-lists ([TOO_LARGE_REFUSALS], [EXPIRED_REFUSALS], [INVALID_REFUSALS], checked in
         * that order).
         */
        fun classify(
            grpcError: Boolean,
            code: Int,
            description: String?
        ): SubmitRejection? {
            if (grpcError || (code != CODE_VERIFY_ERROR && code != CODE_VERIFY_REJECTED)) return null
            val text = description.orEmpty()

            fun mentions(marker: String) = text.contains(marker, ignoreCase = true)
            return when {
                isAlreadyHeld(text) -> null
                INCONCLUSIVE_REFUSALS.any(::mentions) -> null
                TOO_LARGE_REFUSALS.any(::mentions) -> TOO_LARGE
                EXPIRED_REFUSALS.any(::mentions) -> EXPIRED
                INVALID_REFUSALS.any(::mentions) -> INVALID
                else -> null
            }
        }
    }
}
