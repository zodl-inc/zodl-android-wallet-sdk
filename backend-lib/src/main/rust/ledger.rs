//! Ledger hardware-wallet support over Bluetooth LE: the JNI surface over `pczt_ledger`.
//!
//! The engine is sans-I/O. Everything here builds commands and absorbs replies; the Kotlin layer
//! (`cash.z.ecc.android.sdk.internal.jni.LedgerRustBackend`) moves the bytes over BLE. Five groups
//! of functions:
//!
//! - **one-shot pairing commands** — firmware version, device identity, unified address display —
//!   each an APDU builder and a reply parser. A `0x6901` reply surfaces as the `CmdNotAccepted`
//!   kind for the caller to resend the same bytes after the engine's backoff;
//! - **checks of the device's answers** against what the wallet derives from the account's viewing
//!   key (see [`account_keys`]);
//! - **the UFVK export**, an opaque handle over `VkExchange`, which absorbs `0x6901` itself and is
//!   terminal on any other error, so the approval screen is never re-issued by a retrying caller;
//! - **BLE framing** — the MTU handshake, frame splitting, and a handle over the BLE `Deframer`;
//! - **the signing session**, a handle over `LedgerSignSession` built from the wallet's own
//!   account data (see [`session`]).
//!
//! Every failure is thrown as a `JniLedgerException` with a structured kind; see [`error`] for
//! what its reason may and may not carry.
//!
//! # The one rule a transport owns
//!
//! A reply is only ever the answer to the last command handed out. If an exchange fails after
//! its command was written (a timeout, a disconnect, a frame that does not reassemble), the
//! Kotlin caller drops the session *and* closes the BLE connection; it never resends on the same
//! session, and never feeds a later reply to it.

use std::ptr;

use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JString, JValue},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jobject, jobjectArray, jstring},
};
use pczt_ledger::{
    Network as LedgerNetwork,
    ceremony::CMD_NOT_ACCEPTED_BACKOFF,
    framing::{self, BleFrameSize, Deframer},
    pairing::{self, DeviceIdentity, VkExchange, VkStep},
    session::{
        Deadline, LedgerSignSession, MAX_CMD_NOT_ACCEPTED_RETRIES, PumpStep, SessionStatus, Stage,
    },
    transport::DEFAULT_TIMEOUT,
};
use tracing::debug;
use transparent::keys::NonHardenedChildIndex;
use zcash_address::unified::Encoding;
use zeroize::Zeroizing;

use crate::utils::{self, catch_unwind, java_string_to_rust};

mod account_keys;
mod error;
mod handles;
mod session;
#[cfg(test)]
mod tests;

use error::{LedgerError, unwrap_or};
use handles::Registry;
use session::{PreparedSession, SessionRequest};

const JNI_APP_VERSION: &str = "cash/z/ecc/android/sdk/internal/model/ledger/JniLedgerAppVersion";
const JNI_UFVK_STEP: &str = "cash/z/ecc/android/sdk/internal/model/ledger/JniLedgerUfvkStep";
const JNI_SIGN_STEP: &str = "cash/z/ecc/android/sdk/internal/model/ledger/JniLedgerSignStep";
const JNI_POLICY: &str = "cash/z/ecc/android/sdk/internal/model/ledger/JniLedgerPolicy";

/// `JniLedgerUfvkStep.status` values.
const UFVK_MORE_CHUNKS: jint = 0;
const UFVK_RETRY_SAME_APDU: jint = 1;
const UFVK_COMPLETE: jint = 2;

/// `JniLedgerSignStep.kind` values.
const STEP_SEND: jint = 0;
const STEP_AWAITING_REPLY: jint = 1;
const STEP_DONE: jint = 2;

/// `signSessionProcessResponse` return values.
const STATUS_MORE_APDUS: jint = 0;
const STATUS_RETRY_SAME_APDU: jint = 1;
const STATUS_AWAITING_USER_ACTION: jint = 2;
const STATUS_COMPLETE: jint = 3;

static UFVK_EXCHANGES: Registry<VkExchange> = Registry::new();
static BLE_DEFRAMERS: Registry<Deframer> = Registry::new();
static SIGN_SESSIONS: Registry<SignSession> = Registry::new();

