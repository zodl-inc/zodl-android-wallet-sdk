//! P0 SPIKE (throwaway): handle-less JNI binding of the engine's OHTTP request-health stream
//! (`slipstream_core::transport_health`, slipstream-internal PR #67).
//!
//! The engine API is process-global and keyed by `HealthRoute { gateway_host, relay_url, via_tor }`,
//! so no engine handle is needed and the exports are independent of the engine lifecycle. They
//! must NOT be called on `SlipstreamDispatchers.SLIPSTREAM_IO`: `await` blocks.
//!
//! Pull model: the engine's notify callback (native worker thread) only stores the latest
//! snapshot and wakes waiters; Kotlin blocks in `await` on its own IO thread.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jlongArray, jstring};
use slipstream_core::transport_health::{
    self, HealthOutcome, HealthPurpose, HealthRoute, HealthScope, HealthSnapshot, HealthStage,
    HealthSubscription,
};

use super::{catch_unwind, unwrap_exc_or};

/// The latest snapshot and the closed flag live under ONE mutex, the one the condvar waits on, so a
/// `close` can never slip between `await` reading `closed` and starting to wait.
struct LatestState {
    snapshot: Option<HealthSnapshot>,
    closed: bool,
}

struct Latest {
    state: Mutex<LatestState>,
    wake: Condvar,
}

