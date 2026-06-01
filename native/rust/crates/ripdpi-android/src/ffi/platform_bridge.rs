use android_support::ffi_boundary;
use jni::EnvUnowned;
use jni::objects::JObject;
use jni::sys::{JNI_FALSE, jboolean};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiPlatformCapabilities_jniSeqovlSupported(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jboolean {
    ffi_boundary(JNI_FALSE, ripdpi_android_platform_adapter::seqovl_supported)
}
