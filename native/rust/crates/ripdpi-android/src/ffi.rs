use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::{EnvUnowned, Outcome};

use crate::diagnostics::{
    diagnostics_cancel_scan_entry, diagnostics_create_entry, diagnostics_destroy_entry,
    diagnostics_poll_passive_events_entry, diagnostics_poll_progress_entry, diagnostics_start_scan_entry,
    diagnostics_take_report_entry,
};
use crate::owned_tls_http::execute as execute_owned_tls_http;
use crate::proxy::{
    proxy_create_entry, proxy_destroy_entry, proxy_poll_telemetry_entry, proxy_start_entry, proxy_stop_entry,
    proxy_update_network_snapshot_entry,
};
use crate::vpn_protect;

macro_rules! export_jni {
    ($name:ident, ($($arg:ident: $arg_ty:ty),* $(,)?), $ret:ty, $entry:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name(env: EnvUnowned<'_>, _thiz: JObject<'_>, $($arg: $arg_ty),*) -> $ret {
            $entry(env, $($arg),*)
        }
    };
}

export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate,
    (config_json: JString),
    jlong,
    proxy_create_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart,
    (handle: jlong),
    jint,
    proxy_start_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop,
    (handle: jlong),
    (),
    proxy_stop_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry,
    (handle: jlong),
    jstring,
    proxy_poll_telemetry_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy,
    (handle: jlong),
    (),
    proxy_destroy_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUpdateNetworkSnapshot,
    (handle: jlong, snapshot_json: JString),
    (),
    proxy_update_network_snapshot_entry
);

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeOwnedTlsHttpFetcherNativeBindings_jniExecute(
    mut env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString,
) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let payload = execute_owned_tls_http(&request_json).unwrap_or_else(|error| {
                serde_json::json!({
                    "error": error.to_string(),
                })
                .to_string()
            });
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiPlatformCapabilities_jniSeqovlSupported(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jboolean {
    ripdpi_runtime::platform::seqovl_supported()
}

export_jni!(Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate, (), jlong, diagnostics_create_entry);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniStartScan,
    (handle: jlong, request_json: JString, session_id: JString),
    (),
    diagnostics_start_scan_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCancelScan,
    (handle: jlong),
    (),
    diagnostics_cancel_scan_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollProgress,
    (handle: jlong),
    jstring,
    diagnostics_poll_progress_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniTakeReport,
    (handle: jlong),
    jstring,
    diagnostics_take_report_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollPassiveEvents,
    (handle: jlong),
    jstring,
    diagnostics_poll_passive_events_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy,
    (handle: jlong),
    (),
    diagnostics_destroy_entry
);

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniRegisterVpnProtect(
    mut env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    vpn_service: JObject<'_>,
) {
    let _ = env.with_env(move |env| -> jni::errors::Result<()> {
        let vm = env.get_java_vm()?;
        let global_ref = env.new_global_ref(&vpn_service)?;
        vpn_protect::register_vpn_protect(&vm, global_ref);
        Ok(())
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) {
    vpn_protect::unregister_vpn_protect();
}
