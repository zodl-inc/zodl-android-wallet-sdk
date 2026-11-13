//! Why a proposal could not be created, sorted into the categories the JNI layer reports as
//! typed exceptions.
//!
//! Every proposing entry point classifies its failure here: the user's Send Max and gift card
//! sweep, and the ordinary transfer and shielding proposals (including the transfer the review
//! screen re-proposes for a Send Max). The same failure therefore reaches Kotlin as the same
//! typed exception whichever of them reports it.

use std::fmt;

use zcash_client_backend::{
    data_api::{error::Error as DataApiError, wallet::input_selection::GreedyInputSelectorError},
    fees::ChangeError,
    proposal::ProposalError,
    zip321::{PaymentError, Zip321Error},
};
use zcash_protocol::value::Zatoshis;

use crate::size_bound::SizeBoundViolation;

#[cfg(test)]
mod tests;

/// Why a proposal could not be created.
///
/// Every variant other than [`ProposalRejection::Other`] is a category the JNI layer reports as
/// a typed exception. None of them carries the recipient or the memo, and neither does the text
/// of [`ProposalRejection::Other`], which is `zcash_client_backend`'s own description of the
/// failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum ProposalRejection {
    /// The spendable value does not cover the payment and its fee.
    InsufficientFunds {
        available: Zatoshis,
        required: Zatoshis,
    },
    /// The transaction would exceed the maximum size of a block, because it spends too many
    /// notes.
    TransactionTooLarge,
    /// A transaction of the proposal exceeds a count limit of the
    /// [`crate::size_bound::TransactionSizeBound`] the caller asked for, below consensus.
    ExceedsSizeBound {
        /// The index of the proposal step (transaction) that exceeds the bound.
        step_index: usize,
        /// The limit it exceeds.
        violation: SizeBoundViolation,
    },
    /// A memo was given for a recipient that cannot receive one.
    TransparentMemo,
    /// The recipient has no receiver this wallet is able to pay.
    UnsupportedAddress,
    /// The wallet has not scanned enough of the chain to propose a transaction.
    SyncRequired,
    /// Any other failure.
    Other(String),
}

impl ProposalRejection {
    /// The stable category the Kotlin layer's `ProposalRejectedException.reason` carries for
    /// this failure, or `None` for a failure that is not reported as a rejection.
    ///
    /// These strings are the JNI contract with the `ProposalRejectedException` constants.
    pub(crate) fn rejection_reason(&self) -> Option<&'static str> {
        match self {
            ProposalRejection::TransactionTooLarge => Some("transaction_too_large"),
            ProposalRejection::TransparentMemo => Some("transparent_memo"),
            ProposalRejection::UnsupportedAddress => Some("unsupported_address"),
            ProposalRejection::SyncRequired => Some("sync_required"),
            ProposalRejection::InsufficientFunds { .. }
            | ProposalRejection::ExceedsSizeBound { .. }
            | ProposalRejection::Other(_) => None,
        }
    }
}

impl fmt::Display for ProposalRejection {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            // Kotlin's `indicatesInsufficientFunds` still matches the leading "Insufficient
            // balance" when the typed exception cannot be thrown.
            ProposalRejection::InsufficientFunds {
                available,
                required,
            } => write!(
                f,
                "Insufficient balance (available {}, required {})",
                u64::from(*available),
                u64::from(*required)
            ),
            ProposalRejection::TransactionTooLarge => {
                write!(
                    f,
                    "The transaction would spend too many notes to fit in a block"
                )
            }
            ProposalRejection::ExceedsSizeBound {
                step_index,
                violation,
            } => {
                let (count, limit) = violation.count_and_limit();
                write!(
                    f,
                    "Transaction {} exceeds the size bound: {} {} > {}",
                    step_index,
                    violation.dimension(),
                    count,
                    limit
                )
            }
            ProposalRejection::TransparentMemo => {
                write!(f, "The recipient cannot receive a memo")
            }
            ProposalRejection::UnsupportedAddress => {
                write!(f, "The recipient has no receiver this wallet can pay")
            }
            ProposalRejection::SyncRequired => {
                write!(f, "The wallet must sync before proposing a transaction")
            }
            ProposalRejection::Other(description) => {
                write!(f, "Error creating transaction proposal: {}", description)
            }
        }
    }
}

impl std::error::Error for ProposalRejection {}

/// Sorts a `zcash_client_backend` proposal failure into the [`ProposalRejection`] it describes.
pub(crate) fn classify<DE, TE, FE, CE, N>(
    error: DataApiError<DE, TE, GreedyInputSelectorError, FE, CE, N>,
) -> ProposalRejection
where
    DE: fmt::Display,
    TE: fmt::Display,
    FE: fmt::Display,
    CE: fmt::Display,
    N: fmt::Display,
{
    match error {
        DataApiError::InsufficientFunds {
            available,
            required,
        }
        | DataApiError::Change(ChangeError::InsufficientFunds {
            available,
            required,
        }) => ProposalRejection::InsufficientFunds {
            available,
            required,
        },
        DataApiError::Proposal(error) => classify_proposal_error(error),
        DataApiError::Payment(error) => classify_payment_error(error),
        DataApiError::NoteSelection(
            GreedyInputSelectorError::UnsupportedAddress(_)
            | GreedyInputSelectorError::UnsupportedTexAddress,
        )
        | DataApiError::NoSupportedReceivers(_) => ProposalRejection::UnsupportedAddress,
        DataApiError::ScanRequired => ProposalRejection::SyncRequired,
        other => ProposalRejection::Other(other.to_string()),
    }
}

/// Sorts a failure of the proposal itself into the [`ProposalRejection`] it describes.
pub(crate) fn classify_proposal_error(error: ProposalError) -> ProposalRejection {
    match error {
        ProposalError::TransactionTooLarge { .. } => ProposalRejection::TransactionTooLarge,
        ProposalError::Zip321(error) => classify_zip321_error(error),
        other => ProposalRejection::Other(other.to_string()),
    }
}

/// Sorts a failure to construct a single payment (`Payment::new`) into the [`ProposalRejection`]
/// it describes.
pub(crate) fn classify_payment_error(error: PaymentError) -> ProposalRejection {
    match error {
        PaymentError::TransparentMemo => ProposalRejection::TransparentMemo,
        other => ProposalRejection::Other(other.to_string()),
    }
}

/// Sorts a failure to construct or parse a ZIP 321 transaction request into the
/// [`ProposalRejection`] it describes.
pub(crate) fn classify_zip321_error(error: Zip321Error) -> ProposalRejection {
    match error {
        Zip321Error::TransparentMemo(_) => ProposalRejection::TransparentMemo,
        other => ProposalRejection::Other(other.to_string()),
    }
}
