use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::EnvUnowned;

// Verify a signed shared-priors bundle and write the resulting prior store into
// the process-wide registry. The JSON return keeps the Kotlin contract
// self-describing for retry/log/surface decisions.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiSharedPriorsNativeBindings_jniApplySharedPriors(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    manifest_json: JString<'_>,
    priors_base64: JString<'_>,
) -> jstring {
    ripdpi_android_platform_adapter::apply_entry(env, manifest_json, priors_base64)
}
