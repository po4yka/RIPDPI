use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::failure::{classify_transport_error, ClassifiedFailure, FailureClass, FailureStage};
use ripdpi_runtime_decision_ports::policy::{ConnectionRoute, TransportProtocol};

use super::super::adaptive::{note_direct_path_all_ips_failed, note_direct_path_transport_attempt};
use super::super::state::RuntimeState;
use super::connect::{connect_target_candidates_via_group, group_requests_direct_syn_data_tfo};
use super::failure::{advance_route_for_failure, emit_failure_classified, note_block_signal_for_failure};
use super::policy::{preferred_targets_for_transport, select_route};

pub(in crate::runtime) fn connect_target(
    target: SocketAddr,
    state: &RuntimeState,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    host: Option<String>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    let route = select_route(state, target, payload, host.as_deref(), allow_unknown_payload)?;
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_route_selected(target, route.group_index, host.as_deref(), "initial");
    }
    connect_target_with_route(target, state, route, payload, host)
}

pub(in crate::runtime) fn connect_target_with_route(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: ConnectionRoute,
    payload: Option<&[u8]>,
    host: Option<String>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    let max_retries = state.config.max_route_retries;
    let mut retries: usize = 0;
    loop {
        let attempt_targets = preferred_targets_for_transport(state, target, host.as_deref(), TransportProtocol::Tcp);
        let _ = note_direct_path_transport_attempt(state, host.as_deref(), &attempt_targets, TransportProtocol::Tcp);
        match connect_target_candidates_via_group(&attempt_targets, state, route.group_index, payload, true) {
            Ok(stream) => return Ok((stream, route)),
            Err(mut err) => {
                retries += 1;
                let mut failure = classify_transport_error(FailureStage::Connect, &err.source);
                if should_retry_without_tfo(err.tcp_fast_open_enabled, &failure) {
                    tracing::debug!(group_index = route.group_index, target = %target, "retrying connect without TCP Fast Open");
                    match connect_target_candidates_via_group(
                        &attempt_targets,
                        state,
                        route.group_index,
                        payload,
                        false,
                    ) {
                        Ok(stream) => return Ok((stream, route)),
                        Err(fallback_err) => {
                            err = fallback_err;
                            failure = classify_transport_error(FailureStage::Connect, &err.source);
                        }
                    }
                }
                note_block_signal_for_failure(state, host.as_deref(), &failure, err.tcp_total_retransmissions);
                if retries > max_retries {
                    let _ = note_direct_path_all_ips_failed(state, host.as_deref(), &attempt_targets);
                    return Err(err.into_io_error());
                }
                emit_failure_classified(state, target, &failure, host.as_deref());
                let next = advance_route_for_failure(state, target, &route, host.clone(), payload, &failure)?;
                let Some(next) = next else {
                    let _ = note_direct_path_all_ips_failed(state, host.as_deref(), &attempt_targets);
                    return Err(err.into_io_error());
                };
                route = next;
            }
        }
    }
}

fn should_retry_without_tfo(tcp_fast_open_enabled: bool, failure: &ClassifiedFailure) -> bool {
    tcp_fast_open_enabled && matches!(failure.class, FailureClass::ConnectFailure | FailureClass::TcpReset)
}

pub(in crate::runtime) fn reconnect_target(
    target: SocketAddr,
    state: &RuntimeState,
    route: ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    reconnect_target_with_tfo_mode(target, state, route, host, payload, true)
}

pub(in crate::runtime) fn reconnect_target_without_tfo(
    target: SocketAddr,
    state: &RuntimeState,
    route: ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    reconnect_target_with_tfo_mode(target, state, route, host, payload, false)
}

pub(in crate::runtime) fn route_uses_direct_syn_data_tfo(
    state: &RuntimeState,
    route: &ConnectionRoute,
    payload: Option<&[u8]>,
) -> bool {
    state.config.groups.get(route.group_index).is_some_and(|group| group_requests_direct_syn_data_tfo(group, payload))
}

fn reconnect_target_with_tfo_mode(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    let max_retries = state.config.max_route_retries;
    let mut retries: usize = 0;
    loop {
        crate::runtime::retry::apply_retry_pacing_before_connect(state, target, &route, host.as_deref(), payload)?;
        let attempt_targets = preferred_targets_for_transport(state, target, host.as_deref(), TransportProtocol::Tcp);
        match connect_target_candidates_via_group(&attempt_targets, state, route.group_index, payload, allow_tfo) {
            Ok(stream) => return Ok((stream, route)),
            Err(mut err) => {
                retries += 1;
                if retries > max_retries {
                    return Err(err.into_io_error());
                }
                let mut failure = classify_transport_error(FailureStage::Connect, &err.source);
                if allow_tfo && should_retry_without_tfo(err.tcp_fast_open_enabled, &failure) {
                    tracing::debug!(group_index = route.group_index, target = %target, "retrying reconnect without TCP Fast Open");
                    match connect_target_candidates_via_group(
                        &attempt_targets,
                        state,
                        route.group_index,
                        payload,
                        false,
                    ) {
                        Ok(stream) => return Ok((stream, route)),
                        Err(fallback_err) => {
                            err = fallback_err;
                            failure = classify_transport_error(FailureStage::Connect, &err.source);
                        }
                    }
                }
                emit_failure_classified(state, target, &failure, host.as_deref());
                let next = advance_route_for_failure(state, target, &route, host.clone(), payload, &failure)?;
                let Some(next) = next else {
                    return Err(err.into_io_error());
                };
                route = next;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_runtime_adapter::failure::{
        classify_transport_error, ClassifiedFailure, FailureAction, FailureClass, FailureStage,
    };

    #[test]
    fn max_route_retries_default_is_eight() {
        let config = ripdpi_proxy_runtime_adapter::config::RuntimeConfig::default();
        assert_eq!(config.max_route_retries, 8);
    }

    #[test]
    fn max_route_retries_is_customizable() {
        let config = ripdpi_proxy_runtime_adapter::config::RuntimeConfig { max_route_retries: 3, ..Default::default() };
        assert_eq!(config.max_route_retries, 3);
    }

    #[test]
    fn retry_without_tfo_depends_on_attempt_using_tfo() {
        let connect_failure =
            classify_transport_error(FailureStage::Connect, &io::Error::new(io::ErrorKind::ConnectionRefused, "boom"));
        let reset_failure = ClassifiedFailure::new(
            FailureClass::TcpReset,
            FailureStage::Connect,
            FailureAction::RetryWithMatchingGroup,
            "reset",
        );

        assert!(should_retry_without_tfo(true, &connect_failure));
        assert!(should_retry_without_tfo(true, &reset_failure));
        assert!(!should_retry_without_tfo(false, &connect_failure));
        assert!(!should_retry_without_tfo(false, &reset_failure));
    }
}
