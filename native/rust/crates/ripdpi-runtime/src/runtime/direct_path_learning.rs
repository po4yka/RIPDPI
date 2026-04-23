use std::collections::HashMap;
use std::net::SocketAddr;

use super::adaptive::direct_path_ip_set_digest;
use super::state::RuntimeState;
use crate::runtime_policy::TransportProtocol;

const NO_TCP_FALLBACK_WINDOW_MS: u64 = 3_000;

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
struct TupleKey {
    authority: String,
    ip_set_digest: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum TerminalState {
    QuicSuccess,
    AllIpsFailed,
    NoTcpFallbackDetected,
}

#[derive(Clone, Debug, Default)]
struct TupleState {
    udp_failed: bool,
    tls_post_client_hello_failed: bool,
    pending_udp_suppressed_at_ms: Option<u64>,
    terminal_state: Option<TerminalState>,
}

#[derive(Default)]
pub(super) struct DirectPathLearningState {
    tuples: HashMap<TupleKey, TupleState>,
}

impl DirectPathLearningState {
    pub(super) fn note_transport_attempt(
        &mut self,
        state: &RuntimeState,
        host: Option<&str>,
        targets: &[SocketAddr],
        transport: TransportProtocol,
    ) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuples.entry(tuple_key).or_default();
        if transport == TransportProtocol::Tcp {
            entry.pending_udp_suppressed_at_ms = None;
            entry.terminal_state = None;
        }
        let _ = state;
    }

    pub(super) fn note_udp_suppressed(&mut self, host: Option<&str>, targets: &[SocketAddr], now_ms: u64) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuples.entry(tuple_key).or_default();
        entry.pending_udp_suppressed_at_ms.get_or_insert(now_ms);
        entry.terminal_state = None;
    }

    pub(super) fn note_udp_failure(&mut self, host: Option<&str>, targets: &[SocketAddr]) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuples.entry(tuple_key).or_default();
        entry.udp_failed = true;
        entry.terminal_state = None;
    }

    pub(super) fn note_tls_post_client_hello_failure(&mut self, host: Option<&str>, targets: &[SocketAddr]) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuples.entry(tuple_key).or_default();
        entry.tls_post_client_hello_failed = true;
        entry.terminal_state = None;
    }

    pub(super) fn note_quic_success(&mut self, state: &RuntimeState, host: Option<&str>, targets: &[SocketAddr]) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuples.entry(tuple_key.clone()).or_default();
        let should_emit = entry.terminal_state != Some(TerminalState::QuicSuccess);
        clear_negative_state(entry);
        entry.terminal_state = Some(TerminalState::QuicSuccess);
        if should_emit {
            emit_learning_signal(state, &tuple_key, "QUIC_SUCCESS", None);
        }
    }

    pub(super) fn note_tcp_success(
        &mut self,
        state: &RuntimeState,
        host: Option<&str>,
        targets: &[SocketAddr],
        strategy_family: Option<&str>,
    ) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuples.entry(tuple_key.clone()).or_default();
        if entry.tls_post_client_hello_failed {
            clear_negative_state(entry);
            emit_learning_signal(state, &tuple_key, "TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK", strategy_family);
            return;
        }
        if entry.udp_failed {
            clear_negative_state(entry);
            emit_learning_signal(state, &tuple_key, "QUIC_BLOCKED_TCP_OK", None);
        }
    }

    pub(super) fn note_all_ips_failed(&mut self, state: &RuntimeState, host: Option<&str>, targets: &[SocketAddr]) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuples.entry(tuple_key.clone()).or_default();
        if entry.terminal_state == Some(TerminalState::AllIpsFailed) {
            return;
        }
        clear_negative_state(entry);
        entry.terminal_state = Some(TerminalState::AllIpsFailed);
        emit_learning_signal(state, &tuple_key, "ALL_IPS_FAILED", None);
    }

    pub(super) fn emit_due_timeouts(&mut self, state: &RuntimeState, now_ms: u64) {
        let due = self
            .tuples
            .iter()
            .filter_map(|(tuple_key, entry)| {
                entry
                    .pending_udp_suppressed_at_ms
                    .filter(|value| now_ms.saturating_sub(*value) >= NO_TCP_FALLBACK_WINDOW_MS)
                    .map(|_| tuple_key.clone())
            })
            .collect::<Vec<_>>();

        for tuple_key in due {
            if let Some(entry) = self.tuples.get_mut(&tuple_key) {
                clear_negative_state(entry);
                entry.terminal_state = Some(TerminalState::NoTcpFallbackDetected);
                emit_learning_signal(state, &tuple_key, "NO_TCP_FALLBACK_DETECTED", None);
            }
        }
    }
}

