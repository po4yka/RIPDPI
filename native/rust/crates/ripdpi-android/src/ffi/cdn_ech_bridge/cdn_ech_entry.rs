use android_support::ffi_boundary;
use jni::EnvUnowned;
use jni::objects::{JObject, JString};
use jni::sys::{jlong, jstring};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniRefreshCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || ripdpi_android_platform_adapter::refresh_entry(env))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniSnapshotCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || ripdpi_android_platform_adapter::snapshot_entry(env))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniSeedCdnEch(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    config_base64: JString<'_>,
    fetched_at_unix_ms: jlong,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        ripdpi_android_platform_adapter::seed_entry(env, config_base64, fetched_at_unix_ms)
    })
}
