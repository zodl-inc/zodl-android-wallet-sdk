//! The ZIP 317 fee constants, exposed so that clients do not restate them.

use jni::{JNIEnv, objects::JClass, sys::jlong};
use zcash_primitives::transaction::fees::zip317;

use crate::utils::{catch_unwind, exception::unwrap_exc_or};

#[cfg(test)]
mod tests;

/// The ZIP 317 marginal fee, in zatoshis: the fee per logical action.
pub(crate) fn marginal_fee_zatoshis() -> u64 {
    zip317::MARGINAL_FEE.into_u64()
}

/// Returns `zcash_primitives::transaction::fees::zip317::MARGINAL_FEE`, in zatoshis.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustZip317_getMarginalFee<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |_| {
        // The marginal fee is far below `i64::MAX`.
        Ok(jlong::try_from(marginal_fee_zatoshis())?)
    });
    unwrap_exc_or(&mut env, res, 0)
}
