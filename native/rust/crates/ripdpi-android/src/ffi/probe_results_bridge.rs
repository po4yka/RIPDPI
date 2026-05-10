use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_injectProbeResultsJson(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    results_json: JString<'_>,
) -> jstring {
    ripdpi_android_platform_adapter::inject_probe_results_entry(env, results_json)
}
