//! JNI surface of the shared Oblivious HTTP client core (`zodl-ohttp-client`), for the Android
//! preset (`ClientProfile::ANDROID`, profile id 1).
//!
//! The Kotlin transport (`OhttpTransport`) keeps the I/O, the key cache, the retry loop and the
//! relay tables; everything protocol-shaped (key selection, signed-envelope verification,
//! Binary HTTP, HPKE, outer-status classification, size caps) is done here, by the core.
//!
//! Per request there are two native calls, plus one per key fetch:
//!
//! - `verifyKeyConfig`: the signed envelope fetched from the relay, checked and reduced to the
//!   33 public bytes of the gateway key (`KeyConfig::to_bytes`).
//! - `encapsulate`: a request sealed to that key. The `ResponseOpener` (which holds the secret
//!   that opens the answer) is boxed and only an opaque `jlong` handle leaves Rust, so the secret
//!   is never copied into the JVM heap, where it could not be zeroized.
//! - `complete`: the relay's answer, classified and opened. It takes the handle **by value**
//!   (it reclaims the `Box` before anything else can fail), so the single-use rule of the core
//!   holds across the boundary. `openerFree` is the abandon path (the post itself failed).
//!
//! Every attempt must call `encapsulate` again: the gateway answers a repeated nonce with 409.
//!
//! Error text is only ever the fixed `Display` of a core `Error` variant (plus names of the
//! arguments), never request or response content.

use std::ptr;

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JString, JValue},
    sys::{JNI_FALSE, JNI_TRUE, jint, jlong, jobject, jstring},
};
use zodl_ohttp_client::{
    BhttpRequest, CONTENT_TYPE_CHUNKED_RES, ChunkedResponseOpener, ClientProfile, Error, Field,
    KEY_BYTES, KeyConfig, OuterOutcome, OuterResponse, classify_outer_response,
    encapsulate_chunked_request, pack_fields, signed_config_url, unpack_fields,
    verify_signed_config, verify_signed_config_with,
};

use crate::utils::{
    self, catch_unwind, exception::unwrap_exc_or, java_bytes_to_rust, java_nullable_string_to_rust,
    java_string_to_rust,
};

/// Raised on the JVM for every failure of the core. The `(int, String)` constructor is part of
/// the JNI contract with the Kotlin class.
const OHTTP_EXCEPTION_CLASS: &str = "cash/z/ecc/android/sdk/internal/model/JniOhttpException";
const JNI_OHTTP_KEY: &str = "cash/z/ecc/android/sdk/internal/model/JniOhttpKey";
const JNI_OHTTP_SEALED: &str = "cash/z/ecc/android/sdk/internal/model/JniOhttpSealed";
const JNI_OHTTP_OUTCOME: &str = "cash/z/ecc/android/sdk/internal/model/JniOhttpOutcome";
const JNI_OHTTP_PROFILE: &str = "cash/z/ecc/android/sdk/internal/model/JniOhttpProfile";

// What failed, as the `kind` of `JniOhttpException`. Mirrored by `RustOhttpTool.kt`.
const KIND_OTHER: jint = 0;
const KIND_KEY_CONFIG: jint = 1;
const KIND_SEAL: jint = 2;
const KIND_OPEN: jint = 3;
const KIND_NOT_A_RESPONSE: jint = 4;
const KIND_BHTTP_FORMAT: jint = 5;
const KIND_TOO_LARGE: jint = 6;
const KIND_REFUSED: jint = 7;
const KIND_MEDIA_TYPE: jint = 8;
const KIND_ARGUMENT: jint = 9;

// What `complete` found, as the `kind` of `JniOhttpOutcome`. Mirrored by `RustOhttpTool.kt`.
const OUTCOME_RESPONSE: jint = 0;
const OUTCOME_REFETCH_KEY_CONFIG: jint = 1;
const OUTCOME_REFUSED: jint = 2;
const OUTCOME_WRONG_MEDIA_TYPE: jint = 3;

/// A failure to report to the JVM.
#[derive(Debug)]
struct Failure {
    kind: jint,
    message: String,
}

impl Failure {
    fn argument(message: &str) -> Self {
        Failure {
            kind: KIND_ARGUMENT,
            message: message.to_string(),
        }
    }
}

impl From<Error> for Failure {
    fn from(error: Error) -> Self {
        let kind = match &error {
            Error::KeyConfig(_) => KIND_KEY_CONFIG,
            Error::Seal(_) => KIND_SEAL,
            Error::Open => KIND_OPEN,
            Error::NotAResponse => KIND_NOT_A_RESPONSE,
            Error::BhttpFormat(_) => KIND_BHTTP_FORMAT,
            Error::TooLarge { .. } => KIND_TOO_LARGE,
            Error::Refused { .. } => KIND_REFUSED,
            Error::MediaType(_) => KIND_MEDIA_TYPE,
            _ => KIND_OTHER,
        };
        Failure {
            kind,
            message: error.to_string(),
        }
    }
}

