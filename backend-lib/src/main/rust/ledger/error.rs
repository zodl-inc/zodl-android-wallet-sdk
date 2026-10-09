//! The structured error every Ledger JNI function reports, and how it crosses the boundary.
//!
//! The engine's errors (`SessionError`, `PairingError`, `FramingError`, `InvalidDeviceIdentity`)
//! are folded into a small, stable set of kinds the Kotlin layer switches on, and thrown as a
//! `JniLedgerException` carrying the kind, the device's status word, whether the refusal is
//! transient, whether restarting the whole operation could succeed, and a human-readable reason.
//!
//! # What a reason may carry
//!
//! A reason is built only from the engine's `Display` impls, which are written to be loggable
//! (lengths, indices, sections, status words, app versions), or from fixed text. It never carries
//! APDU or reply bytes, a PCZT, a viewing key, an address, a device identity or a signature. Two
//! engine errors print one of those and are replaced by fixed text here:
//! `SessionError::DeviceMismatch` (both device identities) and the address and viewing-key decode
//! failures of `PairingError`, whose messages come from `zcash_address` and are not reviewed for
//! what they echo. A panic's payload is dropped for the same reason.

use std::thread;

use jni::{
    JNIEnv,
    objects::{JObject, JThrowable, JValue},
};
use pczt_ledger::{
    apdu::StatusWord,
    framing::FramingError,
    limits::LimitViolation,
    pairing::{InvalidDeviceIdentity, PairingError},
    session::{LedgerViolation, SessionError},
};
use tracing::error;

/// The Kotlin class every failure is thrown as. Its constructor signature is
/// [`JNI_LEDGER_EXCEPTION_CTOR`]; the two change in lockstep.
const JNI_LEDGER_EXCEPTION: &str =
    "cash/z/ecc/android/sdk/internal/model/ledger/JniLedgerException";

/// `JniLedgerException(kind: Int, statusWord: Int, isTransient: Boolean, isRestartable: Boolean,
/// reason: String?)`.
const JNI_LEDGER_EXCEPTION_CTOR: &str = "(IIZZLjava/lang/String;)V";

/// The status word slot's value when the failure carries none.
const NO_STATUS_WORD: i32 = -1;

/// The failure kinds. The discriminants are the wire values `JniLedgerException.kind` carries and
/// are stable: the Kotlin layer maps them one to one, so a value is never reused. They match the
/// swift SDK's `LedgerErrorKind`, so the two SDKs classify every failure the same way.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub(crate) enum Kind {
    /// A bug or an environment failure on this side of the transport; not the device's answer.
    Internal = 0,
    /// The user declined on the device.
    UserRejected = 1,
    /// The device is not running the Zcash app, or runs a version that does not know the
    /// instruction.
    WrongApp = 2,
    /// The Zcash app on the device predates PCZT signing.
    AppTooOld = 3,
    /// The connected device is not the one the account was paired with, or a key or address
    /// the device answered with is not the one the wallet derives for the account.
    DeviceMismatch = 4,
    /// The connected device runs a different app version from the one the session was built for.
    CapsMismatch = 5,
    /// The app's per-run Orchard derivation budget is spent; the user has to close and reopen it.
    DerivationBudgetExhausted = 6,
    /// The device refused the command with a status word.
    DeviceRefused = 7,
    /// The transaction cannot be signed by the device: a validation, shaping or device-limit rule
    /// refuses it before anything is sent.
    TransactionNotSignable = 8,
    /// A reply or a frame did not have the shape the protocol promises.
    MalformedReply = 9,
    /// A caller-supplied value was refused before any device I/O: a device identity string, an
    /// account or address index, a network, a PCZT that does not parse, an account that is not
    /// in the wallet, or an account that is not tagged as Ledger-imported.
    InvalidInput = 10,
    /// `0x6901`: the device SDK refused the frame before the app saw it. Resend the same command.
    CmdNotAccepted = 11,
}

/// A failure, as the Kotlin layer receives it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct LedgerError {
    pub(crate) kind: Kind,
    pub(crate) status_word: Option<u16>,
    pub(crate) transient: bool,
    pub(crate) restartable: bool,
    pub(crate) reason: Option<String>,
}

impl LedgerError {
    fn new(kind: Kind) -> Self {
        LedgerError {
            kind,
            status_word: None,
            transient: false,
            restartable: false,
            reason: None,
        }
    }

    fn with_status(mut self, status: StatusWord) -> Self {
        self.status_word = Some(status.to_u16());
        self.transient = status.is_transient();
        self
    }

    fn with_reason(mut self, reason: impl Into<String>) -> Self {
        self.reason = Some(reason.into());
        self
    }

    fn restartable(mut self, restartable: bool) -> Self {
        self.restartable = restartable;
        self
    }

    /// An internal failure with fixed text.
    pub(crate) fn internal(reason: &'static str) -> Self {
        LedgerError::new(Kind::Internal).with_reason(reason)
    }

