use ripdpi_config::RuntimeConfig;

use crate::types::{ProxyConfigError, ProxyLogContext, ProxySessionOverrides};

use super::super::shared::trim_non_empty;

pub(crate) fn sanitize_log_context(log_context: Option<ProxyLogContext>) -> Option<ProxyLogContext> {
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

pub(crate) fn apply_session_overrides(
    config: &mut RuntimeConfig,
    session_overrides: Option<ProxySessionOverrides>,
) -> Result<(), ProxyConfigError> {
    let Some(session_overrides) = sanitize_session_overrides(session_overrides) else {
        return Ok(());
    };

    if let Some(port_override) = session_overrides.listen_port_override {
        config.network.listen.listen_port = u16::try_from(port_override)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid sessionOverrides.listenPortOverride".to_string()))?;
    }
    if let Some(auth_token) = session_overrides.auth_token {
        config.network.listen.auth_token = Some(auth_token);
    }
    Ok(())
}

fn sanitize_session_overrides(session_overrides: Option<ProxySessionOverrides>) -> Option<ProxySessionOverrides> {
    let mut session_overrides = session_overrides?;
    session_overrides.auth_token = trim_non_empty(session_overrides.auth_token);
    if session_overrides.listen_port_override.is_none() && session_overrides.auth_token.is_none() {
        None
    } else {
        Some(session_overrides)
    }
}
