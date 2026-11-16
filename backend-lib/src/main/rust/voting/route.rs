use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use zcash_client_backend::tor::Error as TorError;
use zcash_voting::{RouteError, RouteFuture, RouteHttp, RouteRequest, RouteResponse};

use zodl_ohttp_client::OhttpNetworks;

use crate::tor::TorRuntime;

#[cfg(feature = "slipstream")]
use slipstream_core::ohttp::{Dialer, http_round_trip_with_dispatch};

/// The OHTTP gateway host of `network` for voting traffic in the installed configuration
/// (`networks`, see `ohttp_config.rs`), and the network the engine checks it against. Regtest
/// uses the testnet gateway. `None` when OHTTP is off for the network: no configuration was
/// installed for it.
pub(super) fn select_gateway(
    networks: &OhttpNetworks,
    network: zcash_voting::types::Network,
) -> Option<(&str, zcash_protocol::consensus::NetworkType)> {
    use zcash_protocol::consensus::NetworkType;
    let (ohttp_network, network_type) = match network {
        zcash_voting::types::Network::Mainnet => {
            (zodl_ohttp_client::Network::Mainnet, NetworkType::Main)
        }
        zcash_voting::types::Network::Testnet | zcash_voting::types::Network::Regtest => {
            (zodl_ohttp_client::Network::Testnet, NetworkType::Test)
        }
    };
    networks
        .get(ohttp_network)
        .map(|config| (config.gateway_host(), network_type))
}

/// Whether the installed configuration turns OHTTP on for `network`. With it off, predefined
/// servers are reached like custom ones (directly with Tor off), as in a library without OHTTP.
pub(super) fn ohttp_on_for(
    networks: &OhttpNetworks,
    network: zcash_voting::types::Network,
) -> bool {
    select_gateway(networks, network).is_some()
}

/// The host of a vote or PIR server URL that may go through the gateway: an absolute `https`
/// URL on the default port, without userinfo. It is parsed with [`http::Uri`], as the OHTTP
/// transport parses it again, so the host checked here is the host the request goes to (a
/// `https://allowed.example:x@other.example/` form is refused, not read as `allowed.example`).
fn ohttp_target_host(url: &str) -> Result<String, RouteError> {
    let invalid = |why: &str| RouteError::before_dispatch(format!("OHTTP vote route: {why}"));
    let uri: http::Uri = url.parse().map_err(|_| invalid("not a valid URL"))?;
    if uri.scheme() != Some(&http::uri::Scheme::HTTPS) {
        return Err(invalid("the URL must use https"));
    }
    let authority = uri
        .authority()
        .ok_or_else(|| invalid("the URL has no host"))?;
    if authority.as_str().contains('@') {
        return Err(invalid("the URL must not carry userinfo"));
    }
    if authority.port_u16().is_some_and(|port| port != 443) {
        return Err(invalid("the URL must use the default https port"));
    }
    Ok(authority.host().to_string())
}

