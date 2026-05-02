use jni::objects::JObject;
use jni::{EnvUnowned, Outcome};

pub fn register_entry(mut env: EnvUnowned<'_>, vpn_service: JObject<'_>) {
    match env
        .with_env(move |env| -> jni::errors::Result<()> {
            let vm = env.get_java_vm()?;
            let global_ref = env.new_global_ref(&vpn_service)?;
            crate::register_vpn_protect(&vm, global_ref);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            tracing::error!("proxy VPN protect registration failed: {err}");
        }
        Outcome::Panic(payload) => {
            let msg = payload
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| payload.downcast_ref::<&str>().copied())
                .unwrap_or("unknown panic");
            tracing::error!("proxy VPN protect registration panicked: {msg}");
        }
    }
}

pub fn unregister_entry() {
    crate::unregister_vpn_protect();
}
