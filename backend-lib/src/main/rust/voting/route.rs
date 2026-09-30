use std::io;
use std::sync::Arc;

use zcash_client_backend::tor::Error as TorError;
use zcash_voting::{RouteError, RouteFuture, RouteHttp, RouteRequest, RouteResponse};

use crate::tor::TorRuntime;

#[cfg(feature = "slipstream")]
use slipstream_core::ohttp::{Dialer, http_round_trip};

/// OHTTP gateway hostname for voting traffic (mainnet).
const OHTTP_GATEWAY_HOST_MAINNET: &str = "ohttp-gateway.example";

/// OHTTP gateway hostname for voting traffic (testnet).
/// TODO: use when network-aware gateway selection is implemented (route.rs:190-192)
#[allow(dead_code)]
const OHTTP_GATEWAY_HOST_TESTNET: &str = "ohttp-gateway-testnet.example";

/// Exact vote and PIR hosts in gateway BACKENDS (mainnet).
/// Gateway removed suffix routing and uses exact host matching only.
/// MUST match `/work/infra/kubernetes/apps/ohttp-gateway/deployment.yaml` BACKENDS list.
const OHTTP_VOTE_HOSTS: &[&str] = &[
    // Vote servers
    "vote.zodl.com",
    "prod.vote-chain-primary.valargroup.org",
    "voting.valargroup.dev",
    "ru.zcash.space",
    "147-182-192-212.sslip.io",
    "134-209-215-69.sslip.io",
    "mnval01.zcash.legends.sh",
    "zvote-1.stardust.rest",
    "zvote.zodl.com",
    "zvote.keplr.app",
    "zvote.zfnd.org",
    "v.ombie.cash",
    // PIR endpoints (delegation) — fail closed through OHTTP when Tor OFF + predefined servers
    "lb-pir-primary.valargroup.org",
    "lb-pir-backup.valargroup.org",
    "zpir-1.stardust.rest",
];

/// 64 MiB response cap (matches gateway MAX_UPSTREAM_RESPONSE_BYTES default).
const MAX_RESPONSE_BYTES: usize = 64 * 1024 * 1024;

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

/// Checks if a host is in the gateway's exact vote host list.
fn can_handle_ohttp(host: &str) -> bool {
    OHTTP_VOTE_HOSTS.contains(&host)
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
/// Uses the same OHTTP stack as the lightwalletd gRPC path: signed HPKE key
/// configs, relay-simulator.example (mainnet), 64 MiB response cap, client
/// trace prefix.
pub(super) struct ZodlOhttpRoute {
    gateway_host: String,
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

        // Check if host is OHTTP-eligible (exact match in gateway BACKENDS)
        if !can_handle_ohttp(host) {
            return Err(RouteError::before_dispatch(format!(
                "host {} not supported by OHTTP gateway",
                host
            )));
        }

        // Determine gateway host based on network (hardcoded mainnet for now)
        // TODO: derive from network parameter when testnet support is added
        let gateway_host = OHTTP_GATEWAY_HOST_MAINNET.to_string();

        Ok(Self { gateway_host })
    }
}

impl RouteHttp for ZodlOhttpRoute {
    fn execute<'a>(
        &'a self,
        request: RouteRequest<'a>,
        on_dispatch: &'a (dyn Fn() + Send + Sync),
    ) -> RouteFuture<'a> {
        #[cfg(not(feature = "slipstream"))]
        {
            let _ = (request, on_dispatch);
            Box::pin(async move {
                Err(RouteError::before_dispatch(
                    "OHTTP route requires slipstream feature".to_string(),
                ))
            })
        }

        #[cfg(feature = "slipstream")]
        Box::pin(async move {
            let headers: Vec<(String, String)> = request.headers.to_vec();
            let method = request.method.as_str().to_string();
            let url = request.url.to_string();
            let body = request.body.to_vec();
            let max_response_bytes = request.max_response_bytes.min(MAX_RESPONSE_BYTES);

            let gateway_host = self.gateway_host.clone();

            on_dispatch();

            // Send via OHTTP using direct dialer (clearnet)
            // The relay sees the device IP, the gateway does not
            let dialer = Dialer::Direct;

            let result =
                http_round_trip(&gateway_host, dialer, &method, &url, &headers, &body).await;

            match result {
                Ok((status, response_headers, response_body)) => {
                    // Enforce response size cap
                    if response_body.len() > max_response_bytes {
                        return Err(RouteError::after_dispatch(format!(
                            "response body too large: {} > {} bytes",
                            response_body.len(),
                            max_response_bytes
                        )));
                    }

                    Ok(RouteResponse {
                        status,
                        headers: response_headers,
                        body: response_body,
                    })
                }
                Err(e) => Err(RouteError::after_dispatch(format!(
                    "OHTTP transport: {}",
                    e
                ))),
            }
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
    fn ohttp_host_checking_exact_match_only() {
        // Exact matches in OHTTP_VOTE_HOSTS
        assert!(can_handle_ohttp("voting.valargroup.dev"));
        assert!(can_handle_ohttp("vote.zodl.com"));
        assert!(can_handle_ohttp("prod.vote-chain-primary.valargroup.org"));
        assert!(can_handle_ohttp("ru.zcash.space"));
        assert!(can_handle_ohttp("zvote-1.stardust.rest"));

        // Not in list (even with matching suffixes)
        assert!(!can_handle_ohttp("unknown.valargroup.org"));
        assert!(!can_handle_ohttp("custom.example.com"));
        assert!(!can_handle_ohttp("pir.valargroup.org"));
    }

    #[test]
    fn ohttp_route_rejects_unsupported_hosts() {
        let result = ZodlOhttpRoute::new("https://custom.example.com/api/vote");
        assert!(result.is_err());

        let result = ZodlOhttpRoute::new("https://pir.valargroup.org/api");
        assert!(result.is_err());
    }

    #[test]
    fn ohttp_route_accepts_supported_hosts() {
        // Vote servers
        let result = ZodlOhttpRoute::new("https://voting.valargroup.dev/api/vote");
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new("https://prod.vote-chain-primary.valargroup.org/submit");
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new("https://zvote.zodl.com/api/v1/round");
        assert!(result.is_ok());

        // PIR endpoints
        let result = ZodlOhttpRoute::new("https://lb-pir-primary.valargroup.org/root");
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new("https://zpir-1.stardust.rest/api/pir");
        assert!(result.is_ok());
    }

    #[test]
    fn ohttp_vote_hosts_matches_gateway_backends() {
        // This test documents the hosts in gateway BACKENDS (mainnet):
        // 12 vote servers + 3 PIR endpoints.
        // If the gateway adds/removes hosts, this list must be updated.
        assert_eq!(OHTTP_VOTE_HOSTS.len(), 15);

        let expected_vote = vec![
            "vote.zodl.com",
            "prod.vote-chain-primary.valargroup.org",
            "voting.valargroup.dev",
            "ru.zcash.space",
            "147-182-192-212.sslip.io",
            "134-209-215-69.sslip.io",
            "mnval01.zcash.legends.sh",
            "zvote-1.stardust.rest",
            "zvote.zodl.com",
            "zvote.keplr.app",
            "zvote.zfnd.org",
            "v.ombie.cash",
        ];

        let expected_pir = [
            "lb-pir-primary.valargroup.org",
            "lb-pir-backup.valargroup.org",
            "zpir-1.stardust.rest",
        ];

        for host in expected_vote.iter().chain(expected_pir.iter()) {
            assert!(
                OHTTP_VOTE_HOSTS.contains(host),
                "Expected host {} not in OHTTP_VOTE_HOSTS",
                host
            );
        }
    }
}