fn clear_negative_state(entry: &mut TupleState) {
    entry.udp_failed = false;
    entry.tls_post_client_hello_failed = false;
    entry.pending_udp_suppressed_at_ms = None;
}

fn emit_learning_signal(
    state: &RuntimeState,
    tuple_key: &TupleKey,
    event: &'static str,
    strategy_family: Option<&str>,
) {
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_direct_path_learning_signal(
            tuple_key.authority.as_str(),
            tuple_key.ip_set_digest.as_str(),
            event,
            strategy_family,
        );
    }
}

fn tuple_key_for_targets(host: Option<&str>, targets: &[SocketAddr]) -> Option<TupleKey> {
    let first = *targets.first()?;
    Some(TupleKey { authority: normalize_authority(host, first), ip_set_digest: direct_path_ip_set_digest(targets) })
}

fn normalize_authority(host: Option<&str>, target: SocketAddr) -> String {
    host.map(str::trim)
        .filter(|value| !value.is_empty())
        .map(|value| format!("{}:{}", value.trim_end_matches('.').to_ascii_lowercase(), target.port()))
        .unwrap_or_else(|| target.to_string().to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::sync::{Arc as StdArc, Mutex};

    use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
    use crate::adaptive_tuning::AdaptivePlannerResolver;
    use crate::retry_stealth::RetryPacer;
    use crate::runtime::state::RuntimeState;
    use crate::runtime_policy::RuntimePolicy;
    use crate::strategy_evolver::StrategyEvolver;
    use crate::sync::{Arc, AtomicBool, AtomicUsize, RwLock};
    use crate::RuntimeTelemetrySink;
    use ripdpi_config::RuntimeConfig;

    #[derive(Default)]
    struct RecordingTelemetry {
        signals: Mutex<Vec<(String, String, &'static str, Option<String>)>>,
    }

    impl RuntimeTelemetrySink for RecordingTelemetry {
        fn on_direct_path_learning_signal(
            &self,
            authority: &str,
            ip_set_digest: &str,
            event: &'static str,
            strategy_family: Option<&str>,
        ) {
            self.signals.lock().expect("signals").push((
                authority.to_string(),
                ip_set_digest.to_string(),
                event,
                strategy_family.map(ToOwned::to_owned),
            ));
        }
        fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}
        fn on_listener_stopped(&self) {}
        fn on_client_accepted(&self) {}
        fn on_client_finished(&self) {}
        fn on_client_error(&self, _error: &std::io::Error) {}
        fn on_route_selected(
            &self,
            _target: SocketAddr,
            _group_index: usize,
            _host: Option<&str>,
            _phase: &'static str,
        ) {
        }
        fn on_failure_classified(
            &self,
            _target: SocketAddr,
            _failure: &ripdpi_failure_classifier::ClassifiedFailure,
            _host: Option<&str>,
        ) {
        }
        fn on_route_advanced(
            &self,
            _target: SocketAddr,
            _from_group: usize,
            _to_group: usize,
            _trigger: u32,
            _host: Option<&str>,
        ) {
        }
        fn on_host_autolearn_state(
            &self,
            _enabled: bool,
            _learned_host_count: usize,
            _penalized_host_count: usize,
            _blocked_host_count: usize,
            _last_block_signal: Option<&str>,
            _last_block_provider: Option<&str>,
        ) {
        }
        fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}
    }

    fn runtime_state(telemetry: StdArc<dyn RuntimeTelemetrySink>) -> RuntimeState {
        let config = RuntimeConfig::default();
        RuntimeState {
            config: Arc::new(config.clone()),
            cache: Arc::new(RwLock::new(RuntimePolicy::load(&config))),
            adaptive_fake_ttl: Arc::new(RwLock::new(AdaptiveFakeTtlResolver::default())),
            adaptive_tuning: Arc::new(RwLock::new(AdaptivePlannerResolver::default())),
            retry_stealth: Arc::new(RwLock::new(RetryPacer::default())),
            strategy_evolver: Arc::new(RwLock::new(StrategyEvolver::new(false, 0.0))),
            direct_path_learning: Arc::new(RwLock::new(DirectPathLearningState::default())),
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry: Some(telemetry),
            runtime_context: None,
            control: None,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(crate::runtime::reprobe::ReprobeTracker::new()),
            dns_hostname_cache: std::sync::Arc::new(
                crate::dns_hostname_cache::DnsHostnameCache::with_default_capacity(),
            ),
            pcap_hook: None,
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
        }
    }

    #[test]
    fn udp_failure_then_tcp_success_emits_quic_blocked_signal() {
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target"), "203.0.113.11:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        learner.note_udp_failure(Some("example.org"), &targets);
        learner.note_tcp_success(&state, Some("example.org"), &targets, Some("tlsrec_split"));

        let signals = telemetry.signals.lock().expect("signals");
        assert_eq!(signals.len(), 1);
        assert_eq!(signals[0].2, "QUIC_BLOCKED_TCP_OK");
    }

    #[test]
    fn tls_failure_then_tcp_success_emits_post_client_hello_signal_with_family() {
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        learner.note_tls_post_client_hello_failure(Some("example.org"), &targets);
        learner.note_tcp_success(&state, Some("example.org"), &targets, Some("tlsrec_split"));

        let signals = telemetry.signals.lock().expect("signals");
        assert_eq!(signals.len(), 1);
        assert_eq!(signals[0].2, "TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK");
        assert_eq!(signals[0].3.as_deref(), Some("tlsrec_split"));
    }

    #[test]
    fn learner_emits_all_ips_failed_once_per_transition() {
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        learner.note_all_ips_failed(&state, Some("example.org"), &targets);
        learner.note_all_ips_failed(&state, Some("example.org"), &targets);

        let signals = telemetry.signals.lock().expect("signals");
        assert_eq!(signals.len(), 1);
        assert_eq!(signals[0].2, "ALL_IPS_FAILED");
    }

    #[test]
    fn no_tcp_fallback_timeout_emits_signal_and_tcp_attempt_clears_pending_state() {
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        learner.note_udp_suppressed(Some("example.org"), &targets, 10);
        learner.note_transport_attempt(&state, Some("example.org"), &targets, TransportProtocol::Tcp);
        learner.emit_due_timeouts(&state, 3_100);
        assert!(telemetry.signals.lock().expect("signals").is_empty());

        learner.note_udp_suppressed(Some("example.org"), &targets, 10);
        learner.emit_due_timeouts(&state, 3_100);

        let signals = telemetry.signals.lock().expect("signals");
        assert_eq!(signals.len(), 1);
        assert_eq!(signals[0].2, "NO_TCP_FALLBACK_DETECTED");
    }

    #[test]
    fn quic_success_clears_negative_state_and_allows_future_relearning() {
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        learner.note_udp_failure(Some("example.org"), &targets);
        learner.note_quic_success(&state, Some("example.org"), &targets);
        learner.note_udp_failure(Some("example.org"), &targets);
        learner.note_tcp_success(&state, Some("example.org"), &targets, Some("split"));

        let signals = telemetry.signals.lock().expect("signals");
        assert_eq!(signals.len(), 2);
        assert_eq!(signals[0].2, "QUIC_SUCCESS");
        assert_eq!(signals[1].2, "QUIC_BLOCKED_TCP_OK");
    }
}
