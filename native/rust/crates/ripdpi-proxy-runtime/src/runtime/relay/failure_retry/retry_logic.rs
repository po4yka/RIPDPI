use std::io;
use std::net::SocketAddr;

use crate::runtime::adaptive::{
    note_adaptive_fake_ttl_success, note_adaptive_tcp_success, note_direct_path_tcp_success,
};
use crate::runtime::desync::OutboundSendError;
use crate::runtime::retry::note_retry_success;
use crate::runtime::routing::{note_route_success, preferred_targets_for_transport};
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeClassifiedFailure, RuntimeConnectionRoute, RuntimeTransportProtocol};

pub(crate) fn record_stream_relay_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
    success_host: Option<&str>,
    success_payload: Option<&[u8]>,
    strategy_family: Option<&str>,
    primary_strategy_family: Option<&str>,
) -> io::Result<()> {
    if !RuntimeState::should_track_strategy_target(target) {
        return Ok(());
    }
    if let Some(request) = success_payload {
        let targets = preferred_targets_for_transport(state, target, success_host, RuntimeTransportProtocol::Tcp);
        note_adaptive_tcp_success(state, target, route.group_index, success_host, request)?;
        note_retry_success(
            state,
            target,
            route.group_index,
            success_host,
            Some(request),
            RuntimeTransportProtocol::Tcp,
        )?;
        note_direct_path_tcp_success(state, success_host, &targets, strategy_family.or(primary_strategy_family))?;
    }
    note_adaptive_fake_ttl_success(state, target, route.group_index, success_host)?;
    state.note_evolver_success();
    note_route_success(state, target, route, success_host)?;
    Ok(())
}

pub(crate) fn classify_first_write_failure(error: &OutboundSendError) -> RuntimeClassifiedFailure {
    RuntimeState::classify_first_write_failure(error)
}

pub(crate) fn should_retry_syn_data_without_tfo(
    route_requests_direct_syn_data_tfo: bool,
    failure: &RuntimeClassifiedFailure,
    already_retried: bool,
) -> bool {
    RuntimeState::first_write_failure_retries_syn_data_without_tfo(
        route_requests_direct_syn_data_tfo,
        failure,
        already_retried,
    )
}
