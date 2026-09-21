use super::db::*;
use super::helpers::*;
use super::*;

use voting::{SqliteWalletDbOpener, WalletDbOpener};

/// Fails loudly if the wallet has not been scanned through `snapshot_height`.
///
/// Historical-height note queries (`get_unspent_ironwood_notes_at_historical_height`,
/// under `select_snapshot_note_infos` below) do not themselves distinguish "not yet
/// scanned that far" from "genuinely no notes" -- both read as an empty/short result.
/// Checking this explicitly turns a silently-wrong empty note list into a clear error.
fn require_fully_scanned_to_snapshot(
    fully_scanned_height: Option<zcash_protocol::consensus::BlockHeight>,
    snapshot_height: zcash_protocol::consensus::BlockHeight,
) -> anyhow::Result<()> {
    let snapshot_height_u32 = u32::from(snapshot_height);
    let Some(fully_scanned_height) = fully_scanned_height else {
        return Err(anyhow!(
            "wallet DB has no fully scanned height; snapshot_height={snapshot_height_u32}"
        ));
    };

    let fully_scanned_height_u32 = u32::from(fully_scanned_height);
    if fully_scanned_height_u32 < snapshot_height_u32 {
        return Err(anyhow!(
            "wallet DB fully scanned height {fully_scanned_height_u32} is below snapshot_height {snapshot_height_u32}"
        ));
    }

    Ok(())
}

