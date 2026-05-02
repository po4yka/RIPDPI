mod endpoint_probe;
mod lifecycle;
mod provisioning;
mod registry;
mod telemetry;
mod vpn_protect;

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
    lifecycle::create(env, config_json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniStart(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    lifecycle::start(handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniStop(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    lifecycle::stop(handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniPollTelemetry(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    telemetry::poll(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniDestroy(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    lifecycle::destroy(handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniExecuteProvisioning(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    request_json: JString,
) -> jni::sys::jstring {
    provisioning::execute_from_jni(env, request_json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniProbeEndpoint(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    request_json: JString,
) -> jni::sys::jstring {
    endpoint_probe::probe(env, request_json)
}

// @JvmStatic in a Kotlin companion object generates the JNI symbol on the
// class itself (without $Companion / 00024Companion), not on the companion.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniRegisterVpnProtect(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    vpn_service: JObject,
) {
    vpn_protect::register_from_jni(env, vpn_service);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
) {
    vpn_protect::unregister_entry();
}
