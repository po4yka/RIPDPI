use jni::objects::JObject;
use jni::sys::jboolean;
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiPlatformCapabilities_jniSeqovlSupported(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jboolean {
    ripdpi_android_platform_adapter::seqovl_supported()
}
