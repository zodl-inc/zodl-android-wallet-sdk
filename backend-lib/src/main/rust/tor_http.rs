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
//!
//! [`TorHttpBody`] is the body of a response that the JVM reads as it arrives
//! (`TorClient.httpPostStreaming`): the same deadline covers it, and a [`CancelSignal`] set from
//! another thread ends the exchange, whether it is still waiting for the response head
//! ([`block_on_unless_cancelled`]) or for more of the body.

use std::future::Future;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::anyhow;
use bytes::Bytes;
use http_body_util::{BodyExt, combinators::UnsyncBoxBody};
use tor_rtcompat::{PreferredRuntime, SleepProvider, SleepProviderExt, ToplevelBlockOn};
use zcash_client_backend::tor::{Error, http::HttpError};

/// `TorHttpTimeoutException`: the caller-supplied deadline elapsed.
pub(crate) const TIMEOUT_EXCEPTION_CLASS: &str =
    "cash/z/ecc/android/sdk/internal/model/TorHttpTimeoutException";

/// `TorHttpTransportException`: the request failed at the network/transport level and is
/// worth retrying.
pub(crate) const TRANSPORT_EXCEPTION_CLASS: &str =
    "cash/z/ecc/android/sdk/internal/model/TorHttpTransportException";

/// `TorHttpCancelledException`: the caller cancelled the exchange; not a failure to retry.
pub(crate) const CANCELLED_EXCEPTION_CLASS: &str =
    "cash/z/ecc/android/sdk/internal/model/TorHttpCancelledException";

/// How an HTTP-over-Tor call failed.
#[derive(Debug)]
pub(crate) enum TorHttpError {
    /// The caller-supplied deadline elapsed before the request completed.
    Timeout(String),
    /// A network/transport failure (Tor circuit or stream error, connection or TLS failure,
    /// a per-phase client timeout, I/O error).
    Transport(String),
    /// The caller cancelled the exchange ([`CancelSignal`]). Not a transport failure: nothing
    /// went wrong on the network, and a retry would only send the request the caller abandoned.
    Cancelled(String),
    /// Anything else (malformed request, JSON error, ...): retrying will not help.
    Other(String),
}

impl TorHttpError {
    pub(crate) fn message(&self) -> &str {
        match self {
            TorHttpError::Timeout(m)
            | TorHttpError::Transport(m)
            | TorHttpError::Cancelled(m)
            | TorHttpError::Other(m) => m,
        }
    }

