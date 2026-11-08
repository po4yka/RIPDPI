use super::*;
use std::sync::Mutex;

#[derive(Default)]
struct Signals(Mutex<Vec<(&'static str, Option<String>)>>);
impl DirectPathLearningObserver for Signals {
    fn on_direct_path_learning_signal(
        &self,
        _: &str,
        _: &str,
        event: &'static str,
        _: Option<&str>,
        app_scope: Option<&str>,
    ) {
        self.0.lock().expect("signals").push((event, app_scope.map(str::to_owned)));
    }
}

#[test]
fn another_app_tcp_attempt_does_not_cancel_udp_fallback_deadline() {
    let mut learner = DirectPathLearningState::default();
    let targets = ["203.0.113.7:443".parse().expect("target")];
    let signals = Signals::default();
    learner.note_udp_suppressed(Some("shared.example"), &targets, 10, Some("app-a"));
    learner.note_transport_attempt(Some("shared.example"), &targets, TransportProtocol::Tcp, Some("app-b"));
    learner.emit_due_timeouts(Some(&signals), 3_010);
    assert_eq!(*signals.0.lock().expect("signals"), vec![("NO_TCP_FALLBACK_DETECTED", Some("app-a".to_owned()))]);
}

#[test]
fn same_app_tcp_attempt_cancels_udp_deadline_across_distinct_flows() {
    let mut learner = DirectPathLearningState::default();
    let targets = ["203.0.113.7:443".parse().expect("target")];
    let signals = Signals::default();
    learner.note_udp_suppressed(Some("shared.example"), &targets, 10, Some("app-a"));
    learner.note_transport_attempt(Some("shared.example"), &targets, TransportProtocol::Tcp, Some("app-a"));
    learner.emit_due_timeouts(Some(&signals), 3_010);
    assert!(signals.0.lock().expect("signals").is_empty());
}

#[test]
fn another_app_tcp_success_does_not_turn_udp_failure_into_a_recovery() {
    let mut learner = DirectPathLearningState::default();
    let targets = ["203.0.113.7:443".parse().expect("target")];
    let signals = Signals::default();
    learner.note_udp_failure(None, &targets, Some("app-a"));
    learner.note_tcp_success(Some(&signals), None, &targets, None, Some("app-b"));
    assert!(signals.0.lock().expect("signals").is_empty());
    learner.note_tcp_success(Some(&signals), None, &targets, None, Some("app-a"));
    assert_eq!(*signals.0.lock().expect("signals"), vec![("QUIC_BLOCKED_TCP_OK", Some("app-a".to_owned()))]);
}

#[test]
fn unknown_scope_does_not_share_known_app_learning() {
    let mut learner = DirectPathLearningState::default();
    let targets = ["[2001:db8::7]:443".parse().expect("target")];
    let signals = Signals::default();
    learner.note_udp_failure(None, &targets, Some("app-a"));
    learner.note_tcp_success(Some(&signals), None, &targets, None, None);
    assert!(signals.0.lock().expect("signals").is_empty());
}