/// A signing session and the progress figure computed when it was built.
struct SignSession {
    session: LedgerSignSession,
    total_commands: usize,
}

fn ledger_network(network_id: jint) -> Result<LedgerNetwork, LedgerError> {
    let network = crate::parse_network(network_id)
        .map_err(|_| LedgerError::invalid_input("unknown network id"))?;
    LedgerNetwork::try_from(zcash_protocol::consensus::Parameters::network_type(
        &network,
    ))
    .map_err(|_| LedgerError::invalid_input("no Ledger app build exists for this network"))
}

fn zip32_account(index: jlong) -> Result<zip32::AccountId, LedgerError> {
    crate::zip32_account_index_from_jlong(index)
        .map_err(|_| LedgerError::invalid_input("the ZIP 32 account index must be below 2^31"))
}

fn bytes_from_java(env: &JNIEnv, bytes: &JByteArray) -> Result<Zeroizing<Vec<u8>>, LedgerError> {
    Ok(Zeroizing::new(
        utils::java_bytes_to_rust(env, bytes)
            .map_err(|_| LedgerError::internal("a byte array could not be read"))?,
    ))
}

fn bytes_to_java(env: &JNIEnv, bytes: &[u8]) -> Result<jbyteArray, LedgerError> {
    Ok(utils::rust_bytes_to_java(env, bytes)?.into_raw())
}

fn string_to_java(env: &JNIEnv, value: &str) -> Result<jstring, LedgerError> {
    Ok(env.new_string(value)?.into_raw())
}

//
// Policy
//

/// The engine's timing policy: how many consecutive `0x6901` refusals one command tolerates, the
/// pause before each resend, and the wait for a reply the device produces without the user.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_policyNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let backoff = i64::try_from(CMD_NOT_ACCEPTED_BACKOFF.as_millis())
            .map_err(|_| LedgerError::internal("backoff out of range"))?;
        let timeout = i64::try_from(DEFAULT_TIMEOUT.as_millis())
            .map_err(|_| LedgerError::internal("timeout out of range"))?;
        let retries = jint::try_from(MAX_CMD_NOT_ACCEPTED_RETRIES)
            .map_err(|_| LedgerError::internal("retry budget out of range"))?;
        Ok(env
            .new_object(
                JNI_POLICY,
                "(IJJ)V",
                &[
                    JValue::Int(retries),
                    JValue::Long(backoff),
                    JValue::Long(timeout),
                ],
            )?
            .into_raw())
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

//
// One-shot pairing commands
//

/// The `GET_FIRMWARE_VERSION` command.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_firmwareVersionApduNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        bytes_to_java(env, &pairing::firmware_version_apdu())
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Parses a `GET_FIRMWARE_VERSION` reply into the app version and whether it signs PCZTs.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_parseFirmwareVersionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    reply: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let reply = bytes_from_java(env, &reply)?;
        let caps = pairing::device_caps(&reply).map_err(|e| LedgerError::from_pairing(&e))?;
        let (major, minor, patch) = caps.version();
        Ok(env
            .new_object(
                JNI_APP_VERSION,
                "(IIIZ)V",
                &[
                    JValue::Int(major.into()),
                    JValue::Int(minor.into()),
                    JValue::Int(patch.into()),
                    JValue::Bool(u8::from(caps.pczt())),
                ],
            )?
            .into_raw())
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// The silent `GET_WALLET_PUBLIC_KEY` that identifies a device on `network_id`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_deviceIdentityApduNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let apdu = pairing::device_identity_apdu(ledger_network(network_id)?)
            .map_err(|e| LedgerError::from_pairing(&e))?;
        bytes_to_java(env, &apdu)
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Parses the reply to `deviceIdentityApdu` into the device's `tpk0-…` identity string.
///
/// The reply also carries the account's first transparent address, which the engine checks for
/// shape and discards.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_parseDeviceIdentityNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    reply: JByteArray<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let reply = bytes_from_java(env, &reply)?;
        let identity = pairing::parse_device_identity_response(&reply)
            .map_err(|e| LedgerError::from_pairing(&e))?;
        string_to_java(env, &identity.to_string())
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Whether `identity` is a valid `tpk0-<64 lowercase hex>` device identity.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_isValidDeviceIdentityNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    identity: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let identity = java_string_to_rust(env, &identity)
            .map_err(|_| LedgerError::internal("a string could not be read"))?;
        Ok(match DeviceIdentity::parse(&identity) {
            Ok(_) => JNI_TRUE,
            Err(_) => JNI_FALSE,
        })
    });
    unwrap_or(&mut env, res, JNI_FALSE)
}

