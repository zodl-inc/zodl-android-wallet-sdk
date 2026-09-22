@file:Suppress("LongMethod")

package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationInputs
import cash.z.ecc.android.sdk.internal.model.voting.JniKeystoneSignatureInput
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Exercises `VotingRustBackend`'s raw JNI surface against the round-driver session model
 * (voting-5.0.0 SDK port). Every test either round-trips real data through the native
 * boundary or asserts a [RuntimeException] rejection from the native side -- both prove the
 * JNI signature resolved and matched (no [UnsatisfiedLinkError]), which is this file's real
 * job now that Tasks 1-8 replaced most of the JNI export surface with the session-based
 * `openRoundSession`/`runRound`/`openShareTrackingSession`/Keystone-batch API.
 *
 * A handful of tests need a live (but never network-reachable in these tests) Tor runtime
 * handle -- `openRoundSessionNative`/`runRoundNative`/`runShareTrackingSessionNative` all take
 * one. Rather
 * than duplicating `TorClient`'s JNI wiring, [torRuntimeHandleForTesting] reads `TorClient`'s
 * private `nativeHandle` field via reflection; this is test-only scaffolding, not a production
 * pattern.
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("LargeClass", "MagicNumber", "DEPRECATION_ERROR")
class VotingRustBackendTest {
    companion object {
        private const val FIELD_BYTES = 32
        private const val HOTKEY_STORED_SECRET_BYTES = 64
        private const val SHARE_INDEX = 5
        private const val OUT_OF_RANGE_SHARE_INDEX = 16
        private const val DIVERSIFIER_BYTES = 11
        private const val ORCHARD_FVK_BYTES = 96
        private const val ORCHARD_WITNESS_PATH_DEPTH = 32
        private val VOTE_COMMITMENT = ByteArray(FIELD_BYTES) { 1 }
        private val BLIND = ByteArray(FIELD_BYTES) { 2 }
        private val SHORT_FIELD = ByteArray(FIELD_BYTES - 1)
        private val EXPECTED_NULLIFIER =
            "8d6d97caa19a20e5e67e7cc24aaaa7beb72b4a513863f6adbe7b62ba1b1b0010".hexToByteArray()

        private const val WALLET_ID = "wallet-1"
        private const val OTHER_WALLET_ID = "wallet-2"

        // RoundExecutor::with_binding (openRoundSessionNative) requires round_id to be exactly
        // 64 lowercase hex characters -- the crate's canonical Pallas field element encoding.
        // There is no init-round JNI export any more; a round row is created implicitly the
        // first time openRoundSessionNative's RoundBinding is persisted for this id.
        private const val ROUND_ID = "0101010101010101010101010101010101010101010101010101010101010101"
        private const val TESTNET_NETWORK_ID = JNI_VOTING_NETWORK_ID_TESTNET
        private const val ACCOUNT_INDEX = 0
        private const val SECOND_ROUND_ID = "0202020202020202020202020202020202020202020202020202020202020202"
        private const val NOTE_VALUE = 13_000_000L
        private const val LARGE_BUNDLE_WEIGHT = 62_500_000L
        private const val SMALL_BUNDLE_WEIGHT = 12_500_000L
        private const val TWO_BUNDLE_ELIGIBLE_WEIGHT = 75_000_000L
        private val HOTKEY_SEED = ByteArray(64) { 0x42 }
        private val OTHER_HOTKEY_SEED = ByteArray(64) { 0x43 }
        private const val EMPTY_ORCHARD_NOTE_COMMITMENT =
            "0200000000000000000000000000000000000000000000000000000000000000"
        private const val EMPTY_ORCHARD_WITNESS_ROOT =
            "ae2935f1dfd8a24aed7c70df7de3a668eb7a49b1319880dde2bbd9031ae5d82f"
        private val EMPTY_ORCHARD_AUTH_PATH =
            listOf(
                "0200000000000000000000000000000000000000000000000000000000000000",
                "d1ab2507c809c2713c000f525e9fbdcb06c958384e51b9cc7f792dde6c97f411",
                "c7413f4614cd64043abbab7cc1095c9bb104231cea89e2c3e0df83769556d030",
                "2111fc397753e5fd50ec74816df27d6ada7ed2a9ac3816aab2573c8fac794204",
                "806afbfeb45c64d4f2384c51eff30764b84599ae56a7ab3d4a46d9ce3aeab431",
                "873e4157f2c0f0c645e899360069fcc9d2ed9bc11bf59827af0230ed52edab18",
                "27ab1320953ae1ad70c8c15a1253a0a86fbc8a0aa36a84207293f8a495ffc402",
                "4e14563df191a2a65b4b37113b5230680555051b22d74a8e1f1d706f90f3133b",
                "b3bbe4f993d18a0f4eb7f4174b1d8555ce3396855d04676f1ce4f06dda07371f",
                "4ef5bde9c6f0d76aeb9e27e93fba28c679dfcb991cbcb8395a2b57924cbd170e",
                "a3c02568acebf5ca1ec30d6a7d7cd217a47d6a1b8311bf9462a5f939c6b74307",
                "3ef9b30bae6122da1605bad6ec5d49b41d4d40caa96c1cf6302b66c5d2d10d39",
                "22ae2800cb93abe63b70c172de70362d9830e53800398884a7a64ff68ed99e0b",
                "187110d92672c24cedb0979cdfc917a6053b310d145c031c7292bb1d65b7661b",
                "3f98adbe364f148b0cc2042cafc6be1166fae39090ab4b354bfb6217b964453b",
                "63f8dbd10df936f1734973e0b3bd25f4ed440566c923085903f696bc6347ec0f",
                "2182163eac4061885a313568148dfae564e478066dcbe389a0ddb1ecb7f5dc34",
                "bd9dc0681918a3f3f9cd1f9e06aa1ad68927da63acc13b92a2578b2738a6d331",
                "ca2ced953b7fb95e3ba986333da9e69cd355223c929731094b6c2174c7638d2e",
                "55354b96b56f9e45aae1e0094d71ee248dabf668117778bdc3c19ca5331a4e1a",
                "7097b04c2aa045a0deffcaca41c5ac92e694466578f5909e72bb78d33310f705",
                "e81d6821ff813bd410867a3f22e8e5cb7ac5599a610af5c354eb392877362e01",
                "157de8567f7c4996b8c4fdc94938fd808c3b2a5ccb79d1a63858adaa9a6dd824",
                "fe1fce51cd6120c12c124695c4f98b275918fceae6eb209873ed73fe73775d0b",
                "1f91982912012669f74d0cfa1030ff37b152324e5b8346b3335a0aaeb63a0a2d",
                "5dec15f52af17da3931396183cbbbfbea7ed950714540aec06c645c754975522",
                "e8ae2ad91d463bab75ee941d33cc5817b613c63cda943a4c07f600591b088a25",
                "d53fdee371cef596766823f4a518a583b1158243afe89700f0da76da46d0060f",
                "15d2444cefe7914c9a61e829c730eceb216288fee825f6b3b6298f6f6b6bd62e",
                "4c57a617a0aa10ea7a83aa6b6b0ed685b6a3d9e5b8fd14f56cdc18021b12253f",
                "3fd4915c19bd831a7920be55d969b2ac23359e2559da77de2373f06ca014ba27",
                "87d063cd07ee4944222b7762840eb94c688bec743fa8bdf7715c8fe29f104c2a"
            )
    }

