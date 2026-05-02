use std::collections::HashMap;
use std::sync::Mutex;

use jni::sys::jlong;
use once_cell::sync::Lazy;

use crate::runtime::SessionRuntime;

static NEXT_HANDLE: Lazy<Mutex<u64>> = Lazy::new(|| Mutex::new(1));
static SESSIONS: Lazy<Mutex<HashMap<u64, SessionRuntime>>> = Lazy::new(|| Mutex::new(HashMap::new()));

pub(crate) fn insert_session(session: SessionRuntime) -> u64 {
    let handle = {
        let mut next = NEXT_HANDLE.lock().expect("handle mutex");
        let value = *next;
        *next += 1;
        value
    };

    SESSIONS.lock().expect("session mutex").insert(handle, session);
    handle
}

pub(crate) fn session_from_handle(handle: jlong) -> Option<SessionRuntime> {
    let handle = to_handle(handle)?;
    SESSIONS.lock().expect("session mutex").get(&handle).cloned()
}

pub(crate) fn remove_session(handle: jlong) {
    if let Some(handle) = to_handle(handle) {
        SESSIONS.lock().expect("session mutex").remove(&handle);
    }
}

fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok()
}
