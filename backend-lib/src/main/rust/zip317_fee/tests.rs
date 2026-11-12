//! Tests for the ZIP 317 fee constants.

use zcash_client_backend::fees::StandardFeeRule;
use zcash_primitives::transaction::fees::{FeeRule, transparent::InputSize};
use zcash_protocol::consensus::{BlockHeight, MAIN_NETWORK};

use super::*;

/// The fee [`StandardFeeRule::Zip317`], the rule every proposal in this backend is built with,
/// requires for a transaction of `orchard_actions` Orchard actions and nothing else.
fn standard_fee_for_orchard_actions(orchard_actions: usize) -> u64 {
    StandardFeeRule::Zip317
        .fee_required(
            &MAIN_NETWORK,
            BlockHeight::from_u32(3_000_000),
            std::iter::empty::<InputSize>(),
            std::iter::empty::<usize>(),
            0,
            0,
            orchard_actions,
            0,
        )
        .expect("the fee is computable")
        .into_u64()
}

#[test]
fn the_marginal_fee_is_the_zip_317_value() {
    assert_eq!(marginal_fee_zatoshis(), zip317::MARGINAL_FEE.into_u64());
}

/// The value exposed to Kotlin is what the proposals' fee rule actually charges per logical
/// action beyond the grace actions, not merely a constant that happens to share its name.
#[test]
fn the_marginal_fee_is_what_the_standard_fee_rule_charges_per_action() {
    for actions in 2..6 {
        assert_eq!(
            standard_fee_for_orchard_actions(actions + 1)
                - standard_fee_for_orchard_actions(actions),
            marginal_fee_zatoshis(),
            "between {actions} and {} actions",
            actions + 1
        );
    }
}

#[test]
fn the_marginal_fee_fits_the_jni_return_type() {
    assert_eq!(
        jlong::try_from(marginal_fee_zatoshis()).unwrap(),
        jlong::try_from(zip317::MARGINAL_FEE.into_u64()).unwrap()
    );
}
