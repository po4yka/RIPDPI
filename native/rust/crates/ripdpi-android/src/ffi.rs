use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::EnvUnowned;

mod cdn_ech;
mod owned_tls_http;
mod shared_priors;
mod vpn_protect;

use crate::diagnostics::{
    diagnostics_cancel_scan_entry, diagnostics_create_entry, diagnostics_destroy_entry,
    diagnostics_poll_passive_events_entry, diagnostics_poll_progress_entry, diagnostics_start_scan_entry,
    diagnostics_take_report_entry,
};
use crate::proxy::{
    pcap_is_recording_entry, pcap_start_entry, pcap_stop_entry, proxy_create_entry, proxy_destroy_entry,
    proxy_poll_telemetry_entry, proxy_start_entry, proxy_stop_entry, proxy_update_network_snapshot_entry,
};

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
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStartPcapRecording,
    (handle: jlong, dir_path: JString, max_bytes: jlong),
    jboolean,
    pcap_start_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStopPcapRecording,
    (handle: jlong),
    jstring,
    pcap_stop_entry
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniIsPcapRecording,
    (handle: jlong),
    jboolean,
    pcap_is_recording_entry
);

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeOwnedTlsHttpFetcherNativeBindings_jniExecute(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString,
) -> jstring {
    owned_tls_http::execute_entry(env, request_json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiPlatformCapabilities_jniSeqovlSupported(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jboolean {
    ripdpi_runtime_platform::seqovl_supported()
}

// JNI bridge for the process-wide CdnEchUpdater.
//
// The Kotlin `CdnEchRefreshWorker` calls `jniRefreshCdnEch` on its 24h
// schedule and `jniSnapshotCdnEch` afterwards to capture the new bytes
// for `EncryptedSharedPreferences`. At app startup,
// `jniSeedCdnEch` re-hydrates the in-memory cache from the persisted
// snapshot so the TTL window survives process restarts.
//
// All three return / accept JSON status documents so each error class
// surfaces a precise reason in Kotlin logs without needing a custom
// error code table.

/// Refresh the singleton's cache from primary (DoH HTTPS-RR) or
/// fallback (bundled) source. Returns `{"ok": true}` on success or
/// `{"ok": false, "error": "..."}` if both sources fail.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniRefreshCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
    cdn_ech::refresh_entry(env)
}

/// Snapshot the current cache for persistence to platform storage.
/// Returns `{"ok": true, "fetchedAtUnixMs": N, "configBase64": "..."}`
/// when the cache has been populated, `{"ok": true, "empty": true}` for
/// a cold cache (the worker writes nothing to EncryptedSharedPreferences
/// in that case), or `{"ok": false, "error": "..."}` on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniSnapshotCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
    cdn_ech::snapshot_entry(env)
}

/// Seed the singleton's cache from a previously-persisted snapshot
/// (`fetchedAtUnixMs` paired with the original config bytes,
/// base64-encoded). Validates the bytes against the same length-prefix
/// and version checks `RemoteEchConfigSource` would, so a corrupted
/// EncryptedSharedPreferences entry can't poison the cache.
///
/// Returns `{"ok": true}` or `{"ok": false, "error": "..."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniSeedCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    config_base64: JString,
    fetched_at_unix_ms: jlong,
) -> jstring {
    cdn_ech::seed_entry(env, config_base64, fetched_at_unix_ms)
}

// Verify a signed shared-priors bundle and write the resulting prior
// store into the process-wide registry.
//
// The Kotlin worker fetches the manifest + priors from the GitHub-hosted
// release asset, base64-encodes the priors payload (which is opaque
// bytes), and hands both to this entry point. We base64-decode the
// payload and delegate to `apply_global_shared_priors_with_embedded_key`,
// which validates the manifest's ed25519 signature against the embedded
// release public key. On failure the registry is left untouched.
//
// Returns a small JSON status document: `{"ok": true, "count": N}` on
// success, `{"ok": false, "error": "..."}` on any rejection. JSON keeps
// the contract self-describing — Kotlin parses the response and decides
// whether to retry, log, or surface to the user.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiSharedPriorsNativeBindings_jniApplySharedPriors(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    manifest_json: JString,
    priors_base64: JString,
) -> jstring {
    shared_priors::apply_entry(env, manifest_json, priors_base64)
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
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    vpn_service: JObject<'_>,
) {
    vpn_protect::register_entry(env, vpn_service);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) {
    vpn_protect::unregister_entry();
}
