package cash.z.ecc.android.sdk.internal.model

import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ChangeValue
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.FeeRule
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.PriorStepChange
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ProposalStep
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ProposedInput
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ReceivedOutput
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.TransactionBalance
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ValuePool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.Proposal as ProposalProto

class ProposalUnsafeTest {
    private fun receivedOutputInput(
        valuePool: ValuePool,
        value: Long = 10_000L
    ) = ProposedInput
        .newBuilder()
        .setReceivedOutput(
            ReceivedOutput
                .newBuilder()
                .setValuePool(valuePool)
                .setIndex(0)
                .setValue(value)
        ).build()

    private fun proposalWithInputs(vararg valuePools: ValuePool): ProposalUnsafe {
        val step =
            ProposalStep
                .newBuilder()
                .addAllInputs(valuePools.map { receivedOutputInput(it) })
                .setBalance(TransactionBalance.newBuilder().setFeeRequired(10_000L))
                .build()
        val proposal =
            ProposalProto
                .newBuilder()
                .setProtoVersion(1)
                .setFeeRule(FeeRule.Zip317)
                .setMinTargetHeight(1)
                .addSteps(step)
                .build()
        return ProposalUnsafe(proposal)
    }

    @Test
    fun sapling_only_inputs_do_not_use_orchard() {
        val proposal = proposalWithInputs(ValuePool.Sapling, ValuePool.Transparent)
        assertFalse(proposal.usesOrchardInputs())
    }

    @Test
    fun any_orchard_input_uses_orchard() {
        val proposal = proposalWithInputs(ValuePool.Sapling, ValuePool.Orchard)
        assertTrue(proposal.usesOrchardInputs())
    }

    @Test
    fun all_orchard_inputs_use_orchard() {
        val proposal = proposalWithInputs(ValuePool.Orchard, ValuePool.Orchard)
        assertTrue(proposal.usesOrchardInputs())
    }

    @Test
    fun no_inputs_do_not_use_orchard() {
        val proposal = proposalWithInputs()
        assertFalse(proposal.usesOrchardInputs())
    }

    @Test
    fun total_payment_amount_is_what_the_inputs_hold_minus_the_fee() {
        val proposal = proposalWithInputs(ValuePool.Orchard, ValuePool.Orchard, ValuePool.Orchard)

        assertEquals(20_000L, proposal.totalPaymentAmount())
    }

    @Test
    fun total_payment_amount_leaves_out_change_but_not_ephemeral_outputs_spent_by_a_later_step() {
        val first =
            ProposalStep
                .newBuilder()
                .addInputs(receivedOutputInput(ValuePool.Orchard, value = 100_000L))
                .setBalance(
                    TransactionBalance
                        .newBuilder()
                        .setFeeRequired(10_000L)
                        .addProposedChange(changeValue(value = 5_000L, isEphemeral = false))
                        .addProposedChange(changeValue(value = 70_000L, isEphemeral = true))
                ).build()
        val second =
            ProposalStep
                .newBuilder()
                .addInputs(
                    ProposedInput
                        .newBuilder()
                        .setPriorStepChange(PriorStepChange.newBuilder().setStepIndex(0).setChangeIndex(1))
                ).setBalance(TransactionBalance.newBuilder().setFeeRequired(15_000L))
                .build()
        val proposal = ProposalUnsafe(proposal(first, second))

        assertEquals(100_000L - 10_000L - 15_000L - 5_000L, proposal.totalPaymentAmount())
    }

    private fun transparentInput(value: Long) =
        ProposedInput
            .newBuilder()
            .setReceivedOutput(
                ReceivedOutput
                    .newBuilder()
                    .setValuePool(ValuePool.Transparent)
                    .setIndex(0)
                    .setValue(value)
            ).build()

    private fun singleStep(
        inputs: List<ProposedInput>,
        fee: Long,
        change: List<ChangeValue> = emptyList()
    ) = ProposalStep
        .newBuilder()
        .addAllInputs(inputs)
        .setBalance(
            TransactionBalance
                .newBuilder()
                .setFeeRequired(fee)
                .addAllProposedChange(change)
        ).build()

