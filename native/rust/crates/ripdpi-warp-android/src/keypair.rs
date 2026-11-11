use jni::{EnvUnowned, Outcome};

pub(crate) fn generate_from_jni(mut env: EnvUnowned<'_>) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let Ok(pair) = ripdpi_warp_core::generate_warp_keypair() else {
                return Ok(std::ptr::null_mut());
            };
            let Ok(payload) = serde_json::to_string(&pair) else {
                return Ok(std::ptr::null_mut());
            };
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}
