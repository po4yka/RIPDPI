//! JNI export facade for the embedded SOCKS proxy lifecycle.
//!
//! Most `export_jni!` entries are
//! `Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_*` symbols that wrap a
//! `proxy_*_entry` delegate from `ripdpi-android-proxy-adapter` in
//! `android_support::ffi_boundary` for panic containment. This module owns only
//! the export symbols and panic sentinels; the lifecycle logic lives in the
//! adapter crate.
//!
//! ## Handle lifecycle
//! `jniCreate` -> `jniStart` -> `jniStop` -> `jniDestroy`, per session.
//! `jniCreate` registers an `Idle` session and returns an opaque `jlong`
//! registry key (`0` on failure). `jniStart` transitions `Idle -> Running` and
//! **blocks for the whole proxy lifetime**, returning `0` on a clean shutdown
//! or a positive `errno` otherwise. `jniStop` signals the blocked `jniStart`
//! to unwind. `jniDestroy` retires the session and must run only after
//! `jniStart` has returned. `jniUpdateNetworkSnapshot` is a control-plane call
//! valid only while `Running` (ignored otherwise).
//!
//! ## Idempotency, fds, errors
//! `jniStop` throws `IllegalStateException` if the session is not `Running`;
//! `jniDestroy` throws if it is still `Running` — neither is silently
//! idempotent. The proxy adopts no externally supplied fds (it binds its own
//! loopback listener). Failures are reported as Java exceptions thrown by the
//! adapter (see `JniProxyError`); a contained panic yields the panic-default
//! sentinel — `0` for `jlong`, `-1` for `jniStart`, null for `jstring`.
//!
//! See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §6 (panic
//! containment) and §7 (error mapping).

use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jstring};

use ripdpi_android_proxy_adapter::{
    proxy_create_entry, proxy_destroy_entry, proxy_poll_forwarding_evidence_entry, proxy_poll_telemetry_entry,
    proxy_register_readiness_listener_entry, proxy_start_entry, proxy_stop_entry,
    proxy_unregister_readiness_listener_entry, proxy_update_network_snapshot_entry,
};

export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate,
    (config_json: JString),
    jlong,
    proxy_create_entry,
    0,
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart,
    (handle: jlong),
    jint,
    proxy_start_entry,
    -1,
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop,
    (handle: jlong),
    (),
    proxy_stop_entry,
    (),
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry,
    (handle: jlong),
    jstring,
    proxy_poll_telemetry_entry,
    core::ptr::null_mut(),
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeForwardingEvidenceBindings_jniPollForwardingEvidence,
    (handle: jlong),
    jstring,
    proxy_poll_forwarding_evidence_entry,
    core::ptr::null_mut(),
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy,
    (handle: jlong),
    (),
    proxy_destroy_entry,
    (),
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUpdateNetworkSnapshot,
    (handle: jlong, snapshot_json: JString),
    (),
    proxy_update_network_snapshot_entry,
    (),
);
// Native readiness push (ADR 0003): a one-shot onRuntimeReady() callback that
// replaces the 50 ms Kotlin telemetry poll. Register after jniCreate and before
// jniStart; the listener is released on jniUnregisterReadinessListener or when
// the session is destroyed. Both exports are pinned in `jni-symbols.baseline`
// and checked by the JNI symbol-diff workflow; any future export change
// requires an intentional baseline update.
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniRegisterReadinessListener,
    (handle: jlong, listener: JObject),
    jlong,
    proxy_register_readiness_listener_entry,
    0,
);
export_jni!(
    Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUnregisterReadinessListener,
    (handle: jlong),
    (),
    proxy_unregister_readiness_listener_entry,
    (),
);
