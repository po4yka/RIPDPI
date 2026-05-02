use base64::Engine;
use jni::objects::JString;
use jni::sys::{jlong, jstring};
use jni::{EnvUnowned, Outcome};

pub(super) fn refresh_entry(mut env: EnvUnowned<'_>) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let payload = match ripdpi_diagnostics_dns::cdn_ech::production_updater().refresh() {
                Ok(()) => "{\"ok\":true}".to_string(),
                Err(err) => serde_json::json!({"ok": false, "error": err.to_string()}).to_string(),
            };
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

pub(super) fn snapshot_entry(mut env: EnvUnowned<'_>) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let payload = match ripdpi_diagnostics_dns::cdn_ech::production_updater().snapshot_for_persistence() {
                Some(snapshot) => {
                    let b64 = base64::engine::general_purpose::STANDARD.encode(&snapshot.config);
                    serde_json::json!({
                        "ok": true,
                        "fetchedAtUnixMs": snapshot.fetched_at_unix_ms,
                        "configBase64": b64,
                    })
                    .to_string()
                }
                None => "{\"ok\":true,\"empty\":true}".to_string(),
            };
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

pub(super) fn seed_entry(mut env: EnvUnowned<'_>, config_base64: JString<'_>, fetched_at_unix_ms: jlong) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let b64: String = config_base64.mutf8_chars(env)?.to_str().into_owned();
            let payload = match base64::engine::general_purpose::STANDARD.decode(b64.trim()) {
                Ok(bytes) => match ripdpi_diagnostics_dns::cdn_ech::production_updater()
                    .seed_from_persisted(bytes, fetched_at_unix_ms.max(0) as u64)
                {
                    Ok(()) => "{\"ok\":true}".to_string(),
                    Err(err) => serde_json::json!({"ok": false, "error": err.to_string()}).to_string(),
                },
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
