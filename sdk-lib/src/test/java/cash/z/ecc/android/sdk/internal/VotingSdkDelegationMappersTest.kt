package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.voting.VotingDelegationInputs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotSame

/**
 * Regression coverage for the live incident this branch hit on 2026-09-23: a 13-bundle round's
 * automatic bundle-failure retry (`SubmitVotesUseCase.runRoundWithBundleFailureRetry`) reuses the
 * SAME `VotingDelegationInputs` instance across more than one `run()` call on the same
 * `VotingRoundSession` -- so does the Keystone `ensureDelegationPipeline` -> `runToCompletion`
 * path. `VotingRustBackend.RoundSession.runRound()` zeroizes its `JniDelegationInputs.hotkeySecret`
 * / `.softwareSeed` in a `finally` block immediately after every native call returns. Without a
 * defensive copy at the `toInternal()` boundary, that zeroization mutated the CALLER's own array
 * in place, so a second `run()` call on the same reused `VotingDelegationInputs` decoded an
 * all-zero "secret" at the JNI boundary -- still a length-valid ZIP-32 seed, so the crate derived
 * a different-but-well-formed hotkey and refused it with "delegation driver delegates to a
 * different voting hotkey than the round binding" instead of failing to parse.
 */
class VotingSdkDelegationMappersTest {
    @Test
    fun toInternal_copies_hotkeySecret_and_softwareSeed_rather_than_referencing_them() {
        val hotkeySecret = ByteArray(64) { 0x11 }
        val softwareSeed = ByteArray(32) { 0x22 }
        val inputs = delegationInputsFixture(hotkeySecret = hotkeySecret, softwareSeed = softwareSeed)

        val internal = inputs.toInternal(dbHandle = 7L)

        // Equal content...
        assertContentEquals(hotkeySecret, internal.hotkeySecret)
        assertContentEquals(softwareSeed, internal.softwareSeed)
        // ...but NOT the same array -- mutating one must never affect the other.
        assertNotSame(hotkeySecret, internal.hotkeySecret)
        assertNotSame(softwareSeed, internal.softwareSeed)
    }

    @Test
    fun toInternal_output_can_be_zeroized_without_mutating_the_callers_original_arrays() {
        val hotkeySecret = ByteArray(64) { 0x33 }
        val softwareSeed = ByteArray(32) { 0x44 }
        val inputs = delegationInputsFixture(hotkeySecret = hotkeySecret, softwareSeed = softwareSeed)

        val internal = inputs.toInternal(dbHandle = 7L)
        // Exactly what VotingRustBackend.RoundSession.runRound()'s finally block does to its copy.
        internal.hotkeySecret?.fill(0)
        internal.softwareSeed?.fill(0)

        // The caller's own VotingDelegationInputs -- which the app's bundle-failure auto-retry and
        // Keystone flow both legitimately reuse across a second run() call -- must be untouched.
        assertContentEquals(ByteArray(64) { 0x33 }, hotkeySecret)
        assertContentEquals(ByteArray(32) { 0x44 }, softwareSeed)
        // And re-mapping the same still-intact caller input again must still see the real bytes,
        // not the zeroized ones from the first mapping.
        val internalAgain = inputs.toInternal(dbHandle = 7L)
        assertContentEquals(ByteArray(64) { 0x33 }, internalAgain.hotkeySecret)
        assertContentEquals(ByteArray(32) { 0x44 }, internalAgain.softwareSeed)
    }

    @Test
    fun toInternal_handles_null_hotkeySecret_and_softwareSeed() {
        val inputs = delegationInputsFixture(hotkeySecret = null, softwareSeed = null)

        val internal = inputs.toInternal(dbHandle = 7L)

        kotlin.test.assertNull(internal.hotkeySecret)
        kotlin.test.assertNull(internal.softwareSeed)
    }

    private fun delegationInputsFixture(
        hotkeySecret: ByteArray?,
        softwareSeed: ByteArray?
    ) = VotingDelegationInputs(
        walletDbPath = "wallet.db",
        accountUuid = "account-uuid",
        anchorTreeStateBytes = byteArrayOf(1),
        hotkeySecret = hotkeySecret,
        pirEndpoints = listOf("https://pir.example"),
        pirDepth = 1,
        pirTier0Layers = 1,
        pirTier1Layers = 1,
        pirPolyLen = 1,
        keystone = false,
        softwareSeed = softwareSeed,
        keystoneSig = null,
        keystoneSighash = null,
        snapshotHeight = 10,
        eaPk = byteArrayOf(3),
        ncRoot = byteArrayOf(4),
        nullifierImtRoot = byteArrayOf(5)
    )
}
