use jni::objects::JObject;
use jni::{EnvUnowned, Outcome};

/// JNI entry for `jniRegisterVpnProtect`.
///
/// Returns the generation token the registry stamped on the slot, or `0` when
/// registration failed or panicked. Kotlin keeps the token and passes it back
/// to [`unregister_entry`]; a `0` token unregisters as a safe no-op.
pub fn register_entry(mut env: EnvUnowned<'_>, vpn_service: JObject<'_>) -> i64 {
    match env
        .with_env(move |env| -> jni::errors::Result<i64> {
            let vm = env.get_java_vm()?;
            let global_ref = env.new_global_ref(&vpn_service)?;
            Ok(crate::register_vpn_protect(&vm, global_ref))
        })
        .into_outcome()
    {
        Outcome::Ok(token) => token,
        Outcome::Err(err) => {
            tracing::error!("proxy VPN protect registration failed: {err}");
            0
        }
        Outcome::Panic(payload) => {
            let msg = payload
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| payload.downcast_ref::<&str>().copied())
                .unwrap_or("unknown panic");
            tracing::error!("proxy VPN protect registration panicked: {msg}");
            0
        }
    }
}

/// JNI entry for `jniUnregisterVpnProtect`.
///
/// `token` is the value [`register_entry`] returned. Clearing is
/// generation-checked: a stale or `0` token is a safe no-op.
pub fn unregister_entry(token: i64) {
    crate::unregister_vpn_protect(token);
}