/// Throws the typed `JniOhttpException` for `failure` and returns the error for the generic path
/// (which does not override the typed exception once one is pending).
fn raise(env: &mut JNIEnv, failure: Failure) -> anyhow::Error {
    utils::exception::throw_object(env, OHTTP_EXCEPTION_CLASS, |env| {
        let message = env.new_string(&failure.message)?;
        env.new_object(
            OHTTP_EXCEPTION_CLASS,
            "(ILjava/lang/String;)V",
            &[JValue::Int(failure.kind), JValue::Object(&message)],
        )
    });
    anyhow!("{}", failure.message)
}

fn profile_by_id(id: jint) -> Result<ClientProfile, Failure> {
    u32::try_from(id)
        .ok()
        .and_then(ClientProfile::by_id)
        .ok_or_else(|| Failure::argument("unknown OHTTP client profile"))
}

/// The response opener behind a handle returned by `encapsulate`.
fn opener_handle(opener: ChunkedResponseOpener) -> jlong {
    Box::into_raw(Box::new(opener)).expose_provenance() as jlong
}

/// Reclaims the opener behind `handle`, or `None` for the null handle.
///
/// # Safety
///
/// `handle` must be 0 or a handle returned by [`opener_handle`] that has not been passed to this
/// function before. The Kotlin wrapper guarantees it by swapping its handle field for 0 before
/// every call (`AtomicLong.getAndSet(0)`).
unsafe fn take_opener(handle: jlong) -> Option<ChunkedResponseOpener> {
    let ptr = ptr::with_exposed_provenance_mut::<ChunkedResponseOpener>(handle as usize);
    if ptr.is_null() {
        None
    } else {
        // SAFETY: by the contract above, `ptr` came from `Box::into_raw` and is reclaimed once.
        Some(*unsafe { Box::from_raw(ptr) })
    }
}

/// A checked gateway key.
struct VerifiedKey {
    key: [u8; KEY_BYTES],
    not_after: u64,
    network: &'static str,
}

/// The gateway key in the signed envelope `json` fetched from the relay, for the gateway
/// `host`. `signer` replaces the key pinned for the network (tests only; the JNI entry point
/// always passes `None`).
fn verify_key_config_with(
    profile: &ClientProfile,
    host: &str,
    json: &[u8],
    now_secs: u64,
    signer: Option<&zodl_ohttp_client::VerifyingKey>,
) -> Result<VerifiedKey, Failure> {
    let network = profile.network_rule.network_for(host)?;
    let signed = match signer {
        None => verify_signed_config(json, network, now_secs, &profile.signed_config)?,
        Some(signer) => verify_signed_config_with(
            json,
            network.as_str(),
            signer,
            now_secs,
            &profile.signed_config,
        )?,
    };
    let key = KeyConfig::select_with(&signed.document, &profile.key_selection)?;
    Ok(VerifiedKey {
        key: key.to_bytes(),
        not_after: signed.not_after,
        network: network.as_str(),
    })
}

/// The request the Kotlin side describes, with its headers unpacked.
fn request_from_parts(
    method: Vec<u8>,
    scheme: Vec<u8>,
    authority: Vec<u8>,
    path: Vec<u8>,
    packed_headers: &[u8],
    body: Vec<u8>,
) -> Result<BhttpRequest, Failure> {
    Ok(BhttpRequest {
        method,
        scheme,
        authority,
        path,
        headers: unpack_fields(packed_headers)?,
        body,
    })
}

/// `request` sealed to `key` (33 bytes), and the handle of what opens its answer.
fn encapsulate(
    profile: &ClientProfile,
    key: &[u8],
    request: &BhttpRequest,
) -> Result<(Vec<u8>, jlong), Failure> {
    let key = KeyConfig::from_bytes(key)
        .ok_or_else(|| Failure::argument("a gateway key is 33 bytes long"))?;
    let bhttp = zodl_ohttp_client::encode_bhttp_request(request, &profile.encode)?;
    let (sealed, opener) = encapsulate_chunked_request(&key, &bhttp)?;
    Ok((sealed, opener_handle(opener)))
}

/// What `complete` found.
struct Outcome {
    kind: jint,
    status: jint,
    headers: Vec<u8>,
    trailers: Vec<u8>,
    body: Vec<u8>,
    detail: Option<String>,
}

impl Outcome {
    fn without_response(kind: jint, status: jint, detail: Option<String>) -> Self {
        Outcome {
            kind,
            status,
            headers: Vec::new(),
            trailers: Vec::new(),
            body: Vec::new(),
            detail,
        }
    }
}