/// Checks that `ufvk`, which the device exported for ZIP 32 account 0, belongs to the device
/// whose identity is `device_identity`: the identity has to be the hash of the external address
/// key at index 0 under the UFVK's transparent component. Throws `DeviceMismatch` when it is not,
/// and `MalformedReply` when the UFVK does not decode or has no transparent component. Valid for
/// account 0 only.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_checkUfvkDeviceIdentityNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    network_id: jint,
    ufvk: JString<'local>,
    device_identity: JString<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let network = crate::parse_network(network_id)
            .map_err(|_| LedgerError::invalid_input("unknown network id"))?;
        let ufvk = Zeroizing::new(
            java_string_to_rust(env, &ufvk)
                .map_err(|_| LedgerError::internal("a string could not be read"))?,
        );
        let device_identity = java_string_to_rust(env, &device_identity)
            .map_err(|_| LedgerError::internal("a string could not be read"))?;
        account_keys::check_ufvk_device_identity(&network, &ufvk, &device_identity)
    });
    unwrap_or(&mut env, res, ())
}

/// The unified address the device shows for the account whose viewing key is `ufvk`: its Orchard
/// receiver at diversifier index 0 of the external scope, alone, encoded as the Zcash app encodes
/// it. Throws `InvalidInput` when `ufvk` does not decode for the network or has no Orchard
/// component.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_expectedUnifiedAddressNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    network_id: jint,
    ufvk: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let network = crate::parse_network(network_id)
            .map_err(|_| LedgerError::invalid_input("unknown network id"))?;
        let ufvk = Zeroizing::new(
            java_string_to_rust(env, &ufvk)
                .map_err(|_| LedgerError::internal("a string could not be read"))?,
        );
        let address = account_keys::displayed_unified_address(&network, &ufvk)?;
        string_to_java(env, &address)
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// The `GET_SHIELD_ADDR` command for the account's unified address, optionally displayed on the
/// device for the user to compare.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_unifiedAddressApduNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    network_id: jint,
    zip32_account_index: jlong,
    transparent_address_index: jlong,
    display: jboolean,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let index = u32::try_from(transparent_address_index)
            .ok()
            .and_then(NonHardenedChildIndex::from_index)
            .ok_or_else(|| {
                LedgerError::from_pairing(&pczt_ledger::pairing::PairingError::PathOutOfRange {
                    what: "transparent address index",
                    max: pairing::MAX_BIP44_ADDRESS_INDEX,
                })
            })?;
        let apdu = pairing::unified_address_apdu(
            ledger_network(network_id)?,
            zip32_account(zip32_account_index)?,
            index,
            display == JNI_TRUE,
        )
        .map_err(|e| LedgerError::from_pairing(&e))?;
        bytes_to_java(env, &apdu)
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Parses a `GET_SHIELD_ADDR` reply into the encoded unified address, checked against the network
/// the request was built for.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_parseUnifiedAddressNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    reply: JByteArray<'local>,
    network_id: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let network = ledger_network(network_id)?;
        let reply = bytes_from_java(env, &reply)?;
        let address = pairing::parse_unified_address(&reply, network)
            .map_err(|e| LedgerError::from_pairing(&e))?;
        string_to_java(env, &address.encode(&network.network_type()))
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

//
// UFVK export
//

/// Starts a UFVK export for one ZIP 32 account. Free the handle with `ufvkExchangeFree`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_ufvkExchangeNewNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    network_id: jint,
    zip32_account_index: jlong,
) -> jlong {
    let res = catch_unwind(&mut env, |_| {
        let exchange = VkExchange::ufvk(
            ledger_network(network_id)?,
            zip32_account(zip32_account_index)?,
        )
        .map_err(|e| LedgerError::from_pairing(&e))?;
        UFVK_EXCHANGES.insert(exchange)
    });
    unwrap_or(&mut env, res, 0)
}

