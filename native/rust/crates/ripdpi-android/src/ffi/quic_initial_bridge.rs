use android_support::ffi_boundary;
use jni::EnvUnowned;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{JNI_FALSE, jboolean, jstring};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_diagnostics_dpi_NativeQuicInitialPacketBindings_jniCreate(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        ripdpi_android_platform_adapter::create_quic_initial_entry(env, request_json)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_diagnostics_dpi_NativeQuicInitialPacketBindings_jniValidate(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request: JByteArray<'_>,
    response: JByteArray<'_>,
    version_negotiation: jboolean,
) -> jboolean {
    ffi_boundary(JNI_FALSE, move || {
        ripdpi_android_platform_adapter::validate_quic_response_entry(env, request, response, version_negotiation)
    })
}
