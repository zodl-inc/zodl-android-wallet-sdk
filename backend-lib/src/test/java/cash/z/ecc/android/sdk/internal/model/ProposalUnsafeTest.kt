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
    fun total_sent_is_what_the_inputs_hold_minus_the_fee() {
        val proposal = proposalWithInputs(ValuePool.Orchard, ValuePool.Orchard, ValuePool.Orchard)

        assertEquals(20_000L, proposal.totalSent())
    }

    @Test
    fun total_sent_leaves_out_change_but_not_ephemeral_outputs_spent_by_a_later_step() {
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

        assertEquals(100_000L - 10_000L - 15_000L - 5_000L, proposal.totalSent())
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
