// Ranked-arm dispatcher for adaptive direct-path learning.
#![allow(dead_code)]

use std::collections::HashMap;
use std::net::SocketAddr;

use super::adaptive::direct_path_ip_set_digest;
use super::state::RuntimeState;
use crate::runtime_policy::TransportProtocol;

const NO_TCP_FALLBACK_WINDOW_MS: u64 = 3_000;

// ---------------------------------------------------------------------------
// Ranked-arm dispatcher types
// ---------------------------------------------------------------------------

/// The observed block class for a direct-path (host, ip-set) tuple.
///
/// Derived from the accumulated `TupleState` flags and terminal state.  Each
/// variant maps to a distinct ranked arm list via
/// [`DirectPathLearningState::ranked_arms_for`].
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum DirectPathBlockClass {
    /// No negative evidence — plain TCP or QUIC is worth trying first.
    Clean,
    /// UDP/QUIC datagrams are being dropped; TCP-based arms rank higher.
    QuicBlocked,
    /// TLS post-ClientHello interference detected; record-split arms rank higher.
    TlsPostClientHello,
    /// Both UDP blocked and TLS interference observed simultaneously.
    QuicBlockedAndTlsPostClientHello,
    /// UDP was suppressed and no TCP fallback appeared within the observation
    /// window; the host may be UDP-only or completely unreachable directly.
    NoTcpFallback,
    /// Every known IP for the target failed; relay is the only remaining option.
    AllIpsFailed,
    /// A previous QUIC attempt succeeded; QUIC arms rank highest.
    QuicConfirmed,
}

/// A single candidate arm returned by the ranked dispatcher.
///
/// Arms are ordered so that index 0 is the highest-priority choice.  The
/// `score` field is a normalised `f32` in `[0, 1]` where higher means "try
/// this arm first". `attempt_budget` is currently a conservative per-arm
/// default.
#[derive(Clone, Debug, PartialEq)]
pub(super) struct RankedArm {
    /// Short label identifying the transport / strategy arm.
    pub(super) label: &'static str,
    /// Normalised priority score.  Higher = preferred.
    pub(super) score: f32,
    /// Block class that caused this arm to be ranked at this position.
    pub(super) class: DirectPathBlockClass,
    /// Remaining attempt budget before the arm should be backed off.
    pub(super) attempt_budget: u32,
}

// ---------------------------------------------------------------------------
// Internal state types
// ---------------------------------------------------------------------------

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
    /// Per-arm attempt counters. Keys are static arm labels matching
    /// [`RankedArm::label`]; values are the number of attempts recorded for
    /// the current block-class window. Cleared by [`clear_negative_state`]
    /// alongside the rest of the negative evidence so a positive signal
    /// resets the budget.
    arm_attempts: HashMap<&'static str, u32>,
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

    /// Derive the current [`DirectPathBlockClass`] for a (host, targets) tuple.
    ///
    /// Returns `DirectPathBlockClass::Clean` when no negative evidence has been
    /// recorded yet, so callers can always obtain a valid class without special-
    /// casing the absent-tuple case.
    pub(super) fn block_class_for(&self, host: Option<&str>, targets: &[SocketAddr]) -> DirectPathBlockClass {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return DirectPathBlockClass::Clean;
        };
        let Some(entry) = self.tuples.get(&tuple_key) else {
            return DirectPathBlockClass::Clean;
        };
        block_class_from_state(entry)
    }

    /// Return a ranked list of transport arms for the given (host, targets) tuple.
    ///
    /// Arms are ordered from highest priority (index 0) to lowest. The list
    /// always contains at least one entry. Callers may iterate and attempt
    /// each arm in order, stopping on the first success. Each arm's
    /// `attempt_budget` reflects the *remaining* budget after subtracting
    /// previously recorded attempts via [`Self::note_arm_attempt`]; arms
    /// whose budget is fully exhausted are dropped from the list. When all
    /// arms for the current class are exhausted the list collapses to a
    /// single `relay_fallback` entry so callers always have an escalation
    /// path.
    pub(super) fn ranked_arms_for(&self, host: Option<&str>, targets: &[SocketAddr]) -> Vec<RankedArm> {
        let class = self.block_class_for(host, targets);
        let mut arms = ranked_arms_for_class(class);

        let attempts: Option<&HashMap<&'static str, u32>> = tuple_key_for_targets(host, targets)
            .as_ref()
            .and_then(|key| self.tuples.get(key))
            .map(|entry| &entry.arm_attempts);

        if let Some(attempts) = attempts {
            arms.retain_mut(|arm| {
                let used = attempts.get(arm.label).copied().unwrap_or(0);
                if used >= arm.attempt_budget {
                    return false;
                }
                arm.attempt_budget -= used;
                true
            });

            if arms.is_empty() {
                arms.push(RankedArm {
                    label: "relay_fallback",
                    score: 0.5,
                    class: DirectPathBlockClass::AllIpsFailed,
                    attempt_budget: 1,
                });
            }
        }

        arms
    }

    /// Record an attempt against the arm `arm_label` for the (host, targets)
    /// tuple. Subsequent calls to [`Self::ranked_arms_for`] subtract the
    /// recorded attempts from the arm's `attempt_budget`; once the budget is
    /// exhausted the arm is dropped from the ranked list. Counters are reset
    /// when a positive signal clears the negative state for the tuple.
    pub(super) fn note_arm_attempt(&mut self, host: Option<&str>, targets: &[SocketAddr], arm_label: &'static str) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuples.entry(tuple_key).or_default();
        *entry.arm_attempts.entry(arm_label).or_insert(0) += 1;
    }
}

