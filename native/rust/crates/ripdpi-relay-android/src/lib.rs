//! JNI export facade for the relay-transport runtime (`libripdpi-relay.so`).
//!
//! This crate is the `cdylib` boundary for the relay native session. It owns
//! the `JNI_OnLoad` hook and the `Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_*`
//! export symbols; each wraps a `lifecycle::*` delegate in
//! `android_support::ffi_boundary` so a Rust panic cannot unwind across the
//! `extern "system"` boundary.
//!
//! ## Handle lifecycle
//! `jniCreate` -> `jniStart` -> `jniStop` -> `jniDestroy`, per session.
//! `jniCreate` registers a session and returns an opaque `jlong` registry key
//! (`0` on failure). `jniStart` builds a Tokio runtime and **blocks for the
//! whole relay lifetime**, returning `0` (clean exit), `1` (unknown handle) or
//! `2` (runtime error). `jniStop` signals the blocked `jniStart` to unwind.
//! `jniDestroy` removes the session from the registry.
//!
//! ## Idempotency, fds, callbacks, errors
//! `jniStop` and `jniDestroy` are idempotent — each is a silent no-op on an
//! unknown handle. The relay adopts no externally supplied fds; the JNI JSON
//! carries an explicit inactive/VPN-required protection policy, and VPN mode
//! registers the callback that required dialers invoke before network I/O.
//! Failures are reported through return codes
//! only, never Java exceptions; a contained panic yields the panic-default
//! sentinel.
//!
//! See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §6 (panic
//! containment) and §7 (error mapping).

// Crate-local lint floor for the unsafe sites in this cdylib boundary: the
// JNI `#[unsafe(no_mangle)]` exports plus the readiness callback's
// `unsafe impl Send`/`Send` and `JavaVM::from_raw` clone. Re-enabling the
// workspace-deferred `undocumented_unsafe_blocks` lint locally turns the
// `// SAFETY:` requirement into a build-time error, matching the convention
// already adopted by ripdpi-vless / ripdpi-io-uring / ripdpi-privileged-ops /
// ripdpi-proxy-runtime while the rest of the workspace migrates gradually.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

mod lifecycle;
mod readiness;
mod registry;
mod runtime;
mod ssh_probe;
mod telemetry;
mod vpn_protect;

use android_support::ffi_boundary;
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiSshHostKeyNativeBindings_jniProbeHostKey(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    address: JString<'_>,
    port: jint,
    timeout_millis: jint,
    controller: JObject<'_>,
    output: JObjectArray<'_>,
) -> jint {
    ffi_boundary(6, move || ssh_probe::probe_entry(env, address, port, timeout_millis, controller, output))
}

/// # Safety
/// Called once by the JVM at library load; `vm` is a valid `*mut JavaVM` that outlives this call.
/// The function must not panic across the FFI boundary; panic-hook installation and signal masking
/// are handled inside the `catch_unwind` wrapper.
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    match std::panic::catch_unwind(|| lifecycle::jni_on_load_entry(vm)) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniCreate(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    ffi_boundary(0, move || lifecycle::relay_create_entry(env, config_json))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniStart(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    ffi_boundary(-1, move || lifecycle::relay_start_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniStop(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || {
        lifecycle::relay_stop_entry(env, handle);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniPollTelemetry(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || lifecycle::relay_poll_telemetry_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniDestroy(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || {
        lifecycle::relay_destroy_entry(env, handle);
    });
}

// @JvmStatic in a Kotlin companion object generates the JNI symbol on the
// class itself (without $Companion / 00024Companion), not on the companion.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniRegisterVpnProtect(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    vpn_service: JObject,
) -> jni::sys::jlong {
    ffi_boundary(0, move || vpn_protect::register_from_jni(env, vpn_service))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    token: jni::sys::jlong,
) {
    ffi_boundary((), move || {
        vpn_protect::unregister_entry(token);
    });
}

// Native readiness push (ADR 0003): a one-shot onRuntimeReady() callback that
// replaces the 50 ms Kotlin telemetry poll. Register after jniCreate and before
// jniStart. Returns 0 for the Apps Script backend (no native readiness event),
// so Kotlin keeps polling for it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniRegisterReadinessListener(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
    listener: JObject,
) -> jlong {
    ffi_boundary(0, move || readiness::register_from_jni(env, handle, listener))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniUnregisterReadinessListener(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || {
        readiness::unregister_entry(handle);
    });
}
