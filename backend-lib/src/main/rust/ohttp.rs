//! JNI surface of the shared Oblivious HTTP client core (`zodl-ohttp-client`), for the Android
//! preset (`ClientProfile::ANDROID`, profile id 1).
//!
//! The Kotlin transport (`OhttpTransport`) keeps the I/O, the key cache, the retry loop and the
//! relay tables; everything protocol-shaped (key selection, signed-envelope verification,
//! Binary HTTP, HPKE, outer-status classification, size caps) is done here, by the core.
//!
//! Per request there are two native calls (a streamed answer: one more per read), plus one per
//! key fetch:
//!
//! - `verifyKeyConfig`: the signed envelope fetched from the relay, checked and reduced to the
//!   33 public bytes of the gateway key (`KeyConfig::to_bytes`).
//! - `encapsulate`: a request sealed to that key. The `ChunkedResponseOpener` (which holds the
//!   secret that opens the answer) is boxed and only an opaque `jlong` handle leaves Rust, so the
//!   secret is never copied into the JVM heap, where it could not be zeroized.
//! - `complete`: the relay's answer, classified and opened. It takes the handle **by value**
//!   (it reclaims the `Box` before anything else can fail), so the single-use rule of the core
//!   holds across the boundary. `openerFree` is the abandon path (the post itself failed).
//! - `streamOpen`, `streamPush`, `streamFinish`, `streamFree`: the same answer opened as it is
//!   read (`ChunkedResponseDecoder`). `streamOpen` spends the opener like `complete` and returns
//!   the handle of a stream; every chunk is opened as soon as it is complete and the head, body
//!   bytes and trailers it carried come back at once, so a server-streaming call delivers its
//!   first message long before the response ends.
//!
//! Every attempt must call `encapsulate` again: the gateway answers a repeated nonce with 409.
//!
//! Error text is only ever the fixed `Display` of a core `Error` variant (plus names of the
//! arguments), never request or response content.