/// Exact vote and PIR hosts in gateway BACKENDS (mainnet).
/// Gateway removed suffix routing and uses exact host matching only.
/// MUST match the gateway's BACKENDS (infra repo, kubernetes/apps/ohttp-gateway/deployment.yaml).
const OHTTP_VOTE_HOSTS: &[&str] = &[
    // Vote servers
    "vote.zodl.com",
    "prod.vote-chain-primary.valargroup.org",
    "voting.valargroup.dev",
    "ru.zcash.space",
    // Two validators of the production vote chain that are reached by their sslip.io names: the
    // published dynamic voting config (`vote_servers`) lists them, the round driver sends to them.
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
/// configs, the relay of the gateway in the installed configuration, 64 MiB response cap
/// ([`MAX_RESPONSE_BYTES`]), client trace prefix.
pub(super) struct ZodlOhttpRoute {
    /// The OHTTP configuration the session opened under; every request of the session uses it.
    #[cfg_attr(not(feature = "slipstream"), allow(dead_code))]
    networks: Arc<OhttpNetworks>,
    gateway_host: String,
    /// The network of `gateway_host`; the transport refuses a gateway of another network.
    #[cfg_attr(not(feature = "slipstream"), allow(dead_code))]
    gateway_network: zcash_protocol::consensus::NetworkType,
}

impl ZodlOhttpRoute {
    /// Creates a new OHTTP route for voting traffic through the gateway `networks` configures
    /// for `network` (mainnet or testnet). `url` is the target vote/helper server URL to check
    /// for OHTTP eligibility. A pre-dispatch error when the gateway does not carry that host or
    /// OHTTP is off for the network.
    pub(super) fn new(
        networks: Arc<OhttpNetworks>,
        url: &str,
        network: zcash_voting::types::Network,
    ) -> Result<Self, RouteError> {
        // Only hosts the gateway proxies (exact match in its BACKENDS), checked on the host the
        // request goes to.
        let host = ohttp_target_host(url)?;
        if !can_handle_ohttp(&host) {
            return Err(RouteError::before_dispatch(format!(
                "host {} not supported by OHTTP gateway",
                host
            )));
        }

        // Select the gateway of the network (installed configuration; none = unavailable).
        let (gateway_host, gateway_network) =
            select_gateway(&networks, network).ok_or_else(|| {
                RouteError::before_dispatch(
                    "OHTTP unavailable: no gateway configured for this network".to_string(),
                )
            })?;
        let gateway_host = gateway_host.to_string();

        Ok(Self {
            networks,
            gateway_host,
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
                &self.networks,
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

    /// Both networks configured with the example values (RFC 2606 names).
    fn examples() -> Arc<OhttpNetworks> {
        Arc::new(crate::ohttp_config::example::networks())
    }

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

        let result = ZodlOhttpRoute::new(
            examples(),
            "https://custom.example.com/api/vote",
            Network::Mainnet,
        );
        assert!(result.is_err());

        let result = ZodlOhttpRoute::new(
            examples(),
            "https://pir.valargroup.org/api",
            Network::Mainnet,
        );
        assert!(result.is_err());
    }

    #[test]
    fn ohttp_route_checks_the_host_the_request_goes_to() {
        use zcash_voting::types::Network;

        // Userinfo that names an allowed host: `http::Uri` (and so the transport) targets the
        // host after the `@`.
        for url in [
            "https://vote.zodl.com:x@evil.example/",
            "https://vote.zodl.com@evil.example/",
            "https://user:pass@vote.zodl.com/",
            "https://vote.zodl.com:x@vote.zodl.com/",
        ] {
            assert!(
                ZodlOhttpRoute::new(examples(), url, Network::Mainnet).is_err(),
                "{url}"
            );
        }
        // Not https, another port, not absolute, not a URL.
        for url in [
            "http://vote.zodl.com/",
            "https://vote.zodl.com:8443/",
            "https://vote.zodl.com:0/",
            "//vote.zodl.com/",
            "vote.zodl.com/api",
            "/api",
            "https://vote.zodl.com /",
            "",
        ] {
            assert!(
                ZodlOhttpRoute::new(examples(), url, Network::Mainnet).is_err(),
                "{url:?}"
            );
        }
        // A trailing dot or a lookalike is another host.
        for url in [
            "https://vote.zodl.com./",
            "https://vote.zodl.com.evil.example/",
            "https://evil.example/vote.zodl.com",
            "https://evil.example/?h=vote.zodl.com",
        ] {
            assert!(
                ZodlOhttpRoute::new(examples(), url, Network::Mainnet).is_err(),
                "{url}"
            );
        }
        // The default port written out, a path, a query.
        for url in [
            "https://vote.zodl.com",
            "https://vote.zodl.com:443/api/v1/round",
            "https://VOTE.zodl.com/api?x=1",
        ] {
            assert!(
                ZodlOhttpRoute::new(examples(), url, Network::Mainnet).is_ok(),
                "{url}"
            );
        }
    }

    #[test]
    fn ohttp_route_accepts_supported_hosts() {
        use zcash_voting::types::Network;

        // Vote servers
        let result = ZodlOhttpRoute::new(
            examples(),
            "https://voting.valargroup.dev/api/vote",
            Network::Mainnet,
        );
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new(
            examples(),
            "https://prod.vote-chain-primary.valargroup.org/submit",
            Network::Mainnet,
        );
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new(
            examples(),
            "https://zvote.zodl.com/api/v1/round",
            Network::Mainnet,
        );
        assert!(result.is_ok());

        // PIR endpoints
        let result = ZodlOhttpRoute::new(
            examples(),
            "https://lb-pir-primary.valargroup.org/root",
            Network::Mainnet,
        );
        assert!(result.is_ok());

        let result = ZodlOhttpRoute::new(
            examples(),
            "https://zpir-1.stardust.rest/api/pir",
            Network::Mainnet,
        );
        assert!(result.is_ok());
    }

    #[test]
    fn ohttp_route_selects_gateway_by_network() {
        use zcash_protocol::consensus::NetworkType;
        use zcash_voting::types::Network;

        let mainnet_route = ZodlOhttpRoute::new(
            examples(),
            "https://voting.valargroup.dev/api",
            Network::Mainnet,
        )
        .expect("mainnet route should succeed");
        assert_eq!(mainnet_route.gateway_host, "ohttp-gateway.example");
        assert_eq!(mainnet_route.gateway_network, NetworkType::Main);

        let testnet_route = ZodlOhttpRoute::new(
            examples(),
            "https://voting.valargroup.dev/api",
            Network::Testnet,
        )
        .expect("testnet route should succeed");
        assert_eq!(testnet_route.gateway_host, "ohttp-gateway-testnet.example");
        assert_eq!(testnet_route.gateway_network, NetworkType::Test);

        let regtest_route = ZodlOhttpRoute::new(
            examples(),
            "https://voting.valargroup.dev/api",
            Network::Regtest,
        )
        .expect("regtest route should succeed");
        assert_eq!(regtest_route.gateway_host, "ohttp-gateway-testnet.example");
        assert_eq!(regtest_route.gateway_network, NetworkType::Test);
    }

    #[test]
    fn a_network_without_a_configured_gateway_is_unavailable_not_a_panic() {
        use crate::ohttp_config::example;
        use zcash_protocol::consensus::NetworkType;
        use zcash_voting::types::Network;

        let only_testnet =
            OhttpNetworks::new(None, Some(example::config(example::TESTNET))).unwrap();
        assert!(select_gateway(&only_testnet, Network::Mainnet).is_none());
        assert!(!ohttp_on_for(&only_testnet, Network::Mainnet));
        assert_eq!(
            select_gateway(&only_testnet, Network::Regtest).unwrap(),
            ("ohttp-gateway-testnet.example", NetworkType::Test)
        );
        let only_mainnet =
            OhttpNetworks::new(Some(example::config(example::MAINNET)), None).unwrap();
        assert!(select_gateway(&only_mainnet, Network::Testnet).is_none());
        let off = OhttpNetworks::default();
        for network in [Network::Mainnet, Network::Testnet, Network::Regtest] {
            assert!(select_gateway(&off, network).is_none());
            assert!(!ohttp_on_for(&off, network));
        }
        assert!(
            ZodlOhttpRoute::new(
                Arc::new(off),
                "https://voting.valargroup.dev/api",
                Network::Mainnet
            )
            .is_err()
        );
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
