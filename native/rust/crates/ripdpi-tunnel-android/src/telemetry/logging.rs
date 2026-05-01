use super::state::TunnelTelemetryState;

struct LogContextProjection<'a> {
    runtime_id: &'a str,
    mode: &'a str,
    policy_signature: &'a str,
    fingerprint_hash: &'a str,
    diagnostics_session_id: &'a str,
}

impl<'a> LogContextProjection<'a> {
    fn from_state(state: &'a TunnelTelemetryState) -> Self {
        let log_context = state.log_context.as_ref();
        Self {
            runtime_id: log_context.and_then(|context| context.runtime_id.as_deref()).unwrap_or(""),
            mode: log_context.and_then(|context| context.mode.as_deref()).unwrap_or(""),
            policy_signature: log_context.and_then(|context| context.policy_signature.as_deref()).unwrap_or(""),
            fingerprint_hash: log_context.and_then(|context| context.fingerprint_hash.as_deref()).unwrap_or(""),
            diagnostics_session_id: log_context
                .and_then(|context| context.diagnostics_session_id.as_deref())
                .unwrap_or(""),
        }
    }
}

impl TunnelTelemetryState {
    pub(crate) fn log_line(&self, source: &str, level: &str, message: &str) {
        let context = LogContextProjection::from_state(self);
        match level.trim().to_ascii_lowercase().as_str() {
            "trace" => tracing::trace!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id = context.runtime_id,
                mode = context.mode,
                policy_signature = context.policy_signature,
                fingerprint_hash = context.fingerprint_hash,
                diagnostics_session_id = context.diagnostics_session_id,
                "{message}"
            ),
            "debug" => tracing::debug!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id = context.runtime_id,
                mode = context.mode,
                policy_signature = context.policy_signature,
                fingerprint_hash = context.fingerprint_hash,
                diagnostics_session_id = context.diagnostics_session_id,
                "{message}"
            ),
            "warn" | "warning" => tracing::warn!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id = context.runtime_id,
                mode = context.mode,
                policy_signature = context.policy_signature,
                fingerprint_hash = context.fingerprint_hash,
                diagnostics_session_id = context.diagnostics_session_id,
                "{message}"
            ),
            "error" => tracing::error!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id = context.runtime_id,
                mode = context.mode,
                policy_signature = context.policy_signature,
                fingerprint_hash = context.fingerprint_hash,
                diagnostics_session_id = context.diagnostics_session_id,
                "{message}"
            ),
            _ => tracing::info!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id = context.runtime_id,
                mode = context.mode,
                policy_signature = context.policy_signature,
                fingerprint_hash = context.fingerprint_hash,
                diagnostics_session_id = context.diagnostics_session_id,
                "{message}"
            ),
        }
    }

    pub(crate) fn push_event(&self, source: &str, level: &str, message: String) {
        self.log_line(source, level, &message);
    }
}
