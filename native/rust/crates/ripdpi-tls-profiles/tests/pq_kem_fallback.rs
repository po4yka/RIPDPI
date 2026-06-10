//! Negotiation-fallback integration test for the PQ-KEM curve override.
//!
//! Stands up a localhost BoringSSL server that supports ONLY the classical
//! `X25519` group, then connects a client whose curve list offers the hybrid
//! `X25519MLKEM768` first with `X25519` as the fallback. The handshake must
//! succeed and the client must observe that it fell back to `X25519`
//! (group id `0x001d`).
//!
//! Fixture style mirrors the in-crate BoringSSL server in
//! `ripdpi-tls-profiles/src/ech.rs` and `local-network-fixture/src/tls.rs`:
//! a self-signed cert from `rcgen`, a `std::net` listener on a worker thread,
//! and a synchronous client.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use boring::ssl::{Ssl, SslConnector, SslContext, SslMethod, SslVerifyMode};

/// IANA TLS supported-group id for `X25519`.
const X25519_GROUP_ID: u16 = 0x001d;

/// Builds a server `SslContext` with a self-signed cert that supports ONLY the
/// classical `X25519` group, forcing PQ-capable clients to fall back.
fn x25519_only_server_context() -> SslContext {
    let certificate = rcgen::generate_simple_self_signed(vec!["pqkem.example".to_string(), "localhost".to_string()])
        .expect("self-signed certificate");
    let cert = boring::x509::X509::from_der(certificate.cert.der().as_ref()).expect("x509 cert");
    let key = boring::pkey::PKey::private_key_from_der(&certificate.signing_key.serialize_der()).expect("key");
    let mut context = SslContext::builder(SslMethod::tls()).expect("server context");
    context.set_certificate(&cert).expect("server cert");
    context.set_private_key(&key).expect("server key");
    // The server offers only X25519, so a client whose first preference is the
    // hybrid PQ group has to fall back to X25519.
    context.set_curves_list("X25519").expect("server curves");
    context.build()
}

fn start_x25519_only_server() -> (SocketAddr, thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind PQ-KEM fixture");
    let addr = listener.local_addr().expect("fixture addr");
    let context = x25519_only_server_context();
    let handle = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept PQ-KEM client");
        stream.set_read_timeout(Some(Duration::from_secs(5))).expect("server read timeout");
        stream.set_write_timeout(Some(Duration::from_secs(5))).expect("server write timeout");
        let ssl = Ssl::new(&context).expect("server ssl");
        if let Ok(mut tls) = ssl.accept(stream) {
            let _ = tls.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok");
            let _ = tls.flush();
        }
    });
    (addr, handle)
}

#[test]
fn pq_kem_falls_back_to_x25519_when_server_lacks_hybrid_group() {
    let (addr, handle) = start_x25519_only_server();

    let mut connector = SslConnector::builder(SslMethod::tls()).expect("connector builder");
    // The server presents a self-signed cert; we are only exercising group
    // negotiation, so skip cert verification.
    connector.set_verify(SslVerifyMode::NONE);
    // Client prefers the hybrid PQ group but lists X25519 as the fallback.
    connector.set_curves_list("X25519MLKEM768:X25519").expect("client curves");
    let connector = connector.build();
    let tls_config = connector.configure().expect("connect config");

    let stream = TcpStream::connect(addr).expect("connect PQ-KEM fixture");
    stream.set_read_timeout(Some(Duration::from_secs(5))).expect("client read timeout");
    stream.set_write_timeout(Some(Duration::from_secs(5))).expect("client write timeout");

    let mut tls = tls_config.connect("pqkem.example", stream).expect("handshake must succeed via fallback");

    // The negotiated group must be X25519 — the client fell back from the
    // hybrid PQ group the server did not support.
    assert_eq!(tls.ssl().curve(), Some(X25519_GROUP_ID), "expected fallback to X25519 (0x001d)");

    let mut response = Vec::new();
    let _ = tls.read_to_end(&mut response);

    handle.join().expect("server thread join");
}
