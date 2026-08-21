use std::io;
use std::net::{SocketAddr, TcpStream};

use super::super::adaptive::{note_direct_path_all_ips_failed, note_direct_path_transport_attempt};
use super::super::state::RuntimeState;
use super::connect::connect_target_candidates_via_group;
use super::failure::{advance_route_for_failure, emit_failure_classified, note_block_signal_for_failure};
use super::policy::{preferred_targets_for_transport, select_route};
use crate::exit_ip_cap::ExitIpSessionGuard;
use crate::runtime::destination_routing::DestinationEgress;
use crate::runtime::types::{RuntimeConnectionRoute, RuntimeTransportProtocol};

pub(in crate::runtime) fn connect_target(
    target: SocketAddr,
    state: &RuntimeState,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    host: Option<String>,
) -> io::Result<(TcpStream, RuntimeConnectionRoute, Option<ExitIpSessionGuard>)> {
    let egress = state.destination_egress(target, host.as_deref(), RuntimeTransportProtocol::Tcp);
    if egress == DestinationEgress::Block {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "destination blocked by routing policy"));
    }
    let route = select_route(state, target, payload, host.as_deref(), allow_unknown_payload)?;
    state.note_route_selected(target, route.group_index, host.as_deref(), "initial");
    connect_target_with_route_and_egress(target, state, route, payload, host, egress)
}

pub(in crate::runtime) fn connect_target_with_route(
    target: SocketAddr,
    state: &RuntimeState,
    route: RuntimeConnectionRoute,
    payload: Option<&[u8]>,
    host: Option<String>,
) -> io::Result<(TcpStream, RuntimeConnectionRoute, Option<ExitIpSessionGuard>)> {
    let egress = state.destination_egress(target, host.as_deref(), RuntimeTransportProtocol::Tcp);
    if egress == DestinationEgress::Block {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "destination blocked by routing policy"));
    }
    connect_target_with_route_and_egress(target, state, route, payload, host, egress)
}