/// The next command of the export, or `null` once it has completed or failed.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_ufvkExchangeNextApduNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        match UFVK_EXCHANGES.with(handle, |exchange| Ok(exchange.next_apdu()))? {
            Some(apdu) => bytes_to_java(env, &Zeroizing::new(apdu)),
            None => Ok(ptr::null_mut()),
        }
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Whether the reply to the next command waits on the user: true until the first chunk of the
/// key has arrived, since the request that opens the export shows the approval screen.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_ufvkExchangeWaitsForUserNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |_| {
        UFVK_EXCHANGES.with(handle, |exchange| {
            Ok(match exchange.next_deadline() {
                Deadline::UserReview => JNI_TRUE,
                Deadline::Normal => JNI_FALSE,
            })
        })
    });
    unwrap_or(&mut env, res, JNI_FALSE)
}

/// Absorbs one reply of the export.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_ufvkExchangeProcessResponseNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
    reply: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let reply = bytes_from_java(env, &reply)?;
        let step = UFVK_EXCHANGES.with(handle, |exchange| {
            exchange
                .process_response(&reply)
                .map_err(|e| LedgerError::from_pairing(&e))
        })?;
        let (status, ufvk) = match step {
            VkStep::MoreChunks => (UFVK_MORE_CHUNKS, None),
            VkStep::RetrySameApdu => (UFVK_RETRY_SAME_APDU, None),
            VkStep::Complete(pczt_ledger::apdu::decode::VkResponse::Ufvk(ufvk)) => {
                (UFVK_COMPLETE, Some(Zeroizing::new(ufvk)))
            }
            VkStep::Complete(_) => {
                return Err(LedgerError::internal(
                    "a UFVK export completed with another key kind",
                ));
            }
        };
        let ufvk = match &ufvk {
            Some(ufvk) => JObject::from(env.new_string(ufvk.as_str())?),
            None => JObject::null(),
        };
        Ok(env
            .new_object(
                JNI_UFVK_STEP,
                "(ILjava/lang/String;)V",
                &[JValue::Int(status), JValue::Object(&ufvk)],
            )?
            .into_raw())
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Frees a UFVK export handle. Freeing an unknown handle does nothing.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_ufvkExchangeFreeNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        UFVK_EXCHANGES.remove(handle);
        Ok(())
    });
    unwrap_or(&mut env, res, ())
}

//
// BLE framing
//

/// The BLE MTU-handshake request, written before any APDU.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_bleMtuRequestNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        bytes_to_java(env, &framing::ble_mtu_request())
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Reads the negotiated frame size out of the MTU-handshake notification.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_parseBleMtuResponseNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    notification: JByteArray<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let notification = bytes_from_java(env, &notification)?;
        let size = framing::parse_ble_mtu_response(&notification)
            .map_err(|e| LedgerError::from_framing(&e))?;
        jint::try_from(size.get()).map_err(|_| LedgerError::internal("frame size out of range"))
    });
    unwrap_or(&mut env, res, 0)
}

/// Splits one APDU into BLE frames of at most `frame_size` bytes, in write order.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_bleFramesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    apdu: JByteArray<'local>,
    frame_size: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let apdu = bytes_from_java(env, &apdu)?;
        let frame_size = usize::try_from(frame_size)
            .map_err(|_| LedgerError::invalid_input("negative frame size"))
            .and_then(|size| BleFrameSize::new(size).map_err(|e| LedgerError::from_framing(&e)))?;
        // The frames carry the command's bytes, so they live in one `Zeroizing` vector for the
        // whole call: `Zeroizing<Vec<Vec<u8>>>` zeroizes every inner frame and then the outer
        // vector when it drops (`Zeroize for Vec<Z: Zeroize>` is element-wise). The Java arrays
        // are built from borrowed slices of it, so no second copy of any frame exists in Rust.
        let frames = Zeroizing::new(
            framing::ble_frames(&apdu, frame_size).map_err(|e| LedgerError::from_framing(&e))?,
        );
        let frame_slices: Vec<&[u8]> = frames.iter().map(Vec::as_slice).collect();
        Ok(
            utils::rust_vec_to_java(env, frame_slices, "[B", |env, frame| {
                utils::rust_bytes_to_java(env, frame)
            })?
            .into_raw(),
        )
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Creates a BLE reply reassembler. Free the handle with `bleDeframerFree`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_bleDeframerNewNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |_| BLE_DEFRAMERS.insert(Deframer::ble()));
    unwrap_or(&mut env, res, 0)
}

