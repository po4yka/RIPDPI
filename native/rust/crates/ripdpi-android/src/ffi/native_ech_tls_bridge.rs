use android_support::ffi_boundary;
use jni::EnvUnowned;
use jni::objects::{JObject, JString};
use jni::sys::{jlong, jstring};
use ripdpi_android_fetch_adapter::EchRequestId;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeEchTlsHandshakeBridge_jniConnect(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    handle: jlong,
    request_json: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        ripdpi_android_fetch_adapter::connect_ech_entry(env, EchRequestId::from_jni(handle), request_json)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeEchTlsHandshakeBridge_jniCreateRequest(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jlong {
    ffi_boundary(0, || ripdpi_android_fetch_adapter::create_ech_request().as_jni())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeEchTlsHandshakeBridge_jniCancelRequest(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    handle: jlong,
) {
    ffi_boundary((), || ripdpi_android_fetch_adapter::cancel_ech_request(EchRequestId::from_jni(handle)));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeEchTlsHandshakeBridge_jniReleaseRequest(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    handle: jlong,
) {
    ffi_boundary((), || ripdpi_android_fetch_adapter::release_ech_request(EchRequestId::from_jni(handle)));
}
