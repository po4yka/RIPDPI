mod config;
mod session;
mod telemetry;

use android_support::{init_android_logging, JNI_VERSION};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jlongArray};
use jni::{JNIEnv, JavaVM};

use session::{
    tunnel_create_entry, tunnel_destroy_entry, tunnel_start_entry, tunnel_stats_entry, tunnel_stop_entry,
    tunnel_telemetry_entry,
};

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("hs5t-native");
    JNI_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate(
    env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    tunnel_create_entry(env, config_json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
    tun_fd: jint,
) {
    tunnel_start_entry(env, handle, tun_fd);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    tunnel_stop_entry(env, handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jlongArray {
    tunnel_stats_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    tunnel_telemetry_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    tunnel_destroy_entry(env, handle);
}

pub(crate) fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}
