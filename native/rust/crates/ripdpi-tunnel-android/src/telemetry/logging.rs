use tracing::Level;

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
        emit_tunnel_log(self, source, None, level, message);
    }

    pub(crate) fn push_event(&self, source: &str, level: &str, message: String) {
        self.log_line(source, level, &message);
    }

    pub(crate) fn push_event_kind(&self, source: &str, level: &str, kind: &str, message: String) {
        emit_tunnel_log(self, source, Some(kind), level, &message);
    }
}

macro_rules! emit_tunnel_event {
    ($level:expr, $state:expr, $source:expr, $kind:expr, $message:expr) => {{
        let context = LogContextProjection::from_state($state);
        tracing::event!(
            $level,
            ring = "tunnel",
            subsystem = "tunnel",
            session = $state.session_id.as_str(),
            source = $source,
            kind = $kind,
            runtime_id = context.runtime_id,
            mode = context.mode,
            policy_signature = context.policy_signature,
            fingerprint_hash = context.fingerprint_hash,
            diagnostics_session_id = context.diagnostics_session_id,
            "{}",
            $message
        );
    }};
}

fn emit_tunnel_log(state: &TunnelTelemetryState, source: &str, kind: Option<&str>, level: &str, message: &str) {
    match normalized_level(level) {
        Level::TRACE => emit_tunnel_event!(Level::TRACE, state, source, kind, message),
        Level::DEBUG => emit_tunnel_event!(Level::DEBUG, state, source, kind, message),
        Level::WARN => emit_tunnel_event!(Level::WARN, state, source, kind, message),
        Level::ERROR => emit_tunnel_event!(Level::ERROR, state, source, kind, message),
        _ => emit_tunnel_event!(Level::INFO, state, source, kind, message),
    }
}

fn normalized_level(level: &str) -> Level {
    match level.trim().to_ascii_lowercase().as_str() {
        "trace" => Level::TRACE,
        "debug" => Level::DEBUG,
        "warn" | "warning" => Level::WARN,
        "error" => Level::ERROR,
        _ => Level::INFO,
    }
}
