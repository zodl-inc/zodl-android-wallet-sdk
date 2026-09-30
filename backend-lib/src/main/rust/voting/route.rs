use std::io;
use std::sync::Arc;

use zcash_client_backend::tor::Error as TorError;
use zcash_voting::{RouteError, RouteFuture, RouteHttp, RouteRequest, RouteResponse};

use crate::tor::TorRuntime;

/// OHTTP relay URL for voting traffic (mainnet).
/// COR-1830: Will switch to ohttp-relay once it serves /ohttp-configs.
const OHTTP_RELAY_URL: &str = "https://relay-simulator.example/relay";

/// Allowlisted suffixes for OHTTP gateway proxying.
/// Hosts matching these suffixes are proxied to `https://<authority>` directly.
const OHTTP_ALLOWED_SUFFIXES: &[&str] = &[".valargroup.org", ".valargroup.dev", "chaindefuser.com"];

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
                                .map_err(|e| TorError::from(io::Error::other(e)))
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
                                .map_err(|e| TorError::from(io::Error::other(e)))
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

/// Checks if a host can be routed through the OHTTP gateway.
/// Returns `true` if the host has a static proxy mapping OR matches an allowlisted suffix.
fn can_handle_ohttp(host: &str) -> bool {
    proxy_gateway_host_for(host).is_some() || is_ohttp_allowed_host(host)
}

/// Maps a real hostname to the ZODL OHTTP proxy hostname for static aliases.
/// Pass-through entries (vote.zodl.com) allow the driver to use zodl aliases directly.
fn proxy_gateway_host_for(host: &str) -> Option<&'static str> {
    match host {
        "voting.valargroup.dev" => Some("vote.zodl.com"),
        "vote.zodl.com" => Some("vote.zodl.com"),
        _ => None,
    }
}

/// Returns `true` if `host` matches one of `OHTTP_ALLOWED_SUFFIXES`.
/// Match rule: a suffix starting with `.` matches `x.suffix`; a bare domain
/// matches itself or `*.domain`.
fn is_ohttp_allowed_host(host: &str) -> bool {
    let lower_host = host.to_lowercase();
    OHTTP_ALLOWED_SUFFIXES.iter().any(|suffix| {
        let lower_suffix = suffix.to_lowercase();
        if lower_suffix.starts_with('.') {
            lower_host.ends_with(&lower_suffix) || lower_host == lower_suffix.trim_start_matches('.')
        } else {
            lower_host == lower_suffix || lower_host.ends_with(&format!(".{}", lower_suffix))
        }
    })
}

/// [`RouteHttp`] executor that routes voting HTTP requests through the OHTTP
/// gateway when Tor is OFF and the destination is a predefined vote server
/// (not a custom user-added server). Encapsulates requests with OHTTP (BHTTP
/// encoding + HPKE encryption) and sends them via the relay to the gateway,
/// which decapsulates and forwards them to the real vote/helper servers.
///
/// Fails closed: if OHTTP encapsulation or transport fails, the request fails
/// rather than falling back to direct HTTP, matching the privacy-preserving
/// contract that Tor-off predefined-server traffic must not expose the user's
/// IP to vote servers.
///
/// Uses the same OHTTP stack as the lightwalletd gRPC path and the SDK's
/// HTTP NEAR/CMC/voting read operations: relay-simulator.example (mainnet)
/// with HPKE keys and signed configs from the gateway.
pub(super) struct ZodlOhttpRoute {
    // TODO: OHTTP connector state (relay URL, key config, HTTP client)
    // Will use slipstream_core::ohttp APIs or equivalent
    _phantom: std::marker::PhantomData<()>,
}

impl ZodlOhttpRoute {
    /// Creates a new OHTTP route for voting traffic.
    /// `url` is the target vote/helper server URL to check for OHTTP eligibility.
    pub(super) fn new(url: &str) -> Result<Self, RouteError> {
        // Extract host from URL
        let host = url
            .split("://")
            .nth(1)
            .and_then(|s| s.split('/').next())
            .and_then(|s| s.split(':').next())
            .ok_or_else(|| RouteError::before_dispatch(format!("invalid URL: {}", url)))?;

        // Check if host is OHTTP-eligible
        if !can_handle_ohttp(host) {
            return Err(RouteError::before_dispatch(format!(
                "host {} not supported by OHTTP gateway",
                host
            )));
        }

        Ok(Self {
            _phantom: std::marker::PhantomData,
        })
    }
}

impl RouteHttp for ZodlOhttpRoute {
    fn execute<'a>(
        &'a self,
        _request: RouteRequest<'a>,
        _on_dispatch: &'a (dyn Fn() + Send + Sync),
    ) -> RouteFuture<'a> {
        // TODO: Implement OHTTP request encapsulation and dispatch
        // 1. Encode request as BHTTP (Binary HTTP)
        // 2. Encapsulate with OHTTP using HPKE
        // 3. POST to OHTTP_RELAY_URL
        // 4. Decapsulate response
        // 5. Return RouteResponse
        Box::pin(async move {
            Err(RouteError::before_dispatch(
                "OHTTP route not yet implemented".to_string(),
            ))
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

    #[test]
    fn ohttp_host_checking() {
        // Static mappings
        assert!(can_handle_ohttp("voting.valargroup.dev"));
        assert!(can_handle_ohttp("vote.zodl.com"));

        // Allowlisted suffixes
        assert!(can_handle_ohttp("prod.vote-chain-primary.valargroup.org"));
        assert!(can_handle_ohttp("zvote-1.stardust.rest")); // Not in allowlist - should fail
        assert!(can_handle_ohttp("ru.zcash.space")); // Not in allowlist - should fail

        // Custom servers (not allowlisted)
        assert!(!can_handle_ohttp("custom.example.com"));
    }

    #[test]
    fn ohttp_route_rejects_unsupported_hosts() {
        let result = ZodlOhttpRoute::new("https://custom.example.com/api/vote");
        assert!(result.is_err());
    }

    #[test]
    fn ohttp_route_accepts_supported_hosts() {
        let result = ZodlOhttpRoute::new("https://voting.valargroup.dev/api/vote");
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new("https://prod.vote-chain-primary.valargroup.org/submit");
        assert!(result.is_ok());
    }
}
