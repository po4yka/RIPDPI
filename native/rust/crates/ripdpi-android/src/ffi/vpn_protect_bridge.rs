use android_support::ffi_boundary;
use jni::EnvUnowned;
use jni::objects::JObject;
use jni::sys::jlong;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniRegisterVpnProtect(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    vpn_service: JObject<'_>,
) -> jlong {
    // Returns the generation token (0 on failure); Kotlin threads it back to
    // jniUnregisterVpnProtect so a stale unregister cannot clobber a newer
    // session's callback. See docs/architecture/JNI_CONTRACT.md §8.
    ffi_boundary(0, move || ripdpi_android_vpn_protect_adapter::register_entry(env, vpn_service))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    token: jlong,
) {
    ffi_boundary((), move || {
        ripdpi_android_vpn_protect_adapter::unregister_entry(token);
    });
}
