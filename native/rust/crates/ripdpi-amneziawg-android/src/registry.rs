use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};

use jni::sys::jlong;
use ripdpi_warp_core::AmneziaWgRuntime;

static NEXT_HANDLE: LazyLock<Mutex<u64>> = LazyLock::new(|| Mutex::new(1));
static SESSIONS: LazyLock<Mutex<HashMap<u64, Arc<AmneziaWgRuntime>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

pub(crate) fn insert(session: Arc<AmneziaWgRuntime>) -> jlong {
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

pub(crate) fn get(handle: jlong) -> Option<Arc<AmneziaWgRuntime>> {
    let handle = u64::try_from(handle).ok()?;
    SESSIONS.lock().unwrap_or_else(std::sync::PoisonError::into_inner).get(&handle).cloned()
}

pub(crate) fn remove(handle: jlong) {
    if let Ok(handle) = u64::try_from(handle) {
        SESSIONS.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&handle);
    }
}
