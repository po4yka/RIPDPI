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

/// The role a hop plays in an ordered N-hop chain.
///
/// `Hop(0)` is the entry (opens the single real outbound socket); the highest
/// index is the exit. Indices in between are intermediate tunnelling hops. The
/// legacy two-hop telemetry surface (`chain_entry_*` / `chain_exit_*`) is still
/// projected from index `0` and the last index respectively — see
/// [`ChainHopTelemetrySnapshot::entry_state`] and friends.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ChainHopRole {
    Hop(usize),
}

/// Per-hop connect telemetry for an N-hop chain.
///
/// `hops[i]` carries the `(state, latency_ms)` of the hop at index `i`. The
/// `entry_*` / `exit_*` accessors project the first and last recorded hop so
/// the existing two-field wire telemetry stays populated without a schema bump.
#[derive(Debug, Clone, Default)]
pub(crate) struct ChainHopTelemetrySnapshot {
    /// Dense per-hop `(state, latency_ms)` keyed by hop index; grown on demand.
    hops: Vec<Option<(String, Option<u64>)>>,
}

impl ChainHopTelemetrySnapshot {
    fn hop(&self, index: usize) -> Option<&(String, Option<u64>)> {
        self.hops.get(index).and_then(Option::as_ref)
    }

    pub fn entry_state(&self) -> Option<String> {
        self.hop(0).map(|(state, _)| state.clone())
    }

    pub fn entry_latency_ms(&self) -> Option<u64> {
        self.hop(0).and_then(|(_, latency)| *latency)
    }

    fn exit_index(&self) -> Option<usize> {
        self.hops.iter().rposition(Option::is_some)
    }

    pub fn exit_state(&self) -> Option<String> {
        self.exit_index().and_then(|index| self.hop(index)).map(|(state, _)| state.clone())
    }

    pub fn exit_latency_ms(&self) -> Option<u64> {
        self.exit_index().and_then(|index| self.hop(index)).and_then(|(_, latency)| *latency)
    }

    /// The intermediate hops of the chain — every recorded hop strictly between
    /// the entry (index 0) and the exit (the last recorded index). Empty for a
    /// two-hop chain. Each entry is `(hop_index, state, latency_ms)` so the UI
    /// can render per-hop live status keyed by the hop's ordered position.
    pub fn intermediate_hops(&self) -> Vec<ChainIntermediateHopTelemetry> {
        let Some(exit_index) = self.exit_index() else {
            return Vec::new();
        };
        (1..exit_index)
            .filter_map(|index| {
                self.hop(index).map(|(state, latency)| ChainIntermediateHopTelemetry {
                    hop_index: index,
                    state: state.clone(),
                    latency_ms: *latency,
                })
            })
            .collect()
    }
}

/// One intermediate hop's connect telemetry, projected for the wire. `hop_index`
/// is the hop's ordered position in the chain (1..exit); entry (0) and exit are
/// carried by the dedicated `chain_entry_*` / `chain_exit_*` fields instead.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChainIntermediateHopTelemetry {
    pub hop_index: usize,
    pub state: String,
    pub latency_ms: Option<u64>,
}

#[derive(Clone, Default)]
pub(crate) struct ChainHopTelemetryState {
    inner: Arc<Mutex<ChainHopTelemetrySnapshot>>,
}

impl ChainHopTelemetryState {
    pub(crate) fn record(&self, role: ChainHopRole, state: &str, latency_ms: Option<u64>) {
        // Recover from a poisoned lock: a panicked holder must not permanently brick telemetry.
        let mut snapshot = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let ChainHopRole::Hop(index) = role;
        if snapshot.hops.len() <= index {
            snapshot.hops.resize(index + 1, None);
        }
        snapshot.hops[index] = Some((state.to_string(), latency_ms));
    }

    pub(crate) fn snapshot(&self) -> ChainHopTelemetrySnapshot {
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone()
    }
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
    pub chain_entry_latency_ms: Option<u64>,
    pub chain_exit_state: Option<String>,
    pub chain_exit_latency_ms: Option<u64>,
    /// Per-hop live telemetry for the intermediate hops of an N-hop (3..4) chain
    /// (positions strictly between entry and exit). Empty for two-hop chains and
    /// non-chain relays. Serializes as `chainIntermediateHops`.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub chain_intermediate_hops: Vec<ChainIntermediateHopTelemetry>,
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
        // Recover from a poisoned lock: a panicked holder must not permanently brick telemetry.
        let mut snapshot = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        snapshot.status = status.map(ToOwned::to_owned);
        snapshot.reason = reason.map(ToOwned::to_owned);
    }

    pub(crate) fn snapshot(&self) -> (Option<String>, Option<String>) {
        let snapshot = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
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

#[cfg(test)]
mod tests {
    use super::{ChainHopRole, ChainHopTelemetryState};

    #[test]
    fn intermediate_hops_excludes_entry_and_exit_for_a_four_hop_chain() {
        let state = ChainHopTelemetryState::default();
        // A 4-hop chain: hop 0 entry, hops 1..=2 intermediate, hop 3 exit.
        state.record(ChainHopRole::Hop(0), "connected", Some(10));
        state.record(ChainHopRole::Hop(1), "connected", Some(21));
        state.record(ChainHopRole::Hop(2), "connecting", Some(33));
        state.record(ChainHopRole::Hop(3), "connected", Some(40));

        let snapshot = state.snapshot();
        let middle = snapshot.intermediate_hops();

        assert_eq!(2, middle.len());
        assert_eq!(1, middle[0].hop_index);
        assert_eq!("connected", middle[0].state);
        assert_eq!(Some(21), middle[0].latency_ms);
        assert_eq!(2, middle[1].hop_index);
        assert_eq!("connecting", middle[1].state);
        assert_eq!(Some(33), middle[1].latency_ms);
        // Entry/exit stay projected through their dedicated accessors.
        assert_eq!(Some("connected".to_string()), snapshot.entry_state());
        assert_eq!(Some(10), snapshot.entry_latency_ms());
        assert_eq!(Some("connected".to_string()), snapshot.exit_state());
        assert_eq!(Some(40), snapshot.exit_latency_ms());
    }

    #[test]
    fn intermediate_hops_is_empty_for_a_two_hop_chain() {
        let state = ChainHopTelemetryState::default();
        state.record(ChainHopRole::Hop(0), "connected", Some(5));
        state.record(ChainHopRole::Hop(1), "connected", Some(9));

        assert!(state.snapshot().intermediate_hops().is_empty());
    }
}
