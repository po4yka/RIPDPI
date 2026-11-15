use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};

use android_support::{HandleRegistry, ffi_boundary};
use jni::EnvUnowned;
use jni::objects::{JObject, JString};
use jni::sys::jstring;

use ripdpi_android_platform_adapter::DoqRequestControls;

const MAX_DOQ_REQUESTS: usize = 8;
static REQUESTS: OnceLock<HandleRegistry<AtomicBool>> = OnceLock::new();

fn requests() -> &'static HandleRegistry<AtomicBool> {
    REQUESTS.get_or_init(HandleRegistry::new)
}

const CONTROLS: DoqRequestControls = DoqRequestControls {
    reserve: || requests().try_insert_bounded(AtomicBool::new(false), MAX_DOQ_REQUESTS),
    // A request-specific sticky flag publishes no other state.
    cancel: |id| {
        if let Some(cancelled) = requests().get(id) {
            cancelled.store(true, Ordering::Relaxed);
        }
    },
    release: |id| {
        if let Some(cancelled) = requests().remove(id) {
            cancelled.store(true, Ordering::Relaxed);
        }
    },
    cancellation: |id| requests().get(id),
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_diagnostics_dpi_NativeDoqQuicClientNativeBindings_jniExchange(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        ripdpi_android_platform_adapter::exchange_doq_entry(env, request_json, &CONTROLS)
    })
}
