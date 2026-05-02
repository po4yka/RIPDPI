use super::state::ProxyTelemetryState;

impl ProxyTelemetryState {
    pub(super) fn emit_event(&self, source: &str, level: &str, message: &str, kind: Option<&str>) {
        let log_context = self.log_context.as_ref();
        let runtime_id = log_context.and_then(|context| context.runtime_id.as_deref()).unwrap_or("");
        let mode = log_context.and_then(|context| context.mode.as_deref()).unwrap_or("");
        let policy_signature = log_context.and_then(|context| context.policy_signature.as_deref()).unwrap_or("");
        let fingerprint_hash = log_context.and_then(|context| context.fingerprint_hash.as_deref()).unwrap_or("");
        let diagnostics_session_id =
            log_context.and_then(|context| context.diagnostics_session_id.as_deref()).unwrap_or("");
        let kind = kind.unwrap_or("");
        match level.trim().to_ascii_lowercase().as_str() {
            "trace" => tracing::trace!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "debug" => tracing::debug!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "warn" | "warning" => tracing::warn!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "error" => tracing::error!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            _ => tracing::info!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
        }
    }

    /// Atomically update string fields using compare-and-swap.
    /// Retries on concurrent modification (rare at observed write frequencies).
    pub fn clear_last_error(&self) {
        self.update_strings(|s| s.last_error = None);
    }

    pub fn push_event(&self, source: &str, level: &str, message: String) {
        self.emit_event(source, level, &message, None);
    }
}
