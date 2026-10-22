use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use zcash_client_backend::tor::Error as TorError;
use zcash_voting::{RouteError, RouteFuture, RouteHttp, RouteRequest, RouteResponse};

use crate::tor::TorRuntime;

#[cfg(feature = "slipstream")]
use slipstream_core::ohttp::{Dialer, http_round_trip_with_dispatch};

/// OHTTP gateway hostname for voting traffic (mainnet).
const OHTTP_GATEWAY_HOST_MAINNET: &str = "ohttp-gateway.example";

/// OHTTP gateway hostname for voting traffic (testnet).
const OHTTP_GATEWAY_HOST_TESTNET: &str = "ohttp-gateway-testnet.example";

/// Exact vote and PIR hosts in gateway BACKENDS (mainnet).
/// Gateway removed suffix routing and uses exact host matching only.
/// MUST match the gateway's BACKENDS (infra repo, kubernetes/apps/ohttp-gateway/deployment.yaml).
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

/// 64 MiB response cap for voting requests (well below the SDK's 256 MiB sealed-response cap).
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
            // The caller's cap, never above this route's own, as on the OHTTP route below.
            let max_response_bytes = request.max_response_bytes.min(MAX_RESPONSE_BYTES);
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

/// Checks if a host is in the gateway's exact vote host list (case-insensitive).
fn can_handle_ohttp(host: &str) -> bool {
    OHTTP_VOTE_HOSTS
        .iter()
        .any(|h| h.eq_ignore_ascii_case(host))
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
/// configs, relay from `zodl_ohttp_client::relay_for` (Fastly production relays),
/// 64 MiB response cap ([`MAX_RESPONSE_BYTES`]), client trace prefix.
pub(super) struct ZodlOhttpRoute {
    gateway_host: String,
    /// The network of `gateway_host`; the transport refuses a gateway of another network.
    #[cfg_attr(not(feature = "slipstream"), allow(dead_code))]
    gateway_network: zcash_protocol::consensus::NetworkType,
}

impl ZodlOhttpRoute {
    /// Creates a new OHTTP route for voting traffic.
    /// `url` is the target vote/helper server URL to check for OHTTP eligibility.
    /// `network` selects mainnet or testnet gateway.
    pub(super) fn new(
        url: &str,
        network: zcash_voting::types::Network,
    ) -> Result<Self, RouteError> {
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

        // Select gateway based on network
        use zcash_protocol::consensus::NetworkType;
        let (gateway_host, gateway_network) = match network {
            zcash_voting::types::Network::Mainnet => {
                (OHTTP_GATEWAY_HOST_MAINNET, NetworkType::Main)
            }
            zcash_voting::types::Network::Testnet | zcash_voting::types::Network::Regtest => {
                (OHTTP_GATEWAY_HOST_TESTNET, NetworkType::Test)
            }
        };

        Ok(Self {
            gateway_host: gateway_host.to_string(),
            gateway_network,
        })
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
            let gateway_network = self.gateway_network;

            // Track whether the request has been dispatched (on_dispatch was called).
            // Errors before dispatch (key-config fetch, encapsulation) map to
            // RouteError::before_dispatch; errors after (relay POST, decapsulation)
            // map to RouteError::after_dispatch.
            let dispatched = Arc::new(AtomicBool::new(false));
            let dispatched_clone = dispatched.clone();
            let on_dispatch_wrapper = move || {
                dispatched_clone.store(true, Ordering::SeqCst);
                on_dispatch();
            };

            // Send via OHTTP using direct dialer (clearnet)
            // The relay sees the device IP, the gateway does not
            let dialer = Dialer::Direct;

            let result = http_round_trip_with_dispatch(
                &gateway_host,
                gateway_network,
                dialer,
                &method,
                &url,
                &headers,
                &body,
                max_response_bytes,
                &on_dispatch_wrapper,
            )
            .await;

            match result {
                Ok((status, response_headers, response_body)) => {
                    // The core stops reading at the cap; this is the exact check once more.
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
                Err(e) => {
                    let is_dispatched = dispatched.load(Ordering::SeqCst);
                    if is_dispatched {
                        Err(RouteError::after_dispatch(format!(
                            "OHTTP transport: {}",
                            e
                        )))
                    } else {
                        Err(RouteError::before_dispatch(format!(
                            "OHTTP transport: {}",
                            e
                        )))
                    }
                }
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
        use zcash_voting::types::Network;

        let result = ZodlOhttpRoute::new("https://custom.example.com/api/vote", Network::Mainnet);
        assert!(result.is_err());

        let result = ZodlOhttpRoute::new("https://pir.valargroup.org/api", Network::Mainnet);
        assert!(result.is_err());
    }

    #[test]
    fn ohttp_route_accepts_supported_hosts() {
        use zcash_voting::types::Network;

        // Vote servers
        let result =
            ZodlOhttpRoute::new("https://voting.valargroup.dev/api/vote", Network::Mainnet);
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new(
            "https://prod.vote-chain-primary.valargroup.org/submit",
            Network::Mainnet,
        );
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new("https://zvote.zodl.com/api/v1/round", Network::Mainnet);
        assert!(result.is_ok());

        // PIR endpoints
        let result = ZodlOhttpRoute::new(
            "https://lb-pir-primary.valargroup.org/root",
            Network::Mainnet,
        );
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new("https://zpir-1.stardust.rest/api/pir", Network::Mainnet);
        assert!(result.is_ok());
    }

    #[test]
    fn ohttp_route_selects_gateway_by_network() {
        use zcash_protocol::consensus::NetworkType;
        use zcash_voting::types::Network;

        let mainnet_route =
            ZodlOhttpRoute::new("https://voting.valargroup.dev/api", Network::Mainnet)
                .expect("mainnet route should succeed");
        assert_eq!(mainnet_route.gateway_host, OHTTP_GATEWAY_HOST_MAINNET);
        assert_eq!(mainnet_route.gateway_network, NetworkType::Main);

        let testnet_route =
            ZodlOhttpRoute::new("https://voting.valargroup.dev/api", Network::Testnet)
                .expect("testnet route should succeed");
        assert_eq!(testnet_route.gateway_host, OHTTP_GATEWAY_HOST_TESTNET);
        assert_eq!(testnet_route.gateway_network, NetworkType::Test);

        let regtest_route =
            ZodlOhttpRoute::new("https://voting.valargroup.dev/api", Network::Regtest)
                .expect("regtest route should succeed");
        assert_eq!(regtest_route.gateway_host, OHTTP_GATEWAY_HOST_TESTNET);
        assert_eq!(regtest_route.gateway_network, NetworkType::Test);
    }

    #[test]
    fn ohttp_host_checking_is_case_insensitive() {
        // Exact case matches
        assert!(can_handle_ohttp("voting.valargroup.dev"));
        assert!(can_handle_ohttp("zvote.zodl.com"));

        // Case variations
        assert!(can_handle_ohttp("VOTING.valargroup.dev"));
        assert!(can_handle_ohttp("Zvote.Zodl.Com"));
        assert!(can_handle_ohttp("ZVOTE.ZODL.COM"));
    }

    #[test]
    fn ohttp_vote_hosts_matches_gateway_backends() {
        // This test verifies OHTTP_VOTE_HOSTS matches the gateway's BACKENDS
        // (infra repo, kubernetes/apps/ohttp-gateway/deployment.yaml).
        // Source of truth: BACKENDS env var in mainnet and testnet gateway pods.
        //
        // Expected: 12 vote servers + 3 PIR endpoints (as of 2026-10-02).
        let expected_hosts = vec![
            // Vote servers (predefined endpoints)
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
            // PIR endpoints (delegation hosts)
            "lb-pir-primary.valargroup.org",
            "lb-pir-backup.valargroup.org",
            "zpir-1.stardust.rest",
        ];

        assert_eq!(
            OHTTP_VOTE_HOSTS.len(),
            expected_hosts.len(),
            "OHTTP_VOTE_HOSTS length mismatch with expected gateway BACKENDS"
        );

        for host in &expected_hosts {
            assert!(
                OHTTP_VOTE_HOSTS.contains(host),
                "Expected host {} not in OHTTP_VOTE_HOSTS",
                host
            );
        }

        for host in OHTTP_VOTE_HOSTS {
            assert!(
                expected_hosts.contains(host),
                "OHTTP_VOTE_HOSTS contains unexpected host {}",
                host
            );
        }
    }
}