// ---------------------------------------------------------------------------
// Ranked-arm dispatcher implementation
// ---------------------------------------------------------------------------

/// Derive the block class from a recorded `TupleState`.
fn block_class_from_state(entry: &TupleState) -> DirectPathBlockClass {
    match entry.terminal_state {
        Some(TerminalState::QuicSuccess) => DirectPathBlockClass::QuicConfirmed,
        Some(TerminalState::AllIpsFailed) => DirectPathBlockClass::AllIpsFailed,
        Some(TerminalState::NoTcpFallbackDetected) => DirectPathBlockClass::NoTcpFallback,
        None => match (entry.udp_failed, entry.tls_post_client_hello_failed) {
            (true, true) => DirectPathBlockClass::QuicBlockedAndTlsPostClientHello,
            (true, false) => DirectPathBlockClass::QuicBlocked,
            (false, true) => DirectPathBlockClass::TlsPostClientHello,
            (false, false) => DirectPathBlockClass::Clean,
        },
    }
}

/// Default attempt budget used for all arms.
const DEFAULT_ATTEMPT_BUDGET: u32 = 3;

/// Return the ranked arm list for a given block class.
///
/// Each variant encodes expert knowledge about which transport arms are most
/// likely to succeed for the given failure pattern.  Scores are chosen so that
/// the relative ordering is clear but not sensitive to floating-point equality.
fn ranked_arms_for_class(class: DirectPathBlockClass) -> Vec<RankedArm> {
    match class {
        DirectPathBlockClass::Clean => vec![
            RankedArm { label: "quic", score: 0.9, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.8, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::QuicBlocked => vec![
            RankedArm { label: "tcp_plain", score: 0.9, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_tls_split", score: 0.7, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::TlsPostClientHello => vec![
            RankedArm { label: "tcp_tls_split", score: 0.9, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.6, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::QuicBlockedAndTlsPostClientHello => vec![
            RankedArm { label: "tcp_tls_split", score: 0.9, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.5, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::NoTcpFallback => vec![
            RankedArm { label: "quic", score: 0.8, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.3, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::AllIpsFailed => {
            vec![RankedArm { label: "relay_fallback", score: 0.9, class, attempt_budget: 1 }]
        }
        DirectPathBlockClass::QuicConfirmed => vec![
            RankedArm { label: "quic", score: 1.0, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.4, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
    }
}

fn clear_negative_state(entry: &mut TupleState) {
    entry.udp_failed = false;
    entry.tls_post_client_hello_failed = false;
    entry.pending_udp_suppressed_at_ms = None;
    // Reset the per-arm attempt counters: a positive signal restarts the
    // budget window for the new block class.
    entry.arm_attempts.clear();
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
    host.map(str::trim).filter(|value| !value.is_empty()).map_or_else(
        || target.to_string().to_ascii_lowercase(),
        |value| format!("{}:{}", value.trim_end_matches('.').to_ascii_lowercase(), target.port()),
    )
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

    // Ranked-arm dispatcher

    #[test]
    fn ranked_arms_clean_tuple_prefers_quic() {
        let learner = DirectPathLearningState::default();
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert!(!arms.is_empty(), "must return at least one arm");
        assert_eq!(arms[0].label, "quic", "clean tuple: quic should rank first");
        assert!(arms[0].score > arms[1].score, "scores must be strictly descending");
        assert_eq!(arms[0].class, DirectPathBlockClass::Clean);
    }

    #[test]
    fn ranked_arms_quic_blocked_prefers_tcp() {
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();
        learner.note_udp_failure(Some("example.org"), &targets);

        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(arms[0].label, "tcp_plain", "quic_blocked: tcp_plain should rank first");
        assert_eq!(arms[0].class, DirectPathBlockClass::QuicBlocked);
    }

    #[test]
    fn ranked_arms_tls_post_client_hello_prefers_split() {
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();
        learner.note_tls_post_client_hello_failure(Some("example.org"), &targets);

        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(arms[0].label, "tcp_tls_split", "tls_post_client_hello: split should rank first");
        assert_eq!(arms[0].class, DirectPathBlockClass::TlsPostClientHello);
    }

    #[test]
    fn ranked_arms_all_ips_failed_returns_relay_fallback_only() {
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();
        learner.note_all_ips_failed(&state, Some("example.org"), &targets);

        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(arms.len(), 1, "all_ips_failed: exactly one relay arm");
        assert_eq!(arms[0].label, "relay_fallback");
        assert_eq!(arms[0].attempt_budget, 1, "relay fallback budget should be 1");
        assert_eq!(arms[0].class, DirectPathBlockClass::AllIpsFailed);
    }

    #[test]
    fn ranked_arms_quic_confirmed_ranks_quic_with_score_one() {
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();
        learner.note_quic_success(&state, Some("example.org"), &targets);

        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(arms[0].label, "quic");
        assert!((arms[0].score - 1.0_f32).abs() < f32::EPSILON, "quic_confirmed: score should be 1.0");
        assert_eq!(arms[0].class, DirectPathBlockClass::QuicConfirmed);
    }

    #[test]
    fn ranked_arms_unknown_host_returns_clean_arms() {
        let learner = DirectPathLearningState::default();
        // Host not seen before — should return Clean arms without panic.
        let targets = vec!["203.0.113.99:443".parse().expect("target")];
        let arms = learner.ranked_arms_for(Some("never-seen.example"), &targets);
        assert_eq!(arms[0].class, DirectPathBlockClass::Clean);
    }

    #[test]
    fn block_class_for_reflects_both_udp_and_tls_failures() {
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();
        learner.note_udp_failure(Some("example.org"), &targets);
        learner.note_tls_post_client_hello_failure(Some("example.org"), &targets);

        let class = learner.block_class_for(Some("example.org"), &targets);
        assert_eq!(class, DirectPathBlockClass::QuicBlockedAndTlsPostClientHello);

        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(arms[0].label, "tcp_tls_split");
        assert_eq!(arms[0].class, DirectPathBlockClass::QuicBlockedAndTlsPostClientHello);
    }

    #[test]
    fn ranked_arms_are_strictly_score_descending() {
        // For every block class, verify the returned slice is sorted descending.
        let all_classes = [
            DirectPathBlockClass::Clean,
            DirectPathBlockClass::QuicBlocked,
            DirectPathBlockClass::TlsPostClientHello,
            DirectPathBlockClass::QuicBlockedAndTlsPostClientHello,
            DirectPathBlockClass::NoTcpFallback,
            DirectPathBlockClass::AllIpsFailed,
            DirectPathBlockClass::QuicConfirmed,
        ];
        for class in all_classes {
            let arms = ranked_arms_for_class(class);
            assert!(!arms.is_empty(), "{class:?}: arm list must be non-empty");
            for window in arms.windows(2) {
                assert!(
                    window[0].score >= window[1].score,
                    "{class:?}: arms must be score-descending, got {} then {}",
                    window[0].score,
                    window[1].score,
                );
            }
        }
    }

    // Per-class attempt-budget enforcement

    #[test]
    fn note_arm_attempt_decrements_remaining_budget() {
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        // Fresh tuple — full default budget.
        let initial = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(initial[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET);

        learner.note_arm_attempt(Some("example.org"), &targets, "quic");
        let after_one = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(after_one[0].label, "quic");
        assert_eq!(
            after_one[0].attempt_budget,
            DEFAULT_ATTEMPT_BUDGET - 1,
            "remaining budget must reflect recorded attempts",
        );
        // Sibling arm is unaffected.
        assert_eq!(after_one[1].label, "tcp_plain");
        assert_eq!(after_one[1].attempt_budget, DEFAULT_ATTEMPT_BUDGET);
    }

    #[test]
    fn ranked_arms_drops_exhausted_arm_and_keeps_remaining() {
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        for _ in 0..DEFAULT_ATTEMPT_BUDGET {
            learner.note_arm_attempt(Some("example.org"), &targets, "quic");
        }

        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(arms.len(), 1, "exhausted quic should drop, leaving tcp_plain");
        assert_eq!(arms[0].label, "tcp_plain");
        assert_eq!(arms[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET);
    }

    #[test]
    fn ranked_arms_collapses_to_relay_fallback_when_all_arms_exhausted() {
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        for _ in 0..DEFAULT_ATTEMPT_BUDGET {
            learner.note_arm_attempt(Some("example.org"), &targets, "quic");
        }
        for _ in 0..DEFAULT_ATTEMPT_BUDGET {
            learner.note_arm_attempt(Some("example.org"), &targets, "tcp_plain");
        }

        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(arms.len(), 1, "must escalate to relay fallback");
        assert_eq!(arms[0].label, "relay_fallback");
        assert_eq!(arms[0].class, DirectPathBlockClass::AllIpsFailed);
        assert_eq!(arms[0].attempt_budget, 1);
    }

    #[test]
    fn positive_signal_resets_arm_attempts() {
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();

        // Burn the quic budget…
        for _ in 0..DEFAULT_ATTEMPT_BUDGET {
            learner.note_arm_attempt(Some("example.org"), &targets, "quic");
        }
        // …then a successful QUIC observation should clear counters.
        learner.note_quic_success(&state, Some("example.org"), &targets);

        let arms = learner.ranked_arms_for(Some("example.org"), &targets);
        assert_eq!(arms[0].class, DirectPathBlockClass::QuicConfirmed);
        assert_eq!(arms[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET, "budget must reset on positive signal");
    }

    // Deterministic class-to-arm execution ladder

    #[test]
    fn class_to_arm_ladder_walks_clean_quic_blocked_exhausted_relay_and_back() {
        // Drive a single tuple through the full life cycle and assert the
        // ranked-arm response at every step. This pins the contract that
        // negative signals advance the class, attempt budgets shrink, the
        // exhausted arm drops, and a positive signal restores the original
        // ranking.
        let telemetry = StdArc::new(RecordingTelemetry::default());
        let state = runtime_state(telemetry.clone());
        let targets = vec!["203.0.113.10:443".parse().expect("target")];
        let mut learner = DirectPathLearningState::default();
        let host = Some("example.org");

        // Step 1: clean tuple → quic ranks first.
        let step1 = learner.ranked_arms_for(host, &targets);
        assert_eq!(step1[0].label, "quic");
        assert_eq!(step1[0].class, DirectPathBlockClass::Clean);
        assert_eq!(step1[1].label, "tcp_plain");

        // Step 2: UDP failure flips us to QuicBlocked → tcp_plain ranks first.
        learner.note_udp_failure(host, &targets);
        let step2 = learner.ranked_arms_for(host, &targets);
        assert_eq!(step2[0].label, "tcp_plain");
        assert_eq!(step2[0].class, DirectPathBlockClass::QuicBlocked);
        assert_eq!(step2[1].label, "tcp_tls_split");
        assert_eq!(step2[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET);

        // Step 3: record three tcp_plain attempts → arm drops, tcp_tls_split is left.
        for _ in 0..DEFAULT_ATTEMPT_BUDGET {
            learner.note_arm_attempt(host, &targets, "tcp_plain");
        }
        let step3 = learner.ranked_arms_for(host, &targets);
        assert_eq!(step3.len(), 1);
        assert_eq!(step3[0].label, "tcp_tls_split");
        assert_eq!(step3[0].class, DirectPathBlockClass::QuicBlocked);

        // Step 4: exhaust tcp_tls_split too → escalate to relay_fallback.
        for _ in 0..DEFAULT_ATTEMPT_BUDGET {
            learner.note_arm_attempt(host, &targets, "tcp_tls_split");
        }
        let step4 = learner.ranked_arms_for(host, &targets);
        assert_eq!(step4.len(), 1);
        assert_eq!(step4[0].label, "relay_fallback");
        assert_eq!(step4[0].class, DirectPathBlockClass::AllIpsFailed);
        assert_eq!(step4[0].attempt_budget, 1);

        // Step 5: a successful TCP observation while UDP-failed clears the
        // negative state → fresh ranking, fresh budgets.
        learner.note_tcp_success(&state, host, &targets, Some("split"));
        let step5 = learner.ranked_arms_for(host, &targets);
        assert_eq!(step5[0].label, "quic", "after positive TCP, class is back to Clean");
        assert_eq!(step5[0].class, DirectPathBlockClass::Clean);
        assert_eq!(step5[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET);
        assert_eq!(step5[1].label, "tcp_plain");
        assert_eq!(step5[1].attempt_budget, DEFAULT_ATTEMPT_BUDGET);

        // Confirm telemetry observed the QUIC-blocked / TCP-OK transition,
        // matching the existing `quic_success_clears_negative_state` test
        // expectations for the same state machine path.
        let signals = telemetry.signals.lock().expect("signals");
        assert!(
            signals.iter().any(|s| s.2 == "QUIC_BLOCKED_TCP_OK"),
            "expected QUIC_BLOCKED_TCP_OK in: {:?}",
            *signals,
        );
    }
}
