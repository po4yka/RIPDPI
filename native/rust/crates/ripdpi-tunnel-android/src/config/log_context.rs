use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TunnelLogContext {
    pub(crate) runtime_id: Option<String>,
    pub(crate) mode: Option<String>,
    pub(crate) policy_signature: Option<String>,
    pub(crate) fingerprint_hash: Option<String>,
    pub(crate) diagnostics_session_id: Option<String>,
}

pub(crate) fn sanitize_log_context(log_context: Option<TunnelLogContext>) -> Option<TunnelLogContext> {
    let mut log_context = log_context?;
    log_context.runtime_id = trim_non_empty(log_context.runtime_id);
    log_context.mode = trim_non_empty(log_context.mode).map(|value| value.to_ascii_lowercase());
    log_context.policy_signature = trim_non_empty(log_context.policy_signature);
    log_context.fingerprint_hash = trim_non_empty(log_context.fingerprint_hash);
    log_context.diagnostics_session_id = trim_non_empty(log_context.diagnostics_session_id);
    if log_context.runtime_id.is_none()
        && log_context.mode.is_none()
        && log_context.policy_signature.is_none()
        && log_context.fingerprint_hash.is_none()
        && log_context.diagnostics_session_id.is_none()
    {
        None
    } else {
        Some(log_context)
    }
}

fn trim_non_empty(value: Option<String>) -> Option<String> {
    value.map(|entry| entry.trim().to_string()).filter(|entry| !entry.is_empty())
}