/// Reads the account's voting-eligible note plaintexts from the MAIN wallet database at a
/// historical snapshot height -- the input [`computeBundleSetupNative`]/[`setupBundlesNative`]
/// need before any round/`DelegationPipeline` exists.
///
/// A narrower re-addition of the pre-4.0 SDK port's deleted `getWalletNotesNative` (see this
/// repo's `.superpowers/sdd/2026-09-11-voting-4.0.0-sdk-port/symbol-map.md`, `notes.rs:150` row,
/// for the original deletion rationale): the *delegation* pipeline now selects its own notes
/// internally once a round is running (`DelegationPipeline::select_notes`, via
/// `SqliteWalletDbOpener` in `delegation_driver.rs`'s `build_pipeline`), but
/// `computeBundleSetupNative`/`setupBundlesNative` are an earlier, pre-round step that still
/// takes an explicit `notes` array from the caller -- this is how the caller sources it.
///
/// Reuses the same crate mechanism the delegation pipeline uses to open the wallet DB
/// ([`SqliteWalletDbOpener`]) and the crate's own proof-input-shaped selection helper
/// (`zcash_voting::selection::select_snapshot_note_infos`) rather than re-deriving note
/// selection from scratch, so this narrower function tracks the delegation step's own
/// eligibility rules (Ironwood/V3 pool, unspent as of the snapshot) instead of drifting from
/// them independently.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getWalletNotesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    wallet_db_path: JString<'local>,
    snapshot_height: jlong,
    network_id: jint,
    account_uuid_bytes: JByteArray<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        use zcash_client_backend::data_api::WalletRead;
        use zcash_protocol::consensus::BlockHeight;

        let path = java_string_to_rust(env, &wallet_db_path)?;
        let network = voting_network_from_id(network_id)?;
        let height = BlockHeight::from_u32(jlong_to_u32(snapshot_height, "snapshot_height")?);
        let account_uuid_bytes =
            java_fixed_bytes::<ACCOUNT_UUID_BYTES>(env, &account_uuid_bytes, "accountUuidBytes")?;
        let account_uuid = uuid::Uuid::from_bytes(account_uuid_bytes).to_string();

        let wallet_db = SqliteWalletDbOpener::new(path, network)
            .open_for_read()
            .map_err(|e| anyhow!("open wallet database: {}", e))?;

        let fully_scanned_height = wallet_db
            .block_fully_scanned()
            .map_err(|e| anyhow!("block_fully_scanned: {}", e))?
            .map(|metadata| metadata.block_height());
        require_fully_scanned_to_snapshot(fully_scanned_height, height)?;

        let notes = voting::selection::select_snapshot_note_infos(
            &wallet_db,
            &account_uuid,
            u64::from(u32::from(height)),
        )
        .map_err(|e| anyhow!("select_snapshot_note_infos: {}", e))?;

        make_jni_note_info_array(env, notes)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// C. Note setup
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_computeBundleSetupNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let notes = java_note_info_array(env, &notes, "notes")?;
        let (count, weight, bundle_weights) =
            bundle_setup_from_notes(&notes, voting::BundlePolicy::default())?;
        make_jni_bundle_setup_result(env, count, weight, &bundle_weights)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_setupBundlesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let policy = voting::BundlePolicy::default();
        let (expected_count, expected_weight, bundle_weights) =
            bundle_setup_from_notes(&notes, policy)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let layout = db
            .ensure_bundles_with_skipped_suffix_with_policy(&round_id, &notes, policy)
            .map_err(|e| anyhow!("ensure_bundles_with_skipped_suffix_with_policy: {}", e))?;
        if layout.bundle_count != expected_count || layout.eligible_weight != expected_weight {
            // ensure_bundles_with_skipped_suffix_with_policy has already persisted the
            // round's bundles. Treat a mismatch as an internal bug; callers must clear
            // the round before retrying.
            return Err(anyhow!(
                "setup_bundles result mismatch after persisting bundles; call clearRound before retrying: db=({}, {}) chunk=({}, {})",
                layout.bundle_count,
                layout.eligible_weight,
                expected_count,
                expected_weight
            ));
        }
        make_jni_bundle_setup_result(
            env,
            layout.bundle_count,
            layout.eligible_weight,
            &bundle_weights,
        )
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_generateHotkeyNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    stored_secret: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let network = db.network;
        // Wrap in SecretVec like every other hotkey-secret call site in this module
        // (buildGovernancePcztNative, buildAndProveDelegationNative, buildVoteCommitmentNative,
        // deriveHotkeyRawAddressNative): this was the one JNI entry point that read the
        // caller-supplied secret as a bare Vec<u8>, which neither zeroizes on drop nor guards
        // against an incidental `{:?}` from printing the material.
        let stored_secret = SecretVec::new(java_bytes(env, &stored_secret, "storedSecret")?);
        let hotkey = if stored_secret.expose_secret().is_empty() {
            voting::hotkey::generate_random_voting_hotkey(network)
                .map_err(|e| anyhow!("generate_random_voting_hotkey: {}", e))?
        } else {
            voting::types::VotingHotkey::from_stored_secret(stored_secret.expose_secret(), network)
                .map_err(|e| anyhow!("VotingHotkey::from_stored_secret: {}", e))?
        };
        make_jni_voting_hotkey(env, hotkey)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    use orchard::{
        keys::FullViewingKey,
        note::{Note, NoteVersion, RandomSeed, Rho},
        value::NoteValue,
    };
    use rusqlite::{params, Connection};
    use zcash_client_backend::data_api::{chain::ChainState, AccountBirthday, WalletRead, WalletWrite};
    use zcash_client_sqlite::{util::SystemClock, wallet::init::init_wallet_db, WalletDb};
    use zcash_primitives::block::BlockHash;
    use zcash_protocol::consensus::{NetworkUpgrade, Parameters};
    use zip32::Scope;

    /// Real-data proof for `getWalletNotesNative`'s actual logic: builds a real, on-disk
    /// wallet DB with one account and one real, mined, unspent Ironwood note, then exercises
    /// exactly the sequence the JNI entrypoint above runs after decoding its JNI arguments
    /// ([`SqliteWalletDbOpener::open_for_read`], [`require_fully_scanned_to_snapshot`],
    /// `voting::selection::select_snapshot_note_infos`) and asserts a real, sane note comes
    /// back -- not a mock standing in for "does the function exist".
    #[test]
    fn get_wallet_notes_returns_a_real_unspent_ironwood_note() {
        // Regtest, not Testnet/Mainnet: `zcash_voting::selection::select_snapshot_note_infos`
        // (via `VotingShieldedProtocol::for_height`) only recognizes the Ironwood/V3 pool from
        // the NU6.3 activation height onward, and this pin's real Testnet/Mainnet consensus
        // params have no NU6.3 activation height yet (unreleased upgrade) -- Regtest is the
        // only network this pin lets a test force to a specific NU6.3 height, matching how
        // `zcash_voting`'s own `selection.rs` unit tests do the same thing.
        let network = voting::types::Network::Regtest;
        let db_path = unique_test_db_path();
        const EXPECTED_VALUE: u64 = 12_345;
        const COMMITMENT_TREE_POSITION: u64 = 7;
        // `zcash_voting`'s own Regtest NU6.3 activation height for this crate pin
        // (`types::REGTEST_NU6_3_ACTIVATION_HEIGHT`, not itself `pub`) -- snapshot_height must
        // be at or above it for the Ironwood pool to be recognized at all.
        const NU6_3_ACTIVATION_HEIGHT: u32 = 10;

        let sapling_height = network
            .activation_height(NetworkUpgrade::Sapling)
            .expect("regtest has a Sapling activation height");
        let mined_height = NU6_3_ACTIVATION_HEIGHT;
        let snapshot_height = u64::from(mined_height);

        let mut conn = Connection::open(&db_path).expect("open fresh wallet db file");
        let account_uuid = {
            let mut db = WalletDb::from_connection(
                &mut conn,
                network,
                SystemClock,
                rand::rngs::OsRng,
            );
            init_wallet_db(&mut db, Some(SecretVec::new(vec![7u8; 32])))
                .expect("init wallet schema");

            let birthday = AccountBirthday::from_parts(
                ChainState::empty(sapling_height - 1, BlockHash([0; 32])),
                None,
            );
            let (account_uuid, usk) = db
                .create_account(
                    "voter",
                    &SecretVec::new(vec![7u8; 32]),
                    &birthday,
                    None,
                )
                .expect("create test account");
            let orchard_fvk = usk
                .to_unified_full_viewing_key()
                .orchard()
                .expect("test account has an Orchard viewing key")
                .clone();

            let account_ref: i64 = conn
                .query_row(
                    "SELECT id FROM accounts WHERE uuid = ?1",
                    params![account_uuid.expose_uuid().as_bytes()],
                    |row| row.get(0),
                )
                .expect("look up internal account id");
            insert_real_ironwood_note(
                &conn,
                account_ref,
                &orchard_fvk,
                mined_height,
                EXPECTED_VALUE,
                COMMITMENT_TREE_POSITION,
            );
            mark_scanned_through(&conn, u32::from(sapling_height - 1), snapshot_height);

            account_uuid
        };
        drop(conn);

        let wallet_db = SqliteWalletDbOpener::new(
            db_path.to_str().expect("test db path is valid UTF-8").to_string(),
            network,
        )
        .open_for_read()
        .expect("reopen wallet db read-only");

        let fully_scanned_height = wallet_db
            .block_fully_scanned()
            .expect("read fully scanned height")
            .map(|metadata| metadata.block_height());
        require_fully_scanned_to_snapshot(
            fully_scanned_height,
            zcash_protocol::consensus::BlockHeight::from_u32(snapshot_height as u32),
        )
        .expect("wallet is scanned through the snapshot height");

        let notes = voting::selection::select_snapshot_note_infos(
            &wallet_db,
            &account_uuid.expose_uuid().to_string(),
            snapshot_height,
        )
        .expect("select a real, sane note list");

        assert_eq!(notes.len(), 1, "exactly one real note was inserted");
        let note = &notes[0];
        assert_eq!(note.value, EXPECTED_VALUE);
        assert_eq!(note.position, COMMITMENT_TREE_POSITION);
        assert_eq!(note.commitment.len(), PROTOCOL_FIELD_BYTES);
        assert_eq!(note.nullifier.len(), PROTOCOL_FIELD_BYTES);
        assert_eq!(note.scope, 0, "note was received at the external scope");
        assert!(!note.ufvk_str.is_empty());

        let _ = std::fs::remove_file(&db_path);
    }

    #[test]
    fn require_fully_scanned_to_snapshot_rejects_a_wallet_scanned_below_the_snapshot() {
        let err = require_fully_scanned_to_snapshot(
            Some(zcash_protocol::consensus::BlockHeight::from_u32(9)),
            zcash_protocol::consensus::BlockHeight::from_u32(10),
        )
        .unwrap_err();

        assert!(
            err.to_string()
                .contains("wallet DB fully scanned height 9 is below snapshot_height 10")
        );
    }

    #[test]
    fn require_fully_scanned_to_snapshot_rejects_a_wallet_with_no_scanned_height() {
        let err = require_fully_scanned_to_snapshot(
            None,
            zcash_protocol::consensus::BlockHeight::from_u32(10),
        )
        .unwrap_err();

        assert!(err.to_string().contains("wallet DB has no fully scanned height"));
    }

    #[test]
    fn require_fully_scanned_to_snapshot_accepts_a_wallet_scanned_through_the_snapshot() {
        require_fully_scanned_to_snapshot(
            Some(zcash_protocol::consensus::BlockHeight::from_u32(10)),
            zcash_protocol::consensus::BlockHeight::from_u32(10),
        )
        .expect("scanned exactly through the snapshot height must be accepted");
    }

    fn unique_test_db_path() -> std::path::PathBuf {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("current time is after UNIX_EPOCH")
            .as_nanos();
        std::env::temp_dir().join(format!(
            "zcash-android-voting-get-wallet-notes-test-{}-{nanos}.sqlite",
            std::process::id()
        ))
    }

    /// Directly inserts one real, mined, unspent Ironwood/V3 note row, mirroring
    /// `zcash_voting`'s own `selection.rs` test fixtures (same schema, same crate pin) --
    /// there is no higher-level "receive a note" API to drive here without a full synthetic
    /// chain-scan harness, so this matches the pinned crate's own approach to the same problem.
    fn insert_real_ironwood_note(
        conn: &Connection,
        account_ref: i64,
        orchard_fvk: &FullViewingKey,
        mined_height: u32,
        value_zatoshi: u64,
        commitment_tree_position: u64,
    ) {
        let note_tag: u8 = 1;
        let transaction_id = insert_transaction(conn, note_tag, mined_height);
        let note = test_orchard_note(orchard_fvk, note_tag, value_zatoshi);
        let nullifier = note.nullifier(orchard_fvk);

        conn.execute(
            "INSERT INTO ironwood_received_notes (
                transaction_id, action_index, account_id, diversifier, value, rho, rseed,
                nf, is_change, commitment_tree_position, recipient_key_scope, note_version
             )
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, ?9, 0, ?10)",
            params![
                transaction_id,
                i64::from(note_tag),
                account_ref,
                note.recipient().diversifier().as_array(),
                value_zatoshi,
                note.rho().to_bytes(),
                note.rseed().as_bytes(),
                nullifier.to_bytes(),
                commitment_tree_position,
                3, // NoteVersion::V3
            ],
        )
        .expect("insert real ironwood note fixture");
    }

    fn insert_transaction(conn: &Connection, txid_tag: u8, mined_height: u32) -> i64 {
        let txid = [txid_tag; PROTOCOL_FIELD_BYTES];
        conn.execute(
            "INSERT INTO transactions (txid, mined_height, min_observed_height)
             VALUES (?1, ?2, ?3)",
            params![txid, mined_height, mined_height],
        )
        .expect("insert transaction fixture");
        conn.last_insert_rowid()
    }

    fn mark_scanned_through(conn: &Connection, start_height: u32, scanned_height: u64) {
        let scanned_height = u32::try_from(scanned_height).expect("scanned height fits in u32");
        conn.execute("DELETE FROM scan_queue", [])
            .expect("clear scan queue fixture");
        conn.execute("DELETE FROM blocks", [])
            .expect("clear blocks fixture");
        conn.execute(
            "INSERT INTO scan_queue (block_range_start, block_range_end, priority)
             VALUES (?1, ?2, 10)",
            params![start_height, scanned_height + 1],
        )
        .expect("insert scan_queue fixture");
        for height in start_height..=scanned_height {
            conn.execute(
                "INSERT INTO blocks (
                    height, hash, time, sapling_tree, sapling_commitment_tree_size,
                    orchard_commitment_tree_size, sapling_output_count, orchard_action_count
                 )
                 VALUES (?1, ?2, ?3, ?4, 0, 0, 0, 0)",
                params![height, [height as u8; PROTOCOL_FIELD_BYTES], height, Vec::<u8>::new()],
            )
            .expect("insert blocks fixture");
        }
    }

    /// Generates a real, validly-encoded Orchard note for [orchard_fvk], receivable at the
    /// external scope -- the same trial-seed technique `zcash_voting`'s own test fixtures use,
    /// since [`RandomSeed::from_bytes`]/[`orchard::Note::from_parts`] can reject a seed.
    fn test_orchard_note(orchard_fvk: &FullViewingKey, note_tag: u8, value_zatoshi: u64) -> orchard::Note {
        let recipient = orchard_fvk.address_at(u64::from(note_tag), Scope::External);
        let mut rho_bytes = [0u8; PROTOCOL_FIELD_BYTES];
        rho_bytes[..8].copy_from_slice(&(u64::from(note_tag) + 1).to_le_bytes());
        let rho = Option::<Rho>::from(Rho::from_bytes(&rho_bytes))
            .expect("small integers are valid pallas base field elements");

        for seed_nonce in 1..10_000u64 {
            let mut seed = [0u8; PROTOCOL_FIELD_BYTES];
            seed[..8].copy_from_slice(&(seed_nonce + u64::from(note_tag) * 10_000).to_le_bytes());
            if let Some(rseed) = Option::<RandomSeed>::from(RandomSeed::from_bytes(seed, &rho)) {
                if let Some(note) = Option::<Note>::from(Note::from_parts(
                    recipient,
                    NoteValue::from_raw(value_zatoshi),
                    rho,
                    rseed,
                    NoteVersion::V3,
                )) {
                    return note;
                }
            }
        }

        panic!("failed to generate valid shielded note fixture");
    }
}