    @Test
    fun total_payment_amount_of_a_single_step_shielded_payment() {
        val step = singleStep(listOf(receivedOutputInput(ValuePool.Sapling, value = 100_000L)), fee = 15_000L)

        assertEquals(85_000L, ProposalUnsafe(proposal(step)).totalPaymentAmount())
    }

    @Test
    fun total_payment_amount_to_a_transparent_recipient_counts_transparent_inputs() {
        val step = singleStep(listOf(transparentInput(60_000L)), fee = 10_000L)

        assertEquals(50_000L, ProposalUnsafe(proposal(step)).totalPaymentAmount())
    }

    @Test
    fun total_payment_amount_leaves_out_change() {
        val step =
            singleStep(
                listOf(receivedOutputInput(ValuePool.Orchard, value = 100_000L)),
                fee = 10_000L,
                change = listOf(changeValue(value = 60_000L, isEphemeral = false))
            )

        assertEquals(30_000L, ProposalUnsafe(proposal(step)).totalPaymentAmount())
    }

    @Test
    fun total_payment_amount_of_a_tex_two_step_proposal_is_the_amount_the_recipient_receives() {
        // Step 0 moves the funds to an ephemeral transparent output; step 1 spends it to the TEX recipient.
        val first =
            singleStep(
                listOf(receivedOutputInput(ValuePool.Orchard, value = 100_000L)),
                fee = 10_000L,
                change = listOf(changeValue(value = 90_000L, isEphemeral = true))
            )
        val second =
            ProposalStep
                .newBuilder()
                .addInputs(
                    ProposedInput
                        .newBuilder()
                        .setPriorStepChange(PriorStepChange.newBuilder().setStepIndex(0).setChangeIndex(0))
                ).setBalance(TransactionBalance.newBuilder().setFeeRequired(15_000L))
                .build()

        assertEquals(100_000L - 10_000L - 15_000L, ProposalUnsafe(proposal(first, second)).totalPaymentAmount())
    }

    @Test
    fun total_payment_amount_of_a_shielding_proposal_is_zero() {
        // Shielding pays nobody: everything but the fee comes back as shielded change, so the check on every
        // proposal sees zero rather than a negative total.
        val step =
            singleStep(
                listOf(transparentInput(60_000L), transparentInput(40_000L)),
                fee = 15_000L,
                change = listOf(changeValue(value = 85_000L, isEphemeral = false))
            )

        assertEquals(0L, ProposalUnsafe(proposal(step)).totalPaymentAmount())
    }

    @Test
    fun total_payment_amount_is_zero_when_everything_but_the_fee_comes_back_as_change() {
        val step =
            singleStep(
                listOf(receivedOutputInput(ValuePool.Sapling, value = 100_000L)),
                fee = 10_000L,
                change = listOf(changeValue(value = 90_000L, isEphemeral = false))
            )

        assertEquals(0L, ProposalUnsafe(proposal(step)).totalPaymentAmount())
    }

    @Test
    fun total_payment_amount_is_not_clamped() {
        val step = singleStep(listOf(receivedOutputInput(ValuePool.Orchard, value = 5_000L)), fee = 10_000L)

        assertEquals(-5_000L, ProposalUnsafe(proposal(step)).totalPaymentAmount())
    }

    private fun changeValue(
        value: Long,
        isEphemeral: Boolean
    ) = ChangeValue
        .newBuilder()
        .setValue(value)
        .setValuePool(ValuePool.Orchard)
        .setIsEphemeral(isEphemeral)
        .build()

    private fun proposal(vararg steps: ProposalStep) =
        ProposalProto
            .newBuilder()
            .setProtoVersion(1)
            .setFeeRule(FeeRule.Zip317)
            .setMinTargetHeight(1)
            .addAllSteps(steps.toList())
            .build()
}
