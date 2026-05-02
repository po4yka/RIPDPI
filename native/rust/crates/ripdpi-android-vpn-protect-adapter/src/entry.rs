use jni::objects::JObject;
use jni::EnvUnowned;

pub fn register_entry(mut env: EnvUnowned<'_>, vpn_service: JObject<'_>) {
    let _ = env.with_env(move |env| -> jni::errors::Result<()> {
        let vm = env.get_java_vm()?;
        let global_ref = env.new_global_ref(&vpn_service)?;
        crate::register_vpn_protect(&vm, global_ref);
        Ok(())
    });
}

pub fn unregister_entry() {
    crate::unregister_vpn_protect();
}
