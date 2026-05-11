use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_diagnostics_dpi_NativeQuicInitialPacketBindings_jniCreate(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString<'_>,
) -> jstring {
    ripdpi_android_platform_adapter::create_quic_initial_entry(env, request_json)
}
