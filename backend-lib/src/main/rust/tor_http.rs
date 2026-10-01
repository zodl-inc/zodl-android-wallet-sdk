//! Deadline enforcement and error classification for HTTP-over-Tor JNI calls.
//!
//! The Kotlin side of `TorClient.httpGet` / `TorClient.httpPost` holds a mutex and blocks a
//! dispatcher thread for the whole native call, and cancelling a Kotlin coroutine cannot
//! interrupt a blocked JNI call. The only way to honor a caller-supplied timeout end to end is
//! therefore to enforce it inside the native call, which is what [`with_deadline`] does. The
//! deadline covers the whole call (including any `retry_limit` retries), so the caller's budget is
//! never exceeded.
//!
//! Failures are classified so the JNI layer can raise typed Kotlin exceptions rather than a
//! generic `RuntimeException` (see [`TorHttpError`]).

use std::future::Future;
use std::time::Duration;

use anyhow::anyhow;
use tor_rtcompat::{SleepProvider, SleepProviderExt};
use zcash_client_backend::tor::{Error, http::HttpError};

/// `TorHttpTimeoutException`: the caller-supplied deadline elapsed.
pub(crate) const TIMEOUT_EXCEPTION_CLASS: &str =
    "cash/z/ecc/android/sdk/internal/model/TorHttpTimeoutException";

/// `TorHttpTransportException`: the request failed at the network/transport level and is
/// worth retrying.
pub(crate) const TRANSPORT_EXCEPTION_CLASS: &str =
    "cash/z/ecc/android/sdk/internal/model/TorHttpTransportException";

/// How an HTTP-over-Tor call failed.
#[derive(Debug)]
pub(crate) enum TorHttpError {
    /// The caller-supplied deadline elapsed before the request completed.
    Timeout(String),
    /// A network/transport failure (Tor circuit or stream error, connection or TLS failure,
    /// a per-phase client timeout, I/O error).
    Transport(String),
    /// Anything else (malformed request, JSON error, ...): retrying will not help.
    Other(String),
}

impl TorHttpError {
    pub(crate) fn message(&self) -> &str {
        match self {
            TorHttpError::Timeout(m) | TorHttpError::Transport(m) | TorHttpError::Other(m) => m,
        }
    }

    /// The Kotlin exception class to raise, or `None` to fall back to the generic
    /// `RuntimeException`.
    pub(crate) fn exception_class(&self) -> Option<&'static str> {
        match self {
            TorHttpError::Timeout(_) => Some(TIMEOUT_EXCEPTION_CLASS),
            TorHttpError::Transport(_) => Some(TRANSPORT_EXCEPTION_CLASS),
            TorHttpError::Other(_) => None,
        }
    }

    pub(crate) fn into_anyhow(self) -> anyhow::Error {
        anyhow!("{}", self.message())
    }
}

/// Classifies a `zcash_client_backend` Tor error.
///
/// The client's own per-phase timeouts (`HttpError::Timeout`, connect / headers / body) are
/// transport failures here; only the caller-supplied overall deadline is a [`TorHttpError::Timeout`].
pub(crate) fn classify(e: &Error) -> TorHttpError {
    match e {
        Error::Http(HttpError::Timeout(_))
        | Error::Http(HttpError::Hyper(_))
        | Error::Http(HttpError::Tls(_))
        | Error::Http(HttpError::Spawn(_))
        | Error::Io(_)
        | Error::Tor(_) => TorHttpError::Transport(e.to_string()),
        _ => TorHttpError::Other(e.to_string()),
    }
}

/// Runs `fut`, failing with [`TorHttpError::Timeout`] if `timeout_ms > 0` and it has not
/// finished by then. `timeout_ms <= 0` means no caller-supplied deadline (the client's own
/// per-phase timeouts still apply).
pub(crate) async fn with_deadline<R, T, F>(
    runtime: &R,
    timeout_ms: i64,
    fut: F,
) -> Result<T, TorHttpError>
where
    R: SleepProvider,
    F: Future<Output = Result<T, Error>>,
{
    if timeout_ms <= 0 {
        return fut.await.map_err(|e| classify(&e));
    }
    match runtime
        .timeout(Duration::from_millis(timeout_ms as u64), fut)
        .await
    {
        Ok(res) => res.map_err(|e| classify(&e)),
        Err(_) => Err(TorHttpError::Timeout(format!(
            "HTTP request over Tor timed out after {timeout_ms} ms"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;
    use std::time::Instant;
    use tor_rtcompat::{PreferredRuntime, ToplevelBlockOn};
    use zcash_client_backend::tor::http::TimeoutPhase;

    fn runtime() -> PreferredRuntime {
        PreferredRuntime::create().expect("runtime")
    }

    #[test]
    fn deadline_fires_for_a_request_that_never_completes() {
        let rt = runtime();
        let start = Instant::now();
        let res = rt.block_on(with_deadline(
            &rt,
            100,
            std::future::pending::<Result<(), Error>>(),
        ));
        assert!(matches!(res, Err(TorHttpError::Timeout(_))), "{res:?}");
        assert!(start.elapsed() < Duration::from_secs(10));
    }

    #[test]
    fn deadline_does_not_affect_a_request_that_completes() {
        let rt = runtime();
        let res = rt.block_on(with_deadline(&rt, 5_000, async { Ok::<_, Error>(7u8) }));
        assert_eq!(res.unwrap(), 7);
    }

    #[test]
    fn non_positive_timeout_means_no_deadline() {
        let rt = runtime();
        for timeout_ms in [0, -1] {
            let res = rt.block_on(with_deadline(&rt, timeout_ms, async {
                Ok::<_, Error>(1u8)
            }));
            assert_eq!(res.unwrap(), 1);
        }
    }

    #[test]
    fn inner_errors_are_classified_when_no_deadline_fires() {
        let rt = runtime();
        let res = rt.block_on(with_deadline(&rt, 5_000, async {
            Err::<(), _>(Error::Io(io::Error::other("boom")))
        }));
        assert!(matches!(res, Err(TorHttpError::Transport(_))), "{res:?}");
    }

    #[test]
    fn transport_failures_are_classified_as_transport() {
        assert!(matches!(
            classify(&Error::Io(io::Error::other("reset"))),
            TorHttpError::Transport(_)
        ));
        assert!(matches!(
            classify(&Error::Http(HttpError::Tls(io::Error::other("tls")))),
            TorHttpError::Transport(_)
        ));
        // The client's own per-phase deadlines are transport failures, not the caller's timeout.
        assert!(matches!(
            classify(&Error::Http(HttpError::Timeout(TimeoutPhase::Connect))),
            TorHttpError::Transport(_)
        ));
    }

    #[test]
    fn non_transport_failures_are_not_retryable() {
        assert!(matches!(
            classify(&Error::Http(HttpError::NonHttpUrl)),
            TorHttpError::Other(_)
        ));
        assert!(matches!(
            classify(&Error::MissingTorDirectory),
            TorHttpError::Other(_)
        ));
    }

    #[test]
    fn exception_classes_map_to_kotlin_types() {
        assert_eq!(
            TorHttpError::Timeout(String::new()).exception_class(),
            Some(TIMEOUT_EXCEPTION_CLASS)
        );
        assert_eq!(
            TorHttpError::Transport(String::new()).exception_class(),
            Some(TRANSPORT_EXCEPTION_CLASS)
        );
        assert_eq!(TorHttpError::Other(String::new()).exception_class(), None);
    }
}
