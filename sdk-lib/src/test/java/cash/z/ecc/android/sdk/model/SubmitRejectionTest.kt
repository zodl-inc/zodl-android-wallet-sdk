package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.transaction.isAcceptance
import cash.z.ecc.android.sdk.internal.transaction.serverRefusal
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubmitRejectionTest {
    private fun failure(
        code: Int,
        description: String?,
        grpcError: Boolean = false
    ) = TransactionSubmitResult.Failure(FirstClassByteArray(byteArrayOf(1)), grpcError, code, description)

    @Test
    fun a_relay_over_its_mempool_maximum_is_too_large() {
        assertEquals(
            SubmitRejection.TOO_LARGE,
            failure(-25, "transaction size 310000 exceeding the configured mempool maximum of 250000").serverRefusal
        )
    }

    @Test
    fun zebra_over_the_zip_218_budget_is_too_large() {
        assertEquals(
            SubmitRejection.TOO_LARGE,
            failure(-25, "mempool transaction has 400 Orchard actions, exceeding the next block's ZIP 218 limit of 330")
                .serverRefusal
        )
    }

    @Test
    fun the_too_large_texts_need_the_verify_error_code() {
        assertNull(failure(-1, "exceeding the configured mempool maximum").serverRefusal)
    }

    @Test
    fun an_expired_transaction_is_expired_on_either_node() {
        assertEquals(SubmitRejection.EXPIRED, failure(-26, "16: tx-overwinter-expired").serverRefusal)
        assertEquals(SubmitRejection.EXPIRED, failure(-26, "tx-expiring-soon").serverRefusal)
        assertEquals(SubmitRejection.EXPIRED, failure(-25, "transaction is expired").serverRefusal)
        assertEquals(
            SubmitRejection.EXPIRED,
            failure(-25, "transaction must not be mined at a block Height(10) greater than its expiry Height(9)")
                .serverRefusal
        )
    }

    @Test
    fun only_refusals_that_can_never_become_valid_are_invalid_under_either_code() {
        listOf(
            "16: bad-txns-invalid-joinsplit-signature",
            "16: bad-txns-joinsplit-verification-failed",
            "16: bad-txns-sapling-bundle-invalid",
            "16: bad-sapling-bundle-authorization",
            "16: bad-orchard-bundle-authorization",
            "16: bad-txns-vout-negative",
            "16: bad-txns-vout-toolarge",
            "16: bad-txns-txouttotal-toolarge",
            "16: bad-txns-txintotal-toolarge",
            "16: mandatory-script-verify-flag-failed (Script evaluated without error)",
            "16: bad-txns-no-source-of-funds",
            "16: bad-txns-no-sink-of-funds",
            "spend auth signature MUST represent a valid signature",
            "spend proof MUST be valid given a primary input",
            "the proof is malformed",
            "proof or signature verification failed",
            "Halo2 proof verification failed",
            "the proof has a non-canonical size"
        ).forEach { text ->
            assertEquals(SubmitRejection.INVALID, failure(-26, text).serverRefusal, text)
            assertEquals(SubmitRejection.INVALID, failure(-25, text).serverRefusal, text)
        }
        assertNull(failure(-26, null).serverRefusal)
        assertNull(failure(-26, "some reason the SDK does not know").serverRefusal)
    }

    @Test
    fun texts_no_node_emits_are_dropped() {
        listOf(
            "transaction is invalid: bad proof",
            "16: bad-txns-fee-negative",
            "16: bad-txns",
            "16: bad-txns-vin-empty",
            "64: tx-size",
            "transaction is too large",
            "66: mempool full",
            "66: insufficient fee",
            "66: mempool min fee not met, 100 < 1000",
            "binding-signature-invalid",
            "spend-proof-invalid"
        ).forEach { text -> assertNull(failure(-26, text).serverRefusal, text) }
        listOf("transaction already in mempool", "already have transaction", "transaction already known")
            .forEach { text -> assertFalse(SubmitRejection.isAlreadyHeld(text), text) }
    }

    @Test
    fun the_size_family_is_too_large() {
        assertEquals(
            SubmitRejection.TOO_LARGE,
            failure(-25, "transaction size 310000 exceeding the configured mempool maximum of 250000").serverRefusal
        )
        assertEquals(SubmitRejection.TOO_LARGE, failure(-25, "exceeding the next block's ZIP 218 limit").serverRefusal)
        assertEquals(SubmitRejection.TOO_LARGE, failure(-26, "16: bad-txns-oversize").serverRefusal)
    }

    @Test
    fun refusals_that_can_become_valid_are_inconclusive() {
        listOf(
            "16: bad-txns-orchard-unknown-anchor",
            "16: bad-txns-sapling-unknown-anchor",
            "16: bad-txns-sapling-duplicate-nullifier",
            "16: bad-txns-orchard-duplicate-nullifier",
            "16: bad-txns-inputs-spent",
            "16: bad-txns-inputs-missingorspent",
            "18: txn-mempool-conflict",
            "Missing inputs",
            "66: min relay fee not met",
            "64: too-long-mempool-chain",
            "64: non-final",
            "16: bad-tx-consensus-branch-id-mismatch",
            "16: bad-tx-zip225-version-too-low",
            "16: bad-tx-version-group-id",
            "the server is busy"
        ).forEach { text ->
            assertNull(failure(-26, text).serverRefusal, text)
            assertNull(failure(-25, text).serverRefusal, text)
        }
    }

    @Test
    fun an_answer_that_the_node_already_holds_the_transaction_is_an_acceptance_whatever_the_code() {
        listOf(
            -26 to "18: txn-already-in-mempool",
            -26 to "18: txn-already-known",
            -27 to "transaction already in block chain",
            -25 to "transaction already exists in mempool"
        ).forEach { (code, text) ->
            assertTrue(SubmitRejection.isAlreadyHeld(text), text)
            assertTrue(failure(code, text).isAcceptance(), text)
            assertNull(failure(code, text).serverRefusal, text)
        }
        // An acceptance wins over any refusal text in the same answer.
        assertNull(failure(-26, "18: txn-already-in-mempool (bad-txns-vout-negative)").serverRefusal)
        assertFalse(SubmitRejection.isAlreadyHeld("16: bad-txns-vout-negative"))
        assertFalse(SubmitRejection.isAlreadyHeld(null))
    }

    @Test
    fun transport_failures_and_other_codes_have_no_rejection() {
        assertNull(failure(-1, "16: bad-txns-vout-negative").serverRefusal)
        assertNull(failure(14, "exceeding the configured mempool maximum", grpcError = true).serverRefusal)
        assertNull(failure(-26, "bad-txns-vout-negative", grpcError = true).serverRefusal)
    }
}
