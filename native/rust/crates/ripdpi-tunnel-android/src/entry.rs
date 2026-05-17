use android_support::ffi_boundary;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jlongArray};
use jni::EnvUnowned;

use crate::session::{
    tunnel_create_entry, tunnel_destroy_entry, tunnel_start_entry, tunnel_stats_entry, tunnel_stop_entry,
    tunnel_telemetry_entry,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    ffi_boundary(0, move || tunnel_create_entry(env, config_json))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
    tun_fd: jint,
) {
    ffi_boundary((), move || tunnel_start_entry(env, handle, tun_fd));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || tunnel_stop_entry(env, handle));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jlongArray {
    ffi_boundary(core::ptr::null_mut(), move || tunnel_stats_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || tunnel_telemetry_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || tunnel_destroy_entry(env, handle));
}