use std::ptr;
use std::time::Duration;

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JString, JValue},
    sys::{JNI_FALSE, JNI_TRUE, jint, jlong, jobject, jstring},
};
use zodl_ohttp_client::{
    BhttpRequest, ChunkedResponseDecoder, ChunkedResponseOpener, ClientProfile, Error, Field,
    KEY_BYTES, KeyConfig, OuterOutcome, OuterResponse, ResponseEvent, SizeGuard, check_size,
    classify_outer_response, encapsulate_chunked_request, pack_fields, signed_config_url,
    unpack_fields, verify_signed_config, verify_signed_config_with,
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
const JNI_OHTTP_STREAM_EVENTS: &str = "cash/z/ecc/android/sdk/internal/model/JniOhttpStreamEvents";

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
    // The gateway and the relay answer 413 above the cap: refuse here, before the HPKE work
    // on the plaintext (the sealed body is longer still) and exactly on the sealed body.
    check_size(bhttp.len(), profile.limits.max_sealed_request_bytes)?;
    let (sealed, opener) = encapsulate_chunked_request(&key, &bhttp)?;
    check_size(sealed.len(), profile.limits.max_sealed_request_bytes)?;
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
            // Whether a 200 without the chunked media type is still opened is the outer
            // policy's call (`OuterPolicy::ANDROID` does not require it), made above; here the
            // sealed body is opened, and one that does not open is an error.
            check_size(body.len(), profile.limits.max_sealed_response_bytes)?;
            let response = opener.open_response(body)?;

            Outcome {
                kind: OUTCOME_RESPONSE,
                status: jint::from(response.status),
                headers: packed(response.headers)?,
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

/// The sealed answer of an outer 200, opened as it is read: the decoder and the running size
/// of what has been read, against the profile's cap.
struct ResponseStream {
    decoder: ChunkedResponseDecoder,
    guard: SizeGuard,
}

/// The events one `push` or `finish` produced, gathered for the JVM: the head (at most one per
/// response), the body bytes, the trailer section and whether the response ended.
#[derive(Debug, Default)]
struct StreamEvents {
    head: Option<(u16, Vec<u8>)>,
    body: Vec<u8>,
    trailers: Option<Vec<u8>>,
    end: bool,
}

fn packed(fields: Vec<(String, String)>) -> Result<Vec<u8>, Failure> {
    let fields: Vec<Field> = fields
        .into_iter()
        .map(|(name, value)| (name.into_bytes(), value.into_bytes()))
        .collect();
    Ok(pack_fields(&fields)?)
}

fn gather(events: Vec<ResponseEvent>) -> Result<StreamEvents, Failure> {
    let mut gathered = StreamEvents::default();
    for event in events {
        match event {
            ResponseEvent::Head { status, headers } => {
                gathered.head = Some((status, packed(headers)?));
            }
            ResponseEvent::Data(data) => gathered.body.extend_from_slice(&data),
            ResponseEvent::Trailers(trailers) => gathered.trailers = Some(packed(trailers)?),
            ResponseEvent::End => gathered.end = true,
        }
    }
    Ok(gathered)
}

/// Starts opening the relay's answer as it arrives. The outer response is classified first
/// (`status`, `content_type`; the body is not read yet), and `opener` is spent whatever happens.
fn open_stream(
    profile: &ClientProfile,
    opener: Option<ChunkedResponseOpener>,
    status: jint,
    content_type: Option<&str>,
    attempt: jint,
) -> Result<ResponseStream, Failure> {
    let opener = opener.ok_or_else(|| Failure::argument("the response opener is already spent"))?;
    let status = u16::try_from(status).map_err(|_| Failure::argument("not an HTTP status"))?;
    let attempt =
        u32::try_from(attempt).map_err(|_| Failure::argument("a negative attempt number"))?;
    let outcome = classify_outer_response(
        &profile.outer,
        &OuterResponse {
            status,
            content_type,
            body: &[],
        },
        attempt,
    );
    match outcome {
        OuterOutcome::Sealed => Ok(ResponseStream {
            decoder: opener.decoder(),
            guard: profile.limits.response_guard(),
        }),
        OuterOutcome::WrongMediaType { .. } => Err(Failure {
            kind: KIND_MEDIA_TYPE,
            message: "the relay's answer is not a sealed response".to_string(),
        }),
        OuterOutcome::RefetchKeyConfig | OuterOutcome::Refused { .. } => Err(Failure {
            kind: KIND_REFUSED,
            message: "the relay refused the request".to_string(),
        }),
    }
}

impl ResponseStream {
    /// More sealed bytes: counted against the cap before they are buffered, then opened as far
    /// as they complete chunks.
    fn push(&mut self, bytes: &[u8]) -> Result<StreamEvents, Failure> {
        self.guard.push(bytes.len())?;
        gather(self.decoder.push(bytes)?)
    }

    /// The body ended: the final chunk is opened and the message must be complete.
    fn finish(&mut self) -> Result<StreamEvents, Failure> {
        gather(self.decoder.finish()?)
    }
}

fn stream_handle(stream: ResponseStream) -> jlong {
    Box::into_raw(Box::new(stream)).expose_provenance() as jlong
}

/// The stream behind `handle`, borrowed for one call.
///
/// # Safety
///
/// `handle` must be 0 or a handle returned by [`stream_handle`] that has not been freed, and no
/// other call may use it at the same time. The Kotlin wrapper holds a lock around every call
/// and swaps its handle for 0 when it frees it.
unsafe fn borrow_stream<'a>(handle: jlong) -> Result<&'a mut ResponseStream, Failure> {
    let ptr = ptr::with_exposed_provenance_mut::<ResponseStream>(handle as usize);
    // SAFETY: by the contract above, `ptr` is null or a live `Box` used by this call alone.
    unsafe { ptr.as_mut() }.ok_or_else(|| Failure::argument("the response stream is closed"))
}

/// Frees the stream behind `handle`; 0 is a no-op.
///
/// # Safety
///
/// As for [`borrow_stream`], and `handle` is not used again.
unsafe fn free_stream(handle: jlong) {
    let ptr = ptr::with_exposed_provenance_mut::<ResponseStream>(handle as usize);
    if !ptr.is_null() {
        // SAFETY: by the contract above, `ptr` came from `Box::into_raw` and is reclaimed once.
        drop(unsafe { Box::from_raw(ptr) });
    }
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

/// Starts opening an outer 200 as it is read; returns the handle of the stream, which
/// `streamPush` and `streamFinish` use and `streamFree` frees. Spends `opener` whatever happens.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_streamOpen<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    profile: jint,
    opener: jlong,
    outer_status: jint,
    content_type: JString<'local>,
    attempt: jint,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        // SAFETY: the Kotlin wrapper passes each handle once (see `take_opener`).
        let opener = unsafe { take_opener(opener) };
        let profile = profile_by_id(profile).map_err(|f| raise(env, f))?;
        let content_type = java_nullable_string_to_rust(env, &content_type)?;
        let stream = open_stream(
            &profile,
            opener,
            outer_status,
            content_type.as_deref(),
            attempt,
        )
        .map_err(|f| raise(env, f))?;
        Ok(stream_handle(stream))
    });
    unwrap_exc_or(&mut env, res, 0)
}

