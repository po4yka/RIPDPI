use android_support::ffi_boundary;
use jni::EnvUnowned;
use jni::objects::{JObject, JString};
use jni::sys::jstring;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeEchTlsHandshakeBridge_jniConnect(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || ripdpi_android_fetch_adapter::connect_ech_entry(env, request_json))
}
