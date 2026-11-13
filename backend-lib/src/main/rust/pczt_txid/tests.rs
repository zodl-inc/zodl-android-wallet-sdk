//! Tests for the PCZT transaction id.

use super::*;

#[test]
fn bytes_that_are_not_a_pczt_have_no_transaction_id() {
    assert_eq!(pczt_transaction_id(&[]), Err(TxIdError::Parse));
    assert_eq!(pczt_transaction_id(&[0u8; 32]), Err(TxIdError::Parse));
}

#[test]
fn the_transaction_id_is_the_migration_engines() {
    let garbage = [0x50u8, 0x43, 0x5a, 0x54, 1, 0, 0, 0];
    assert_eq!(
        pczt_transaction_id(&garbage),
        stored_pczt_txid(&garbage).map(|txid| *txid.as_ref())
    );
}