    @Test
    fun compute_share_nullifier_returns_known_vector() =
        runTest {
            val backend = VotingRustBackend.new()
            val nullifier = backend.computeShareNullifier(VOTE_COMMITMENT, SHARE_INDEX, BLIND)
            val swappedNullifier = backend.computeShareNullifier(BLIND, SHARE_INDEX, VOTE_COMMITMENT)

            assertContentEquals(EXPECTED_NULLIFIER, nullifier)
            assertFalse(EXPECTED_NULLIFIER.contentEquals(swappedNullifier))
        }

    @Test
    fun compute_share_nullifier_rejects_malformed_inputs() =
        runTest {
            val backend = VotingRustBackend.new()

            assertFailsWith<RuntimeException> {
                backend.computeShareNullifier(SHORT_FIELD, SHARE_INDEX, BLIND)
            }
            assertFailsWith<RuntimeException> {
                backend.computeShareNullifier(VOTE_COMMITMENT, SHARE_INDEX, SHORT_FIELD)
            }
            assertFailsWith<RuntimeException> {
                backend.computeShareNullifier(VOTE_COMMITMENT, OUT_OF_RANGE_SHARE_INDEX, BLIND)
            }
        }

    @Test
    fun warm_proving_caches_smoke() =
        // warmProvingCachesNative now calls the crate's start_proving_cache_warmup, which only
        // spawns the crate's own background thread and returns immediately -- unlike the old
        // synchronous warm_proving_caches (which built the zk proving keys inline and needed a
        // 5-minute runTest timeout on a slow emulator), this call itself is cheap and fits well
        // within runTest's default timeout regardless of how long the actual, now-backgrounded,
        // key generation takes.
        runTest {
            VotingRustBackend.new().warmProvingCaches()
        }

    @Test
    fun warm_proving_caches_is_idempotent() =
        // start_proving_cache_warmup's own doc comment: "Later calls are no-ops." -- mirrors
        // configure_voting_is_idempotent's shape for the sibling one-time-setup native call.
        runTest {
            val backend = VotingRustBackend.new()
            backend.warmProvingCaches()
            backend.warmProvingCaches()
        }

