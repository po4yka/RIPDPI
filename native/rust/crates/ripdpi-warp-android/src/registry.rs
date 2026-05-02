use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use jni::sys::jlong;
use once_cell::sync::Lazy;
use ripdpi_warp_core::WarpRuntime;

static NEXT_HANDLE: Lazy<Mutex<u64>> = Lazy::new(|| Mutex::new(1));
static SESSIONS: Lazy<Mutex<HashMap<u64, Arc<WarpRuntime>>>> = Lazy::new(|| Mutex::new(HashMap::new()));

pub(crate) fn insert(session: Arc<WarpRuntime>) -> jlong {
    let handle = {
        let mut next = NEXT_HANDLE.lock().expect("handle mutex");
        let value = *next;
        *next += 1;
        value
    };
    SESSIONS.lock().expect("session mutex").insert(handle, session);
    jlong::try_from(handle).unwrap_or(0)
}

pub(crate) fn get(handle: jlong) -> Option<Arc<WarpRuntime>> {
    let handle = u64::try_from(handle).ok()?;
    SESSIONS.lock().expect("session mutex").get(&handle).cloned()
}

pub(crate) fn remove(handle: jlong) {
    if let Ok(handle) = u64::try_from(handle) {
        SESSIONS.lock().expect("session mutex").remove(&handle);
    }
}
