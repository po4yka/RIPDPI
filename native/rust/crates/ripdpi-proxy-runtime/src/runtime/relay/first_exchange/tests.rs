use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::Arc;
use std::time::{Duration, Instant};

use super::*;
use crate::runtime::config::RuntimeConfig;
use crate::runtime::payload::RUNTIME_DEFAULT_FAKE_TLS;
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;

fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind owned pair");
    let first = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
    let second = listener.accept().expect("accept").0;
    (first, second)
}

#[test]
fn partial_tls_response_and_unread_continuation_survive_handoff() {
    let mut config = RuntimeConfig::default();
    config.timeouts.partial_timeout_ms = 1000;
    let state = RuntimeState::test(config);
    let (mut client, mut app) = pair();
    let (mut upstream, mut origin) = pair();
    upstream.set_read_timeout(Some(Duration::from_millis(40))).expect("existing relay read timeout");
    let target = upstream.peer_addr().expect("target");
    let response = b"\x16\x03\x03\x00\x04\x02\x00\x00\x00";
    origin.write_all(&response[..3]).expect("partial TLS header");
    app.write_all(b"continued request").expect("client continuation");
    let reader = FirstResponseReader::new(&state, RUNTIME_DEFAULT_FAKE_TLS);
    let FirstResponse::Continuation(reader) =
        read_first_response(&state, target, None, &mut upstream, RUNTIME_DEFAULT_FAKE_TLS, Some(&client), reader)
            .expect("observe both readable sockets")
    else {
        panic!("partial TLS response must hand off without waiting for its rest");
    };
    let mut continuation = [0; 17];
    client.read_exact(&mut continuation).expect("outbound retains all continuation bytes");
    assert_eq!(&continuation, b"continued request");
    origin.write_all(&response[3..]).expect("remaining TLS response");
    let FirstResponse::Forward(bytes, _) =
        read_first_response(&state, target, None, &mut upstream, RUNTIME_DEFAULT_FAKE_TLS, None, *reader)
            .expect("complete resumed response")
    else {
        panic!("complete owned TLS record must be forwarded");
    };
    assert_eq!(bytes, response, "response bytes are retained once and in order");
    let started = Instant::now();
    let error = upstream.read(&mut [0]).expect_err("idle steady-state read keeps its timeout");
    assert!(matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut));
    assert!(started.elapsed() < Duration::from_secs(1));
}

#[test]
fn continuation_does_not_restart_first_response_deadline() {
    let mut config = RuntimeConfig::default();
    config.timeouts.timeout_ms = 50;
    let state = RuntimeState::test(config);
    let (client, mut app) = pair();
    let (mut upstream, _origin) = pair();
    let target = upstream.peer_addr().expect("target");
    let request = b"GET / HTTP/1.1\r\n\r\n";
    app.write_all(b"continuation").expect("client continuation");
    let FirstResponse::Continuation(reader) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        request,
        Some(&client),
        FirstResponseReader::new(&state, request),
    )
    .expect("handoff") else {
        panic!("expected continuation");
    };
    std::thread::sleep(Duration::from_millis(75));
    let started = Instant::now();
    assert!(matches!(
        read_first_response(&state, target, None, &mut upstream, request, None, *reader).expect("expired deadline"),
        FirstResponse::Failure { .. }
    ));
    assert!(started.elapsed() < Duration::from_millis(40), "handoff restarted the expired deadline");
}

#[test]
fn owner_stop_cancels_a_pending_response_without_a_socket_timeout() {
    let control = Arc::new(EmbeddedProxyControl::new(None));
    let state = RuntimeState::test_with_control(RuntimeConfig::default(), control.clone());
    let (mut upstream, _origin) = pair();
    let target = upstream.peer_addr().expect("target");
    control.request_shutdown();
    let started = Instant::now();
    let result = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        b"request",
        None,
        FirstResponseReader::new(&state, b"request"),
    );
    assert!(result.is_err_and(|error| error.kind() == io::ErrorKind::Interrupted));
    assert!(started.elapsed() < Duration::from_secs(1));
}
