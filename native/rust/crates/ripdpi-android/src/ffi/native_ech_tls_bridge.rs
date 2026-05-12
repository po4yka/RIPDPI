use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeEchTlsHandshakeBridge_jniConnect(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString<'_>,
) -> jstring {
    ripdpi_android_fetch_adapter::connect_ech_entry(env, request_json)
}