    @Test
    fun configure_voting_is_idempotent() =
        runTest {
            val backend = VotingRustBackend.new()
            // configureVotingNative's own doc comment: a second call observes
            // AlreadyConfigured and is intentionally not escalated to an exception.
            backend.configureVoting()
            backend.configureVoting()
        }

    @Test
    fun extract_orchard_fvk_from_ufvk_is_deterministic() =
        runTest {
            val backend = VotingRustBackend.new()
            val ufvk = deriveTestUfvk()

            val first = backend.extractOrchardFvkFromUfvk(ufvk, TESTNET_NETWORK_ID)
            val second = backend.extractOrchardFvkFromUfvk(ufvk, TESTNET_NETWORK_ID)

            assertEquals(ORCHARD_FVK_BYTES, first.size)
            assertContentEquals(first, second)
            assertFailsWith<RuntimeException> {
                backend.extractOrchardFvkFromUfvk("not-a-ufvk", TESTNET_NETWORK_ID)
            }
        }

    @Test
    fun derive_hotkey_raw_address_is_deterministic_and_rejects_short_seed() =
        runTest {
            val backend = VotingRustBackend.new()

            val first = backend.deriveHotkeyRawAddress(HOTKEY_SEED, TESTNET_NETWORK_ID)
            val second = backend.deriveHotkeyRawAddress(HOTKEY_SEED, TESTNET_NETWORK_ID)
            val otherSeed = backend.deriveHotkeyRawAddress(OTHER_HOTKEY_SEED, TESTNET_NETWORK_ID)

            assertEquals(DIVERSIFIER_BYTES + FIELD_BYTES, first.size)
            assertContentEquals(first, second)
            assertFalse(first.contentEquals(otherSeed))
            assertFailsWith<RuntimeException> {
                backend.deriveHotkeyRawAddress(SHORT_FIELD, TESTNET_NETWORK_ID)
            }
        }

    @Test
    fun extract_nc_root_decodes_tree_state() =
        runTest {
            val backend = VotingRustBackend.new()

            val root = backend.extractNcRoot(backend.treeStateFixtureForTesting())

            assertContentEquals(EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray(), root)
            assertFailsWith<RuntimeException> {
                backend.extractNcRoot(byteArrayOf(1, 2, 3))
            }
        }

    @Test
    fun verify_witness_returns_boolean() =
        runTest {
            val backend = VotingRustBackend.new()
            val validWitness = witnesses().single()

            assertTrue(backend.verifyWitness(validWitness))
            assertFalse(backend.verifyWitness(validWitness.copy(root = ByteArray(FIELD_BYTES) { 4 })))
            assertFailsWith<RuntimeException> {
                backend.verifyWitness(
                    validWitness.copy(
                        authPath = validWitness.authPath.dropLast(1)
                    )
                )
            }
        }