fn connect_target_with_route_and_egress(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: RuntimeConnectionRoute,
    payload: Option<&[u8]>,
    host: Option<String>,
    egress: DestinationEgress,
) -> io::Result<(TcpStream, RuntimeConnectionRoute, Option<ExitIpSessionGuard>)> {
    let mut retries: usize = 0;
    loop {
        let attempt_targets =
            preferred_targets_for_transport(state, target, host.as_deref(), RuntimeTransportProtocol::Tcp);
        note_direct_path_transport_attempt(state, host.as_deref(), &attempt_targets, RuntimeTransportProtocol::Tcp)?;
        match connect_target_candidates_via_group(
            &attempt_targets,
            host.as_deref(),
            state,
            route.group_index,
            payload,
            true,
            true,
            egress,
        ) {
            Ok((stream, guard)) => return Ok((stream, route, guard)),
            Err(mut err) => {
                retries += 1;
                let mut failure = RuntimeState::classify_connect_transport_error(&err.source);
                if RuntimeState::connect_failure_retries_without_tfo(err.tcp_fast_open_enabled, &failure) {
                    tracing::debug!(group_index = route.group_index, target = %target, "retrying connect without TCP Fast Open");
                    match connect_target_candidates_via_group(
                        &attempt_targets,
                        host.as_deref(),
                        state,
                        route.group_index,
                        payload,
                        false,
                        true,
                        egress,
                    ) {
                        Ok((stream, guard)) => return Ok((stream, route, guard)),
                        Err(fallback_err) => {
                            err = fallback_err;
                            failure = RuntimeState::classify_connect_transport_error(&err.source);
                        }
                    }
                }
                note_block_signal_for_failure(state, host.as_deref(), &failure, err.tcp_total_retransmissions);
                if retries > state.max_route_retries() {
                    note_direct_path_all_ips_failed(state, host.as_deref(), &attempt_targets)?;
                    return Err(err.into_io_error());
                }
                emit_failure_classified(state, target, &failure, host.as_deref());
                let next = advance_route_for_failure(state, target, &route, host.clone(), payload, &failure)?;
                let Some(next) = next else {
                    note_direct_path_all_ips_failed(state, host.as_deref(), &attempt_targets)?;
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
    let egress = state.destination_egress(target, host.as_deref(), RuntimeTransportProtocol::Tcp);
    if egress == DestinationEgress::Block {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "destination blocked by routing policy"));
    }
    let mut retries: usize = 0;
    loop {
        crate::runtime::retry::apply_retry_pacing_before_connect(state, target, &route, host.as_deref(), payload)?;
        let attempt_targets =
            preferred_targets_for_transport(state, target, host.as_deref(), RuntimeTransportProtocol::Tcp);
        match connect_target_candidates_via_group(
            &attempt_targets,
            host.as_deref(),
            state,
            route.group_index,
            payload,
            allow_tfo,
            false,
            egress,
        ) {
            Ok((stream, _)) => return Ok((stream, route)),
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
                        host.as_deref(),
                        state,
                        route.group_index,
                        payload,
                        false,
                        false,
                        egress,
                    ) {
                        Ok((stream, _)) => return Ok((stream, route)),
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
    use ripdpi_proxy_runtime_adapter::model::config::{
        DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationRoutingAction, DestinationRoutingNetwork,
        DestinationRoutingPolicy, DestinationRoutingRule, DesyncGroup, UpstreamSocksConfig,
    };
    use std::net::{Ipv4Addr, TcpListener};
    use std::thread;

    fn policy_for_host(action: DestinationRoutingAction, host: &str) -> DestinationRoutingPolicy {
        DestinationRoutingPolicy {
            rules: vec![DestinationRoutingRule {
                action,
                network: DestinationRoutingNetwork::Tcp,
                domains: vec![DestinationDomainMatcher {
                    kind: DestinationDomainMatcherKind::Exact,
                    value: host.to_string(),
                }],
                ip_ranges: vec![],
                destination_ports: vec![],
            }],
            default_action: DestinationRoutingAction::Tunneled,
            canonical_digest: "digest".to_string(),
        }
    }

    #[test]
    fn blocked_destination_opens_no_tcp_egress_socket() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind target listener");
        listener.set_nonblocking(true).expect("nonblocking listener");
        let target = listener.local_addr().expect("listener addr");
        let config = RuntimeConfig {
            groups: vec![DesyncGroup::new(0)],
            destination_routing: policy_for_host(DestinationRoutingAction::Block, "blocked.example"),
            ..Default::default()
        };

        let error =
            connect_target(target, &RuntimeState::test(config), None, true, Some("blocked.example".to_string()))
                .expect_err("blocked route must fail before connect");

        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
        assert_eq!(listener.accept().expect_err("no egress connection").kind(), io::ErrorKind::WouldBlock);
    }

    #[test]
    fn direct_destination_bypasses_group_upstream_socks() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind target listener");
        let target = listener.local_addr().expect("listener addr");
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let accept_thread = thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("direct target accepted");
            release_rx.recv().expect("release target");
        });
        let unavailable_socks = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("reserve socks port");
        let unavailable_socks_addr = unavailable_socks.local_addr().expect("socks addr");
        drop(unavailable_socks);
        let mut group = DesyncGroup::new(0);
        group.policy.ext_socks = Some(UpstreamSocksConfig { addr: unavailable_socks_addr });
        let config = RuntimeConfig {
            groups: vec![group],
            destination_routing: policy_for_host(DestinationRoutingAction::Direct, "direct.example"),
            ..Default::default()
        };

        let (stream, _, _) =
            connect_target(target, &RuntimeState::test(config), None, true, Some("direct.example".to_string()))
                .expect("direct route must bypass unavailable SOCKS upstream");

        assert_eq!(stream.peer_addr().expect("peer"), target);
        drop(stream);
        release_tx.send(()).expect("release target");
        accept_thread.join().expect("accept thread");
    }

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

        let (stream, route, _cap_guard) =
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

    #[test]
    fn refusal_telemetry_uses_logical_hostname_not_resolved_address_for_deduplication() {
        let first = std::net::SocketAddr::from((Ipv4Addr::new(192, 0, 2, 1), 443));
        let second = std::net::SocketAddr::from((Ipv4Addr::new(192, 0, 2, 2), 443));
        let state = RuntimeState::test(RuntimeConfig {
            groups: vec![DesyncGroup::new(0)],
            max_route_retries: 0,
            ..Default::default()
        });

        for target in [first, second] {
            state.note_candidate_upstream_connect_failed(target, Some("Example.COM"), io::ErrorKind::ConnectionRefused);
        }
        let same_host = state.candidate_refusal_counters();
        assert_eq!(same_host.connection_refused_count, 2);
        assert_eq!(same_host.duplicate_refusal_count, 1);

        let shared_address_state = RuntimeState::test(RuntimeConfig {
            groups: vec![DesyncGroup::new(0)],
            max_route_retries: 0,
            ..Default::default()
        });
        for host in ["one.example", "two.example"] {
            shared_address_state.note_candidate_upstream_connect_failed(
                first,
                Some(host),
                io::ErrorKind::ConnectionRefused,
            );
        }
        let distinct_hosts = shared_address_state.candidate_refusal_counters();
        assert_eq!(distinct_hosts.connection_refused_count, 2);
        assert_eq!(distinct_hosts.duplicate_refusal_count, 0);
    }
}
