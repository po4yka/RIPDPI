use base64::Engine;

pub(super) fn from_inputs(manifest_bytes: &[u8], priors_b64: &str) -> String {
    match base64::engine::general_purpose::STANDARD.decode(priors_b64.trim()) {
        Ok(bytes) => match ripdpi_shared_priors::apply_global_shared_priors_with_embedded_key(manifest_bytes, &bytes) {
            Ok(count) => serde_json::json!({
                "ok": true,
                "count": count,
                "threatCount": ripdpi_shared_priors::global_protocol_threat_priors_len(),
            })
            .to_string(),
            Err(err) => serde_json::json!({"ok": false, "error": err.to_string()}).to_string(),
        },
        Err(err) => serde_json::json!({"ok": false, "error": format!("invalid base64: {err}")}).to_string(),
    }
}
