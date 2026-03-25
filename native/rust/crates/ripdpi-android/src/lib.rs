mod config;
mod diagnostics;
mod errors;
mod proxy;
#[cfg(test)]
mod support;
mod telemetry;

use android_support::{init_android_logging, JNI_VERSION};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::OnceCell;

use diagnostics::{
    diagnostics_cancel_scan_entry, diagnostics_create_entry, diagnostics_destroy_entry,
    diagnostics_poll_passive_events_entry, diagnostics_poll_progress_entry, diagnostics_start_scan_entry,
    diagnostics_take_report_entry,
};
use proxy::{
    proxy_create_entry, proxy_destroy_entry, proxy_poll_telemetry_entry, proxy_start_entry, proxy_stop_entry,
    proxy_update_network_snapshot_entry,
};

static JVM: OnceCell<JavaVM> = OnceCell::new();

fn jni_on_load_impl() -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-native");
    android_support::install_panic_hook();
    ripdpi_telemetry::recorder::install();
    JNI_VERSION
}

#[cfg(test)]
pub(crate) fn shared_test_jvm() -> &'static JavaVM {
    static TEST_JVM: OnceCell<JavaVM> = OnceCell::new();
    TEST_JVM.get_or_init(|| {
        let args = jni::InitArgsBuilder::new()
            .version(jni::JNIVersion::V8)
            .option("-Xcheck:jni")
            .build()
            .expect("build test JVM init args");
        JavaVM::new(args).expect("create in-process test JVM")
    })
}

#[cfg(test)]
pub(crate) fn shared_jni_test_mutex() -> &'static std::sync::Mutex<()> {
    static JNI_TEST_MUTEX: once_cell::sync::Lazy<std::sync::Mutex<()>> =
        once_cell::sync::Lazy::new(|| std::sync::Mutex::new(()));
    &JNI_TEST_MUTEX
}

/// # Safety
/// Called by the JVM when the native library is loaded. Must not unwind across
/// the FFI boundary -- a panic here would be UB (extern "system" + unwind).
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let _ = JVM.set(vm);
    match std::panic::catch_unwind(jni_on_load_impl) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

macro_rules! export_diagnostics_jni {
    ($name:ident, ($($arg:ident: $arg_ty:ty),* $(,)?), $ret:ty, $entry:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name(env: JNIEnv, _thiz: JObject, $($arg: $arg_ty),*) -> $ret {
            $entry(env, $($arg),*)
        }
    };
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate(
    env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    proxy_create_entry(env, config_json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    proxy_start_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    proxy_stop_entry(env, handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jstring {
    proxy_poll_telemetry_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    proxy_destroy_entry(env, handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUpdateNetworkSnapshot(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
    snapshot_json: JString,
) {
    proxy_update_network_snapshot_entry(env, handle, snapshot_json);
}

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate,
    (),
    jlong,
    diagnostics_create_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniStartScan,
    (handle: jlong, request_json: JString, session_id: JString),
    (),
    diagnostics_start_scan_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCancelScan,
    (handle: jlong),
    (),
    diagnostics_cancel_scan_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollProgress,
    (handle: jlong),
    jstring,
    diagnostics_poll_progress_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniTakeReport,
    (handle: jlong),
    jstring,
    diagnostics_take_report_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollPassiveEvents,
    (handle: jlong),
    jstring,
    diagnostics_poll_passive_events_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy,
    (handle: jlong),
    (),
    diagnostics_destroy_entry
);

pub(crate) fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jni_on_load_impl_returns_supported_jni_version() {
        assert_eq!(jni_on_load_impl(), JNI_VERSION);
    }

    #[test]
    fn to_handle_accepts_positive_values_only() {
        assert_eq!(to_handle(0), None);
        assert_eq!(to_handle(-1), None);
        assert_eq!(to_handle(7), Some(7));
    }
}
