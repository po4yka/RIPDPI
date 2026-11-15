use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use crate::util::with_scan_io_deadline;

use super::*;

#[test]
fn cancelling_an_upgrade_closes_the_socket_before_the_deadline() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let cancel = AtomicBool::new(false);
    let (cancelled_at_tx, cancelled_at_rx) = std::sync::mpsc::channel();
    let (error, elapsed) = std::thread::scope(|scope| {
        scope.spawn(|| {
            let (mut peer, _) = listener.accept().unwrap();
            peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            read_upgrade_request(&mut peer);
            peer.write_all(b"HTTP/1.1 101").unwrap();
            cancelled_at_tx.send(Instant::now()).unwrap();
            cancel.store(true, Ordering::Release);
            let received = peer.read(&mut [0]);
            assert!(
                matches!(received, Ok(0)) || received.is_err_and(|err| err.kind() == io::ErrorKind::ConnectionReset)
            );
        });
        let should_abort = || cancel.load(Ordering::Acquire).then_some("cancelled");
        let control = WsOverTlsControl::new(&should_abort);
        let target = WsOverTlsTarget::new("example.test", "/apiws");
        let socket = client.try_clone().unwrap();
        let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_secs(1)), || {
            upgrade_websocket(build_ws_request(&target).unwrap(), client, socket, &control)
        });
        (result.expect_err("cancelled upgrade"), cancelled_at_rx.recv().unwrap().elapsed())
    });
    assert_eq!(error.kind(), io::ErrorKind::Interrupted);
    assert_eq!(error.to_string(), "probe_aborted:cancelled");
    assert!(elapsed < Duration::from_millis(350), "upgrade took {elapsed:?} after cancellation");
}

fn read_upgrade_request(peer: &mut TcpStream) {
    let mut request = Vec::new();
    while !request.ends_with(b"\r\n\r\n") {
        let mut next = [0];
        peer.read_exact(&mut next).unwrap();
        request.push(next[0]);
        assert!(request.len() < 4096, "fixture request must fit");
    }
    assert!(request.starts_with(b"GET /apiws HTTP/1.1\r\n"));
}

#[test]
fn upgrade_that_runs_out_of_scan_time_returns_a_typed_deadline() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let deadline = Instant::now() + Duration::from_millis(400);
    let result = std::thread::scope(|scope| {
        scope.spawn(|| {
            let (mut peer, _) = listener.accept().unwrap();
            peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            read_upgrade_request(&mut peer);
            peer.write_all(b"HTTP/1.1 101").unwrap();
            let _ = peer.read(&mut [0]);
        });
        let should_abort = || (Instant::now() >= deadline).then_some("deadline_exceeded");
        let control = WsOverTlsControl::new(&should_abort);
        let target = WsOverTlsTarget::new("example.test", "/apiws");
        let socket = client.try_clone().unwrap();
        with_scan_io_deadline(Some(deadline), || {
            upgrade_websocket(build_ws_request(&target).unwrap(), client, socket, &control)
        })
    });
    let error = result.expect_err("partial Upgrade should time out");
    assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    assert_eq!(
        error.get_ref().and_then(|cause| cause.downcast_ref::<WsProbeFailure>()),
        Some(&WsProbeFailure::DeadlineExceeded)
    );
}

#[test]
fn socks5_negotiation_obeys_the_wss_opening_budget() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut target = WsOverTlsTarget::new("example.test", "/apiws").with_transport(TransportConfig::Socks5 {
        host: listener.local_addr().unwrap().ip().to_string(),
        port: listener.local_addr().unwrap().port(),
        credentials: None,
    });
    target.connect_timeout = Some(Duration::from_millis(120));
    let started = Instant::now();
    let error = std::thread::scope(|scope| {
        scope.spawn(|| {
            let (mut proxy, _) = listener.accept().unwrap();
            proxy.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut greeting = [0; 3];
            proxy.read_exact(&mut greeting).unwrap();
            assert_eq!(greeting, [5, 1, 0]);
            let count = proxy.read(&mut [0]).unwrap();
            assert_eq!(count, 0, "expired negotiation must close the socket");
        });
        let error = WsOverTlsConnector.probe(&target).expect_err("silent proxy must time out");
        assert!(started.elapsed() < Duration::from_millis(600), "SOCKS used its own longer timeout");
        error
    });

    assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    assert_eq!(
        error.get_ref().and_then(|cause| cause.downcast_ref::<WsProbeFailure>()),
        Some(&WsProbeFailure::OpenTimedOut)
    );
}

#[test]
fn wss_upgrade_authority_includes_the_configured_nondefault_port() {
    for (host, authority) in [("example.test", "example.test:8443"), ("::1", "[::1]:8443")] {
        let mut target = WsOverTlsTarget::new(host, "/apiws?x=1");
        target.port = 8443;
        let request = build_ws_request(&target).unwrap();

        assert_eq!(request.headers().get(tungstenite::http::header::HOST).unwrap(), authority);
        assert_eq!(request.uri().path_and_query().unwrap().as_str(), "/apiws?x=1");
        assert_eq!(request.uri().port_u16(), Some(8443));
    }
}
