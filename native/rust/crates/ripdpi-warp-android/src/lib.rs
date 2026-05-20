//! JNI export facade for the WARP (WireGuard) runtime (`libripdpi-warp.so`).
//!
//! This crate is the `cdylib` boundary for the WARP native session. It owns
//! the `JNI_OnLoad` hook and the `Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_*`
//! export symbols; each wraps a `lifecycle`/`telemetry`/`provisioning`/
//! `endpoint_probe`/`vpn_protect` delegate in `android_support::ffi_boundary`
//! so a Rust panic cannot unwind across the `extern "system"` boundary.
//!
//! ## Handle lifecycle
//! `jniCreate` -> `jniStart` -> `jniStop` -> `jniDestroy`, per session.
//! `jniCreate` registers a session and returns an opaque `jlong` registry key
//! (`0` on failure). `jniStart` builds a Tokio runtime and **blocks for the
//! whole tunnel lifetime**, returning `0` (clean exit), `1` (unknown handle)
//! or `2` (runtime error). `jniStop` signals the blocked `jniStart` to unwind.
//! `jniDestroy` removes the session from the registry. `jniStop`/`jniDestroy`
//! are idempotent — a no-op on an unknown handle.
//!
//! ## Stateless entries
//! `jniExecuteProvisioning` and `jniProbeEndpoint` take no handle: each runs a
//! one-shot request and returns a JSON `jstring` (`null` on failure). They are
//! independent of the session registry.
//!
//! ## fd ownership and callbacks
//! The WARP runtime adopts no externally supplied fds — it opens its own
//! WireGuard UDP socket and loopback SOCKS listener. That UDP socket is kept
//! off the VPN tunnel via the `jniRegisterVpnProtect` callback, which stores a
//! JNI `GlobalRef` to the `VpnService`; `jniUnregisterVpnProtect` releases it.
//! Register before `jniStart` and unregister after `jniDestroy` (see the
//! `vpnservice-protect-invariant` rule).
//!
//! ## Errors
//! Lifecycle entries report failure through return codes only, never Java
//! exceptions; a contained panic yields the panic-default sentinel.
//!
//! See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §6 (panic
//! containment), §7 (error mapping), §8 (callback rules) and §10
//! (VpnService.protect callback).

mod endpoint_probe;
mod lifecycle;
mod provisioning;
mod registry;
mod telemetry;
mod vpn_protect;

use android_support::ffi_boundary;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM};

/// # Safety
/// Called once by the JVM at library load. `vm` is a valid `*mut JavaVM` that outlives this call.
/// Must not unwind across the FFI boundary; panics are caught by `catch_unwind` inside the impl.
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    match std::panic::catch_unwind(lifecycle::jni_on_load) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniCreate(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    ffi_boundary(0, move || lifecycle::create(env, config_json))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniStart(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    ffi_boundary(-1, move || lifecycle::start(handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniStop(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || {
        lifecycle::stop(handle);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniPollTelemetry(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || telemetry::poll(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniDestroy(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || {
        lifecycle::destroy(handle);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniExecuteProvisioning(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    request_json: JString,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || provisioning::execute_from_jni(env, request_json))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniProbeEndpoint(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    request_json: JString,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || endpoint_probe::probe(env, request_json))
}

// @JvmStatic in a Kotlin companion object generates the JNI symbol on the
// class itself (without $Companion / 00024Companion), not on the companion.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniRegisterVpnProtect(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    vpn_service: JObject,
) {
    ffi_boundary((), move || {
        vpn_protect::register_from_jni(env, vpn_service);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
) {
    ffi_boundary((), || {
        vpn_protect::unregister_entry();
    });
}