/// Absorbs one notification. Returns the complete reply (data and status word) on the frame that
/// finishes it, and `null` while more frames are expected. A refused frame poisons the reassembly
/// until `bleDeframerReset`; the transport closes the connection instead of resetting.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_bleDeframerPushNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
    frame: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let frame = bytes_from_java(env, &frame)?;
        let reply = BLE_DEFRAMERS.with(handle, |deframer| {
            deframer
                .push(&frame)
                .map_err(|e| LedgerError::from_framing(&e))
        })?;
        match reply {
            Some(reply) => bytes_to_java(env, &Zeroizing::new(reply)),
            None => Ok(ptr::null_mut()),
        }
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Drops any partial reply, for a deframer reused after a successful reassembly.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_bleDeframerResetNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        BLE_DEFRAMERS.with(handle, |deframer| {
            deframer.reset();
            Ok(())
        })
    });
    unwrap_or(&mut env, res, ())
}

/// Frees a deframer handle. Freeing an unknown handle does nothing.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_bleDeframerFreeNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        BLE_DEFRAMERS.remove(handle);
        Ok(())
    });
    unwrap_or(&mut env, res, ())
}

//
// Signing session
//

/// Builds a signing session for `pczt` (as `createPcztFromProposal` returned it) from the
/// account's own keys, bound to the paired device, and validated against the app version the
/// device's `GET_FIRMWARE_VERSION` reply names. Free the handle with `signSessionFree`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_signSessionNewNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    pczt: JByteArray<'local>,
    device_identity: JString<'local>,
    zip32_account_index: jlong,
    firmware_version_reply: JByteArray<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("LedgerRustBackend.signSessionNew").entered();
        let network = crate::parse_network(network_id)
            .map_err(|_| LedgerError::invalid_input("unknown network id"))?;
        let db = crate::wallet_db(crate::system_rng(), env, network, db_data)
            .map_err(|_| LedgerError::internal("the wallet database could not be opened"))?;
        let account = crate::account_id_from_jni(env, account_uuid)
            .map_err(|_| LedgerError::invalid_input("invalid account UUID"))?;
        let pczt = bytes_from_java(env, &pczt)?;
        let device_identity = java_string_to_rust(env, &device_identity)
            .map_err(|_| LedgerError::internal("a string could not be read"))?;
        let firmware_version_reply = bytes_from_java(env, &firmware_version_reply)?;

        let PreparedSession {
            session,
            total_commands,
        } = session::new_sign_session(
            &db,
            SessionRequest {
                network: ledger_network(network_id)?,
                account,
                pczt: &pczt,
                device_identity: &device_identity,
                zip32_account: zip32_account(zip32_account_index)?,
                firmware_version_reply: &firmware_version_reply,
            },
        )?;
        SIGN_SESSIONS.insert(SignSession {
            session,
            total_commands,
        })
    });
    unwrap_or(&mut env, res, 0)
}

/// How many commands a complete ceremony exchanges, `0x6901` resends not counted.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_signSessionTotalCommandsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jint {
    let res = catch_unwind(&mut env, |_| {
        SIGN_SESSIONS.with(handle, |s| {
            jint::try_from(s.total_commands)
                .map_err(|_| LedgerError::internal("command count out of range"))
        })
    });
    unwrap_or(&mut env, res, 0)
}

