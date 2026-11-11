//! The OHTTP configuration the app installs once at startup.
//!
//! The library carries no OHTTP endpoint or key, neither in its sources nor from its build. The
//! app passes the configuration of mainnet and testnet in through
//! `cash.z.ecc.android.sdk.Ohttp.configure`, which reaches [`install`] through
//! `RustOhttpTool.nativeConfigure`. Every OHTTP path of the library reads it through
//! [`installed_networks`]: the key-configuration check and the relay and DNS lookups of the Kotlin
//! transport (`ohttp.rs`), the gateway classification of the Tor paths (`tor.rs`), each start of
//! the Slipstream engine and its restore anchor (`slipstream/`), the relay health exports
//! (`slipstream/health.rs`) and each voting session as it opens (`voting/`).
//!
//! The configuration is set once per process: the first successful install wins, an identical
//! one later is accepted and changes nothing, a different one is refused. Until it is installed,
//! and for a network passed as null, OHTTP is off: no host is that network's gateway, so its
//! servers are plain servers.

use std::sync::{Arc, LazyLock, PoisonError, RwLock};

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JClass, JString},
};
use zodl_ohttp_client::{Network, OhttpConfig, OhttpConfigParams, OhttpNetworks};

use crate::utils::{catch_unwind, exception::unwrap_exc_or, java_nullable_string_to_rust};

/// The installed configuration, `None` until [`install`] succeeds. Never edited in place, so a
/// reader that keeps the `Arc` keeps one complete configuration.
static INSTALLED: RwLock<Option<Arc<OhttpNetworks>>> = RwLock::new(None);

/// What [`installed_networks`] returns while nothing is installed: both networks off.
static OFF: LazyLock<Arc<OhttpNetworks>> = LazyLock::new(|| Arc::new(OhttpNetworks::default()));

/// The configuration installed now; both networks are off until one is installed. A caller that
/// looks up more than one thing reads it once and keeps the `Arc`, so that its lookups agree.
pub(crate) fn installed_networks() -> Arc<OhttpNetworks> {
    INSTALLED
        .read()
        .unwrap_or_else(PoisonError::into_inner)
        .as_ref()
        .map_or_else(|| Arc::clone(&OFF), Arc::clone)
}

/// Why [`install`] refused a configuration. Neither message carries a configured value.
#[derive(Debug)]
pub(crate) enum InstallError {
    /// A value breaks its rule, or the two networks share one: names the network, the field
    /// and the rule.
    Invalid(String),
    /// Another configuration is already installed; it stays.
    AlreadyInstalled,
}

impl std::fmt::Display for InstallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            InstallError::Invalid(message) => f.write_str(message),
            InstallError::AlreadyInstalled => f.write_str(
                "OHTTP configuration: a different configuration is already installed; it is set once per process",
            ),
        }
    }
}

/// The configuration of one network as the app passed it: all four values, or none (`None`,
/// OHTTP off for that network).
pub(crate) type NetworkValues = Option<[String; 4]>;

/// Checks the values of both networks and installs them, once per process.
///
/// Each network has all four values or none, every value is checked by `zodl-ohttp-client`'s
/// rule for it, and the two networks share no value (ignoring ASCII case). Values are taken as
/// given; the Kotlin API trims them and lower-cases the host names before they get here.
/// Installing a configuration equal to the installed one succeeds and changes nothing.
pub(crate) fn install(mainnet: NetworkValues, testnet: NetworkValues) -> Result<(), InstallError> {
    let networks = networks_from(mainnet, testnet).map_err(InstallError::Invalid)?;
    let mut installed = INSTALLED.write().unwrap_or_else(PoisonError::into_inner);
    match installed.as_ref() {
        None => {
            *installed = Some(Arc::new(networks));
            Ok(())
        }
        Some(current) if **current == networks => Ok(()),
        Some(_) => Err(InstallError::AlreadyInstalled),
    }
}

/// The validated configuration of both networks, or the message of the first value refused.
fn networks_from(mainnet: NetworkValues, testnet: NetworkValues) -> Result<OhttpNetworks, String> {
    let mainnet = network_config(Network::Mainnet, mainnet)?;
    let testnet = network_config(Network::Testnet, testnet)?;
    OhttpNetworks::new(mainnet, testnet).map_err(|error| error.to_string())
}

fn network_config(network: Network, values: NetworkValues) -> Result<Option<OhttpConfig>, String> {
    let Some([gateway_host, relay_url, keys_dns_name, signing_pubkey]) = values else {
        return Ok(None);
    };
    OhttpConfig::new(OhttpConfigParams {
        gateway_host: &gateway_host,
        relay_url: &relay_url,
        keys_dns_name: &keys_dns_name,
        signing_pubkey: &signing_pubkey,
    })
    .map(Some)
    .map_err(|error| format!("{}: {error}", network.as_str()))
}

