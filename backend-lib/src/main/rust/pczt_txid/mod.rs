//! The transaction id of a PCZT, from the moment it is created: the key under which the SDK marks
//! the transactions it must never report as rejected, before they are signed, proven or extracted.

use jni::{
    JNIEnv,
    objects::{JByteArray, JClass},
    sys::jbyteArray,
};
use zcash_pool_migration::pczt_txid::{TxIdError, stored_pczt_txid};

use crate::utils::{self, catch_unwind, exception::unwrap_exc_or};

#[cfg(test)]
mod tests;

/// The id of the transaction the PCZT `bytes` describes, in its internal byte order: the same
/// for the unsigned, signed and proven PCZT, and for the transaction extracted from it
/// (`zcash_pool_migration::pczt_txid::stored_pczt_txid`).
pub(crate) fn pczt_transaction_id(bytes: &[u8]) -> Result<[u8; 32], TxIdError> {
    stored_pczt_txid(bytes).map(|txid| *txid.as_ref())
}

/// Returns [`pczt_transaction_id`] of the given PCZT bytes, or throws when they are not a PCZT
/// whose effects assemble into transaction data. Opens no wallet database.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustPcztTransactionId_getPcztTransactionId<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    pczt: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let bytes = env.convert_byte_array(pczt)?;
        let txid = pczt_transaction_id(&bytes)
            .map_err(|e| anyhow::anyhow!("Cannot derive the PCZT's transaction id: {e}"))?;
        Ok(utils::rust_bytes_to_java(env, &txid)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}
