use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use ripdpi_ws_tunnel::{WsTransportConfig, build_ws_request};

const ACCEPT: &str = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

fn raw_peer(response: String) -> (TcpStream, thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind peer");
    let addr = listener.local_addr().expect("peer address");
    let peer = thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept client");
        stream.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
        let mut head = Vec::new();
        while !head.ends_with(b"\r\n\r\n") {
            let mut byte = [0];
            stream.read_exact(&mut byte).expect("request byte");
            head.push(byte[0]);
            assert!(head.len() < 8192);
        }
        stream.write_all(response.as_bytes()).expect("response");
    });
    let stream = TcpStream::connect(addr).expect("connect peer");
    stream.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
    (stream, peer)
}

fn request() -> tungstenite::handshake::client::Request {
    let mut request = build_ws_request(&WsTransportConfig::new("localhost", "/"), false).expect("request");
    // The accept value is the independent RFC 6455 section 1.3 example.
    request.headers_mut().insert("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==".parse().expect("sample key"));
    request
}

fn response(connection_fields: &str, extra_fields: &str) -> String {
    format!(
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n{connection_fields}Sec-WebSocket-Accept: {ACCEPT}\r\nSec-WebSocket-Protocol: binary\r\n{extra_fields}\r\n"
    )
}

#[test]
fn handshake_rejects_unrequested_extensions_before_returning_a_carrier() {
    for extra in ["", "Sec-WebSocket-Extensions: permessage-deflate\r\n"] {
        let (stream, peer) = raw_peer(response("Connection: Upgrade\r\n", extra));
        let result = tungstenite::client(request(), stream);
        peer.join().expect("peer joins");
        assert_eq!(result.is_ok(), extra.is_empty(), "unexpected extension negotiation: {extra}");
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: test-owned streams are dropped; no carrier is published before validation.
async fn handshake_async_rejects_unrequested_extensions() {
    let (stream, peer) =
        raw_peer(response("Connection: Upgrade\r\n", "Sec-WebSocket-Extensions: permessage-deflate\r\n"));
    stream.set_nonblocking(true).expect("nonblocking stream");
    let stream = tokio::net::TcpStream::from_std(stream).expect("async stream");
    let result = tokio_tungstenite::client_async(request(), stream).await;
    peer.join().expect("peer joins");
    assert!(result.is_err(), "an extension that was not offered must fail the handshake");
}

#[test]
fn handshake_accepts_connection_upgrade_in_any_field_or_token_position() {
    for fields in [
        "Connection: keep-alive, UpGrAdE\r\n",
        "Connection: Upgrade, keep-alive\r\n",
        "Connection: keep-alive\r\nConnection: Upgrade\r\n",
    ] {
        let (stream, peer) = raw_peer(response(fields, ""));
        let result = tungstenite::client(request(), stream);
        peer.join().expect("peer joins");
        assert!(result.is_ok(), "valid Connection tokens rejected: {fields}");
    }
}

#[test]
fn handshake_requires_an_exact_connection_upgrade_token() {
    for fields in ["", "Connection: keep-alive\r\n", "Connection: xUpgrade\r\n", "Connection: \"Upgrade\"\r\n"] {
        let (stream, peer) = raw_peer(response(fields, ""));
        let result = tungstenite::client(request(), stream);
        peer.join().expect("peer joins");
        assert!(result.is_err(), "missing Upgrade token accepted: {fields}");
    }
}

#[test]
fn handshake_token_lists_preserve_accept_and_subprotocol_validation() {
    let valid = response("Connection: keep-alive, Upgrade\r\n", "");
    for invalid in [valid.replace(ACCEPT, "wrong-accept"), valid.replace("Protocol: binary", "Protocol: unoffered")] {
        let (stream, peer) = raw_peer(invalid);
        let result = tungstenite::client(request(), stream);
        peer.join().expect("peer joins");
        assert!(result.is_err(), "invalid Accept or subprotocol accepted");
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: this test owns the stream and publishes no carrier until validation succeeds.
async fn handshake_async_accepts_connection_token_lists() {
    let (stream, peer) = raw_peer(response("Connection: keep-alive, Upgrade\r\n", ""));
    stream.set_nonblocking(true).expect("nonblocking stream");
    let stream = tokio::net::TcpStream::from_std(stream).expect("async stream");
    let result = tokio_tungstenite::client_async(request(), stream).await;
    peer.join().expect("peer joins");
    assert!(result.is_ok(), "valid Connection tokens rejected by async client");
}

#[test]
fn handshake_rejects_repeated_accept_response_fields() {
    for extra in [
        format!("Sec-WebSocket-Accept: {ACCEPT}\r\n"),
        "Sec-WebSocket-Accept: wrong-accept\r\n".to_owned(),
        format!("sEc-WeBsOcKeT-aCcEpT: {ACCEPT}\r\n"),
    ] {
        let (stream, peer) = raw_peer(response("Connection: keep-alive, Upgrade\r\n", &extra));
        let result = tungstenite::client(request(), stream);
        peer.join().expect("peer joins");
        assert!(result.is_err(), "repeated Accept response field accepted: {extra}");
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the handshake owns the stream; cancellation drops it before publishing a carrier.
async fn handshake_async_rejects_repeated_protocol_response_fields() {
    for extra in [
        "Sec-WebSocket-Protocol: binary\r\n",
        "Sec-WebSocket-Protocol: unoffered\r\n",
        "sEc-WeBsOcKeT-pRoToCoL: binary\r\n",
    ] {
        let (stream, peer) = raw_peer(response("Connection: keep-alive, Upgrade\r\n", extra));
        stream.set_nonblocking(true).expect("nonblocking stream");
        let stream = tokio::net::TcpStream::from_std(stream).expect("async stream");
        let result = tokio_tungstenite::client_async(request(), stream).await;
        peer.join().expect("peer joins");
        assert!(result.is_err(), "repeated Protocol response field accepted: {extra}");
    }
}