    /// The Kotlin exception class to raise, or `None` to fall back to the generic
    /// `RuntimeException`.
    pub(crate) fn exception_class(&self) -> Option<&'static str> {
        match self {
            TorHttpError::Timeout(_) => Some(TIMEOUT_EXCEPTION_CLASS),
            TorHttpError::Transport(_) => Some(TRANSPORT_EXCEPTION_CLASS),
            TorHttpError::Cancelled(_) => Some(CANCELLED_EXCEPTION_CLASS),
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

/// The error of a body read over Tor.
pub(crate) type BodyError = Box<dyn std::error::Error + Send + Sync>;

/// How long a wait goes before it looks at the [`CancelSignal`] again.
const CANCEL_POLL: Duration = Duration::from_millis(100);

/// Set from any thread to end an exchange over Tor (`TorClient.httpPostStreaming` sets it when
/// its caller is cancelled); a waiting exchange notices it within [`CANCEL_POLL`].
pub(crate) type CancelSignal = Arc<AtomicBool>;

fn cancelled_error() -> TorHttpError {
    TorHttpError::Cancelled("the HTTP exchange over Tor was cancelled".to_string())
}

/// Runs `fut` on `runtime` until it completes, or fails it once `cancel` is set.
pub(crate) fn block_on_unless_cancelled<T, F>(
    runtime: &PreferredRuntime,
    cancel: &AtomicBool,
    fut: F,
) -> Result<T, TorHttpError>
where
    F: Future<Output = Result<T, TorHttpError>>,
{
    let mut fut = std::pin::pin!(fut);
    loop {
        if cancel.load(Ordering::Acquire) {
            return Err(cancelled_error());
        }
        // The timer is made inside the runtime: `timeout` is called in the async block.
        let polled = runtime.block_on(async { runtime.timeout(CANCEL_POLL, fut.as_mut()).await });
        if let Ok(output) = polled {
            return output;
        }
    }
}

/// The body of an HTTP response over Tor, read one frame at a time by the JVM. The deadline of
/// the request that produced it (`timeout_ms` from `started`) bounds every read; setting
/// `cancel` (from any thread) makes a waiting read fail within [`CANCEL_POLL`].
pub(crate) struct TorHttpBody {
    runtime: PreferredRuntime,
    body: Mutex<UnsyncBoxBody<Bytes, BodyError>>,
    cancel: CancelSignal,
    timeout_ms: i64,
    deadline: Option<Instant>,
}

impl TorHttpBody {
    pub(crate) fn new(
        runtime: PreferredRuntime,
        body: UnsyncBoxBody<Bytes, BodyError>,
        cancel: CancelSignal,
        timeout_ms: i64,
        started: Instant,
    ) -> Self {
        TorHttpBody {
            runtime,
            body: Mutex::new(body),
            cancel,
            timeout_ms,
            deadline: u64::try_from(timeout_ms)
                .ok()
                .filter(|ms| *ms > 0)
                .map(|ms| started + Duration::from_millis(ms)),
        }
    }

    /// The next bytes of the body, `None` once it has ended. Trailer frames are skipped.
    pub(crate) fn read(&self) -> Result<Option<Bytes>, TorHttpError> {
        let mut body = self
            .body
            .lock()
            .map_err(|_| TorHttpError::Other("the response body is unusable".to_string()))?;
        loop {
            if self.cancel.load(Ordering::Acquire) {
                return Err(cancelled_error());
            }
            let wait = match self.deadline {
                Some(deadline) => {
                    let left = deadline.saturating_duration_since(Instant::now());
                    if left.is_zero() {
                        return Err(TorHttpError::Timeout(format!(
                            "HTTP request over Tor timed out after {} ms",
                            self.timeout_ms
                        )));
                    }
                    left.min(CANCEL_POLL)
                }
                None => CANCEL_POLL,
            };
            let next = async { self.runtime.timeout(wait, body.frame()).await };
            let frame = match self.runtime.block_on(next) {
                Err(_) => continue,
                Ok(None) => return Ok(None),
                Ok(Some(Err(e))) => return Err(TorHttpError::Transport(e.to_string())),
                Ok(Some(Ok(frame))) => frame,
            };
            if let Ok(data) = frame.into_data()
                && !data.is_empty()
            {
                return Ok(Some(data));
            }
        }
    }
}

/// `body` boxed for [`TorHttpBody`].
pub(crate) fn boxed_body<B, E>(body: B) -> UnsyncBoxBody<Bytes, BodyError>
where
    B: BodyExt<Data = Bytes, Error = E> + Send + 'static,
    E: std::error::Error + Send + Sync + 'static,
{
    body.map_err(|e| Box::new(e) as BodyError).boxed_unsync()
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

    fn body_of(
        bytes: &'static [u8],
        cancel: CancelSignal,
        timeout_ms: i64,
        started: Instant,
    ) -> TorHttpBody {
        TorHttpBody::new(
            runtime(),
            boxed_body(http_body_util::Full::new(Bytes::from_static(bytes))),
            cancel,
            timeout_ms,
            started,
        )
    }

    #[test]
    fn a_body_is_read_to_its_end() {
        let body = body_of(b"sealed", CancelSignal::default(), 5_000, Instant::now());
        assert_eq!(body.read().unwrap().as_deref(), Some(&b"sealed"[..]));
        assert!(body.read().unwrap().is_none());
    }

    #[test]
    fn a_cancelled_body_read_fails_as_cancelled_not_as_a_transport_failure() {
        let cancel = CancelSignal::default();
        let body = body_of(b"sealed", cancel.clone(), 5_000, Instant::now());
        cancel.store(true, Ordering::Release);
        assert!(matches!(body.read(), Err(TorHttpError::Cancelled(_))));
    }

    #[test]
    fn a_body_read_past_the_request_deadline_times_out() {
        let started = Instant::now() - Duration::from_secs(2);
        let body = body_of(b"sealed", CancelSignal::default(), 1_000, started);
        assert!(matches!(body.read(), Err(TorHttpError::Timeout(_))));
        let unbounded = body_of(b"sealed", CancelSignal::default(), 0, started);
        assert!(unbounded.read().unwrap().is_some());
    }

    #[test]
    fn a_wait_for_the_response_head_ends_when_cancelled_from_another_thread() {
        let rt = runtime();
        let cancel = CancelSignal::default();
        let setter = {
            let cancel = cancel.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(150));
                cancel.store(true, Ordering::Release);
            })
        };
        let start = Instant::now();
        let res = block_on_unless_cancelled(
            &rt,
            &cancel,
            std::future::pending::<Result<(), TorHttpError>>(),
        );
        assert!(matches!(res, Err(TorHttpError::Cancelled(_))), "{res:?}");
        assert!(start.elapsed() < Duration::from_secs(5));
        setter.join().unwrap();

        let done = block_on_unless_cancelled(&rt, &CancelSignal::default(), async {
            Ok::<_, TorHttpError>(3u8)
        });
        assert_eq!(done.unwrap(), 3);
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
        assert_eq!(
            TorHttpError::Cancelled(String::new()).exception_class(),
            Some(CANCELLED_EXCEPTION_CLASS)
        );
        assert_eq!(TorHttpError::Other(String::new()).exception_class(), None);
    }
}
