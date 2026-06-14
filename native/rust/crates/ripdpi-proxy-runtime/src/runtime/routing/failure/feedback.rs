use std::io;
use std::net::SocketAddr;

use crate::runtime::adaptive::{
    note_adaptive_fake_ttl_failure, note_adaptive_tcp_failure, note_direct_path_tls_post_client_hello_failure,
};
use crate::runtime::failure::{RuntimeClassifiedFailure, RuntimeFailureClass};
use crate::runtime::retry::note_retry_failure;
use crate::runtime::routing::policy::preferred_targets_for_transport;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeConnectionRoute, RuntimeTransportProtocol};

pub(super) fn record_failure_feedback(
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
    host: Option<&str>,
    payload: Option<&[u8]>,
    failure: &RuntimeClassifiedFailure,
) -> io::Result<bool> {
    note_retry_failure(state, target, route.group_index, host, payload, RuntimeTransportProtocol::Tcp)?;
    let penalize = RuntimeState::failure_penalizes_strategy(failure);
    if !penalize {
        return Ok(false);
    }

    if matches!(failure.class, RuntimeFailureClass::TlsAlert | RuntimeFailureClass::TlsHandshakeFailure) {
        let targets = preferred_targets_for_transport(state, target, host, RuntimeTransportProtocol::Tcp);
        // The learner update is the side effect of this call (the wrapper is
        // currently infallible, so the TLS post-client-hello failure already
        // reaches the learner). Propagate its result with `?` to match the
        // sibling adaptive calls below and the UDP-path feedback — the last
        // feedback call still using `let _ =` — so that if the wrapper ever
        // becomes fallible the error surfaces instead of being silently dropped.
        // The recorded failure lets a later TCP success emit
        // `TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK`.
        note_direct_path_tls_post_client_hello_failure(state, host, &targets)?;
    }
    if let Some(payload) = payload {
        note_adaptive_tcp_failure(state, target, route.group_index, host, payload)?;
    }
    note_adaptive_fake_ttl_failure(state, target, route.group_index, host)?;
    state.note_evolver_failure(failure.class);
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::adaptive::note_direct_path_tcp_success;
    use crate::runtime::config::RuntimeConfig;
    use std::net::{IpAddr, Ipv4Addr};
    use std::sync::Arc;

    /// Telemetry sink that captures the direct-path learner's state-transition
    /// signals so the test can assert the TLS post-client-hello failure feedback
    /// reaches the learner.
    #[derive(Default)]
    struct RecordingLearnerSink {
        signals: std::sync::Mutex<Vec<&'static str>>,
    }

    impl RecordingLearnerSink {
        fn events(&self) -> Vec<&'static str> {
            self.signals.lock().expect("learner signals lock").clone()
        }
    }

    impl ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink for RecordingLearnerSink {
        fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}
        fn on_listener_stopped(&self) {}
        fn on_client_accepted(&self) {}
        fn on_client_finished(&self) {}
        fn on_client_error(&self, _error: &io::Error) {}
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
            _failure: &crate::runtime::failure::RuntimeClassifiedFailure,
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
        fn on_direct_path_learning_signal(
            &self,
            _authority: &str,
            _ip_set_digest: &str,
            event: &'static str,
            _strategy_family: Option<&str>,
        ) {
            self.signals.lock().expect("learner signals lock").push(event);
        }
    }

    /// Wiring lock: a TLS post-client-hello failure must drive the direct-path
    /// learner's negative state so a subsequent direct-path TCP success emits
    /// `TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK`. The TLS branch of
    /// `record_failure_feedback` was the last feedback call still using `let _ =`
    /// instead of `?`; because that wrapper is infallible the signal already
    /// reached the learner, so this test guards the wiring contract going forward,
    /// at parity with the UDP path's
    /// `udp_path_failure_drives_quic_blocked_learner_state`.
    #[test]
    fn tls_post_client_hello_failure_drives_learner_state() {
        let sink = Arc::new(RecordingLearnerSink::default());
        let state = RuntimeState::test_with_telemetry(RuntimeConfig::default(), Some(sink.clone()));
        let host = Some("example.com");
        let targets = [SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443)];

        note_direct_path_tls_post_client_hello_failure(&state, host, &targets)
            .expect("record tls post-client-hello failure");
        note_direct_path_tcp_success(&state, host, &targets, Some("tlsrec_split")).expect("record tcp success");

        assert!(
            sink.events().contains(&"TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK"),
            "TLS post-client-hello failure must drive the learner's negative state; got {:?}",
            sink.events()
        );
    }
}
