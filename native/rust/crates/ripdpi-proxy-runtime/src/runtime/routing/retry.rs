use std::io;
use std::net::{SocketAddr, TcpStream};

use super::super::adaptive::{note_direct_path_all_ips_failed, note_direct_path_transport_attempt};
use super::super::state::RuntimeState;
use super::connect::connect_target_candidates_via_group;
use super::failure::{advance_route_for_failure, emit_failure_classified, note_block_signal_for_failure};
use super::policy::{preferred_targets_for_transport, select_route};
use crate::runtime::types::{RuntimeConnectionRoute, RuntimeTransportProtocol};

pub(in crate::runtime) fn connect_target(
    target: SocketAddr,
    state: &RuntimeState,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    host: Option<String>,
) -> io::Result<(TcpStream, RuntimeConnectionRoute)> {
    let route = select_route(state, target, payload, host.as_deref(), allow_unknown_payload)?;
    state.note_route_selected(target, route.group_index, host.as_deref(), "initial");
    connect_target_with_route(target, state, route, payload, host)
}

pub(in crate::runtime) fn connect_target_with_route(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: RuntimeConnectionRoute,
    payload: Option<&[u8]>,
    host: Option<String>,
) -> io::Result<(TcpStream, RuntimeConnectionRoute)> {
    let mut retries: usize = 0;
    loop {
        let attempt_targets =
            preferred_targets_for_transport(state, target, host.as_deref(), RuntimeTransportProtocol::Tcp);
        let _ =
            note_direct_path_transport_attempt(state, host.as_deref(), &attempt_targets, RuntimeTransportProtocol::Tcp);
        match connect_target_candidates_via_group(&attempt_targets, state, route.group_index, payload, true) {
            Ok(stream) => return Ok((stream, route)),
            Err(mut err) => {
                retries += 1;
                let mut failure = RuntimeState::classify_connect_transport_error(&err.source);
                if RuntimeState::connect_failure_retries_without_tfo(err.tcp_fast_open_enabled, &failure) {
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
                            failure = RuntimeState::classify_connect_transport_error(&err.source);
                        }
                    }
                }
                note_block_signal_for_failure(state, host.as_deref(), &failure, err.tcp_total_retransmissions);
                if retries > state.max_route_retries() {
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

pub(in crate::runtime) fn reconnect_target(
    target: SocketAddr,
    state: &RuntimeState,
    route: RuntimeConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
) -> io::Result<(TcpStream, RuntimeConnectionRoute)> {
    reconnect_target_with_tfo_mode(target, state, route, host, payload, true)
}

pub(in crate::runtime) fn reconnect_target_without_tfo(
    target: SocketAddr,
    state: &RuntimeState,
    route: RuntimeConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
) -> io::Result<(TcpStream, RuntimeConnectionRoute)> {
    reconnect_target_with_tfo_mode(target, state, route, host, payload, false)
}

pub(in crate::runtime) fn route_uses_direct_syn_data_tfo(
    state: &RuntimeState,
    route: &RuntimeConnectionRoute,
    payload: Option<&[u8]>,
) -> bool {
    state.route_uses_direct_syn_data_tfo(route, payload)
}

fn reconnect_target_with_tfo_mode(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: RuntimeConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> io::Result<(TcpStream, RuntimeConnectionRoute)> {
    let mut retries: usize = 0;
    loop {
        crate::runtime::retry::apply_retry_pacing_before_connect(state, target, &route, host.as_deref(), payload)?;
        let attempt_targets =
            preferred_targets_for_transport(state, target, host.as_deref(), RuntimeTransportProtocol::Tcp);
        match connect_target_candidates_via_group(&attempt_targets, state, route.group_index, payload, allow_tfo) {
            Ok(stream) => return Ok((stream, route)),
            Err(mut err) => {
                retries += 1;
                if retries > state.max_route_retries() {
                    return Err(err.into_io_error());
                }
                let mut failure = RuntimeState::classify_connect_transport_error(&err.source);
                if allow_tfo && RuntimeState::connect_failure_retries_without_tfo(err.tcp_fast_open_enabled, &failure) {
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
                            failure = RuntimeState::classify_connect_transport_error(&err.source);
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
    use crate::runtime::config::RuntimeConfig;
    use crate::runtime::failure::{
        RuntimeClassifiedFailure, RuntimeFailureAction, RuntimeFailureClass, RuntimeFailureStage,
    };
    use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
    use std::net::{Ipv4Addr, TcpListener};
    use std::thread;

    #[test]
    fn max_route_retries_default_is_eight() {
        let config = RuntimeConfig::default();
        assert_eq!(config.max_route_retries, 8);
    }

    #[test]
    fn max_route_retries_is_customizable() {
        let config = RuntimeConfig { max_route_retries: 3, ..Default::default() };
        assert_eq!(config.max_route_retries, 3);
    }

    #[test]
    fn retry_without_tfo_depends_on_attempt_using_tfo() {
        let connect_failure =
            RuntimeState::classify_connect_transport_error(&io::Error::new(io::ErrorKind::ConnectionRefused, "boom"));
        let reset_failure = RuntimeClassifiedFailure::new(
            RuntimeFailureClass::TcpReset,
            RuntimeFailureStage::Connect,
            RuntimeFailureAction::RetryWithMatchingGroup,
            "reset",
        );

        assert!(RuntimeState::connect_failure_retries_without_tfo(true, &connect_failure));
        assert!(RuntimeState::connect_failure_retries_without_tfo(true, &reset_failure));
        assert!(!RuntimeState::connect_failure_retries_without_tfo(false, &connect_failure));
        assert!(!RuntimeState::connect_failure_retries_without_tfo(false, &reset_failure));
    }

    #[test]
    fn reconnect_without_tfo_returns_connected_stream_and_route() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream listener");
        let target = listener.local_addr().expect("listener addr");
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let accept_thread = thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("accept reconnect");
            release_rx.recv().expect("wait for test release");
        });
        let config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
        let state = RuntimeState::test(config);
        let route = RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 };

        let (stream, actual_route) =
            reconnect_target_without_tfo(target, &state, route, Some("example.com".to_string()), None)
                .expect("reconnect succeeds");

        assert_eq!(actual_route.group_index, 0);
        assert_eq!(stream.peer_addr().expect("peer addr"), target);
        drop(stream);
        release_tx.send(()).expect("release accept thread");
        accept_thread.join().expect("accept thread finished");
    }

    #[test]
    fn reconnect_returns_connect_error_when_route_retries_are_exhausted() {
        let closed_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind closed listener");
        let target = closed_listener.local_addr().expect("listener addr");
        drop(closed_listener);
        let config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], max_route_retries: 0, ..Default::default() };
        let state = RuntimeState::test(config);
        let route = RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 };

        let err = reconnect_target(target, &state, route, Some("example.com".to_string()), None)
            .expect_err("closed listener should fail");

        assert!(
            matches!(err.kind(), io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut),
            "unexpected reconnect failure kind: {err}"
        );
    }

    #[test]
    fn connect_target_selects_initial_route_and_connects() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream listener");
        let target = listener.local_addr().expect("listener addr");
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let accept_thread = thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("accept connect");
            release_rx.recv().expect("wait for test release");
        });
        let config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
        let state = RuntimeState::test(config);

        let (stream, route) =
            connect_target(target, &state, Some(b"GET / HTTP/1.1\r\n\r\n"), false, Some("example.com".to_string()))
                .expect("connect succeeds");

        assert_eq!(route.group_index, 0);
        assert_eq!(stream.peer_addr().expect("peer addr"), target);
        drop(stream);
        release_tx.send(()).expect("release accept thread");
        accept_thread.join().expect("accept thread finished");
    }

    #[test]
    fn connect_target_returns_connect_error_when_route_retries_are_exhausted() {
        let closed_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind closed listener");
        let target = closed_listener.local_addr().expect("listener addr");
        drop(closed_listener);
        let config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], max_route_retries: 0, ..Default::default() };
        let state = RuntimeState::test(config);

        let err =
            connect_target(target, &state, Some(b"GET / HTTP/1.1\r\n\r\n"), false, Some("example.com".to_string()))
                .expect_err("closed listener should fail");

        assert!(
            matches!(err.kind(), io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut),
            "unexpected connect failure kind: {err}"
        );
    }
}
