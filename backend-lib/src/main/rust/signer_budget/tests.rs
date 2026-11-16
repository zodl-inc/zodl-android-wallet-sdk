//! Tests for the external signer budget.

use super::*;

#[test]
fn the_qr_round_budget_is_the_engines_keystone_budget() {
    assert_eq!(
        qr_signing_round_max_actions(),
        SigningRoundBudget::KEYSTONE.max_actions()
    );
    assert!(qr_signing_round_max_actions() > 0);
}

#[test]
fn the_qr_round_budget_fits_the_jni_return_type() {
    assert!(jint::try_from(qr_signing_round_max_actions()).is_ok());
}