    /// A transaction the device cannot sign, for a reason this layer found before the engine did.
    pub(crate) fn not_signable(reason: impl Into<String>) -> Self {
        LedgerError::new(Kind::TransactionNotSignable).with_reason(reason)
    }

    /// A caller-supplied value refused before any device I/O, with fixed text.
    pub(crate) fn invalid_input(reason: impl Into<String>) -> Self {
        LedgerError::new(Kind::InvalidInput).with_reason(reason)
    }

    /// A reply that does not have the shape the protocol promises, with fixed text.
    pub(crate) fn malformed_reply(reason: &'static str) -> Self {
        LedgerError::new(Kind::MalformedReply).with_reason(reason)
    }

    /// A device that is not the one expected, with fixed text.
    pub(crate) fn device_mismatch(reason: &'static str) -> Self {
        LedgerError::new(Kind::DeviceMismatch).with_reason(reason)
    }

    /// An app too old to sign PCZTs.
    pub(crate) fn app_too_old(version: (u8, u8, u8)) -> Self {
        LedgerError::new(Kind::AppTooOld).with_reason(format!(
            "the Zcash app on the device is v{}.{}.{}, which predates PCZT signing",
            version.0, version.1, version.2
        ))
    }

    /// Maps a signing-session failure. `restartable` is the session's own
    /// `LedgerSignSession::is_restartable` after the failure; construction failures pass `false`.
    pub(crate) fn from_session(error: &SessionError, restartable: bool) -> Self {
        let mapped = match error {
            SessionError::Parse(_) => {
                LedgerError::invalid_input("the transaction bytes are not a PCZT")
            }
            // A plan refused only because the connected app predates the instructions it needs,
            // or would hash a displayed memo wrongly (fixed by updating the app to v3.9.4), is
            // the app's age, not the transaction's shape. Every violation has to be an app-age
            // one: if any other rule is broken too, updating the app would not make the
            // transaction signable, so a mixed set stays `TransactionNotSignable`.
            SessionError::Validation(violations)
                if !violations.is_empty()
                    && violations.iter().all(|violation| {
                        matches!(
                            violation,
                            LedgerViolation::Limit(
                                LimitViolation::PcztUnsupported { .. }
                                    | LimitViolation::IronwoodUnsupported { .. }
                                    | LimitViolation::HashedMemoUnsupported { .. }
                            )
                        )
                    }) =>
            {
                LedgerError::new(Kind::AppTooOld).with_reason(error.to_string())
            }
            SessionError::Validation(_)
            | SessionError::Shape(_)
            | SessionError::AccountMismatch { .. }
            | SessionError::NonCompliantPath { .. }
            | SessionError::TransparentKeyMismatch { .. }
            | SessionError::TransparentAccountKeyRequired { .. } => {
                LedgerError::not_signable(error.to_string())
            }
            SessionError::UnshapedAction { .. } => {
                LedgerError::internal("shaping left an action without a derivation path")
            }
            // `EncodeError` renders a value sum, so the reason is fixed text.
            SessionError::Encode(_) => {
                LedgerError::internal("a signing record could not be encoded onto the wire")
            }
            SessionError::UserRejected { .. } => LedgerError::new(Kind::UserRejected),
            SessionError::WrongApp { status } => LedgerError::new(Kind::WrongApp)
                .with_status(*status)
                .with_reason(error.to_string()),
            SessionError::CapsMismatch { .. } => {
                LedgerError::new(Kind::CapsMismatch).with_reason(error.to_string())
            }
            // The engine's message prints both identities; see the module docs.
            SessionError::DeviceMismatch { .. } => LedgerError::new(Kind::DeviceMismatch)
                .with_reason(
                    "the connected device is not the one this account was paired with; no \
                     transaction data was sent to it",
                ),
            SessionError::Device { status, .. } => LedgerError::new(Kind::DeviceRefused)
                .with_status(*status)
                .with_reason(error.to_string()),
            SessionError::Decode(_) | SessionError::Apply(_) => {
                LedgerError::new(Kind::MalformedReply).with_reason(error.to_string())
            }
            SessionError::OutOfOrder => {
                LedgerError::internal("the signing session was driven out of order")
            }
            _ => LedgerError::internal("the signing session failed in an unrecognized way"),
        };
        mapped.restartable(restartable)
    }

