use jni::objects::JObject;
use jni::EnvUnowned;

pub(super) fn register_entry(mut env: EnvUnowned<'_>, vpn_service: JObject<'_>) {
    let _ = env.with_env(move |env| -> jni::errors::Result<()> {
        let vm = env.get_java_vm()?;
        let global_ref = env.new_global_ref(&vpn_service)?;
        crate::vpn_protect::register_vpn_protect(&vm, global_ref);
        Ok(())
    });
}

pub(super) fn unregister_entry() {
    crate::vpn_protect::unregister_vpn_protect();
}
