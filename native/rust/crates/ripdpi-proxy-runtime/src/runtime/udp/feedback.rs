use std::io;

use ripdpi_config::DETECT_CONNECT;
use ripdpi_runtime_decision_ports::policy::{RouteAdvance, TransportProtocol};

use super::flow::UdpFlowActivationState;
use crate::runtime::adaptive::{
    note_adaptive_udp_failure, note_adaptive_udp_success, note_direct_path_all_ips_failed,
    note_direct_path_quic_success, note_direct_path_udp_failure, note_evolver_failure, note_evolver_success,
};
use crate::runtime::retry::{
    build_retry_selection_penalties, maybe_emit_candidate_diversification, note_retry_failure, note_retry_success,
};
use crate::runtime::routing::{note_block_signal_for_failure, note_route_success_for_transport};
use crate::runtime::state::RuntimeState;

pub(super) fn note_udp_first_response_success(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
) -> io::Result<()> {
    if !entry.awaiting_response {
        return Ok(());
    }

    let _ = note_direct_path_quic_success(state, entry.host.as_deref(), &entry.target_candidates);
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
        TransportProtocol::Udp,
    )?;
    note_route_success_for_transport(
        state,
        entry.current_target,
        &entry.route,
        entry.host.as_deref(),
        TransportProtocol::Udp,
    )?;
    note_evolver_success(state, 0);
    entry.awaiting_response = false;
    Ok(())
}

pub(super) fn note_udp_flow_timeout_failure(state: &RuntimeState, entry: &UdpFlowActivationState) -> io::Result<()> {
    if let Some(failure) = ripdpi_proxy_runtime_adapter::failure::classify_quic_probe(
        "quic_timeout",
        Some("UDP flow expired before first response"),
    ) {
        note_block_signal_for_failure(state, entry.host.as_deref(), &failure, None);
    }
    let failed_target = entry.current_target;
    note_retry_failure(
        state,
        failed_target,
        entry.route.group_index,
        entry.host.as_deref(),
        Some(entry.payload.as_slice()),
        TransportProtocol::Udp,
    )?;
    let _ = note_direct_path_udp_failure(state, entry.host.as_deref(), &entry.target_candidates);
    note_adaptive_udp_failure(state, failed_target, entry.route.group_index, entry.host.as_deref(), &entry.payload)?;
    note_evolver_failure(state, ripdpi_proxy_runtime_adapter::failure::FailureClass::SilentDrop);
    let retry_penalties = build_retry_selection_penalties(
        state,
        failed_target,
        entry.host.as_deref(),
        Some(entry.payload.as_slice()),
        TransportProtocol::Udp,
    )?;
    let next = state.policy.advance_route(
        &state.config,
        &entry.route,
        RouteAdvance {
            dest: failed_target,
            payload: Some(entry.payload.as_slice()),
            transport: TransportProtocol::Udp,
            trigger: DETECT_CONNECT,
            can_reconnect: true,
            host: entry.host.clone(),
            penalize_strategy_failure: false,
            retry_penalties: Some(&retry_penalties),
        },
    )?;
    if let Some(next_route) = next.as_ref() {
        maybe_emit_candidate_diversification(state, failed_target, next_route, &retry_penalties);
    }
    if let (Some(telemetry), Some(next)) = (&state.telemetry, next) {
        telemetry.on_route_advanced(
            failed_target,
            entry.route.group_index,
            next.group_index,
            DETECT_CONNECT,
            entry.host.as_deref(),
        );
    } else {
        let _ = note_direct_path_all_ips_failed(state, entry.host.as_deref(), &entry.target_candidates);
    }
    Ok(())
}