/// The four values of one network from the JVM, `None` when the gateway host is null (OHTTP off
/// for the network). A null among the other three, with a gateway host set, is an empty value,
/// which [`OhttpConfig::new`] refuses by name.
fn jni_network_values(env: &mut JNIEnv, values: [&JString; 4]) -> anyhow::Result<NetworkValues> {
    let [gateway_host, relay_url, keys_dns_name, signing_pubkey] = values;
    let Some(gateway_host) = java_nullable_string_to_rust(env, gateway_host)? else {
        return Ok(None);
    };
    let relay_url = java_nullable_string_to_rust(env, relay_url)?.unwrap_or_default();
    let keys_dns_name = java_nullable_string_to_rust(env, keys_dns_name)?.unwrap_or_default();
    let signing_pubkey = java_nullable_string_to_rust(env, signing_pubkey)?.unwrap_or_default();
    Ok(Some([
        gateway_host,
        relay_url,
        keys_dns_name,
        signing_pubkey,
    ]))
}

/// Installs the OHTTP configuration of mainnet and testnet for the process (see [`install`]). A
/// null gateway host turns OHTTP off for that network. Throws `IllegalArgumentException` when a
/// value breaks its rule (the message names the network, the field and the rule, never the
/// value) and `IllegalStateException` when a different configuration is already installed; in
/// both cases nothing changes.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_nativeConfigure<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    mainnet_gateway_host: JString<'local>,
    mainnet_relay_url: JString<'local>,
    mainnet_keys_dns_name: JString<'local>,
    mainnet_signing_pubkey: JString<'local>,
    testnet_gateway_host: JString<'local>,
    testnet_relay_url: JString<'local>,
    testnet_keys_dns_name: JString<'local>,
    testnet_signing_pubkey: JString<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let mainnet = jni_network_values(
            env,
            [
                &mainnet_gateway_host,
                &mainnet_relay_url,
                &mainnet_keys_dns_name,
                &mainnet_signing_pubkey,
            ],
        )?;
        let testnet = jni_network_values(
            env,
            [
                &testnet_gateway_host,
                &testnet_relay_url,
                &testnet_keys_dns_name,
                &testnet_signing_pubkey,
            ],
        )?;
        install(mainnet, testnet).map_err(|error| {
            let class = match error {
                InstallError::Invalid(_) => "java/lang/IllegalArgumentException",
                InstallError::AlreadyInstalled => "java/lang/IllegalStateException",
            };
            let message = error.to_string();
            // A pending exception wins over the generic one `unwrap_exc_or` would throw.
            let _ = env.throw_new(class, &message);
            anyhow!(message)
        })
    });
    unwrap_exc_or(&mut env, res, ())
}

/// Serializes the tests that install a configuration or read the installed one: it is
/// process-wide, and the test harness runs tests on parallel threads of one process. Every test
/// that touches it holds [`InstalledGuard`] for its whole body.
#[cfg(test)]
static TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Exclusive use of the installed configuration for one test; the one installed before is put
/// back when the guard drops, also after a failed test.
#[cfg(test)]
pub(crate) struct InstalledGuard {
    _lock: std::sync::MutexGuard<'static, ()>,
    previous: Option<Arc<OhttpNetworks>>,
}

#[cfg(test)]
impl Drop for InstalledGuard {
    fn drop(&mut self) {
        *INSTALLED.write().unwrap_or_else(PoisonError::into_inner) = self.previous.take();
    }
}

#[cfg(test)]
impl InstalledGuard {
    /// Replaces the installed configuration for this test, bypassing set-once.
    pub(crate) fn set(&self, networks: Option<OhttpNetworks>) {
        *INSTALLED.write().unwrap_or_else(PoisonError::into_inner) = networks.map(Arc::new);
    }
}

/// Takes the installed configuration for the calling test, starting with none installed.
#[cfg(test)]
pub(crate) fn lock_installed() -> InstalledGuard {
    let lock = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    let previous = INSTALLED
        .write()
        .unwrap_or_else(PoisonError::into_inner)
        .take();
    InstalledGuard {
        _lock: lock,
        previous,
    }
}

/// The example configuration of the tests: RFC 2606 names, and the public keys of RFC 8032 §7.1
/// TEST 1 (mainnet) and TEST 2 (testnet) as signing keys.
#[cfg(test)]
pub(crate) mod example {
    use zodl_ohttp_client::{OhttpConfig, OhttpConfigParams, OhttpNetworks};

