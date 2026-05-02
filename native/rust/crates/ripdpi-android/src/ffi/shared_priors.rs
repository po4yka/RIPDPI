use base64::Engine;
use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};

pub(super) fn apply_entry(mut env: EnvUnowned<'_>, manifest_json: JString<'_>, priors_base64: JString<'_>) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let manifest_bytes: Vec<u8> = manifest_json.mutf8_chars(env)?.to_str().into_owned().into_bytes();
            let priors_b64: String = priors_base64.mutf8_chars(env)?.to_str().into_owned();
            let payload = match base64::engine::general_purpose::STANDARD.decode(priors_b64.trim()) {
                Ok(bytes) => {
                    match ripdpi_runtime_strategy::strategy_evolver::apply_global_shared_priors_with_embedded_key(
                        &manifest_bytes,
                        &bytes,
                    ) {
                        Ok(count) => serde_json::json!({"ok": true, "count": count}).to_string(),
                        Err(err) => serde_json::json!({"ok": false, "error": err.to_string()}).to_string(),
                    }
                }
                Err(err) => serde_json::json!({"ok": false, "error": format!("invalid base64: {err}")}).to_string(),
            };
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}
