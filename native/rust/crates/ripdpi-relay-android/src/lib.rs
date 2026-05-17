mod lifecycle;
mod registry;
mod runtime;
mod telemetry;

use android_support::ffi_boundary;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM};

/// # Safety
/// Called once by the JVM at library load; `vm` is a valid `*mut JavaVM` that outlives this call.
/// The function must not panic across the FFI boundary; panic-hook installation and signal masking
/// are handled inside the `catch_unwind` wrapper.
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, reserved: *mut std::ffi::c_void) -> jint {
    match std::panic::catch_unwind(|| lifecycle::jni_on_load_entry(vm, reserved)) {
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