    pub(crate) const MAINNET: OhttpConfigParams<'static> = OhttpConfigParams {
        gateway_host: "ohttp-gateway.example",
        relay_url: "https://relay-mainnet.example/relay",
        keys_dns_name: "mainnet.ohttp-keys.example",
        signing_pubkey: "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
    };

    pub(crate) const TESTNET: OhttpConfigParams<'static> = OhttpConfigParams {
        gateway_host: "ohttp-gateway-testnet.example",
        relay_url: "https://relay-testnet.example/relay",
        keys_dns_name: "testnet.ohttp-keys.example",
        signing_pubkey: "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
    };

    pub(crate) fn config(params: OhttpConfigParams<'_>) -> OhttpConfig {
        OhttpConfig::new(params).expect("valid example values")
    }

    /// Both networks on, with the example values.
    pub(crate) fn networks() -> OhttpNetworks {
        OhttpNetworks::new(Some(config(MAINNET)), Some(config(TESTNET))).expect("example networks")
    }

    /// The four values of `params`, as [`super::install`] takes them.
    pub(crate) fn values(params: OhttpConfigParams<'_>) -> super::NetworkValues {
        Some([
            params.gateway_host.to_string(),
            params.relay_url.to_string(),
            params.keys_dns_name.to_string(),
            params.signing_pubkey.to_string(),
        ])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use example::{MAINNET, TESTNET, values};

    #[test]
    fn nothing_installed_means_ohttp_off() {
        let _guard = lock_installed();
        let networks = installed_networks();
        assert!(networks.get(Network::Mainnet).is_none());
        assert!(networks.get(Network::Testnet).is_none());
        assert_eq!(networks.relay_for(MAINNET.gateway_host), None);
    }

    #[test]
    fn a_valid_configuration_is_installed_and_read_back() {
        let _guard = lock_installed();
        install(values(MAINNET), None).unwrap();
        let networks = installed_networks();
        assert_eq!(
            networks.relay_for(MAINNET.gateway_host),
            Some(MAINNET.relay_url)
        );
        assert!(networks.get(Network::Testnet).is_none());
    }

    #[test]
    fn the_configuration_is_set_once() {
        let _guard = lock_installed();
        install(values(MAINNET), values(TESTNET)).unwrap();
        // The same configuration again: accepted, nothing changes.
        install(values(MAINNET), values(TESTNET)).unwrap();
        // A different one: refused, the first stays.
        assert!(matches!(
            install(values(MAINNET), None),
            Err(InstallError::AlreadyInstalled)
        ));
        assert_eq!(
            installed_networks().relay_for(TESTNET.gateway_host),
            Some(TESTNET.relay_url)
        );
    }

    #[test]
    fn both_networks_off_is_a_configuration_too() {
        let _guard = lock_installed();
        install(None, None).unwrap();
        assert!(matches!(
            install(values(MAINNET), None),
            Err(InstallError::AlreadyInstalled)
        ));
        assert_eq!(installed_networks().relay_for(MAINNET.gateway_host), None);
    }

    #[test]
    fn an_invalid_value_is_refused_by_name_without_the_value_and_installs_nothing() {
        let _guard = lock_installed();
        let upper = Some([
            "OHTTP-GATEWAY.example".to_string(),
            MAINNET.relay_url.to_string(),
            MAINNET.keys_dns_name.to_string(),
            MAINNET.signing_pubkey.to_string(),
        ]);
        let Err(InstallError::Invalid(message)) = install(upper, None) else {
            panic!("an upper-case gateway host must be refused");
        };
        assert!(message.starts_with("mainnet: "), "{message}");
        assert!(!message.contains("OHTTP-GATEWAY"), "{message}");
        let padded = Some([
            MAINNET.gateway_host.to_string(),
            MAINNET.relay_url.to_string(),
            MAINNET.keys_dns_name.to_string(),
            format!("{}\n", MAINNET.signing_pubkey),
        ]);
        assert!(matches!(
            install(padded, None),
            Err(InstallError::Invalid(_))
        ));
        let missing = Some([
            MAINNET.gateway_host.to_string(),
            String::new(),
            MAINNET.keys_dns_name.to_string(),
            MAINNET.signing_pubkey.to_string(),
        ]);
        assert!(matches!(
            install(missing, None),
            Err(InstallError::Invalid(_))
        ));
        // Nothing was installed: a valid configuration still is.
        install(values(MAINNET), None).unwrap();
    }

    #[test]
    fn the_two_networks_must_not_share_a_value() {
        let _guard = lock_installed();
        let shared = Some([
            TESTNET.gateway_host.to_string(),
            TESTNET.relay_url.to_string(),
            TESTNET.keys_dns_name.to_string(),
            MAINNET.signing_pubkey.to_string(),
        ]);
        assert!(matches!(
            install(values(MAINNET), shared),
            Err(InstallError::Invalid(_))
        ));
        assert!(installed_networks().get(Network::Mainnet).is_none());
    }
}
