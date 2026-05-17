use android_support::ffi_boundary;
use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeOwnedTlsHttpFetcherNativeBindings_jniExecute(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || ripdpi_android_fetch_adapter::execute_entry(env, request_json))
}
