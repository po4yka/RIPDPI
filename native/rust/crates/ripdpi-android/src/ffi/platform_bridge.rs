use android_support::ffi_boundary;
use jni::objects::JObject;
use jni::sys::{jboolean, JNI_FALSE};
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiPlatformCapabilities_jniSeqovlSupported(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jboolean {
    ffi_boundary(JNI_FALSE, ripdpi_android_platform_adapter::seqovl_supported)
}
