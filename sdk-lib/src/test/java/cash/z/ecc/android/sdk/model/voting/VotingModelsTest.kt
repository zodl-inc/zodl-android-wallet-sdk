package cash.z.ecc.android.sdk.model.voting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VotingModelsTest {
    @Test
    fun votingRoundPhase_ordinal_order_matches_JniRoundPhase() {
        val expectedOrder =
            listOf(
                VotingRoundPhase.INITIALIZED,
                VotingRoundPhase.HOTKEY_GENERATED,
                VotingRoundPhase.DELEGATION_CONSTRUCTED,
                VotingRoundPhase.DELEGATION_PROVED,
                VotingRoundPhase.VOTE_READY
            )
        assertEquals(expectedOrder, VotingRoundPhase.entries)
    }

    @Test
    fun votingNoteInfo_to_string_omits_note_secrets() {
        val text =
            VotingNoteInfo(
                commitment = byteArrayOf(1),
                nullifier = byteArrayOf(2),
                value = 3,
                position = 4,
                diversifier = byteArrayOf(5),
                rho = byteArrayOf(6),
                rseed = byteArrayOf(7),
                scope = VotingNoteScope.EXTERNAL,
                ufvk = "ufvk-fixture"
            ).toString()

        assertEquals("VotingNoteInfo(redacted)", text)
        assertFalse(text.contains("ufvk-fixture"))
    }

    // Task 10 (voting-4.0.0 SDK port): the pre-4.0 mirror types this test used to redaction-test
    // (VotingSharePayload/VotingEncryptedShare/VotingShareDelegationRecord) were deleted --
    // their backing native calls (buildSharePayloadsNative/recordShareDelegationNative) are gone.
    // The port's own sensitive carrier is VotingDelegationInputs (softwareSeed/keystoneSig/
    // keystoneSighash), tested below in its place.
    @Test
    fun votingDelegationInputs_to_string_omits_signing_secrets() {
        val text =
            VotingDelegationInputs(
                walletDbPath = "wallet.db",
                accountUuid = "account-uuid-fixture",
                anchorTreeStateBytes = byteArrayOf(1),
                hotkeySecret = byteArrayOf(2),
                pirEndpoints = listOf("https://pir.example"),
                pirDepth = 1,
                pirTier0Layers = 1,
                pirTier1Layers = 1,
                pirPolyLen = 1,
                keystone = false,
                softwareSeed = byteArrayOf(101),
                keystoneSig = byteArrayOf(102),
                keystoneSighash = byteArrayOf(103)
            ).toString()

        assertEquals("VotingDelegationInputs(redacted)", text)
        assertFalse(text.contains("101"))
        assertFalse(text.contains("102"))
        assertFalse(text.contains("103"))
    }

    @Test
    fun votingHotkey_to_string_omits_stored_secret() {
        val text =
            VotingHotkey(
                storedSecret = byteArrayOf(101),
                rawAddress = byteArrayOf(1),
                address = "address-fixture"
            ).toString()

        assertEquals("VotingHotkey(redacted)", text)
        assertFalse(text.contains("101"))
        assertFalse(text.contains("address-fixture"))
    }
}
