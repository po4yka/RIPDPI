use super::*;

use boring::ssl::SslVerifyMode;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::thread;
use std::time::Instant;

fn fixture_connector() -> io::Result<SslConnector> {
    // This fixture isolates the transport deadline; certificate verification has separate tests.
    let mut builder = ripdpi_tls_profiles::configure_builder("chrome_stable").map_err(io::Error::other)?;
    builder.set_verify(SslVerifyMode::NONE);
    builder.set_alpn_protos(HTTP11_ALPN).map_err(io::Error::other)?;
    Ok(builder.build())
}

#[test]
fn bootstrap_deadline_spans_tls_and_http_upgrade() {
    let acceptor = super::alpn_selecting_acceptor();
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
    let target = listener.local_addr().expect("fixture address");
    let server = thread::spawn(move || {
        let (tcp, _) = listener.accept().expect("accept fixture");
        tcp.set_read_timeout(Some(Duration::from_secs(2))).expect("fixture timeout");
        thread::sleep(Duration::from_millis(240));
        let mut tls = acceptor.accept(tcp).expect("accept TLS");
        let mut request = Vec::new();
        while !request.ends_with(b"\r\n\r\n") {
            let mut byte = [0];
            if tls.read(&mut byte).unwrap_or(0) == 0 {
                return;
            }
            request.push(byte[0]);
        }
        let request = String::from_utf8(request).expect("HTTP request");
        let key = request
            .lines()
            .find_map(|line| {
                line.split_once(':')
                    .filter(|(name, _)| name.eq_ignore_ascii_case("Sec-WebSocket-Key"))
                    .map(|(_, value)| value.trim())
            })
            .expect("upgrade key");
        thread::sleep(Duration::from_millis(240));
        let accept = tungstenite::handshake::derive_accept_key(key.as_bytes());
        let _ = write!(
            tls,
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\nSec-WebSocket-Protocol: binary\r\n\r\n"
        );
    });
    let started = Instant::now();
    let result = open_ws_tunnel_with_timeout_and_connector(
        TelegramDc::production(2),
        Some(target),
        None,
        test_bootstrap(Some(Duration::from_millis(400))),
        None,
        None,
        |_, _| fixture_connector(),
    );
    let elapsed = started.elapsed();
    server.join().expect("fixture thread");
    let error = result.expect_err("TLS and upgrade must share the 400 ms budget");
    assert_eq!(error.kind(), io::ErrorKind::TimedOut, "{error}");
    assert!(elapsed < Duration::from_millis(650), "deadline took {elapsed:?}");
}

#[test]
fn stalled_tls_bootstrap_is_cancelled_and_closes_its_socket() {
    cancel_bootstrap_on_observed_stage(false);
}

#[test]
fn stalled_http_upgrade_is_cancelled_and_closes_its_socket() {
    cancel_bootstrap_on_observed_stage(true);
}

fn cancel_bootstrap_on_observed_stage(upgrade: bool) {
    let acceptor = super::alpn_selecting_acceptor();
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
    let target = listener.local_addr().expect("fixture address");
    let (observed_tx, observed_rx) = mpsc::channel();
    let server = thread::spawn(move || {
        let (mut tcp, _) = listener.accept().expect("accept fixture");
        tcp.set_read_timeout(Some(Duration::from_secs(2))).expect("fixture timeout");
        let drained = if upgrade {
            let mut tls = acceptor.accept(tcp).expect("accept TLS");
            read_upgrade_request(&mut tls);
            observed_tx.send(()).expect("observe HTTP upgrade request");
            let mut raw = tls.get_ref();
            raw.read_to_end(&mut Vec::new())
        } else {
            let mut header = [0; 5];
            tcp.read_exact(&mut header).expect("read TLS record header");
            assert_eq!(header[0], 0x16, "client must send a TLS handshake");
            observed_tx.send(()).expect("observe ClientHello");
            tcp.read_to_end(&mut Vec::new())
        };
        assert!(
            drained.is_ok() || drained.is_err_and(|error| error.kind() == io::ErrorKind::ConnectionReset),
            "cancellation must close the client socket"
        );
    });
    let cancelled = Cell::new(false);
    let should_cancel = || {
        cancelled.set(cancelled.get() || observed_rx.try_recv().is_ok());
        cancelled.get()
    };
    let started = Instant::now();
    let result = open_ws_tunnel_with_timeout_and_connector(
        TelegramDc::production(2),
        Some(target),
        None,
        Bootstrap::new(Some(Duration::from_secs(2)), &should_cancel).expect("bootstrap policy"),
        None,
        None,
        |_, _| fixture_connector(),
    );
    let elapsed = started.elapsed();
    server.join().expect("fixture thread");
    let error = result.expect_err("owner cancellation must interrupt a stalled handshake");
    assert_eq!(error.kind(), io::ErrorKind::Interrupted, "{error}");
    assert!(elapsed < Duration::from_secs(1), "cancellation took {elapsed:?}");
    assert!(cancelled.get(), "fixture must observe the stalled network stage");
}

fn read_upgrade_request(tls: &mut SslStream<TcpStream>) -> String {
    let mut request = Vec::new();
    while !request.ends_with(b"\r\n\r\n") {
        let mut byte = [0];
        tls.read_exact(&mut byte).expect("read HTTP upgrade request");
        request.push(byte[0]);
    }
    String::from_utf8(request).expect("HTTP request")
}

#[test]
fn cancelled_or_expired_bootstrap_does_not_connect() {
    for (timeout, cancel, expected) in [
        (Some(Duration::from_secs(2)), true, io::ErrorKind::Interrupted),
        (Some(Duration::ZERO), false, io::ErrorKind::TimedOut),
    ] {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
        listener.set_nonblocking(true).expect("nonblocking listener");
        let error = open_ws_tunnel_with_timeout_and_cancel(
            TelegramDc::production(2),
            Some(listener.local_addr().expect("fixture address")),
            None,
            timeout,
            None,
            None,
            &|| cancel,
        )
        .expect_err("a cancelled or expired bootstrap must not start TCP");
        assert_eq!(error.kind(), expected);
        assert_eq!(listener.accept().expect_err("no outbound connection").kind(), io::ErrorKind::WouldBlock);
    }
}

#[test]
fn cancellation_during_socket_protection_prevents_tcp_connect() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
    listener.set_nonblocking(true).expect("nonblocking listener");
    let cancelled = Cell::new(false);
    let should_cancel = || cancelled.get();
    let bootstrap = Bootstrap::new(Some(Duration::from_secs(2)), &should_cancel).expect("bootstrap policy");
    let error = connect_tcp_socket_with(
        listener.local_addr().expect("fixture address"),
        Some("/tmp/protect.sock"),
        &bootstrap,
        |_, _| {
            cancelled.set(true);
            Ok(())
        },
    )
    .expect_err("cancellation during protection must prevent outbound TCP");
    assert_eq!(error.kind(), io::ErrorKind::Interrupted);
    assert_eq!(listener.accept().expect_err("no outbound connection").kind(), io::ErrorKind::WouldBlock);
}
