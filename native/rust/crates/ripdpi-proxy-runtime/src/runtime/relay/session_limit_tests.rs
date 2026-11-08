use std::io::{Read, Write};
use std::net::{Ipv4Addr, Ipv6Addr, Shutdown, TcpListener, TcpStream};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime_adapter::model::config::{DETECT_HTTP_BLOCKPAGE, DesyncGroup, RuntimeConfig};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyPreferredEdge, ProxyRuntimeContext};

use crate::runtime::routing::connect_target;
use crate::runtime::state::RuntimeState;

const REQUEST: &[u8] = b"GET / HTTP/1.1\r\nHost: fixture.test\r\n\r\n";
const RESPONSE: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
const TIMEOUT: Duration = Duration::from_secs(3);

fn accept_before(listener: &TcpListener) -> TcpStream {
    listener.set_nonblocking(true).expect("bound accept");
    let deadline = Instant::now() + TIMEOUT;
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                stream.set_nonblocking(false).expect("blocking accepted socket");
                stream.set_read_timeout(Some(TIMEOUT)).expect("bound peer reads");
                return stream;
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                assert!(Instant::now() < deadline, "the replacement must reach the fallback listener");
                thread::sleep(Duration::from_millis(2));
            }
            Err(error) => panic!("accept: {error}"),
        }
    }
}

fn retry_session(fail_replacement: bool) {
    let fallback = TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).expect("bind original IPv6 target");
    let target = fallback.local_addr().expect("original target");
    let preferred = TcpListener::bind((Ipv4Addr::LOCALHOST, target.port())).expect("bind preferred IPv4 edge");
    let preferred_ip = preferred.local_addr().expect("preferred edge").ip();
    let host = "fixture.test".to_owned();
    let mut context = ProxyRuntimeContext::default();
    context.preferred_edges.insert(
        host.clone(),
        vec![ProxyPreferredEdge { ip: preferred_ip.to_string(), transport_kind: "tcp".to_owned() }],
    );
    let mut blockpage_group = DesyncGroup::new(1);
    blockpage_group.matches.detect = DETECT_HTTP_BLOCKPAGE;
    let config = RuntimeConfig {
        groups: vec![DesyncGroup::new(0), blockpage_group],
        max_route_retries: 0,
        ..Default::default()
    };
    let state = RuntimeState::test_with_context(config, Some(context));
    assert!(state.first_response_exchange_required().expect("first-response policy"));
    let (upstream, route, guard) = connect_target(target, &state, None, false, Some(host)).expect("initial route");
    let mut preferred_peer = accept_before(&preferred);
    assert_eq!(upstream.peer_addr().expect("initial peer").ip(), preferred_ip);
    drop(preferred);
    let fallback = (!fail_replacement).then_some(fallback);
    let (old_closed_tx, old_closed_rx) = mpsc::sync_channel(1);
    let first_peer = thread::spawn(move || {
        let mut request = vec![0; REQUEST.len()];
        preferred_peer.read_exact(&mut request).expect("initial request");
        assert_eq!(request, REQUEST);
        preferred_peer
            .write_all(b"HTTP/1.1 451 Unavailable For Legal Reasons\r\nContent-Length: 0\r\n\r\n")
            .expect("trigger a first-response retry");
        assert_eq!(preferred_peer.read(&mut [0]).expect("old socket must close"), 0);
        old_closed_tx.send(()).expect("report old physical close");
    });
    let app_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind app socket pair");
    let mut app = TcpStream::connect(app_listener.local_addr().expect("app listener")).expect("connect app");
    app.set_read_timeout(Some(TIMEOUT)).expect("bound app response");
    let client = accept_before(&app_listener);
    app.write_all(REQUEST).expect("send independently specified request");
    let relay_state = state.clone();
    let (finished_tx, finished_rx) = mpsc::sync_channel(1);
    let relay = thread::spawn(move || {
        let result = super::relay(client, upstream, &relay_state, target, route, None, None, guard);
        finished_tx.send(result).expect("report relay result");
    });
    if let Some(fallback) = fallback {
        let mut peer = accept_before(&fallback);
        let mut request = vec![0; REQUEST.len()];
        peer.read_exact(&mut request).expect("replayed request");
        assert_eq!(request, REQUEST);
        old_closed_rx.recv_timeout(TIMEOUT).expect("old descriptor and registry clone closed");
        assert_eq!(state.active_exit_sessions(preferred_ip), 0, "the closed preferred IP must have no permit");
        assert_eq!(state.active_exit_sessions(target.ip()), 1, "the live fallback must own its permit");
        assert_eq!(state.active_upstream_socket_count(), 1);
        peer.write_all(RESPONSE).expect("successful fallback response");
        let mut response = vec![0; RESPONSE.len()];
        app.read_exact(&mut response).expect("relay response");
        assert_eq!(response, RESPONSE);
        app.shutdown(Shutdown::Both).expect("close client");
        drop(peer);
        finished_rx.recv_timeout(TIMEOUT).expect("relay finishes before the deadline").expect("relay completes");
        relay.join().expect("join relay");
    } else {
        let result = finished_rx.recv_timeout(TIMEOUT);
        let _ = app.shutdown(Shutdown::Both);
        state.shutdown_active_tcp_sockets();
        relay.join().expect("join failed relay");
        assert!(
            result.expect("failed relay finishes before the deadline").is_err(),
            "closed replacement target must fail"
        );
        old_closed_rx.recv_timeout(TIMEOUT).expect("failed relay closes old physical socket");
    }
    first_peer.join().expect("join preferred peer");
    assert_eq!(state.active_exit_sessions(preferred_ip), 0);
    assert_eq!(state.active_exit_sessions(target.ip()), 0);
    assert_eq!(state.active_upstream_socket_count(), 0);
}

#[test]
fn first_response_retry_moves_permit_to_the_new_physical_ip() {
    retry_session(false);
}

#[test]
fn failed_first_response_replacement_releases_both_ip_permits() {
    retry_session(true);
}