fn stream_events_object(env: &mut JNIEnv, events: StreamEvents) -> anyhow::Result<jobject> {
    let (status, headers): (jint, JObject) = match &events.head {
        Some((status, headers)) => (
            jint::from(*status),
            env.byte_array_from_slice(headers)?.into(),
        ),
        None => (-1, JObject::null()),
    };
    let trailers: JObject = match &events.trailers {
        Some(trailers) => env.byte_array_from_slice(trailers)?.into(),
        None => JObject::null(),
    };
    let body = env.byte_array_from_slice(&events.body)?;
    Ok(env
        .new_object(
            JNI_OHTTP_STREAM_EVENTS,
            "(I[B[B[BZ)V",
            &[
                JValue::Int(status),
                (&headers).into(),
                (&body).into(),
                (&trailers).into(),
                jboolean_of(events.end),
            ],
        )?
        .into_raw())
}

/// More of the sealed body: `JniOhttpStreamEvents(status, headers, body, trailers, end)` for
/// what it completed (`status` -1 and `headers` null until the head has been read).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_streamPush<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    stream: jlong,
    bytes: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let bytes = java_bytes_to_rust(env, &bytes)?;
        // SAFETY: the Kotlin wrapper serialises the calls on a handle (see `borrow_stream`).
        let events = unsafe { borrow_stream(stream) }
            .and_then(|stream| stream.push(&bytes))
            .map_err(|f| raise(env, f))?;
        stream_events_object(env, events)
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// The sealed body ended: the events of the final chunk, `end` true, or the failure of an
/// incomplete or unauthenticated response.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_streamFinish<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    stream: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        // SAFETY: the Kotlin wrapper serialises the calls on a handle (see `borrow_stream`).
        let events = unsafe { borrow_stream(stream) }
            .and_then(|stream| stream.finish())
            .map_err(|f| raise(env, f))?;
        stream_events_object(env, events)
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Frees a stream (finished, failed or abandoned). 0 is a no-op.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_streamFree<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    stream: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        // SAFETY: the Kotlin wrapper frees each handle once, outside any other call on it.
        unsafe { free_stream(stream) };
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

/// The OHTTP client trace prefix a caller asked for, as this library honours it: only a build
/// with the `ohttp-trace` feature (QA and E2E, never a release library) sends trace ids at all.
#[cfg(feature = "ohttp-trace")]
pub(crate) fn gated_trace_prefix(prefix: Option<String>) -> Option<String> {
    prefix
}

/// The OHTTP client trace prefix a caller asked for, as this library honours it: only a build
/// with the `ohttp-trace` feature (QA and E2E, never a release library) sends trace ids at all.
#[cfg(not(feature = "ohttp-trace"))]
pub(crate) fn gated_trace_prefix(_prefix: Option<String>) -> Option<String> {
    None
}

