//! JNI export facade for the generic AmneziaWG runtime (`libripdpi-amneziawg.so`).
//!
//! This crate is the `cdylib` boundary for a **user-configured** AmneziaWG
//! tunnel (an arbitrary endpoint + key pair + AmneziaWG obfuscation knobs),
//! distinct from `ripdpi-warp-android` which drives Cloudflare WARP. Both wrap
//! the same `ripdpi-warp-core` WireGuard + AmneziaWG data plane; this bridge
//! carries no Cloudflare provisioning / endpoint-probe entries.
//!
//! It owns the `JNI_OnLoad` hook and the
//! `Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_*` export symbols;
//! each wraps a `lifecycle`/`telemetry`/`vpn_protect`/`readiness` delegate in
//! `android_support::ffi_boundary` so a Rust panic cannot unwind across the
//! `extern "system"` boundary (ADR 0005 crash isolation).
//!
//! ## Handle lifecycle
//! `jniCreate` -> `jniStart` -> `jniStop` -> `jniDestroy`, per session.
//! `jniCreate` registers a session and returns an opaque `jlong` registry key
//! (`0` on failure). `jniStart` builds a Tokio runtime and **blocks for the
//! whole tunnel lifetime**, returning `0` (clean exit), `1` (unknown handle)
//! or `2` (runtime error). `jniStop` signals the blocked `jniStart` to unwind.
//! `jniDestroy` removes the session. `jniStop`/`jniDestroy` are idempotent.
//!
//! ## fd ownership and callbacks
//! The runtime adopts no externally supplied fds -- it opens its own WireGuard
//! UDP socket and loopback SOCKS listener. That UDP socket is kept off the VPN
//! tunnel via the `jniRegisterVpnProtect` callback, which stores a JNI
//! `GlobalRef` to the `VpnService`; `jniUnregisterVpnProtect` releases it.
//! Register before `jniStart` and unregister after `jniDestroy` (see the
//! `vpnservice-protect-invariant` rule).
//!
//! See `docs/architecture/JNI_CONTRACT.md` §4, §6, §7, §8, §10.

#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]
#![warn(clippy::missing_safety_doc)]

mod lifecycle;
mod readiness;
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
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniCreate(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    ffi_boundary(0, move || lifecycle::create(env, config_json))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniStart(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    ffi_boundary(-1, move || lifecycle::start(handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniStop(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || {
        lifecycle::stop(handle);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniPollTelemetry(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || telemetry::poll(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniDestroy(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || {
        lifecycle::destroy(handle);
    });
}

// @JvmStatic in a Kotlin companion object generates the JNI symbol on the
// class itself (without $Companion / 00024Companion), not on the companion.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniRegisterVpnProtect(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    vpn_service: JObject,
) -> jni::sys::jlong {
    ffi_boundary(0, move || vpn_protect::register_from_jni(env, vpn_service))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    token: jni::sys::jlong,
) {
    ffi_boundary((), move || {
        vpn_protect::unregister_entry(token);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniRegisterReadinessListener(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
    listener: JObject,
) -> jlong {
    ffi_boundary(0, move || readiness::register_from_jni(env, handle, listener))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiAmneziaWgNativeBindings_jniUnregisterReadinessListener(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || {
        readiness::unregister_entry(handle);
    });
}