/// The relay's answer (`status`, `content_type`, `body`), classified under the profile and, if
/// sealed, opened with `opener`, which is spent whatever the outcome.
fn complete(
    profile: &ClientProfile,
    opener: Option<ChunkedResponseOpener>,
    status: jint,
    content_type: Option<&str>,
    body: &[u8],
    attempt: jint,
) -> Result<Outcome, Failure> {
    let opener = opener.ok_or_else(|| Failure::argument("the response opener is already spent"))?;
    let status = u16::try_from(status).map_err(|_| Failure::argument("not an HTTP status"))?;
    let attempt =
        u32::try_from(attempt).map_err(|_| Failure::argument("a negative attempt number"))?;

    // Classify the outer response
    let outer_outcome = classify_outer_response(
        &profile.outer,
        &OuterResponse {
            status,
            content_type,
            body,
        },
        attempt,
    );

    Ok(match outer_outcome {
        OuterOutcome::Sealed => {
            // For chunked OHTTP, verify the content type is chunked-res
            if content_type != Some(CONTENT_TYPE_CHUNKED_RES) {
                return Ok(Outcome::without_response(
                    OUTCOME_WRONG_MEDIA_TYPE,
                    status.into(),
                    Some(content_type.unwrap_or("(none)").to_string()),
                ));
            }

            // Decrypt the chunked response
            let response = opener.open_response(body)?;

            // Convert headers from Vec<(String, String)> to Vec<(Vec<u8>, Vec<u8>)>
            let headers_bytes: Vec<(Vec<u8>, Vec<u8>)> = response
                .headers
                .into_iter()
                .map(|(name, value)| (name.into_bytes(), value.into_bytes()))
                .collect();

            Outcome {
                kind: OUTCOME_RESPONSE,
                status: jint::from(response.status),
                headers: pack_fields(&headers_bytes)?,
                trailers: pack_fields(&[])?, // Trailers are already appended to headers
                body: response.body,
                detail: None,
            }
        }
        OuterOutcome::RefetchKeyConfig => {
            Outcome::without_response(OUTCOME_REFETCH_KEY_CONFIG, status.into(), None)
        }
        OuterOutcome::Refused { status, detail } => {
            Outcome::without_response(OUTCOME_REFUSED, status.into(), Some(detail))
        }
        OuterOutcome::WrongMediaType { content_type } => {
            Outcome::without_response(OUTCOME_WRONG_MEDIA_TYPE, status.into(), Some(content_type))
        }
    })
}

/// What the Kotlin transport needs to know about the preset.
struct ProfileInfo {
    post_headers: Vec<u8>,
    key_fetch_headers: Vec<u8>,
    max_sealed_response: jlong,
    max_key_config: jlong,
    cache_refresh_margin_secs: jlong,
    drop_before_refetch: bool,
    trace_on_key_fetch: bool,
    body_window: jint,
}

fn profile_info(profile: &ClientProfile) -> Result<ProfileInfo, Failure> {
    let fields = |headers: Vec<(&'static str, String)>| -> Result<Vec<u8>, Failure> {
        let fields: Vec<Field> = headers
            .into_iter()
            .map(|(name, value)| (name.as_bytes().to_vec(), value.into_bytes()))
            .collect();
        Ok(pack_fields(&fields)?)
    };
    let limit =
        |limit: Option<usize>| limit.map_or(-1, |n| jlong::try_from(n).unwrap_or(jlong::MAX));
    Ok(ProfileInfo {
        post_headers: fields(profile.headers.relay_post(None))?,
        key_fetch_headers: fields(profile.headers.key_fetch(None))?,
        max_sealed_response: limit(profile.limits.max_sealed_response_bytes),
        max_key_config: limit(profile.limits.max_key_config_bytes),
        cache_refresh_margin_secs: jlong::try_from(profile.cache.refresh_margin_secs)
            .unwrap_or(jlong::MAX),
        drop_before_refetch: profile.cache.drop_before_refetch,
        trace_on_key_fetch: profile.headers.trace_on_key_fetch,
        body_window: profile
            .outer
            .body_window
            .map_or(-1, |n| jint::try_from(n).unwrap_or(jint::MAX)),
    })
}

fn jboolean_of(value: bool) -> JValue<'static, 'static> {
    JValue::Bool(if value { JNI_TRUE } else { JNI_FALSE })
}

