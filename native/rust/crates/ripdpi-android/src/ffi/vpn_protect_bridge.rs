use android_support::ffi_boundary;
use jni::objects::JObject;
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniRegisterVpnProtect(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    vpn_service: JObject<'_>,
) {
    ffi_boundary((), move || {
        ripdpi_android_vpn_protect_adapter::register_entry(env, vpn_service);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) {
    ffi_boundary((), || {
        ripdpi_android_vpn_protect_adapter::unregister_entry();
    });
}