/// Hands out the next command.
///
/// `waitsForUser` is the deadline for its reply (no timeout from the review packet to the end of
/// the session), and `announcesReview` is true exactly once, on the packet that puts the review
/// on the device's screen — a `0x6901` resend of it is the same review.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_signSessionNextStepNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (kind, apdu, waits_for_user, announces_review) = SIGN_SESSIONS.with(handle, |s| {
            let session = &mut s.session;
            let announces_review =
                session.pending_user_action().is_some() && !session.is_past_review();
            Ok(match session.next_step() {
                PumpStep::Send(apdu) => (
                    STEP_SEND,
                    Some(Zeroizing::new(apdu)),
                    session.next_deadline() == Deadline::UserReview,
                    announces_review,
                ),
                PumpStep::AwaitingReply => (STEP_AWAITING_REPLY, None, false, false),
                PumpStep::Done => (STEP_DONE, None, false, false),
            })
        })?;
        let apdu = match &apdu {
            Some(apdu) => JObject::from(utils::rust_bytes_to_java(env, apdu)?),
            None => JObject::null(),
        };
        Ok(env
            .new_object(
                JNI_SIGN_STEP,
                "(I[BZZ)V",
                &[
                    JValue::Int(kind),
                    JValue::Object(&apdu),
                    JValue::Bool(u8::from(waits_for_user)),
                    JValue::Bool(u8::from(announces_review)),
                ],
            )?
            .into_raw())
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Absorbs the reply to the outstanding command. A failure carries the session's
/// `is_restartable` verdict.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_signSessionProcessResponseNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
    reply: JByteArray<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let reply = bytes_from_java(env, &reply)?;
        SIGN_SESSIONS.with(handle, |s| {
            let status = s
                .session
                .process_response(&reply)
                .map_err(|e| LedgerError::from_session(&e, s.session.is_restartable()))?;
            Ok(match status {
                SessionStatus::MoreApdus => STATUS_MORE_APDUS,
                SessionStatus::RetrySameApdu => STATUS_RETRY_SAME_APDU,
                SessionStatus::AwaitingUserAction { .. } => STATUS_AWAITING_USER_ACTION,
                SessionStatus::Complete => STATUS_COMPLETE,
            })
        })
    });
    unwrap_or(&mut env, res, -1)
}

/// Where the ceremony is: 0 identifying the device, 1 streaming, 2–4 signing (Orchard, Ironwood,
/// transparent), 5 complete, 6 failed.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_signSessionStageNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jint {
    let res = catch_unwind(&mut env, |_| {
        SIGN_SESSIONS.with(handle, |s| Ok(stage_code(s.session.stage())))
    });
    unwrap_or(&mut env, res, -1)
}

fn stage_code(stage: Stage) -> jint {
    match stage {
        Stage::IdentifyingDevice => 0,
        Stage::Streaming => 1,
        Stage::SigningOrchard => 2,
        Stage::SigningIronwood => 3,
        Stage::SigningTransparent => 4,
        Stage::Complete => 5,
        Stage::Failed => 6,
    }
}

/// Whether a fresh session over the same PCZT could succeed after the failure that ended this
/// one.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_signSessionIsRestartableNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |_| {
        SIGN_SESSIONS.with(handle, |s| {
            Ok(if s.session.is_restartable() {
                JNI_TRUE
            } else {
                JNI_FALSE
            })
        })
    });
    unwrap_or(&mut env, res, JNI_FALSE)
}

/// Applies the device's signatures and returns the signed PCZT, ready to be combined with the
/// proven one by `createTransactionFromPczt`. Valid once, after the session reported completion.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_signSessionFinishNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let outcome = SIGN_SESSIONS.with(handle, |s| {
            s.session
                .finish()
                .map_err(|e| LedgerError::from_session(&e, false))
        })?;
        debug!(
            "Ledger signing session finished: {} Orchard, {} Ironwood, {} transparent signatures",
            outcome.orchard_sigs().len(),
            outcome.ironwood_sigs().len(),
            outcome.transparent_sigs().len()
        );
        bytes_to_java(env, outcome.pczt_with_sigs())
    });
    unwrap_or(&mut env, res, ptr::null_mut())
}

/// Frees a signing session handle. Freeing an unknown handle does nothing.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_LedgerRustBackend_signSessionFreeNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        SIGN_SESSIONS.remove(handle);
        Ok(())
    });
    unwrap_or(&mut env, res, ())
}