/// Checks the signed key configuration envelope fetched from the relay and returns the gateway
/// key: `JniOhttpKey(key: ByteArray /* 33 */, notAfter: Long, network: String)`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_verifyKeyConfig<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    profile: jint,
    gateway_host: JString<'local>,
    json: JByteArray<'local>,
    now_secs: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let profile = profile_by_id(profile).map_err(|f| raise(env, f))?;
        let host = java_string_to_rust(env, &gateway_host)?;
        let json = java_bytes_to_rust(env, &json)?;
        let now_secs = u64::try_from(now_secs)
            .map_err(|_| raise(env, Failure::argument("a negative time")))?;
        let verified = verify_key_config_with(&profile, &host, &json, now_secs, None)
            .map_err(|f| raise(env, f))?;

        let key = env.byte_array_from_slice(&verified.key)?;
        let network = env.new_string(verified.network)?;
        Ok(env
            .new_object(
                JNI_OHTTP_KEY,
                "([BJLjava/lang/String;)V",
                &[
                    (&key).into(),
                    JValue::Long(jlong::try_from(verified.not_after).unwrap_or(jlong::MAX)),
                    (&network).into(),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Seals a request to the gateway key: `JniOhttpSealed(body: ByteArray, opener: Long)`. Every
/// attempt calls this again; `opener` is spent by `complete` or freed by `openerFree`.
#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_encapsulate<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    profile: jint,
    key: JByteArray<'local>,
    method: JByteArray<'local>,
    scheme: JByteArray<'local>,
    authority: JByteArray<'local>,
    path: JByteArray<'local>,
    headers: JByteArray<'local>,
    body: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let profile = profile_by_id(profile).map_err(|f| raise(env, f))?;
        let key = java_bytes_to_rust(env, &key)?;
        let request = request_from_parts(
            java_bytes_to_rust(env, &method)?,
            java_bytes_to_rust(env, &scheme)?,
            java_bytes_to_rust(env, &authority)?,
            java_bytes_to_rust(env, &path)?,
            &java_bytes_to_rust(env, &headers)?,
            java_bytes_to_rust(env, &body)?,
        )
        .map_err(|f| raise(env, f))?;
        let (sealed, handle) = encapsulate(&profile, &key, &request).map_err(|f| raise(env, f))?;

        // From here on the handle is owned by this function: free it if the result cannot be
        // built, or it would leak.
        let built = env
            .byte_array_from_slice(&sealed)
            .map_err(anyhow::Error::from)
            .and_then(|sealed| {
                env.new_object(
                    JNI_OHTTP_SEALED,
                    "([BJ)V",
                    &[(&sealed).into(), JValue::Long(handle)],
                )
                .map_err(anyhow::Error::from)
            });
        match built {
            Ok(object) => Ok(object.into_raw()),
            Err(e) => {
                drop(unsafe { take_opener(handle) });
                Err(e)
            }
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Classifies the relay's answer and opens it:
/// `JniOhttpOutcome(kind, status, headers, trailers, body, detail)`. Spends `opener` whatever
/// happens, a thrown exception included.
#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_complete<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    profile: jint,
    opener: jlong,
    outer_status: jint,
    content_type: JString<'local>,
    body: JByteArray<'local>,
    attempt: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        // Before anything else can fail: the handle is spent from here on.
        // SAFETY: the Kotlin wrapper passes each handle once (see `take_opener`).
        let opener = unsafe { take_opener(opener) };
        let profile = profile_by_id(profile).map_err(|f| raise(env, f))?;
        let content_type = java_nullable_string_to_rust(env, &content_type)?;
        let body = java_bytes_to_rust(env, &body)?;
        let outcome = complete(
            &profile,
            opener,
            outer_status,
            content_type.as_deref(),
            &body,
            attempt,
        )
        .map_err(|f| raise(env, f))?;
        drop(body);

        let headers = env.byte_array_from_slice(&outcome.headers)?;
        let trailers = env.byte_array_from_slice(&outcome.trailers)?;
        let content = env.byte_array_from_slice(&outcome.body)?;
        let detail: JObject = match &outcome.detail {
            Some(detail) => env.new_string(detail)?.into(),
            None => JObject::null(),
        };
        Ok(env
            .new_object(
                JNI_OHTTP_OUTCOME,
                "(II[B[B[BLjava/lang/String;)V",
                &[
                    JValue::Int(outcome.kind),
                    JValue::Int(outcome.status),
                    (&headers).into(),
                    (&trailers).into(),
                    (&content).into(),
                    (&detail).into(),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Frees an opener whose `complete` will never be called (the post failed). 0 is a no-op.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_openerFree<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    opener: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        // SAFETY: the Kotlin wrapper passes each handle once (see `take_opener`).
        drop(unsafe { take_opener(opener) });
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

/// The URL of the signed key configuration of the relay at `relay_url`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_signedConfigUrl<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    profile: jint,
    relay_url: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let profile = profile_by_id(profile).map_err(|f| raise(env, f))?;
        let relay_url = java_string_to_rust(env, &relay_url)?;
        let url = signed_config_url(&relay_url, profile.signed_config_url)
            .map_err(|e| raise(env, e.into()))?;
        Ok(env.new_string(url)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns the relay URL for a given gateway host, or null if the host is not a known gateway.
/// The built-in mapping can be overridden at build time via environment variables
/// `ZODL_OHTTP_RELAY_URL_MAINNET` and `ZODL_OHTTP_RELAY_URL_TESTNET`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_nativeRelayUrlFor<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    gateway_host: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let gateway_host = java_string_to_rust(env, &gateway_host)?;
        match zodl_ohttp_client::relay_for(&gateway_host) {
            Some(relay_url) => Ok(env.new_string(relay_url)?.into_raw()),
            None => Ok(ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// The preset's headers, limits and cache rules:
/// `JniOhttpProfile(postHeaders, keyFetchHeaders, maxSealedResponse, maxKeyConfig,
/// cacheRefreshMarginSecs, dropBeforeRefetch, traceOnKeyFetch, bodyWindow)`, a limit of -1
/// meaning none. The headers do not include `X-Client-Trace-Id`, which the wrapper appends.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_profileInfo<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    profile: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let profile = profile_by_id(profile).map_err(|f| raise(env, f))?;
        let info = profile_info(&profile).map_err(|f| raise(env, f))?;

        let post_headers = env.byte_array_from_slice(&info.post_headers)?;
        let key_fetch_headers = env.byte_array_from_slice(&info.key_fetch_headers)?;
        Ok(env
            .new_object(
                JNI_OHTTP_PROFILE,
                "([B[BJJJZZI)V",
                &[
                    (&post_headers).into(),
                    (&key_fetch_headers).into(),
                    JValue::Long(info.max_sealed_response),
                    JValue::Long(info.max_key_config),
                    JValue::Long(info.cache_refresh_margin_secs),
                    jboolean_of(info.drop_before_refetch),
                    jboolean_of(info.trace_on_key_fetch),
                    JValue::Int(info.body_window),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use aes_gcm::{
        Aes128Gcm, Nonce,
        aead::{Aead, KeyInit, Payload},
    };
    use base64::{Engine as _, engine::general_purpose::STANDARD};
    use ed25519_dalek::{Signer, SigningKey};
    use hkdf::Hkdf;
    use hpke::{
        Deserializable, Kem, OpModeR, Serializable, aead::AesGcm128, kdf::HkdfSha256,
        kem::X25519HkdfSha256,
    };
    use sha2::{Digest, Sha256};
    use zodl_ohttp_client::{EncodeOptions, encode_bhttp_request};

    use super::*;

    const TESTNET_HOST: &str = "ohttp-gateway-testnet.example";
    const NOW: u64 = 1_800_000_000;

    fn android() -> ClientProfile {
        profile_by_id(1).unwrap()
    }

    fn signer() -> SigningKey {
        SigningKey::from_bytes(&[2; 32])
    }

    type GatewayKey = <X25519HkdfSha256 as Kem>::PrivateKey;

    fn gateway_keys() -> (GatewayKey, Vec<u8>) {
        let (sk, pk) = X25519HkdfSha256::derive_keypair(&[7; 32]);
        (sk, pk.to_bytes().to_vec())
    }

    /// An `application/ohttp-keys` document: a configuration of a KEM this client does not
    /// speak, then the gateway's X25519 one.
    fn key_document() -> Vec<u8> {
        let mut other = vec![9u8, 0x00, 0x10];
        other.extend_from_slice(&[0; 20]);
        let mut ours = vec![1u8, 0x00, 0x20];
        ours.extend_from_slice(&gateway_keys().1);
        ours.extend_from_slice(&[0, 4, 0, 1, 0, 1]);
        let mut document = Vec::new();
        for entry in [other, ours] {
            document.extend_from_slice(&(entry.len() as u16).to_be_bytes());
            document.extend(entry);
        }
        document
    }

    fn envelope(signer: &SigningKey, network: &str, not_before: u64, not_after: u64) -> Vec<u8> {
        let configs = key_document();
        let mut message = b"zodl-ohttp-configs-v1".to_vec();
        message.push(0);
        message.extend_from_slice(network.as_bytes());
        message.push(0);
        message.extend_from_slice(&not_before.to_be_bytes());
        message.extend_from_slice(&not_after.to_be_bytes());
        message.extend_from_slice(&configs);
        let signature = signer.sign(&message).to_bytes();
        let key_id = hex::encode(&Sha256::digest(signer.verifying_key().as_bytes())[..4]);
        format!(
            r#"{{"v":1,"network":"{network}","configs":"{}","not_before":{not_before},"not_after":{not_after},"key_id":"{key_id}","sig":"{}"}}"#,
            STANDARD.encode(&configs),
            STANDARD.encode(signature),
        )
        .into_bytes()
    }

    fn verified_key() -> VerifiedKey {
        verify_key_config_with(
            &android(),
            TESTNET_HOST,
            &envelope(&signer(), "testnet", NOW - 3600, NOW + 3600),
            NOW,
            Some(&signer().verifying_key()),
        )
        .unwrap()
    }

    fn request() -> BhttpRequest {
        BhttpRequest::new(
            "POST",
            "ohttp-gateway-testnet.example",
            "/cash.z.wallet.sdk.rpc.CompactTxStreamer/GetLightdInfo",
        )
        .header("Content-Type", "application/grpc")
        .header("TE", "trailers")
        .body(vec![0, 0, 0, 0, 0])
    }

    /// What a gateway does with a sealed chunked request: the Binary HTTP message inside it, the
    /// secret it exports for the answer and the encapsulated key.
    fn gateway_opens(sealed: &[u8]) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
        let (header, rest) = sealed.split_at(7);
        let (enc, mut rest) = rest.split_at(32);
        let mut info = b"message/bhttp chunked request".to_vec();
        info.push(0);
        info.extend_from_slice(header);
        let enc_key = <X25519HkdfSha256 as Kem>::EncappedKey::from_bytes(enc).unwrap();
        let mut context = hpke::setup_receiver::<AesGcm128, HkdfSha256, X25519HkdfSha256>(
            &OpModeR::Base,
            &gateway_keys().0,
            &enc_key,
            &info,
        )
        .unwrap();

        // Export the secret for sealing responses (before opening anything)
        let mut secret = [0u8; 16];
        context
            .export(b"message/bhttp chunked response", &mut secret)
            .unwrap();

        // Read and decrypt the first chunk (non-final)
        let (chunk_len, tail) = read_varint_helper(rest);
        rest = tail;
        let (sealed_chunk, tail) = rest.split_at(chunk_len as usize);
        rest = tail;
        let mut plain = context.open(sealed_chunk, b"").unwrap();

        // Read and decrypt the final chunk (empty)
        let (final_len, tail) = read_varint_helper(rest);
        assert_eq!(final_len, 0);
        let sealed_final = tail;
        let final_plain = context.open(sealed_final, b"final").unwrap();
        plain.extend_from_slice(&final_plain);

        (plain, secret.to_vec(), enc.to_vec())
    }

    /// Simple varint reader for tests (no error handling).
    fn read_varint_helper(data: &[u8]) -> (usize, &[u8]) {
        let mut value = 0u64;
        let mut shift = 0;
        for (i, &byte) in data.iter().enumerate() {
            value |= ((byte & 0x7f) as u64) << shift;
            if byte & 0x80 == 0 {
                return (value as usize, &data[i + 1..]);
            }
            shift += 7;
        }
        panic!("Invalid varint");
    }

    /// Write a varint for tests.
    fn write_varint_helper(value: u64) -> Vec<u8> {
        let mut out = Vec::new();
        let mut v = value;
        loop {
            let mut byte = (v & 0x7f) as u8;
            v >>= 7;
            if v != 0 {
                byte |= 0x80;
            }
            out.push(byte);
            if v == 0 {
                break;
            }
        }
        out
    }

    /// What a gateway does to seal a chunked answer (draft-ietf-ohai-chunked-ohttp-08 §6.2).
    fn gateway_seals(bhttp: &[u8], enc: &[u8], secret: &[u8]) -> Vec<u8> {
        let response_nonce = [9u8; 16];
        let mut salt = enc.to_vec();
        salt.extend_from_slice(&response_nonce);
        let hkdf = Hkdf::<Sha256>::new(Some(&salt), secret);
        let mut aead_key = [0u8; 16];
        let mut aead_nonce_base = [0u8; 12];
        hkdf.expand(b"key", &mut aead_key).unwrap();
        hkdf.expand(b"nonce", &mut aead_nonce_base).unwrap();

        let cipher = Aes128Gcm::new_from_slice(&aead_key).unwrap();

        // Seal the first chunk (non-final, counter 0)
        let mut nonce = aead_nonce_base;
        // XOR counter 0 (no change)
        let sealed_chunk = cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: bhttp,
                    aad: b"",
                },
            )
            .unwrap();

        // Seal the final chunk (empty, with "final" AAD, counter 1)
        for i in (0..8).rev() {
            nonce[11 - i] ^= ((1u64 >> (i * 8)) & 0xff) as u8;
        }
        let sealed_final = cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &[],
                    aad: b"final",
                },
            )
            .unwrap();

        // Build the response: response_nonce || varint(len) || sealed_chunk || varint(0) || sealed_final
        let mut out = response_nonce.to_vec();
        out.extend_from_slice(&write_varint_helper(sealed_chunk.len() as u64));
        out.extend_from_slice(&sealed_chunk);
        out.extend_from_slice(&write_varint_helper(0));
        out.extend_from_slice(&sealed_final);
        out
    }

    /// A known-length Binary HTTP response: 200, `grpc-status: 0` in the header section, the
    /// content 1, 2, 3 and no trailers.
    fn grpc_answer() -> Vec<u8> {
        let mut fields = Vec::new();
        for part in [&b"grpc-status"[..], b"0"] {
            fields.push(part.len() as u8);
            fields.extend_from_slice(part);
        }
        let mut response = vec![1u8, 0x40, 200, fields.len() as u8];
        response.extend(fields);
        response.extend_from_slice(&[3, 1, 2, 3, 0]);
        response
    }

    #[test]
    fn the_profile_is_the_android_preset() {
        let info = profile_info(&android()).unwrap();
        assert_eq!(
            unpack_fields(&info.post_headers).unwrap(),
            vec![
                (
                    b"Content-Type".to_vec(),
                    b"message/ohttp-chunked-req".to_vec()
                ),
                (b"Accept".to_vec(), b"message/ohttp-chunked-res".to_vec()),
                (b"Accept-Encoding".to_vec(), b"identity".to_vec()),
            ]
        );
        assert_eq!(
            unpack_fields(&info.key_fetch_headers).unwrap(),
            vec![(b"Accept".to_vec(), b"application/json".to_vec())]
        );
        assert_eq!(info.max_sealed_response, 268_435_456 + 65_536);
        assert_eq!(info.max_key_config, 65_536);
        assert_eq!(info.cache_refresh_margin_secs, 0);
        assert!(info.drop_before_refetch);
        assert!(!info.trace_on_key_fetch);
        assert_eq!(info.body_window, 200);
        assert!(profile_by_id(0).is_err());
        assert!(profile_by_id(-1).is_err());
    }

    #[test]
    fn a_signed_envelope_yields_the_key_and_skips_other_kems() {
        let key = verified_key();
        assert_eq!(key.network, "testnet");
        assert_eq!(key.not_after, NOW + 3600);
        assert_eq!(key.key[0], 1);
        assert_eq!(key.key[1..], gateway_keys().1[..]);
    }

    #[test]
    fn a_bad_envelope_is_a_key_configuration_failure() {
        let good = envelope(&signer(), "testnet", NOW - 3600, NOW + 3600);
        let attempts: [(&str, Vec<u8>, SigningKey); 5] = [
            // Signed by somebody else.
            (
                TESTNET_HOST,
                envelope(
                    &SigningKey::from_bytes(&[3; 32]),
                    "testnet",
                    NOW - 3600,
                    NOW + 3600,
                ),
                signer(),
            ),
            // Expired.
            (
                TESTNET_HOST,
                envelope(&signer(), "testnet", NOW - 7200, NOW - 3600),
                signer(),
            ),
            // Signed for the other network.
            (
                TESTNET_HOST,
                envelope(&signer(), "mainnet", NOW - 3600, NOW + 3600),
                signer(),
            ),
            // Not JSON.
            (TESTNET_HOST, b"not json".to_vec(), signer()),
            // A gateway host this client has no network for.
            ("example.org", good.clone(), signer()),
        ];
        for (host, json, trusted) in attempts {
            let failure = verify_key_config_with(
                &android(),
                host,
                &json,
                NOW,
                Some(&trusted.verifying_key()),
            )
            .err()
            .unwrap();
            assert_eq!(failure.kind, KIND_KEY_CONFIG, "{}", failure.message);
        }
    }

    #[test]
    fn a_request_is_sealed_afresh_for_every_attempt() {
        let key = verified_key().key;
        let (first, first_handle) = encapsulate(&android(), &key, &request()).unwrap();
        let (second, second_handle) = encapsulate(&android(), &key, &request()).unwrap();
        assert_ne!(first, second);
        assert_ne!(first_handle, 0);
        assert_ne!(first_handle, second_handle);
        drop(unsafe { take_opener(first_handle) });
        drop(unsafe { take_opener(second_handle) });
    }

    #[test]
    fn a_wrong_sized_key_is_an_argument_error() {
        let failure = encapsulate(&android(), &[1; 32], &request()).err().unwrap();
        assert_eq!(failure.kind, KIND_ARGUMENT);
    }

    #[test]
    fn a_sealed_request_opens_at_the_gateway_and_its_answer_completes() {
        let key = verified_key().key;
        let (sealed, handle) = encapsulate(&android(), &key, &request()).unwrap();
        assert_eq!(&sealed[..7], [1, 0, 0x20, 0, 1, 0, 1]);

        let (plain, secret, enc) = gateway_opens(&sealed);
        let mut lowercased = request();
        lowercased.headers = vec![
            (b"content-type".to_vec(), b"application/grpc".to_vec()),
            (b"te".to_vec(), b"trailers".to_vec()),
        ];
        assert_eq!(
            plain,
            encode_bhttp_request(&lowercased, &EncodeOptions::PRESERVE).unwrap()
        );

        let answer = gateway_seals(&grpc_answer(), &enc, &secret);
        let outcome = complete(
            &android(),
            unsafe { take_opener(handle) },
            200,
            Some(CONTENT_TYPE_CHUNKED_RES),
            &answer,
            0,
        )
        .unwrap();
        assert_eq!(outcome.kind, OUTCOME_RESPONSE);
        assert_eq!(outcome.status, 200);
        assert_eq!(
            unpack_fields(&outcome.headers).unwrap(),
            vec![(b"grpc-status".to_vec(), b"0".to_vec())]
        );
        assert!(unpack_fields(&outcome.trailers).unwrap().is_empty());
        assert_eq!(outcome.body, [1, 2, 3]);
        assert_eq!(outcome.detail, None);
    }

    fn sealed_handle() -> jlong {
        encapsulate(&android(), &verified_key().key, &request())
            .unwrap()
            .1
    }

    #[test]
    fn an_outer_400_or_422_refetches_once_and_is_then_final() {
        for status in [400, 422] {
            let outcome = complete(
                &android(),
                unsafe { take_opener(sealed_handle()) },
                status,
                None,
                &[],
                0,
            )
            .unwrap();
            assert_eq!(outcome.kind, OUTCOME_REFETCH_KEY_CONFIG);
            assert_eq!(outcome.status, status);

            let outcome = complete(
                &android(),
                unsafe { take_opener(sealed_handle()) },
                status,
                None,
                b"problem",
                1,
            )
            .unwrap();
            assert_eq!(outcome.kind, OUTCOME_REFUSED);
            assert_eq!(outcome.status, status);
            assert_eq!(outcome.detail.as_deref(), Some("problem"));
        }
    }

    #[test]
    fn other_outer_statuses_are_refused_with_a_bounded_detail() {
        let body = vec![b'x'; 500];
        let outcome = complete(
            &android(),
            unsafe { take_opener(sealed_handle()) },
            503,
            None,
            &body,
            0,
        )
        .unwrap();
        assert_eq!(outcome.kind, OUTCOME_REFUSED);
        assert_eq!(outcome.status, 503);
        assert_eq!(outcome.detail.as_deref().map(str::len), Some(200));
    }

    #[test]
    fn a_tampered_answer_does_not_open_and_still_spends_the_opener() {
        let key = verified_key().key;
        let (sealed, handle) = encapsulate(&android(), &key, &request()).unwrap();
        let (_, secret, enc) = gateway_opens(&sealed);
        let mut answer = gateway_seals(&grpc_answer(), &enc, &secret);
        let last = answer.len() - 1;
        answer[last] ^= 1;
        let failure = complete(
            &android(),
            unsafe { take_opener(handle) },
            200,
            Some(CONTENT_TYPE_CHUNKED_RES),
            &answer,
            0,
        )
        .err()
        .unwrap();
        assert_eq!(failure.kind, KIND_OPEN);
    }

    #[test]
    fn a_spent_or_null_handle_is_an_argument_error_and_freeing_it_is_a_no_op() {
        let failure = complete(&android(), None, 200, None, &[], 0).err().unwrap();
        assert_eq!(failure.kind, KIND_ARGUMENT);
        assert!(unsafe { take_opener(0) }.is_none());
        drop(unsafe { take_opener(0) });
    }

    #[test]
    fn a_request_with_a_torn_header_list_is_a_binary_http_failure() {
        let failure = request_from_parts(
            b"GET".to_vec(),
            b"https".to_vec(),
            b"a.example".to_vec(),
            b"/".to_vec(),
            &[0, 0, 0, 9, 1],
            Vec::new(),
        )
        .err()
        .unwrap();
        assert_eq!(failure.kind, KIND_BHTTP_FORMAT);
    }

    #[test]
    fn the_signed_config_url_follows_the_android_rule() {
        let rule = android().signed_config_url;
        assert_eq!(
            signed_config_url("https://relay.example/relay/", rule).unwrap(),
            "https://relay.example/ohttp-configs/signed"
        );
        assert_eq!(
            signed_config_url("https://relay.example", rule).unwrap(),
            "https://relay.example/ohttp-configs/signed"
        );
    }
}
