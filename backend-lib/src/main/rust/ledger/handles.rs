//! Opaque handles for the engine's stateful objects.
//!
//! A handle is a positive `jlong` naming an entry in a per-type registry, never a pointer: a
//! handle used after it was freed, or passed to a function for another type, is an error rather
//! than undefined behaviour. Each entry sits behind its own mutex, so a Kotlin caller that drives
//! one object from two threads at once serializes instead of racing — a signing session in
//! particular cannot hand out a command while another thread is absorbing a reply.

use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicI64, Ordering},
    },
};

use jni::sys::jlong;

use super::error::LedgerError;

pub(crate) struct Registry<T> {
    entries: OnceLock<Mutex<HashMap<jlong, Arc<Mutex<T>>>>>,
    next: AtomicI64,
}

impl<T> Registry<T> {
    pub(crate) const fn new() -> Self {
        Registry {
            entries: OnceLock::new(),
            next: AtomicI64::new(1),
        }
    }

    fn entries(&self) -> &Mutex<HashMap<jlong, Arc<Mutex<T>>>> {
        self.entries.get_or_init(|| Mutex::new(HashMap::new()))
    }

    /// Stores `value` and returns its handle.
    pub(crate) fn insert(&self, value: T) -> Result<jlong, LedgerError> {
        let handle = self
            .next
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
            .map_err(|_| LedgerError::internal("the Ledger handle space is exhausted"))?;
        self.entries()
            .lock()
            .map_err(|_| LedgerError::internal("the Ledger handle registry is poisoned"))?
            .insert(handle, Arc::new(Mutex::new(value)));
        Ok(handle)
    }

    /// Runs `f` on the value behind `handle`, holding that value's lock for the duration.
    ///
    /// The registry lock is released before `f` runs, so a long call on one handle does not
    /// block the others. A value whose lock was poisoned by a panic is refused: whatever the
    /// panic interrupted cannot be trusted to be consistent.
    pub(crate) fn with<R>(
        &self,
        handle: jlong,
        f: impl FnOnce(&mut T) -> Result<R, LedgerError>,
    ) -> Result<R, LedgerError> {
        let entry = self
            .entries()
            .lock()
            .map_err(|_| LedgerError::internal("the Ledger handle registry is poisoned"))?
            .get(&handle)
            .cloned()
            .ok_or_else(|| LedgerError::internal("the Ledger handle is closed or unknown"))?;
        let mut value = entry
            .lock()
            .map_err(|_| LedgerError::internal("the Ledger object was poisoned by a panic"))?;
        f(&mut value)
    }

    /// Frees the value behind `handle`. Freeing an unknown or already-freed handle does nothing.
    ///
    /// The value is dropped after the registry lock is released: dropping a signing session
    /// wipes buffers, and nothing of that should run while every other handle waits.
    pub(crate) fn remove(&self, handle: jlong) {
        let removed = match self.entries().lock() {
            Ok(mut entries) => entries.remove(&handle),
            Err(_) => None,
        };
        drop(removed);
    }

    #[cfg(test)]
    pub(crate) fn contains(&self, handle: jlong) -> bool {
        self.entries()
            .lock()
            .map(|entries| entries.contains_key(&handle))
            .unwrap_or(false)
    }
}
