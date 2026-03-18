mod config;
mod diagnostics;
mod errors;
mod proxy;
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

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let _ = JVM.set(vm);
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-native");
    JNI_VERSION
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
