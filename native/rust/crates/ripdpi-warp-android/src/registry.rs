use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use jni::sys::jlong;
use once_cell::sync::Lazy;
use ripdpi_warp_core::WarpRuntime;

static NEXT_HANDLE: Lazy<Mutex<u64>> = Lazy::new(|| Mutex::new(1));
static SESSIONS: Lazy<Mutex<HashMap<u64, Arc<WarpRuntime>>>> = Lazy::new(|| Mutex::new(HashMap::new()));

pub(crate) fn insert(session: Arc<WarpRuntime>) -> jlong {
    let handle = {
        // Recover from a poisoned lock: a panicked holder must not permanently brick the registry.
        let mut next = NEXT_HANDLE.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let value = *next;
        *next += 1;
        value
    };
    SESSIONS.lock().unwrap_or_else(std::sync::PoisonError::into_inner).insert(handle, session);
    jlong::try_from(handle).unwrap_or(0)
}

pub(crate) fn get(handle: jlong) -> Option<Arc<WarpRuntime>> {
    let handle = u64::try_from(handle).ok()?;
    SESSIONS.lock().unwrap_or_else(std::sync::PoisonError::into_inner).get(&handle).cloned()
}

pub(crate) fn remove(handle: jlong) {
    if let Ok(handle) = u64::try_from(handle) {
        SESSIONS.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&handle);
    }
}
