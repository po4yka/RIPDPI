use jni::objects::JObject;
use jni::EnvUnowned;

use crate::ffi::vpn_protect;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniRegisterVpnProtect(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    vpn_service: JObject<'_>,
) {
    vpn_protect::register_entry(env, vpn_service);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUnregisterVpnProtect(
    _env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) {
    vpn_protect::unregister_entry();
}
