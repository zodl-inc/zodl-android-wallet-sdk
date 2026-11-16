//! The per-round action budget of an external signer that signs over animated QR codes,
//! exposed so that clients do not restate it.

use jni::{JNIEnv, objects::JClass, sys::jint};
use zcash_pool_migration::signing_rounds::SigningRoundBudget;

use crate::utils::{catch_unwind, exception::unwrap_exc_or};

#[cfg(test)]
mod tests;

/// The most Orchard-family actions one QR signing round may carry, across every transaction in
/// that round (`zcash_pool_migration::signing_rounds::SigningRoundBudget::KEYSTONE`).
pub(crate) fn qr_signing_round_max_actions() -> u32 {
    SigningRoundBudget::KEYSTONE.max_actions()
}

/// Returns [`qr_signing_round_max_actions`]. Pure constant: no wallet database is opened and no
/// migration state is needed.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustExternalSignerBudgets_getQrSigningRoundMaxActions<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |_| {
        Ok(jint::try_from(qr_signing_round_max_actions())?)
    });
    unwrap_exc_or(&mut env, res, 0)
}
