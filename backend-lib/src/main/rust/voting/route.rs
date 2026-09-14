use std::io;
use std::sync::Arc;

use zcash_client_backend::tor::Error as TorError;
use zcash_voting::{RouteError, RouteFuture, RouteHttp, RouteRequest, RouteResponse};

use crate::tor::TorRuntime;

/// [`RouteHttp`] executor that routes every voting chain-submission HTTP
/// request through this session's Tor client, reusing the same
/// [`TorRuntime`] the SDK already uses for lightwalletd and the
/// exchange-rate/HTTP JNI exports in `lib.rs`. Fails closed: there is no
/// direct-HTTP fallback branch, matching the crate's "never fall back to a
/// direct connection" contract (`http_transport.rs:271-302`).
///
/// Holds its OWN isolated `TorRuntime` (via `TorRuntime::isolated_client`,
/// `pub(crate)` in `tor.rs` — accessible here since this module tree is
/// inside the same crate), not a borrow of the caller's — `RouteHttp:
/// Send + Sync + 'static` rules out storing a borrowed `&TorRuntime` across
/// calls, and `TorRuntime` has no `Clone`, so `new` takes `&TorRuntime` and
/// builds its own owned, circuit-isolated copy once at construction.
pub(super) struct ZodlVotingRoute {
    tor: Arc<TorRuntime>,
}

impl ZodlVotingRoute {
    pub(super) fn new(tor_runtime: &TorRuntime) -> Self {
        Self {
            tor: Arc::new(tor_runtime.isolated_client()),
        }
    }
}

impl RouteHttp for ZodlVotingRoute {
    fn execute<'a>(
        &'a self,
        request: RouteRequest<'a>,
        on_dispatch: &'a (dyn Fn() + Send + Sync),
    ) -> RouteFuture<'a> {
        Box::pin(async move {
            let headers: Vec<(String, String)> = request.headers.to_vec();
            let method = request.method.clone();
            let max_response_bytes = request.max_response_bytes;
            let url = request
                .url
                .try_into()
                .map_err(|e| RouteError::before_dispatch(format!("invalid URL: {e}")))?;

            on_dispatch();
            let outcome = if method == http::Method::GET {
                self.tor
                    .client()
                    .http_get(
                        url,
                        |builder| headers.iter().fold(builder, |b, (k, v)| b.header(k, v)),
                        |body| async move {
                            use http_body_util::{BodyExt, Limited};
                            Limited::new(body, max_response_bytes)
                                .collect()
                                .await
                                .map(|agg| agg.to_bytes())
                                .map_err(|e| {
                                    TorError::from(io::Error::new(io::ErrorKind::Other, e))
                                })
                        },
                        0,
                        |_res| None,
                    )
                    .await
            } else {
                self.tor
                    .client()
                    .http_post(
                        url,
                        |builder| headers.iter().fold(builder, |b, (k, v)| b.header(k, v)),
                        http_body_util::Full::new(bytes::Bytes::from(request.body.clone())),
                        |body| async move {
                            use http_body_util::{BodyExt, Limited};
                            Limited::new(body, max_response_bytes)
                                .collect()
                                .await
                                .map(|agg| agg.to_bytes())
                                .map_err(|e| {
                                    TorError::from(io::Error::new(io::ErrorKind::Other, e))
                                })
                        },
                        0,
                        |_res| None,
                    )
                    .await
            };

            let response: http::Response<bytes::Bytes> =
                outcome.map_err(|e| RouteError::after_dispatch(e.to_string()))?;
            let status = response.status().as_u16();
            let headers = response
                .headers()
                .iter()
                .filter_map(|(name, value)| {
                    value
                        .to_str()
                        .ok()
                        .map(|v| (name.as_str().to_string(), v.to_string()))
                })
                .collect();
            let body = response.body().to_vec();
            Ok(RouteResponse {
                status,
                headers,
                body,
            })
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn route_requires_a_tor_runtime_to_construct() {
        // Compile-time check: `ZodlVotingRoute::new` takes `&TorRuntime`,
        // not `Option<&TorRuntime>` — there is no direct-HTTP fallback path
        // to accidentally construct. Regressing this signature to an
        // `Option` should fail a code review, not just a docs read.
        fn _assert_signature(tor: &TorRuntime) -> ZodlVotingRoute {
            ZodlVotingRoute::new(tor)
        }
    }
}
