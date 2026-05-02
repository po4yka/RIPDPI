use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::EnvUnowned;

use crate::ffi::owned_tls_http;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NativeOwnedTlsHttpFetcherNativeBindings_jniExecute(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    request_json: JString<'_>,
) -> jstring {
    owned_tls_http::execute_entry(env, request_json)
}