    /// Maps a pairing-command failure.
    pub(crate) fn from_pairing(error: &PairingError) -> Self {
        match error {
            PairingError::Decode(_) => {
                LedgerError::new(Kind::MalformedReply).with_reason(error.to_string())
            }
            PairingError::Encode(_) => {
                LedgerError::internal("the pairing command could not be encoded")
            }
            // The user may approve when asked again.
            PairingError::UserRejected => LedgerError::new(Kind::UserRejected).restartable(true),
            PairingError::WrongApp { status } => LedgerError::new(Kind::WrongApp)
                .with_status(*status)
                .with_reason(error.to_string()),
            PairingError::Device { status } => LedgerError::new(Kind::DeviceRefused)
                .with_status(*status)
                .with_reason(error.to_string())
                .restartable(status.is_transient()),
            PairingError::PathOutOfRange { .. } => LedgerError::invalid_input(error.to_string()),
            PairingError::DerivationBudgetExhausted => {
                LedgerError::new(Kind::DerivationBudgetExhausted)
                    .with_status(StatusWord::NotEnoughMemorySpace)
                    .with_reason(error.to_string())
            }
            PairingError::CmdNotAccepted => LedgerError::new(Kind::CmdNotAccepted)
                .with_status(StatusWord::CmdNotAccepted)
                .restartable(true),
            PairingError::OutOfOrder => {
                LedgerError::internal("the viewing-key export was driven out of order")
            }
            // `zcash_address`'s parse messages are not reviewed for what they echo, so the
            // replies that fail to decode as a key or an address get fixed text.
            PairingError::UnifiedAddress(_) | PairingError::InvalidOrchardAddress => {
                LedgerError::new(Kind::MalformedReply)
                    .with_reason("the device's reply is not a valid address")
            }
            PairingError::AddressNetworkMismatch { .. } => LedgerError::new(Kind::MalformedReply)
                .with_reason("the device answered with an address for another network"),
            PairingError::UnifiedViewingKey(_) | PairingError::InvalidOrchardViewingKey => {
                LedgerError::new(Kind::MalformedReply)
                    .with_reason("the device's reply is not a valid viewing key")
            }
            PairingError::ViewingKeyNetworkMismatch { .. } => {
                LedgerError::new(Kind::MalformedReply)
                    .with_reason("the device answered with a viewing key for another network")
            }
            _ => LedgerError::internal("the pairing command failed in an unrecognized way"),
        }
    }

    /// Maps a framing failure.
    pub(crate) fn from_framing(error: &FramingError) -> Self {
        match error {
            FramingError::ApduTooLong { .. } => LedgerError::invalid_input(error.to_string()),
            _ => LedgerError::new(Kind::MalformedReply).with_reason(error.to_string()),
        }
    }

    /// Maps a stored identity string that does not parse. The message names the rule, never the
    /// input.
    pub(crate) fn from_identity(error: &InvalidDeviceIdentity) -> Self {
        LedgerError::invalid_input(format!("invalid Ledger device identity: {error}"))
    }
}

impl From<jni::errors::Error> for LedgerError {
    fn from(_: jni::errors::Error) -> Self {
        LedgerError::internal("a JNI call failed")
    }
}

/// Returns the value, or throws the failure as a `JniLedgerException` and returns `error_val`.
///
/// The thrown exception surfaces when the native method returns. A caught panic is thrown as an
/// internal failure with fixed text: the payload is not reviewed for what it carries, and the
/// panic hook installed at library load has already logged it.
pub(crate) fn unwrap_or<T>(
    env: &mut JNIEnv,
    result: thread::Result<Result<T, LedgerError>>,
    error_val: T,
) -> T {
    match result {
        Ok(Ok(value)) => value,
        Ok(Err(e)) => {
            throw(env, &e);
            error_val
        }
        Err(_) => {
            throw(env, &LedgerError::internal("the Ledger engine panicked"));
            error_val
        }
    }
}

/// Queues `error` to be thrown when control returns to the JVM, unless a Java exception is already
/// pending, which is more specific and is left to surface.
fn throw(env: &mut JNIEnv, error: &LedgerError) {
    if env.exception_check().unwrap_or(false) {
        return;
    }
    if let Err(e) = throw_typed(env, error) {
        // Constructing the typed exception failed. If that left a Java exception pending (a
        // missing class is `NoClassDefFoundError`), it surfaces on return; otherwise fall back to
        // a plain RuntimeException so the call still fails.
        error!("Unable to throw JniLedgerException: {e}");
        if !env.exception_check().unwrap_or(false) {
            let _ = env.throw_new("java/lang/RuntimeException", "Ledger operation failed");
        }
    }
}

fn throw_typed(env: &mut JNIEnv, error: &LedgerError) -> jni::errors::Result<()> {
    let reason = match &error.reason {
        Some(reason) => JObject::from(env.new_string(reason)?),
        None => JObject::null(),
    };
    let exception = env.new_object(
        JNI_LEDGER_EXCEPTION,
        JNI_LEDGER_EXCEPTION_CTOR,
        &[
            JValue::Int(error.kind as i32),
            JValue::Int(error.status_word.map_or(NO_STATUS_WORD, i32::from)),
            JValue::Bool(u8::from(error.transient)),
            JValue::Bool(u8::from(error.restartable)),
            JValue::Object(&reason),
        ],
    )?;
    env.throw(JThrowable::from(exception))
}