/// Records that the relay at `relay_url` asked for `delay_ms` before the next request (an outer
/// 429's `Retry-After`), in the process-wide table the Slipstream engine's OHTTP transport reads
/// too. A shorter pause never cuts a running one short.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_nativeRelayBackoffNote<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    relay_url: JString<'local>,
    delay_ms: jlong,
) {
    let res = catch_unwind(&mut env, |env| {
        let relay_url = java_string_to_rust(env, &relay_url)?;
        let delay = Duration::from_millis(u64::try_from(delay_ms).unwrap_or(0));
        zodl_ohttp_client::RelayBackoff::global().note(&relay_url, delay);
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

/// What is left, in milliseconds, of the pause the relay at `relay_url` asked for (from either
/// transport); 0 when a request may be sent now.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_nativeRelayBackoffRemainingMs<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    relay_url: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let relay_url = java_string_to_rust(env, &relay_url)?;
        Ok(zodl_ohttp_client::RelayBackoff::global()
            .remaining(&relay_url)
            .map_or(0, |left| {
                jlong::try_from(left.as_millis()).unwrap_or(jlong::MAX)
            }))
    });
    unwrap_exc_or(&mut env, res, 0)
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
    use base64::{Engine as _, engine::general_purpose::STANDARD};
    use bytes::Bytes;
    use ed25519_dalek::{Signer, SigningKey};
    use futures_util::StreamExt;
    use ohttp_gateway::{
        chunked_stream::{ChunkSealer, UpFrame, encode_response_stream},
        config::Config,
        hpke::{KeyState, ResponseContext},
        ohttp_chunked::decapsulate_chunked_request,
    };
    use sha2::{Digest, Sha256};
    use std::{collections::HashMap, time::Duration};
    use zodl_ohttp_client::{CONTENT_TYPE_CHUNKED_RES, EncodeOptions, encode_bhttp_request};

    use super::*;

    const TESTNET_HOST: &str = "ohttp-gateway-testnet.example";
    const NOW: u64 = 1_800_000_000;

    fn android() -> ClientProfile {
        profile_by_id(1).unwrap()
    }

    fn signer() -> SigningKey {
        SigningKey::from_bytes(&[2; 32])
    }

    /// The gateway's configuration, as its own tests build it.
    fn gateway_config() -> Config {
        // Use the same seed as the old test: [7; 32]
        let private_key =
            "0707070707070707070707070707070707070707070707070707070707070707".to_string();
        Config {
            port: 0,
            lwd_addr: String::new(),
            mode: "ohttp".to_string(),
            relay_secrets: vec![],
            hpke_private_key: None,
            hpke_key_id: 1,
            hpke_keys: vec![(1, private_key)],
            lwd_timeout_secs: 80,
            long_poll_idle_secs: 3,
            long_poll_max_initial_silence_secs: 25,
            sse_keepalive_secs: 20,
            redis_url: String::new(),
            redis_security_url: String::new(),
            backends: HashMap::new(),
            backend_headers: HashMap::new(),
            backends_policy: HashMap::new(),
            backends_policy_default_allow: false,
            max_inflight_lwd: 128,
            redis_op_timeout_ms: 250,
            max_cache_get_concurrency: 64,
            max_large_response_concurrency: 2,
            large_response_threshold: 8 * 1024 * 1024,
            max_upstream_response_bytes: 64 * 1024 * 1024,
            max_inflight_upstream: 256,
            max_inflight_per_backend: 64,
            max_block_range: 1000,
            allow_unauthenticated_relay: false,
            proxy_allow_suffixes: vec![],
            network: "testnet".to_string(),
            log_outer_header_names: false,
            gateway_hosts: vec![],
            configs_signing_seed: None,
            chunk_size: 16384,
            chunk_flush_ms: 50,
            flexa_publishable_key: None,
        }
    }

    fn gateway_key_state() -> KeyState {
        KeyState::new(&gateway_config()).unwrap()
    }

    /// The gateway's `application/ohttp-keys` document: one configuration,
    /// `length(2) || key_id(1) || kem_id(2) || public_key(32) || suites_len(2) || suites`.
    fn gateway_public_config() -> Vec<u8> {
        gateway_key_state().public_config()
    }

    /// The gateway's X25519 public key, as `KeyConfig::to_bytes` keeps it after the key id.
    fn gateway_public_key() -> [u8; 32] {
        gateway_public_config()[5..37].try_into().unwrap()
    }

    /// An `application/ohttp-keys` document: a configuration of a KEM this client does not
    /// speak (which `KeySelection::ANDROID` skips unread), then the gateway's X25519 one.
    fn key_document() -> Vec<u8> {
        let mut other = vec![9u8, 0x00, 0x10];
        other.extend_from_slice(&[0; 20]);
        let mut document = (other.len() as u16).to_be_bytes().to_vec();
        document.extend(other);
        document.extend(gateway_public_config());
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

    /// What a gateway does with a sealed chunked request: the Binary HTTP message inside it and
    /// the response context for sealing the answer.
    fn gateway_opens(sealed: &[u8]) -> (Vec<u8>, ohttp_gateway::hpke::ResponseContext) {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let key_state = gateway_key_state();
            decapsulate_chunked_request(sealed, &key_state)
                .await
                .unwrap()
        })
    }

    /// What a gateway does to seal a chunked answer (draft-ietf-ohai-chunked-ohttp-08 §6.2).
    fn gateway_seals(bhttp: &[u8], response_ctx: &ohttp_gateway::hpke::ResponseContext) -> Vec<u8> {
        let response_nonce = [9u8; 16];
        let mut sealer = ChunkSealer::new(response_ctx, &response_nonce).unwrap();
        let mut out = response_nonce.to_vec();
        // Seal the entire bhttp message as the first (non-final) chunk
        out.extend_from_slice(&sealer.seal(bhttp, false).unwrap());
        // Seal an empty final chunk
        out.extend_from_slice(&sealer.seal(&[], true).unwrap());
        out
    }

    /// What the gateway really sends for a plain HTTP backend (cmc, near, voting): its own
    /// `encode_response_stream` over the backend's `status`, `headers` and `body` (one upstream
    /// frame, no trailers), in `chunk_size` chunks, the last one zero-padded to `chunk_size`.
    fn gateway_streams(
        response_ctx: ResponseContext,
        status: u16,
        headers: &[(&str, &str)],
        body: &[u8],
        chunk_size: usize,
    ) -> Vec<u8> {
        let headers = headers
            .iter()
            .map(|(name, value)| (name.to_string(), value.to_string()))
            .collect();
        let upstream = futures_util::stream::iter([Ok::<UpFrame, anyhow::Error>(UpFrame::Data(
            Bytes::copy_from_slice(body),
        ))]);
        let rt = tokio::runtime::Runtime::new().unwrap();
        let frames: Vec<Bytes> = rt.block_on(
            encode_response_stream(
                response_ctx,
                status,
                headers,
                upstream,
                chunk_size,
                Duration::from_millis(50),
                "testnet".to_string(),
            )
            .map(|frame| frame.unwrap())
            .collect(),
        );
        frames
            .iter()
            .flat_map(|frame| frame.iter().copied())
            .collect()
    }

    /// An indeterminate-length Binary HTTP response (RFC 9292 §3.2, what the gateway's
    /// `encode_response_stream` writes): framing indicator 3, status 200, `grpc-status: 0` in
    /// the header section, the content 1, 2, 3 and no trailers. Field sections and the content
    /// are not length-prefixed but terminated by a zero varint.
    fn grpc_answer() -> Vec<u8> {
        let mut response = vec![3u8, 0x40, 200];
        for part in [&b"grpc-status"[..], b"0"] {
            response.push(part.len() as u8);
            response.extend_from_slice(part);
        }
        response.push(0); // end of the header section
        response.extend_from_slice(&[3, 1, 2, 3]); // one content chunk
        response.push(0); // end of the content
        response.push(0); // an empty trailer section
        response
    }

    /// Without the `ohttp-trace` feature (every release library) a trace prefix is never honoured,
    /// whatever the caller passes.
    #[cfg(not(feature = "ohttp-trace"))]
    #[test]
    fn a_trace_prefix_is_ignored_without_the_ohttp_trace_feature() {
        assert_eq!(
            super::gated_trace_prefix(Some("qa-wallet".to_string())),
            None
        );
        assert_eq!(super::gated_trace_prefix(None), None);
    }

    #[cfg(feature = "ohttp-trace")]
    #[test]
    fn a_trace_prefix_is_honoured_with_the_ohttp_trace_feature() {
        assert_eq!(
            super::gated_trace_prefix(Some("qa-wallet".to_string())).as_deref(),
            Some("qa-wallet")
        );
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
        assert_eq!(key.key[1..], gateway_public_key()[..]);
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

    /// The gateway and the relay answer 413 above 10 MiB (`Limits::WALLET`): the request is
    /// refused here instead, which `RustOhttpToolTest` relies on.
    #[test]
    fn a_request_over_the_cap_is_too_large_before_it_is_sealed() {
        let key = verified_key().key;
        let too_large = request().body(vec![0; 10 * 1024 * 1024]);
        let failure = encapsulate(&android(), &key, &too_large).err().unwrap();
        assert_eq!(failure.kind, KIND_TOO_LARGE);
        assert_eq!(
            android().limits.max_sealed_request_bytes,
            Some(10 * 1024 * 1024)
        );
    }

    /// `OuterPolicy::ANDROID` does not require the response media type: a 200 is opened
    /// whatever its `Content-Type` says, and one that does not open is an error (not a
    /// `WrongMediaType` outcome). `RustOhttpToolTest` relies on this too.
    #[test]
    fn a_200_is_opened_whatever_its_media_type_and_garbage_does_not_open() {
        for content_type in [None, Some("text/plain"), Some(CONTENT_TYPE_CHUNKED_RES)] {
            let failure = complete(
                &android(),
                unsafe { take_opener(sealed_handle()) },
                200,
                content_type,
                &[0; 64],
                0,
            )
            .err()
            .unwrap();
            assert_eq!(failure.kind, KIND_OPEN, "{content_type:?}");
        }
    }

    #[test]
    fn a_sealed_request_opens_at_the_gateway_and_its_answer_completes() {
        let key = verified_key().key;
        let (sealed, handle) = encapsulate(&android(), &key, &request()).unwrap();
        assert_eq!(&sealed[..7], [1, 0, 0x20, 0, 1, 0, 1]);

        let (plain, response_ctx) = gateway_opens(&sealed);
        // The ANDROID profile lower-cases the names and appends `ohttp-freshness` (the gateway
        // refuses requests without it once it enforces freshness): read the stamped value back.
        let name = b"ohttp-freshness";
        let at = plain
            .windows(name.len())
            .position(|w| w == name)
            .expect("freshness field written")
            + name.len();
        let len = plain[at] as usize;
        let stamp = plain[at + 1..at + 1 + len].to_vec();
        let ts: i64 = std::str::from_utf8(&stamp).unwrap().parse().unwrap();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs() as i64;
        assert!((ts - now).abs() <= 5, "timestamp {ts} vs now {now}");
        let mut lowercased = request();
        lowercased.headers = vec![
            (b"content-type".to_vec(), b"application/grpc".to_vec()),
            (b"te".to_vec(), b"trailers".to_vec()),
            (name.to_vec(), stamp),
        ];
        assert_eq!(
            plain,
            encode_bhttp_request(&lowercased, &EncodeOptions::PRESERVE).unwrap()
        );

        let answer = gateway_seals(&grpc_answer(), &response_ctx);
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

    /// The third-party path of the app (cmc, near, voting), as the Kotlin transport drives it:
    /// `OhttpTransport.exchange` passes a 200 with **no content type**
    /// (`opener.open(HTTP_OK, null, answer, attempt)`), and the gateway's answer is its real
    /// streaming encoder's output for a plain HTTP response (JSON, no trailers), spanning
    /// several chunks with a padded final one. A 404 with a text body is an answer too.
    #[test]
    fn a_plain_http_answer_of_the_gateway_completes_as_the_kotlin_transport_calls_it() {
        /// A backend answer and the gateway's chunk size: status, headers, body, chunk size.
        type Answer<'a> = (u16, &'a [(&'a str, &'a str)], &'a [u8], usize);
        let key = verified_key().key;
        let json = br#"{"data":[{"id":2781,"symbol":"USD","name":"United States Dollar"}]}"#;
        let cases: [Answer; 3] = [
            (
                200,
                &[
                    ("content-type", "application/json"),
                    ("cache-control", "max-age=60"),
                ],
                json,
                16,
            ),
            (200, &[("content-type", "application/json")], json, 16384),
            (404, &[("content-type", "text/plain")], b"not found", 16384),
        ];
        for (status, headers, body, chunk_size) in cases {
            let request = BhttpRequest::new("GET", "cmc.zodl.com", "/v1/fiat/map")
                .header("Accept", "application/json");
            let (sealed, handle) = encapsulate(&android(), &key, &request).unwrap();
            let (_, response_ctx) = gateway_opens(&sealed);
            let answer = gateway_streams(response_ctx, status, headers, body, chunk_size);

            let outcome = complete(
                &android(),
                unsafe { take_opener(handle) },
                200,
                None,
                &answer,
                0,
            )
            .unwrap();
            assert_eq!(outcome.kind, OUTCOME_RESPONSE, "{status} / {chunk_size}");
            assert_eq!(outcome.status, jint::from(status));
            let expected: Vec<Field> = headers
                .iter()
                .map(|(name, value)| (name.as_bytes().to_vec(), value.as_bytes().to_vec()))
                .collect();
            assert_eq!(unpack_fields(&outcome.headers).unwrap(), expected);
            assert!(unpack_fields(&outcome.trailers).unwrap().is_empty());
            assert_eq!(outcome.body, body);
            assert_eq!(outcome.detail, None);
        }
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
        let (_, response_ctx) = gateway_opens(&sealed);
        let mut answer = gateway_seals(&grpc_answer(), &response_ctx);
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
        // Flipping the last byte tampers with the AEAD tag of the final chunk, so
        // decapsulation fails during AEAD verification (KIND_OPEN).
        assert_eq!(failure.kind, KIND_OPEN);
    }

    #[test]
    fn a_spent_or_null_handle_is_an_argument_error_and_freeing_it_is_a_no_op() {
        let failure = complete(&android(), None, 200, None, &[], 0).err().unwrap();
        assert_eq!(failure.kind, KIND_ARGUMENT);
        assert!(unsafe { take_opener(0) }.is_none());
        drop(unsafe { take_opener(0) });
    }

    fn streamed_answer(body: &[u8]) -> (jlong, Vec<u8>) {
        let request = BhttpRequest::new("GET", "cmc.zodl.com", "/v1/fiat/map");
        let (sealed, handle) = encapsulate(&android(), &verified_key().key, &request).unwrap();
        let (_, response_ctx) = gateway_opens(&sealed);
        let answer = gateway_streams(
            response_ctx,
            200,
            &[("content-type", "application/grpc")],
            body,
            16,
        );
        (handle, answer)
    }

    fn opened_stream(handle: jlong) -> ResponseStream {
        open_stream(&android(), unsafe { take_opener(handle) }, 200, None, 0).unwrap()
    }

    #[test]
    fn a_streamed_answer_yields_its_head_and_body_before_it_ends() {
        let body: Vec<u8> = (0..200u8).collect();
        let (handle, answer) = streamed_answer(&body);
        let mut stream = opened_stream(handle);

        let (early, late) = answer.split_at(answer.len() / 2);
        let mut seen = StreamEvents::default();
        for byte in early {
            let events = stream.push(&[*byte]).unwrap();
            assert!(!events.end);
            if events.head.is_some() {
                seen.head = events.head;
            }
            seen.body.extend(events.body);
        }
        let (status, headers) = seen.head.expect("the head comes out with the first chunks");
        assert_eq!(status, 200);
        assert_eq!(
            unpack_fields(&headers).unwrap(),
            vec![(b"content-type".to_vec(), b"application/grpc".to_vec())]
        );
        assert!(!seen.body.is_empty(), "body bytes come out before the end");
        assert_eq!(seen.body, body[..seen.body.len()]);

        let rest = stream.push(late).unwrap();
        assert!(rest.head.is_none());
        seen.body.extend(rest.body);
        let last = stream.finish().unwrap();
        assert!(last.end);
        seen.body.extend(last.body);
        assert_eq!(seen.body, body);
    }

    #[test]
    fn a_streamed_answer_cut_before_its_final_chunk_is_truncated() {
        let (handle, answer) = streamed_answer(&[7; 200]);
        let mut stream = opened_stream(handle);
        let events = stream.push(&answer[..answer.len() / 2]).unwrap();
        assert!(events.head.is_some());
        let failure = stream.finish().err().unwrap();
        assert_eq!(failure.kind, KIND_OTHER);
        assert_eq!(failure.message, Error::Truncated.to_string());
    }

    /// Whole, as streamed: the core's `Error::Truncated` is `KIND_OTHER` (gRPC `UNAVAILABLE`).
    #[test]
    fn a_whole_answer_cut_before_its_final_chunk_is_truncated() {
        let (handle, answer) = streamed_answer(&[7; 200]);
        let failure = complete(
            &android(),
            unsafe { take_opener(handle) },
            200,
            Some(CONTENT_TYPE_CHUNKED_RES),
            &answer[..answer.len() / 2],
            0,
        )
        .err()
        .unwrap();
        assert_eq!(failure.kind, KIND_OTHER);
        assert_eq!(failure.message, Error::Truncated.to_string());
    }

    /// A non-final chunk that does not open is `KIND_OPEN` (gRPC `INTERNAL`) as the final one is,
    /// streamed or whole: the core reports it as `Error::Open` itself.
    #[test]
    fn a_tampered_chunk_fails_as_soon_as_it_is_complete() {
        let (handle, mut answer) = streamed_answer(&[7; 200]);
        answer[20] ^= 1;
        let mut stream = opened_stream(handle);
        let failure = stream.push(&answer[..answer.len() / 2]).err().unwrap();
        assert_eq!(failure.kind, KIND_OPEN);

        let (handle, mut answer) = streamed_answer(&[7; 200]);
        answer[20] ^= 1;
        let failure = complete(
            &android(),
            unsafe { take_opener(handle) },
            200,
            None,
            &answer,
            0,
        )
        .err()
        .unwrap();
        assert_eq!(failure.kind, KIND_OPEN);
    }

    #[test]
    fn a_streamed_answer_over_the_cap_fails_before_it_is_buffered() {
        let (handle, answer) = streamed_answer(&[7; 200]);
        let mut stream = opened_stream(handle);
        stream.guard = SizeGuard::new(Some(64));
        stream.push(&answer[..64]).unwrap();
        let failure = stream.push(&answer[64..65]).err().unwrap();
        assert_eq!(failure.kind, KIND_TOO_LARGE);
    }

    #[test]
    fn opening_a_stream_spends_the_opener_and_needs_a_live_one() {
        let failure = open_stream(&android(), None, 200, None, 0).err().unwrap();
        assert_eq!(failure.kind, KIND_ARGUMENT);
        let failure = open_stream(
            &android(),
            unsafe { take_opener(sealed_handle()) },
            503,
            None,
            0,
        )
        .err()
        .unwrap();
        assert_eq!(failure.kind, KIND_REFUSED);
        assert!(unsafe { borrow_stream(0) }.is_err());
        unsafe { free_stream(0) };
        let handle = stream_handle(opened_stream(sealed_handle()));
        assert!(unsafe { borrow_stream(handle) }.is_ok());
        unsafe { free_stream(handle) };
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

/// Returns the DNS name of the signed Key Configuration TXT record for the gateway at `gateway_host`:
/// `ohttp-keys.example` or `ohttp-keys-testnet.example`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_keyConfigDnsName<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    gateway_host: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        use zodl_ohttp_client::{NetworkRule, key_config_dns_name};
        let host = java_string_to_rust(env, &gateway_host)?;
        let network = NetworkRule::KnownHosts
            .network_for(&host)
            .map_err(|e| raise(env, Failure::argument(&e.to_string())))?;
        let dns_name = key_config_dns_name(network);
        Ok(env.new_string(dns_name)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns the DoH query URL for the resolver and gateway: the JSON API URL to fetch the TXT record.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_dohQueryUrl<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    resolver: JString<'local>,
    gateway_host: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        use zodl_ohttp_client::{NetworkRule, doh_query_url};
        let resolver = java_string_to_rust(env, &resolver)?;
        let host = java_string_to_rust(env, &gateway_host)?;
        let network = NetworkRule::KnownHosts
            .network_for(&host)
            .map_err(|e| raise(env, Failure::argument(&e.to_string())))?;
        let url = doh_query_url(&resolver, network);
        Ok(env.new_string(url)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Parses a DoH JSON response (the body of `GET <doh_query_url>`) and extracts the signed envelope.
/// The envelope can then be verified with `verifyKeyConfig`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_envelopeFromDohJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    doh_body: JByteArray<'local>,
    gateway_host: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        use zodl_ohttp_client::{NetworkRule, envelope_from_doh_json};
        let body = java_bytes_to_rust(env, &doh_body)?;
        let host = java_string_to_rust(env, &gateway_host)?;
        let network = NetworkRule::KnownHosts
            .network_for(&host)
            .map_err(|e| raise(env, Failure::argument(&e.to_string())))?;
        let envelope =
            envelope_from_doh_json(&body, network).map_err(|e| raise(env, Failure::from(e)))?;
        Ok(env.byte_array_from_slice(&envelope)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns the DNS-over-HTTPS resolvers as a string array (Cloudflare, then Google).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustOhttpTool_dohResolvers<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        use zodl_ohttp_client::DOH_RESOLVERS;
        let array = env.new_object_array(
            DOH_RESOLVERS.len() as i32,
            "java/lang/String",
            env.new_string("")?,
        )?;
        for (i, resolver) in DOH_RESOLVERS.iter().enumerate() {
            let jstr = env.new_string(*resolver)?;
            env.set_object_array_element(&array, i as i32, jstr)?;
        }
        Ok(array.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}
