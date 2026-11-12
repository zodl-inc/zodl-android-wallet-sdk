//! Tests for [`super::classify`] and its companions, of constructed `zcash_client_backend`
//! errors. The same classification against a synthetic wallet, for both a send-max and the
//! ordinary transfer that re-proposes it, is tested in `send_max::tests`.

use std::convert::Infallible;

use transparent::address::TransparentAddress;
use zcash_address::ZcashAddress;
use zcash_client_backend::{
    data_api::{error::Error as DataApiError, wallet::input_selection::GreedyInputSelectorError},
    fees::ChangeError,
    proposal::ProposalError,
    zip321::{Payment, PaymentError, TransactionRequest, Zip321Error},
};
use zcash_keys::address::Address;
use zcash_protocol::{consensus::MAIN_NETWORK, memo::MemoBytes, value::Zatoshis};

use super::*;
use crate::send_max::tests::{Received, external_recipient, funded_wallet};

// With the `orchard`
// feature enabled every unified address has a payable receiver, so an unsupported recipient
// cannot be produced from a synthetic wallet.

/// A `zcash_client_backend` proposal failure with display-only type parameters.
type BackendError =
    DataApiError<String, Infallible, GreedyInputSelectorError, String, String, String>;

#[test]
fn an_oversized_transaction_is_classified_as_too_large() {
    let too_large = || ProposalError::TransactionTooLarge {
        estimated_size: 2_000_001,
        limit: 2_000_000,
        sapling_input_count: 0,
        orchard_input_count: 3_000,
        ironwood_input_count: 0,
    };
    let error: BackendError = DataApiError::Proposal(too_large());

    assert_eq!(classify(error), ProposalRejection::TransactionTooLarge);
    assert_eq!(
        classify_proposal_error(too_large()),
        ProposalRejection::TransactionTooLarge
    );
}

#[test]
fn an_unpayable_recipient_is_classified_as_unsupported() {
    let st = funded_wallet(Received::Orchard);
    let Address::Unified(ua) =
        Address::try_from_zcash_address(st.network(), external_recipient(&st))
            .expect("decodes the test address")
    else {
        unreachable!("the test recipient is a unified address");
    };

    let errors: [BackendError; 3] = [
        DataApiError::NoteSelection(GreedyInputSelectorError::UnsupportedAddress(ua.clone())),
        DataApiError::NoteSelection(GreedyInputSelectorError::UnsupportedTexAddress),
        DataApiError::NoSupportedReceivers(ua),
    ];

    for error in errors {
        assert_eq!(classify(error), ProposalRejection::UnsupportedAddress);
    }
}

#[test]
fn every_form_of_a_transparent_memo_is_classified_alike() {
    let errors: [BackendError; 2] = [
        DataApiError::Payment(PaymentError::TransparentMemo),
        DataApiError::Proposal(ProposalError::Zip321(Zip321Error::TransparentMemo(0))),
    ];

    for error in errors {
        assert_eq!(classify(error), ProposalRejection::TransparentMemo);
    }
}

#[test]
fn every_form_of_insufficient_funds_is_classified_alike() {
    let available = Zatoshis::const_from_u64(1);
    let required = Zatoshis::const_from_u64(2);
    let errors: [BackendError; 2] = [
        DataApiError::InsufficientFunds {
            available,
            required,
        },
        DataApiError::Change(ChangeError::InsufficientFunds {
            available,
            required,
        }),
    ];

    for error in errors {
        assert_eq!(
            classify(error),
            ProposalRejection::InsufficientFunds {
                available,
                required
            }
        );
    }
}

#[test]
fn any_other_failure_keeps_the_backend_description() {
    let error: BackendError = DataApiError::DataSource("the database is locked".to_owned());

    let classified = classify(error);

    assert!(matches!(classified, ProposalRejection::Other(_)));
    assert!(
        classified
            .to_string()
            .starts_with("Error creating transaction proposal: "),
        "{classified}"
    );
    assert!(classified.to_string().contains("the database is locked"));
}

/// The categories are the JNI contract with the Kotlin `ProposalRejectedException` constants.
#[test]
fn rejections_cross_the_jni_boundary_as_their_fixed_categories() {
    let available = Zatoshis::ZERO;
    let cases = [
        (
            ProposalRejection::TransactionTooLarge,
            Some("transaction_too_large"),
        ),
        (ProposalRejection::TransparentMemo, Some("transparent_memo")),
        (
            ProposalRejection::UnsupportedAddress,
            Some("unsupported_address"),
        ),
        (ProposalRejection::SyncRequired, Some("sync_required")),
        (
            ProposalRejection::InsufficientFunds {
                available,
                required: available,
            },
            None,
        ),
        (ProposalRejection::Other("anything".to_owned()), None),
        // Thrown as its own class, carrying the exceeded limit (see `size_bound`).
        (
            ProposalRejection::ExceedsSizeBound {
                step_index: 0,
                violation: crate::size_bound::SizeBoundViolation::ShieldedActions {
                    count: 2,
                    limit: 1,
                },
            },
            None,
        ),
    ];

    for (error, reason) in cases {
        assert_eq!(error.rejection_reason(), reason, "{error:?}");
    }
}

/// A transparent P2PKH address that belongs to nobody.
fn transparent_recipient() -> ZcashAddress {
    Address::Transparent(TransparentAddress::PublicKeyHash([7; 20])).to_zcash_address(&MAIN_NETWORK)
}

/// The JNI `proposeTransfer` builds its single payment before proposing it, so a memo for a
/// recipient that cannot receive one fails there, and must be the same category a send-max
/// reports for it.
#[test]
fn a_payment_with_a_memo_to_a_transparent_recipient_is_classified_as_a_transparent_memo() {
    let memo = MemoBytes::from_bytes(b"gift").unwrap();
    let error = Payment::new(
        transparent_recipient(),
        Some(Zatoshis::const_from_u64(1)),
        Some(memo),
        None,
        None,
        vec![],
    )
    .expect_err("a transparent recipient cannot receive a memo");

    assert_eq!(
        classify_payment_error(error),
        ProposalRejection::TransparentMemo
    );
}

/// The JNI `proposeTransferFromUri` parses the request before proposing it, so a URI asking for
/// a memo to a recipient that cannot receive one fails there.
#[test]
fn a_uri_with_a_memo_to_a_transparent_recipient_is_classified_as_a_transparent_memo() {
    let uri = format!(
        "zcash:{}?amount=1&memo=Z2lmdA",
        transparent_recipient().encode()
    );

    let error = TransactionRequest::from_uri(&uri).expect_err("the memo is refused");

    assert_eq!(
        classify_zip321_error(error),
        ProposalRejection::TransparentMemo
    );
}

#[test]
fn other_request_failures_keep_their_description() {
    assert!(matches!(
        classify_payment_error(PaymentError::ZeroValuedTransparentOutput),
        ProposalRejection::Other(_)
    ));
    assert!(matches!(
        classify_zip321_error(Zip321Error::TooManyPayments(10_000)),
        ProposalRejection::Other(_)
    ));
}
