use super::plan::connect_target_candidates_via_group;
use crate::runtime::destination_routing::DestinationEgress;
use crate::runtime::state::RuntimeState;
use ripdpi_proxy_runtime_adapter::model::config::{DesyncGroup, RuntimeConfig, UpstreamSocksConfig};
use ripdpi_session_limit::DEFAULT_EXIT_IP_SESSION_CAP;
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::time::{Duration, Instant};

fn listener() -> TcpListener {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind candidate");
    listener.set_nonblocking(true).expect("nonblocking listener");
    listener
}

fn accept_service(listener: &TcpListener) -> TcpStream {
    let deadline = Instant::now() + Duration::from_secs(1);
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                stream.set_nonblocking(false).expect("blocking origin");
                stream.set_read_timeout(Some(Duration::from_secs(1))).expect("origin read timeout");
                return stream;
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(5));
            }
            Err(error) => panic!("independent origin did not accept the flow: {error}"),
        }
    }
}

fn state(address: SocketAddr, upstream: Option<SocketAddr>) -> RuntimeState {
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_ip = address.ip();
    config.network.listen.listen_port = address.port();
    config.timeouts.connect_timeout_ms = 50;
    let mut group = DesyncGroup::new(0);
    group.policy.ext_socks = upstream.map(|addr| UpstreamSocksConfig { addr, auth: None });
    config.groups = vec![group];
    RuntimeState::test(config)
}

fn reject_candidates(state: &RuntimeState, candidates: &[SocketAddr], target: &TcpListener) {
    let error =
        connect_target_candidates_via_group(candidates, None, state, 0, None, false, true, DestinationEgress::Tunneled)
            .expect_err("listener candidate must be rejected");
    assert_eq!(error.source.kind(), io::ErrorKind::PermissionDenied);
    assert_eq!(target.accept().expect_err("no self connection").kind(), io::ErrorKind::WouldBlock);
}

#[test]
fn final_listener_candidate_is_rejected() {
    let target = listener();
    let address = target.local_addr().expect("address");
    reject_candidates(&state(address, None), &[address], &target);
}

#[test]
fn later_listener_candidate_is_rejected_after_an_unavailable_candidate() {
    let target = listener();
    let address = target.local_addr().expect("address");
    let unavailable = listener();
    let first = unavailable.local_addr().expect("unavailable address");
    drop(unavailable);
    reject_candidates(&state(address, None), &[first, address], &target);
}

#[test]
fn advisory_cap_fallback_still_rejects_the_listener() {
    let target = listener();
    let address = target.local_addr().expect("address");
    let state = state(address, None);
    let _slots: Vec<_> = (0..DEFAULT_EXIT_IP_SESSION_CAP)
        .map(|_| state.try_acquire_exit_session(address.ip()).expect("fill advisory cap"))
        .collect();
    reject_candidates(&state, &[address], &target);
}

#[test]
fn wildcard_listener_rejects_a_concrete_local_candidate() {
    let target = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind wildcard listener");
    target.set_nonblocking(true).expect("nonblocking listener");
    let address = target.local_addr().expect("wildcard address");
    let candidate = SocketAddr::from((Ipv4Addr::LOCALHOST, address.port()));
    reject_candidates(&state(address, None), &[candidate], &target);
}

#[test]
fn mapped_ipv6_listener_candidate_is_rejected() {
    let target = listener();
    let address = target.local_addr().expect("address");
    let mapped = SocketAddr::new(Ipv4Addr::LOCALHOST.to_ipv6_mapped().into(), address.port());
    reject_candidates(&state(address, None), &[mapped], &target);
}

#[test]
fn listener_as_socks_upstream_is_rejected_before_negotiation() {
    let target = listener();
    let address = target.local_addr().expect("address");
    let destination = SocketAddr::from(([192, 0, 2, 7], 443));
    reject_candidates(&state(address, Some(address)), &[destination], &target);
}

#[test]
fn listener_candidate_is_skipped_for_a_different_local_service() {
    let proxy = listener();
    let proxy_address = proxy.local_addr().expect("proxy address");
    let origin = listener();
    let origin_address = origin.local_addr().expect("origin address");
    let state = state(proxy_address, None);
    let (stream, _) = connect_target_candidates_via_group(
        &[proxy_address, origin_address],
        None,
        &state,
        0,
        None,
        false,
        true,
        DestinationEgress::Direct,
    )
    .expect("valid later candidate must remain usable");
    assert_eq!(stream.peer_addr().expect("connected peer"), origin_address);
    let _origin_stream = accept_service(&origin);
    assert_eq!(proxy.accept().expect_err("no self connection").kind(), io::ErrorKind::WouldBlock);
}

#[test]
fn different_loopback_service_port_can_exchange_payload() {
    let proxy = listener();
    let origin = listener();
    let origin_addr = origin.local_addr().expect("origin address");
    let state = state(proxy.local_addr().expect("proxy address"), None);
    let (mut stream, _) = connect_target_candidates_via_group(
        &[origin_addr],
        None,
        &state,
        0,
        None,
        false,
        true,
        DestinationEgress::Direct,
    )
    .expect("different loopback port is allowed");
    stream.set_read_timeout(Some(Duration::from_secs(1))).expect("client read timeout");
    stream.write_all(b"PING").expect("send payload");
    let mut accepted = accept_service(&origin);
    let mut payload = [0; 4];
    accepted.read_exact(&mut payload).expect("origin payload");
    assert_eq!(&payload, b"PING");
    accepted.write_all(b"PONG").expect("origin response");
    stream.read_exact(&mut payload).expect("client response");
    assert_eq!(&payload, b"PONG");
    assert_eq!(proxy.accept().expect_err("proxy was not targeted").kind(), io::ErrorKind::WouldBlock);
}

#[test]
fn exact_ipv4_listener_allows_a_distinct_ipv6_service_on_the_same_port() {
    let proxy = listener();
    let proxy_address = proxy.local_addr().expect("proxy address");
    let origin = TcpListener::bind((std::net::Ipv6Addr::LOCALHOST, proxy_address.port()))
        .expect("bind distinct local origin with the proxy port");
    let origin_address = origin.local_addr().expect("origin address");
    origin.set_nonblocking(true).expect("nonblocking origin");
    let state = state(proxy_address, None);
    let (mut stream, _) = connect_target_candidates_via_group(
        &[origin_address],
        None,
        &state,
        0,
        None,
        false,
        true,
        DestinationEgress::Direct,
    )
    .expect("exact IPv4 proxy cannot accept traffic for the IPv6 origin");
    stream.write_all(b"DISTINCT").expect("client payload");
    let mut accepted = accept_service(&origin);
    let mut payload = [0; 8];
    accepted.read_exact(&mut payload).expect("origin payload");
    assert_eq!(&payload, b"DISTINCT");
    assert_eq!(proxy.accept().expect_err("proxy was not reached").kind(), io::ErrorKind::WouldBlock);
}