struct Entry {
    // Taken (and dropped, which joins the worker) outside any lock the callback uses.
    subscription: Mutex<Option<HealthSubscription>>,
    latest: Arc<Latest>,
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn registry() -> &'static Mutex<HashMap<u64, Arc<Entry>>> {
    static REGISTRY: OnceLock<Mutex<HashMap<u64, Arc<Entry>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Longs per observation in the encoded snapshot (see [`encode`]).
const STRIDE: usize = 14;

/// Flat encoding of a snapshot, decoded by `OhttpHealthNative.decode` in Kotlin (no JNI class lookups):
/// `[generation, version, count]`, then per observation [`STRIDE`] longs:
/// `scope, outcomeCategory, retryNotBeforeNs, stage, purpose, historical, version, requestId,
/// startedAtNs, observedAtNs, hasOperation, operationStartedAtNs, operationIdHigh, operationIdLow`.
/// Enum values are the engine's declaration order, which the Kotlin enums mirror.
fn encode(snapshot: &HealthSnapshot) -> Vec<i64> {
    let mut out = Vec::with_capacity(3 + snapshot.observations.len() * STRIDE);
    out.push(snapshot.generation as i64);
    out.push(snapshot.version as i64);
    out.push(snapshot.observations.len() as i64);
    for o in &snapshot.observations {
        let (category, retry) = match o.outcome {
            HealthOutcome::Usable => (0, 0),
            HealthOutcome::TransportUnavailable => (1, 0),
            HealthOutcome::BackendUnavailable => (2, 0),
            HealthOutcome::RateLimited {
                retry_not_before_nanoseconds,
            } => (3, retry_not_before_nanoseconds as i64),
            HealthOutcome::PolicyRejected => (4, 0),
            HealthOutcome::ConfigurationFailure => (5, 0),
            HealthOutcome::ResponseRejected => (6, 0),
            HealthOutcome::Cancelled => (7, 0),
            HealthOutcome::Inconclusive => (8, 0),
        };
        let scope = match o.scope {
            HealthScope::Route => 0,
            HealthScope::Lightwalletd => 1,
            HealthScope::ExchangeRates => 2,
            HealthScope::Swaps => 3,
            HealthScope::Voting => 4,
            HealthScope::Flexa => 5,
            HealthScope::Other => 6,
        };
        let stage = match o.stage {
            HealthStage::Connection => 0,
            HealthStage::KeyConfiguration => 1,
            HealthStage::ResponseHead => 2,
            HealthStage::ResponseBody => 3,
            HealthStage::Complete => 4,
        };
        let purpose = match o.purpose {
            HealthPurpose::Background => 0,
            HealthPurpose::UserOperation => 1,
            HealthPurpose::Diagnostic => 2,
        };
        let (has_op, op_started, id_hi, id_lo) = match o.operation {
            Some(op) => {
                let hi = i64::from_be_bytes(op.id[0..8].try_into().unwrap());
                let lo = i64::from_be_bytes(op.id[8..16].try_into().unwrap());
                (1, op.started_at_nanoseconds as i64, hi, lo)
            }
            None => (0, 0, 0, 0),
        };
        out.extend_from_slice(&[
            scope,
            category,
            retry,
            stage,
            purpose,
            o.historical as i64,
            o.version as i64,
            o.request_id as i64,
            o.started_at_nanoseconds as i64,
            o.observed_at_nanoseconds as i64,
            has_op,
            op_started,
            id_hi,
            id_lo,
        ]);
    }
    out
}

/// Returns a subscription id (> 0), or 0 when the gateway has no known relay.
/// `generation` must be non-zero and unique per subscription.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_OhttpHealthNative_subscribe<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    gateway_host: JString<'local>,
    via_tor: jboolean,
    generation: jlong,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let host: String = env.get_string(&gateway_host)?.into();
        let Some(relay) = slipstream_core::ohttp::relay_for(&host) else {
            return Ok(0);
        };
        let route = HealthRoute {
            gateway_host: host,
            relay_url: relay.to_owned(),
            via_tor: via_tor != 0,
        };
        let latest = Arc::new(Latest {
            state: Mutex::new(LatestState {
                snapshot: None,
                closed: false,
            }),
            wake: Condvar::new(),
        });
        let sink = latest.clone();
        let subscription = transport_health::subscribe(
            route,
            generation as u64,
            Arc::new(move |snapshot| {
                sink.state.lock().unwrap().snapshot = Some(snapshot);
                sink.wake.notify_all();
            }),
        );
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        registry().lock().unwrap().insert(
            id,
            Arc::new(Entry {
                subscription: Mutex::new(Some(subscription)),
                latest,
            }),
        );
        Ok(id as jlong)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// Blocks until a snapshot newer than `since_version` is available, the subscription is closed,
/// or `timeout_ms` elapses. Returns the [`encode`]d snapshot, or null on timeout/closed/unknown id.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_OhttpHealthNative_await<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    id: jlong,
    since_version: jlong,
    timeout_ms: jlong,
) -> jlongArray {
    let res = catch_unwind(&mut env, |env| {
        let entry = registry().lock().unwrap().get(&(id as u64)).cloned();
        let Some(entry) = entry else {
            return Ok(std::ptr::null_mut());
        };
        let deadline = Instant::now() + Duration::from_millis(timeout_ms.max(0) as u64);
        let mut guard = entry.latest.state.lock().unwrap();
        loop {
            if let Some(snapshot) = guard.snapshot.as_ref() {
                if snapshot.version as i64 > since_version {
                    let encoded = encode(snapshot);
                    drop(guard);
                    let array = env.new_long_array(encoded.len() as i32)?;
                    env.set_long_array_region(&array, 0, &encoded)?;
                    return Ok(array.into_raw());
                }
            }
            if guard.closed {
                return Ok(std::ptr::null_mut());
            }
            let now = Instant::now();
            if now >= deadline {
                return Ok(std::ptr::null_mut());
            }
            let (next, _) = entry.latest.wake.wait_timeout(guard, deadline - now).unwrap();
            guard = next;
        }
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Idempotent. Joins the engine's notification worker, so it must not run under a lock the
/// callback needs (none is held here).
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_OhttpHealthNative_close<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    id: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        let entry = registry().lock().unwrap().remove(&(id as u64));
        if let Some(entry) = entry {
            entry.latest.state.lock().unwrap().closed = true;
            entry.latest.wake.notify_all();
            let subscription = entry.subscription.lock().unwrap().take();
            drop(subscription);
        }
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

/// The engine's evidence clock (CLOCK_MONOTONIC, ns). Kotlin must compare observation times
/// against this, not against SystemClock.elapsedRealtime*.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_OhttpHealthNative_monotonicNowNanos<'local>(
    _env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jlong {
    transport_health::monotonic_time_nanoseconds() as jlong
}

/// The relay URL the engine uses for [`gateway_host`] (the same `relay_for` that keys the health route),
/// or null when the gateway has none. Kotlin builds its health route from this so both stacks' evidence
/// lands on one route key.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_OhttpHealthNative_relayUrlFor<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    gateway_host: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let host: String = env.get_string(&gateway_host)?.into();
        match slipstream_core::ohttp::relay_for(&host) {
            Some(relay) => Ok(env.new_string(relay)?.into_raw()),
            None => Ok(std::ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}
