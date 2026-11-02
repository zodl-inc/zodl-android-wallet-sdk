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
use std::fmt::Write as _;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use slipstream_core::transport_health::{
    self, HealthRoute, HealthSnapshot, HealthSubscription,
};

use super::{catch_unwind, unwrap_exc_or};

struct Latest {
    snapshot: Mutex<Option<HealthSnapshot>>,
    wake: Condvar,
    closed: Mutex<bool>,
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

fn render(snapshot: &HealthSnapshot) -> String {
    let mut out = String::new();
    let _ = writeln!(
        out,
        "gen={} version={} n={}",
        snapshot.generation,
        snapshot.version,
        snapshot.observations.len()
    );
    for o in &snapshot.observations {
        let _ = writeln!(
            out,
            "scope={:?} outcome={:?} stage={:?} purpose={:?} hist={} req={} ver={} started={} observed={} op={}",
            o.scope,
            o.outcome,
            o.stage,
            o.purpose,
            o.historical,
            o.request_id,
            o.version,
            o.started_at_nanoseconds,
            o.observed_at_nanoseconds,
            o.operation.is_some()
        );
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
            snapshot: Mutex::new(None),
            wake: Condvar::new(),
            closed: Mutex::new(false),
        });
        let sink = latest.clone();
        let subscription = transport_health::subscribe(
            route,
            generation as u64,
            Arc::new(move |snapshot| {
                *sink.snapshot.lock().unwrap() = Some(snapshot);
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
/// or `timeout_ms` elapses. Returns a text rendering, or null on timeout/closed/unknown id.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_OhttpHealthNative_await<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    id: jlong,
    since_version: jlong,
    timeout_ms: jlong,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let entry = registry().lock().unwrap().get(&(id as u64)).cloned();
        let Some(entry) = entry else {
            return Ok(std::ptr::null_mut());
        };
        let deadline = Instant::now() + Duration::from_millis(timeout_ms.max(0) as u64);
        let mut guard = entry.latest.snapshot.lock().unwrap();
        loop {
            if let Some(snapshot) = guard.as_ref() {
                if snapshot.version as i64 > since_version {
                    let text = render(snapshot);
                    drop(guard);
                    return Ok(env.new_string(text)?.into_raw());
                }
            }
            if *entry.latest.closed.lock().unwrap() {
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
            *entry.latest.closed.lock().unwrap() = true;
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
