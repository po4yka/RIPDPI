use std::io;

use super::flow::UdpFlowActivationState;
use crate::runtime::adaptive::{
    note_adaptive_udp_failure, note_adaptive_udp_success, note_direct_path_all_ips_failed,
    note_direct_path_quic_success, note_direct_path_udp_failure,
};
use crate::runtime::retry::{
    build_retry_selection_penalties, maybe_emit_candidate_diversification, note_retry_failure, note_retry_success,
};
use crate::runtime::routing::{note_block_signal_for_failure, note_route_success_for_transport};
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeRouteAdvance, RuntimeTransportProtocol};

pub(super) fn note_udp_first_response_success(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
) -> io::Result<()> {
    if !entry.awaiting_response {
        return Ok(());
    }

    note_direct_path_quic_success(state, entry.host.as_deref(), &entry.target_candidates)?;
    note_adaptive_udp_success(
        state,
        entry.current_target,
        entry.route.group_index,
        entry.host.as_deref(),
        &entry.payload,
    )?;
    note_retry_success(
        state,
        entry.current_target,
        entry.route.group_index,
        entry.host.as_deref(),
        Some(&entry.payload),
        RuntimeTransportProtocol::Udp,
    )?;
    note_route_success_for_transport(
        state,
        entry.current_target,
        &entry.route,
        entry.host.as_deref(),
        RuntimeTransportProtocol::Udp,
    )?;
    state.note_evolver_success();
    entry.awaiting_response = false;
    Ok(())
}