    @Test
    fun note_and_witness_array_fixtures_cross_rust_to_kotlin_construction() =
        runTest {
            val backend = VotingRustBackend.new()

            val note = backend.noteInfoArrayFixtureForTesting().single()
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x01 }, note.commitment)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x02 }, note.nullifier)
            assertEquals(123_456L, note.value)
            assertEquals(7L, note.position)
            assertContentEquals(ByteArray(DIVERSIFIER_BYTES) { 0x03 }, note.diversifier)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x04 }, note.rho)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x05 }, note.rseed)
            assertEquals(1, note.scope)
            assertEquals("ufvk-fixture", note.ufvk)

            val witness = backend.witnessDataArrayFixtureForTesting().single()
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x11 }, witness.noteCommitment)
            assertEquals(9L, witness.position)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x12 }, witness.root)
            assertEquals(ORCHARD_WITNESS_PATH_DEPTH, witness.authPath.size)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x20 }, witness.authPath.first())
        }

    @Test
    fun voting_db_close_is_idempotent_and_disables_further_calls() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)

            assertNull(db.getRoundState(ROUND_ID))

            db.close()
            db.close()
            assertFailsWith<IllegalStateException> {
                db.getRoundState(ROUND_ID)
            }
        }

    @Test
    fun voting_db_list_rounds_is_empty_for_a_fresh_wallet() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                assertEquals(emptyList(), db.listRounds().asList())
                assertNull(db.getRoundState(ROUND_ID))
            } finally {
                db.close()
            }
        }

    @Test
    fun compute_bundle_setup_returns_exact_weights() =
        runTest {
            val setup = VotingRustBackend.new().computeBundleSetup(notes(noteCount = 6))

            assertEquals(2, setup.bundleCount)
            assertEquals(TWO_BUNDLE_ELIGIBLE_WEIGHT, setup.eligibleWeight)
            assertEquals(listOf(LARGE_BUNDLE_WEIGHT, SMALL_BUNDLE_WEIGHT), setup.bundleWeights)
            assertEquals(setup.eligibleWeight, setup.bundleWeights.sum())
        }

    @Test
    fun compute_bundle_setup_rejects_unknown_note_scope() =
        runTest {
            val notes =
                listOf(note(value = NOTE_VALUE, position = 0, byteValue = 1, scope = 2))

            assertFailsWith<RuntimeException> {
                VotingRustBackend.new().computeBundleSetup(notes)
            }
        }

    @Test
    fun compute_bundle_setup_rejects_malformed_diversifier() =
        runTest {
            val notes =
                listOf(
                    note(value = NOTE_VALUE, position = 0, byteValue = 1)
                        .copy(diversifier = ByteArray(DIVERSIFIER_BYTES - 1))
                )

            assertFailsWith<RuntimeException> {
                VotingRustBackend.new().computeBundleSetup(notes)
            }
        }

    @Test
    fun setup_bundles_rejects_a_round_that_does_not_exist_yet() =
        runTest {
            // There is no init-round JNI export any more, and (confirmed empirically on-device
            // -- see round_session_run_round_does_not_persist_the_round_row's doc comment)
            // openRoundSessionNative/runRoundNative do not persist a `rounds` table row either.
            // setupBundlesNative's insert therefore always violates the bundles-to-rounds
            // foreign key for a round no JNI call has created -- this reaches the real native
            // boundary (a RuntimeException, not UnsatisfiedLinkError), which is what this test
            // actually proves; see this task's report for why round creation is currently a gap.
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                assertFailsWith<RuntimeException> {
                    db.setupBundles(ROUND_ID, notes(noteCount = 6))
                }
                assertEquals(0, db.getBundleCount(ROUND_ID))
            } finally {
                db.close()
            }
        }

    @Test
    fun get_bundle_count_and_delete_skipped_bundles_are_zero_for_an_unknown_round() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                assertEquals(0, db.getBundleCount(ROUND_ID))
                assertEquals(0L, db.deleteSkippedBundles(ROUND_ID, keepCount = 0))
                // clearRound is a pure delete-if-present; it does not require the round to exist.
                db.clearRound(ROUND_ID)
            } finally {
                db.close()
            }
        }

    @Test
    fun voting_db_keeps_wallet_state_isolated() =
        runTest {
            val dbPath = newDbPath()
            val firstWallet = VotingRustBackend.new().openVotingDb(dbPath, WALLET_ID, TESTNET_NETWORK_ID)
            val secondWallet = VotingRustBackend.new().openVotingDb(dbPath, OTHER_WALLET_ID, TESTNET_NETWORK_ID)
            try {
                assertEquals(0, firstWallet.getBundleCount(ROUND_ID))
                assertEquals(0, secondWallet.getBundleCount(ROUND_ID))
                assertNull(firstWallet.getRoundState(ROUND_ID))
                assertNull(secondWallet.getRoundState(ROUND_ID))
            } finally {
                firstWallet.close()
                secondWallet.close()
            }
        }

    @Test
    fun generate_hotkey_mints_fresh_random_and_reconstructs_from_stored_secret() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                val fresh = db.generateHotkey(ByteArray(0))
                val otherFresh = db.generateHotkey(ByteArray(0))
                assertEquals(HOTKEY_STORED_SECRET_BYTES, fresh.storedSecret.size)
                assertEquals(DIVERSIFIER_BYTES + FIELD_BYTES, fresh.rawAddress.size)
                assertFalse(fresh.storedSecret.contentEquals(otherFresh.storedSecret))

                val reconstructed = db.generateHotkey(fresh.storedSecret)
                assertContentEquals(fresh.storedSecret, reconstructed.storedSecret)
                assertContentEquals(fresh.rawAddress, reconstructed.rawAddress)
                assertEquals(fresh.address, reconstructed.address)
                assertTrue(fresh.address.startsWith("utest1"))

                val seeded = db.generateHotkey(HOTKEY_SEED)
                val otherSeeded = db.generateHotkey(OTHER_HOTKEY_SEED)
                assertContentEquals(
                    seeded.rawAddress,
                    VotingRustBackend.new().deriveHotkeyRawAddress(HOTKEY_SEED, TESTNET_NETWORK_ID)
                )
                assertFalse(seeded.rawAddress.contentEquals(otherSeeded.rawAddress))

                assertFailsWith<RuntimeException> {
                    db.generateHotkey(SHORT_FIELD)
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun precompute_delegation_pir_rejects_malformed_pir_url() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                val notes = notes(noteCount = 6)

                assertFailsWith<RuntimeException> {
                    db.precomputeDelegationPir(
                        roundId = ROUND_ID,
                        bundleIndex = 0,
                        pirServerUrl = "not-a-valid-url",
                        pirDepth = 1,
                        pirTier0Layers = 1,
                        pirTier1Layers = 1,
                        pirPolyLen = 2048,
                        notes = bundledNotes(notes, bundleIndex = 0)
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun precompute_pir_proofs_rejects_malformed_pir_url() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                // Unlike precomputeDelegationPir, this call is bundle- and round-independent --
                // notes are passed directly, with no bundledNotes()/roundId/bundleIndex needed.
                val notes = notes(noteCount = 6)

                assertFailsWith<RuntimeException> {
                    db.precomputePirProofs(
                        torRuntime = 0,
                        pirServerUrl = "not-a-valid-url",
                        pirDepth = 1,
                        pirTier0Layers = 1,
                        pirTier1Layers = 1,
                        pirPolyLen = 2048,
                        notes = notes
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun precompute_snapshot_bundles_rejects_malformed_pir_url() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                // connect_pir_client runs before precompute_snapshot_bundles_with_report ever
                // touches round state (same ordering precomputeDelegationPirNative/
                // precomputePirProofsNative use above), so this fails on the malformed URL
                // without needing a real round to exist first.
                val notes = notes(noteCount = 6)

                assertFailsWith<RuntimeException> {
                    db.precomputeSnapshotBundles(
                        torRuntime = 0,
                        roundId = ROUND_ID,
                        pirServerUrl = "not-a-valid-url",
                        pirDepth = 1,
                        pirTier0Layers = 1,
                        pirTier1Layers = 1,
                        pirPolyLen = 2048,
                        notes = notes
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun sync_vote_tree_and_reset_tree_client_reach_native_boundary() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                assertFailsWith<RuntimeException> {
                    db.syncVoteTree(ROUND_ID, "not-a-url")
                }
                // resetTreeClient succeeds even for a round with no cached tree client -- it is
                // a pure cache-drop, not a lookup.
                db.resetTreeClient(ROUND_ID)
            } finally {
                db.close()
            }
        }

    @Test
    fun reset_voting_session_state_is_a_no_op_for_an_unknown_round() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                // No round has been created (there is no init-round JNI export any more --
                // round creation now happens implicitly via openRoundSession).
                // reset_voting_session_state is a best-effort clear of unsigned delegation setup
                // fields; confirmed empirically on-device that it succeeds as a no-op for a
                // round with no persisted state, rather than throwing. This still proves the
                // JNI call reaches the native boundary correctly (no UnsatisfiedLinkError).
                db.resetVotingSessionState(SECOND_ROUND_ID)
            } finally {
                db.close()
            }
        }

    @Test
    fun store_and_get_keystone_signatures_round_trip_and_reject_mismatched_context() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            try {
                assertEquals(emptyList(), db.getKeystoneSignatures(ROUND_ID).asList())

                // No bundle has ever been persisted for this round (there is no bundle-setup
                // path reachable without an existing round row -- see this task's report), so
                // the batch's signing-context match against bundles.pczt_sighash/rk always
                // fails here. This still proves the JNI array marshaling and native round trip
                // work correctly -- the crate-side matching guard is exercised, not bypassed.
                assertFailsWith<RuntimeException> {
                    db.storeKeystoneSignatures(
                        ROUND_ID,
                        listOf(
                            JniKeystoneSignatureInput(
                                bundleIndex = 0,
                                sig = ByteArray(64) { 0x11 },
                                sighash = ByteArray(FIELD_BYTES) { 0xAA.toByte() },
                                rk = ByteArray(FIELD_BYTES) { 0x22 }
                            )
                        )
                    )
                }

                assertEquals(emptyList(), db.getKeystoneSignatures(ROUND_ID).asList())
            } finally {
                db.close()
            }
        }

    /**
     * `runShareTrackingSessionNative` bootstraps a real Tor circuit before it can even attempt
     * (and fail to reach) the fake helper URL, which can legitimately take well past `runTest`'s
     * default 60s timeout on a slow emulator. A generous 5-minute allowance still proves the JNI
     * call resolves and marshals correctly (a real report or a RuntimeException), without
     * flaking on timing.
     */
    @Test
    fun share_tracking_session_reaches_native_boundary_without_a_reachable_helper() =
        runTest(timeout = 5.minutes) {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            val torClient = newTorClientForTesting()
            try {
                val session = db.openShareTrackingSession(ROUND_ID)
                try {
                    // No real helper fleet is reachable, and no shares are pending, so this either
                    // reports a NothingToTrack-style quiescence report or fails while trying to
                    // resolve the helper URL -- either outcome (a real report, or a
                    // RuntimeException) proves the JNI array/report marshaling and native round
                    // trip work; only UnsatisfiedLinkError would indicate a real signature
                    // mismatch.
                    runCatching {
                        session.run(
                            torRuntime = torClient.torRuntimeHandleForTesting(),
                            helperUrls = listOf("https://helper.example"),
                            voteEndTimeSeconds = -1
                        )
                    }.onFailure { error ->
                        assertTrue(error is RuntimeException, "expected RuntimeException, got $error")
                    }
                } finally {
                    session.close()
                }
            } finally {
                db.close()
                torClient.dispose()
            }
        }

    /**
     * Confirmed empirically on-device: neither `openRoundSessionNative`'s `RoundBinding` nor a
     * `runRoundNative` pass persists a `rounds` table row -- `getRoundPlanNative` and
     * `runRoundNative`'s own "needs_ballot" early return both work from the session's in-memory
     * binding alone, but `setBallotIntentsNative` (an actual write path) fails with a
     * `FOREIGN KEY constraint failed` storage error, exactly like `setupBundlesNative` does with
     * no round row. There is currently no JNI export that creates one (the old `initRoundNative`
     * is gone and nothing in the final Task 1-8 export list replaces it) -- see this task's
     * report for why that is flagged as a concern rather than something Task 9 works around.
     */
    @Test
    fun round_session_run_round_and_plan_work_without_a_persisted_round_row() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            val torClient = newTorClientForTesting()
            try {
                val session =
                    db.openRoundSession(
                        torRuntime = torClient.torRuntimeHandleForTesting(),
                        roundId = ROUND_ID,
                        proposalIds = intArrayOf(1),
                        proposalOptionCounts = intArrayOf(2),
                        hotkeySecret = null,
                        chainEndpoints = listOf("https://chain.example"),
                        operationEpoch = 0,
                        configuredHelperUrls = emptyList(),
                        voteTreeNodeUrls = emptyList(),
                        ceremonyStartSeconds = -1,
                        voteEndTimeSeconds = -1
                    )
                try {
                    // No ballot decisions exist yet, so RoundDriver::run determines there is
                    // nothing to submit and returns a needs_ballot quiescence report without
                    // ever needing to reach the (fake) chain endpoint -- confirmed empirically
                    // on-device: this completes immediately, no real network I/O involved.
                    val report = session.runRound(torClient.torRuntimeHandleForTesting(), delegationInputs = null)
                    assertNotNull(report)
                    assertEquals("needs_ballot", report.quiescenceKind)
                    val plan = assertNotNull(report.plan)
                    assertEquals(ROUND_ID, plan.roundId)
                    assertEquals(listOf(1), plan.openProposals.toList())

                    val refetchedPlan = session.getRoundPlan()
                    assertNotNull(refetchedPlan)
                    assertEquals(ROUND_ID, refetchedPlan.roundId)

                    // setBallotIntents is an actual write path; with no rounds-table row ever
                    // persisted for this round_id it fails the bundles/ballots foreign key, per
                    // this test's doc comment.
                    assertFailsWith<RuntimeException> {
                        session.setBallotIntents(intArrayOf(1), intArrayOf(0))
                    }

                    // No delegation-enabled runRound call ever succeeded on this session, so no
                    // pipeline is cached yet.
                    assertFailsWith<RuntimeException> {
                        session.getKeystoneSigningRequests(intArrayOf(0))
                    }

                    session.setOperationEpoch(1)
                    session.cancel()
                } finally {
                    session.close()
                    session.close()
                }
            } finally {
                db.close()
                torClient.dispose()
            }
        }

    /**
     * Round-bootstrap fix, verified on-device with concrete evidence: [ensureRound] genuinely
     * bootstraps a virgin round, and a delegation-enabled `runRoundNative` call against it no
     * longer fails with "round not found".
     *
     * This test used to be `runRound_with_delegation_inputs_cannot_bootstrap_a_virgin_round` and
     * documented the opposite: Task 10's finding that `delegation_driver.rs`'s
     * `delegation_step_inputs_from_jni` called `voting::storage::queries::load_round_params` --
     * a hard `SELECT ... FROM rounds` -- immediately after decoding `delegation_inputs` and
     * unconditionally before constructing the `DelegationPipeline`, so an unknown `round_id` was
     * rejected before `zcash_voting`'s own bootstrap mechanism (`DelegationPipeline`'s
     * `execute_prepare` -> `prepare_delegation_bundle_inner` -> `observe_ensure_round_context` ->
     * `VotingDb::ensure_round_state`) ever ran. That fix (removing the premature read;
     * [JniDelegationInputs] now carries `snapshotHeight`/`eaPk`/`ncRoot`/`nullifierImtRoot`
     * directly) turned out, on real-device verification, to be necessary but **not sufficient**
     * on its own: `RoundDriver::run`'s planner (`round_planning::classify`) derives delegation
     * obligations from a `DelegationPhase` snapshot over *persisted* `bundles` rows, so with zero
     * bundle rows it proposes zero `Delegate` steps no matter what `delegationInputs` carries --
     * `execute_prepare`'s own bootstrap call is never reached from `runRound` alone on a fully
     * virgin round. Confirmed empirically: even after the `load_round_params` fix,
     * `session.runRound(delegationInputs)` alone still left `getRoundState(ROUND_ID)` `null`.
     *
     * The real fix is the sequence this test now exercises: [ensureRoundNative] (new, exposes
     * the crate's own standalone `DelegationPipeline::ensure_round` -- an inherent method that
     * touches neither the wallet nor bundles, so it can run before either exists) creates the
     * round row from caller-supplied metadata; [setupBundlesNative] can then create bundle rows
     * (its insert has a foreign key on `rounds`, so it could not before); only then can
     * `runRoundNative`'s planner have anything to propose delegation work for. Concrete evidence
     * below: the `rounds` row does not exist before [ensureRound], does exist immediately after
     * it (with this round's own `snapshotHeight`), `runRound` no longer rejects with "round not
     * found", and a write that used to fail with a foreign-key error against a nonexistent round
     * (`setBallotIntents`, per `round_session_run_round_and_plan_work_without_a_persisted_round_row`
     * above) now succeeds.
     */
    @Test
    fun ensureRound_bootstraps_a_virgin_round_and_unblocks_setup_and_run() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID, TESTNET_NETWORK_ID)
            val torClient = newTorClientForTesting()
            try {
                assertNull(db.getRoundState(ROUND_ID))
                assertTrue(db.listRounds().isEmpty())

                val eaPk = ByteArray(FIELD_BYTES) { 0xEA.toByte() }
                val ncRoot = ByteArray(FIELD_BYTES) { 0x01 }
                val nullifierImtRoot = ByteArray(FIELD_BYTES) { 0x02 }

                // The concrete evidence this test is named for: the round genuinely bootstraps.
                db.ensureRound(ROUND_ID, ByteArray(0), snapshotHeight = 10L, eaPk, ncRoot, nullifierImtRoot)
                val bootstrappedState = assertNotNull(db.getRoundState(ROUND_ID))
                assertEquals(ROUND_ID, bootstrappedState.roundId)
                assertEquals(10L, bootstrappedState.snapshotHeight)
                assertTrue(db.listRounds().isNotEmpty())

                // Re-calling with the same params is idempotent (VotingDb::ensure_round's own
                // contract): no error, no change.
                db.ensureRound(ROUND_ID, ByteArray(0), snapshotHeight = 10L, eaPk, ncRoot, nullifierImtRoot)
                assertEquals(10L, assertNotNull(db.getRoundState(ROUND_ID)).snapshotHeight)

                // setupBundles's insert has a foreign key on rounds -- this only succeeds now
                // that ensureRound has created the row (contrast with
                // round_session_run_round_and_plan_work_without_a_persisted_round_row's
                // setBallotIntents failing the same way on a round ensureRound was never called
                // for).
                val bundleSetup = db.setupBundles(ROUND_ID, notes(1))
                assertEquals(1, bundleSetup.bundleCount)
                assertEquals(1, db.getBundleCount(ROUND_ID))

                val hotkey = db.generateHotkey(HOTKEY_SEED)

                val session =
                    db.openRoundSession(
                        torRuntime = torClient.torRuntimeHandleForTesting(),
                        roundId = ROUND_ID,
                        proposalIds = intArrayOf(1),
                        proposalOptionCounts = intArrayOf(2),
                        hotkeySecret = hotkey.storedSecret,
                        chainEndpoints = listOf("https://chain.example"),
                        operationEpoch = 0,
                        configuredHelperUrls = emptyList(),
                        voteTreeNodeUrls = emptyList(),
                        ceremonyStartSeconds = -1,
                        voteEndTimeSeconds = -1
                    )
                try {
                    // A real (if otherwise-unused) temp path: SqliteWalletDbOpener::open_for_read
                    // genuinely opens (and, since the path does not exist yet, creates) this
                    // file now that a delegation-enabled run reaches real wallet I/O.
                    val walletDbPath =
                        createTempDirectory("wallet-db-").resolve("wallet.db").toFile().absolutePath
                    val delegationInputs =
                        JniDelegationInputs(
                            dbHandle = db.dbHandleForTesting(),
                            walletDbPath = walletDbPath,
                            accountUuid = "unused-account-uuid",
                            anchorTreeStateBytes = ByteArray(0),
                            hotkeySecret = hotkey.storedSecret,
                            pirEndpoints = arrayOf("https://pir.example"),
                            // A real, valid YPIR layout (zcash_voting's own
                            // config::tests::test_pir_layout fixture) -- confirmed empirically
                            // that PirFleet::new rejects an inconsistent/undersized one before
                            // the pipeline is even touched, so this cannot be arbitrary.
                            pirDepth = 19,
                            pirTier0Layers = 12,
                            pirTier1Layers = 7,
                            pirPolyLen = 4096,
                            keystone = false,
                            softwareSeed = ByteArray(FIELD_BYTES) { 0x5A },
                            keystoneSig = null,
                            keystoneSighash = null,
                            // Must match ensureRound's params above exactly: VotingDb::ensure_round
                            // rejects a round it already knows under different parameters.
                            snapshotHeight = 10,
                            eaPk = eaPk,
                            ncRoot = ncRoot,
                            nullifierImtRoot = nullifierImtRoot
                        )

                    // Whatever happens deeper in the pipeline (the wallet path has no real
                    // notes, so a later stage may legitimately fail), the call must not be
                    // rejected up front with "round not found" -- the original bug this test
                    // guards against.
                    runCatching {
                        session.runRound(torClient.torRuntimeHandleForTesting(), delegationInputs)
                    }.onFailure { error ->
                        assertFalse(
                            error.message.orEmpty().contains("round not found"),
                            "the round-bootstrap bug regressed: ${error.message}"
                        )
                    }

                    // The round is still there, unaffected by whatever runRound did or did not
                    // dispatch.
                    assertEquals(10L, assertNotNull(db.getRoundState(ROUND_ID)).snapshotHeight)

                    // Further proof the bootstrap is real, not a half-write: a write that used
                    // to fail with a foreign-key error against a nonexistent round now succeeds.
                    session.setBallotIntents(intArrayOf(1), intArrayOf(0))
                } finally {
                    session.close()
                }
            } finally {
                db.close()
                torClient.dispose()
            }
        }

    private suspend fun newTorClientForTesting() =
        TorClient.new(
            createTempDirectory("tor-client-").toFile(),
            FakeRustBackend(TESTNET_NETWORK_ID, mutableListOf())
        )

    private fun TorClient.torRuntimeHandleForTesting(): Long {
        val field = TorClient::class.java.getDeclaredField("nativeHandle")
        field.isAccessible = true
        return field.get(this) as Long
    }

    private fun VotingRustBackend.VotingDb.dbHandleForTesting(): Long {
        val field = VotingRustBackend.VotingDb::class.java.getDeclaredField("dbHandle")
        field.isAccessible = true
        return field.get(this) as Long
    }

    private fun newDbPath() =
        createTempDirectory("voting-db-").resolve("voting.db").toFile().absolutePath

    private fun bundledNotes(
        notes: List<JniNoteInfo>,
        bundleIndex: Int,
        bundleSize: Int = 5
    ): List<JniNoteInfo> {
        val start = bundleIndex * bundleSize
        return notes.subList(start, minOf(start + bundleSize, notes.size))
    }

    private suspend fun deriveTestUfvk(
        seed: ByteArray = HOTKEY_SEED,
        networkId: Int = TESTNET_NETWORK_ID,
        accountIndex: Int = ACCOUNT_INDEX
    ): String =
        RustDerivationTool
            .new()
            .deriveUnifiedFullViewingKeys(seed, networkId, accountIndex + 1)
            .last()

    private fun notes(
        noteCount: Int,
        value: Long = NOTE_VALUE,
        positionOffset: Long = 0,
        ufvkString: String = ""
    ): List<JniNoteInfo> =
        List(noteCount) { index ->
            note(
                value = value,
                position = positionOffset + index.toLong(),
                byteValue = index + 1,
                ufvkString = ufvkString
            )
        }

    private fun note(
        value: Long,
        position: Long,
        byteValue: Int,
        scope: Int = 0,
        ufvkString: String = ""
    ) = JniNoteInfo(
        commitment = ByteArray(FIELD_BYTES) { byteValue.toByte() },
        nullifier = ByteArray(FIELD_BYTES) { (byteValue + 1).toByte() },
        value = value,
        position = position,
        diversifier = ByteArray(DIVERSIFIER_BYTES),
        rho = ByteArray(FIELD_BYTES),
        rseed = ByteArray(FIELD_BYTES),
        scope = scope,
        ufvk = ufvkString
    )

    private fun witnesses(
        authPathEntries: Int = ORCHARD_WITNESS_PATH_DEPTH,
        noteCommitment: String = EMPTY_ORCHARD_NOTE_COMMITMENT,
        position: Long = 0
    ) = listOf(
        JniWitnessData(
            noteCommitment = noteCommitment.hexToByteArray(),
            position = position,
            root = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray(),
            authPath =
                List(authPathEntries) { index ->
                    EMPTY_ORCHARD_AUTH_PATH[index].hexToByteArray()
                }
        )
    )
}
