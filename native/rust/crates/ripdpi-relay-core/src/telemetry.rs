use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;

/// A single TCP upstream-connect observation for quality telemetry.
///
/// `rtt_ms` is the elapsed time from the start of `connect_tcp` to the first
/// byte of the upstream response (or the error). `succeeded` is `false` for
/// any error arm of the connect path; failed samples increment the loss
/// counter in `QualityWindow` but are NOT entered into the latency histogram
/// (their RTT is undefined). Pass `rtt_ms = 0` when the producer cannot
/// measure RTT.
///
/// Cancel-safety: the observation is a plain `Copy` value; caller drops it on
/// cancellation with no side-effect.
#[derive(Debug, Clone, Copy)]
pub struct TcpConnectObservation {
    /// Elapsed milliseconds from connect-start to first upstream byte, or 0 if
    /// RTT is unavailable (e.g. virtual-TCP paths).
    pub rtt_ms: u64,
    /// `true` if the upstream TCP connection succeeded, `false` on any error.
    pub succeeded: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayTelemetry {
    pub source: &'static str,
    pub state: String,
    pub health: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    pub listener_address: Option<String>,
    pub upstream_address: Option<String>,
    pub last_target: Option<String>,
    pub last_error: Option<String>,
    pub profile_id: Option<String>,
    pub protocol_kind: Option<String>,
    pub tcp_capable: Option<bool>,
    pub udp_capable: Option<bool>,
    pub fallback_mode: Option<String>,
    pub last_handshake_error: Option<String>,
    pub chain_entry_state: Option<String>,
    pub chain_exit_state: Option<String>,
    pub strategy_pack_id: Option<String>,
    pub strategy_pack_version: Option<String>,
    pub tls_profile_id: Option<String>,
    pub tls_profile_catalog_version: Option<String>,
    pub morph_policy_id: Option<String>,
    pub quic_migration_status: Option<String>,
    pub quic_migration_reason: Option<String>,
    pub pt_runtime_kind: Option<String>,
    pub pt_runtime_state: Option<String>,
    pub captured_at: u64,
}

#[derive(Clone, Default)]
pub(crate) struct QuicMigrationTelemetryState {
    inner: Arc<Mutex<QuicMigrationTelemetrySnapshot>>,
}

#[derive(Default)]
struct QuicMigrationTelemetrySnapshot {
    status: Option<String>,
    reason: Option<String>,
}

impl QuicMigrationTelemetryState {
    pub(crate) fn update(&self, status: Option<&str>, reason: Option<&str>) {
        let mut snapshot = self.inner.lock().expect("quic migration telemetry");
        snapshot.status = status.map(ToOwned::to_owned);
        snapshot.reason = reason.map(ToOwned::to_owned);
    }

    pub(crate) fn snapshot(&self) -> (Option<String>, Option<String>) {
        let snapshot = self.inner.lock().expect("quic migration telemetry");
        (snapshot.status.clone(), snapshot.reason.clone())
    }
}

pub(crate) fn sync_quic_migration_state(
    telemetry: &QuicMigrationTelemetryState,
    snapshot: (Option<String>, Option<String>),
) {
    telemetry.update(snapshot.0.as_deref(), snapshot.1.as_deref());
}

pub(crate) fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |duration| duration.as_millis() as u64)
}