pub(super) fn note_udp_flow_timeout_failure(state: &RuntimeState, entry: &UdpFlowActivationState) -> io::Result<()> {
    if let Some(failure) = RuntimeState::udp_flow_timeout_failure() {
        note_block_signal_for_failure(state, entry.host.as_deref(), &failure, None);
    }
    let failed_target = entry.current_target;
    note_retry_failure(
        state,
        failed_target,
        entry.route.group_index,
        entry.host.as_deref(),
        Some(entry.payload.as_slice()),
        RuntimeTransportProtocol::Udp,
    )?;
    note_direct_path_udp_failure(state, entry.host.as_deref(), &entry.target_candidates)?;
    note_adaptive_udp_failure(state, failed_target, entry.route.group_index, entry.host.as_deref(), &entry.payload)?;
    state.note_evolver_failure(RuntimeState::silent_drop_failure_class());
    let retry_penalties = build_retry_selection_penalties(
        state,
        failed_target,
        entry.host.as_deref(),
        Some(entry.payload.as_slice()),
        RuntimeTransportProtocol::Udp,
    )?;
    let next = state.advance_route(
        &entry.route,
        RuntimeRouteAdvance {
            dest: failed_target,
            payload: Some(entry.payload.as_slice()),
            transport: RuntimeTransportProtocol::Udp,
            trigger: RuntimeState::connect_failure_trigger(),
            can_reconnect: true,
            host: entry.host.clone(),
            penalize_strategy_failure: false,
            retry_penalties: Some(&retry_penalties),
        },
    )?;
    if let Some(next_route) = next.as_ref() {
        maybe_emit_candidate_diversification(state, failed_target, next_route, &retry_penalties);
    }
    if let Some(next) = next {
        state.note_route_advanced(
            failed_target,
            entry.route.group_index,
            next.group_index,
            RuntimeState::connect_failure_trigger(),
            entry.host.as_deref(),
        );
    } else {
        note_direct_path_all_ips_failed(state, entry.host.as_deref(), &entry.target_candidates)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::config::RuntimeConfig;
    use crate::runtime::destination_routing::DestinationEgress;
    use crate::runtime::types::RuntimeConnectionRoute;
    use crate::runtime::udp::flow::UdpFlowActivationState;
    use crate::runtime::udp::session::UdpFlowSession;
    use crate::runtime::udp::{RuntimeUdpPacketSettings, RuntimeUdpSocketSettings, RuntimeUdpSourceRebindPolicy};
    use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
    use std::time::Instant;

    fn flow_entry(awaiting_response: bool) -> UdpFlowActivationState {
        let current_target = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443);
        UdpFlowActivationState {
            session: UdpFlowSession::new(),
            last_used: Instant::now(),
            route: RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 },
            destination_egress: DestinationEgress::Tunneled,
            socket_settings: RuntimeUdpSocketSettings { bind_low_port: false },
            packet_settings: RuntimeUdpPacketSettings { default_ttl: 64, ip_id_mode: None },
            source_rebind_policy: RuntimeUdpSourceRebindPolicy::after_handshake(false),
            execution_family: None,
            attempt_token: None,
            host: Some("example.com".to_string()),
            payload: b"quic-initial".to_vec(),
            awaiting_response,
            upstream: UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp upstream"),
            quic_migrated: false,
            logical_target: current_target,
            current_target,
            target_candidates: vec![current_target],
            target_index: 0,
            cache_host: true,
            upstream_socks: None,
        }
    }

    #[test]
    fn udp_first_response_success_is_idempotent_after_first_observation() {
        let state = RuntimeState::test(RuntimeConfig::default());
        let mut entry = flow_entry(true);

        note_udp_first_response_success(&state, &mut entry).expect("record udp success");
        assert!(!entry.awaiting_response);
        note_udp_first_response_success(&state, &mut entry).expect("second success is ignored");
    }

    #[test]
    fn udp_timeout_failure_records_failed_flow_without_next_route() {
        let state = RuntimeState::test(RuntimeConfig::default());
        let entry = flow_entry(true);

        note_udp_flow_timeout_failure(&state, &entry).expect("record udp timeout");
    }

    /// A recording telemetry sink that captures the direct-path learner's
    /// state-transition signals so a test can assert the UDP-path-failure /
    /// route-exhaustion feedback actually reached the learner (rather than
    /// being swallowed by a `let _ =` discard).
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

    /// Regression: a UDP-path failure must drive the learner's QUIC-blocked
    /// transition. Before the fix, `note_direct_path_udp_failure`'s result was
    /// dropped with `let _ =`, so the negative state was never recorded and the
    /// follow-up TCP success could not emit `QUIC_BLOCKED_TCP_OK`.
    ///
    /// The UDP-path-failure feedback is exercised directly (rather than through
    /// the full timeout path) so the assertion isolates the QUIC-blocked
    /// transition from the route-exhaustion transition, which clears the
    /// negative state — see `route_exhaustion_drives_all_ips_failed_learner_state`.
    #[test]
    fn udp_path_failure_drives_quic_blocked_learner_state() {
        use crate::runtime::adaptive::{note_direct_path_tcp_success, note_direct_path_udp_failure};

        let sink = std::sync::Arc::new(RecordingLearnerSink::default());
        let state = RuntimeState::test_with_telemetry(RuntimeConfig::default(), Some(sink.clone()));
        let entry = flow_entry(true);

        // Records the UDP-path failure in the learner (previously discarded).
        note_direct_path_udp_failure(&state, entry.host.as_deref(), &entry.target_candidates)
            .expect("record udp-path failure");

        // A subsequent direct-path TCP success only emits QUIC_BLOCKED_TCP_OK
        // when the prior UDP failure was actually recorded on the same authority.
        note_direct_path_tcp_success(&state, entry.host.as_deref(), &entry.target_candidates, Some("tlsrec_split"))
            .expect("record tcp success");

        assert!(
            sink.events().contains(&"QUIC_BLOCKED_TCP_OK"),
            "UDP-path failure must drive the learner's QUIC-blocked transition; got {:?}",
            sink.events()
        );
    }

    /// Regression: route exhaustion (no next route) must drive the learner's
    /// `ALL_IPS_FAILED` transition. Before the fix the
    /// `note_direct_path_all_ips_failed` result was dropped with `let _ =`.
    #[test]
    fn route_exhaustion_drives_all_ips_failed_learner_state() {
        let sink = std::sync::Arc::new(RecordingLearnerSink::default());
        let state = RuntimeState::test_with_telemetry(RuntimeConfig::default(), Some(sink.clone()));
        let entry = flow_entry(true);

        // Default config has no alternate route, so advance_route returns None
        // and the route-exhaustion feedback fires.
        note_udp_flow_timeout_failure(&state, &entry).expect("record udp timeout");

        assert!(
            sink.events().contains(&"ALL_IPS_FAILED"),
            "route exhaustion must drive the learner's ALL_IPS_FAILED transition; got {:?}",
            sink.events()
        );
    }
}
