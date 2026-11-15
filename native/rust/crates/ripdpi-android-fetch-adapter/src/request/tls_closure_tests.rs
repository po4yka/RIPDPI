use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpListener};
use std::thread;
use std::time::Duration;

use boring::pkey::PKey;
use boring::ssl::{SslAcceptor, SslMethod};
use boring::x509::X509;
use http::Method;
use hyper_util::rt::TokioIo;
use tokio::net::TcpStream;

use super::send_request;
use crate::dto::{NativeOwnedTlsHttpRequest, RawHttpResponse};
use crate::tls_profile::TlsTransport;

fn fetch_response(response: &'static [u8], clean_close: bool) -> io::Result<RawHttpResponse> {
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_owned()]).expect("test identity");
    let cert_der = cert.cert.der().to_vec();
    let key_der = cert.signing_key.serialize_der();
    let mut acceptor = SslAcceptor::mozilla_intermediate(SslMethod::tls()).expect("test acceptor");
    acceptor.set_certificate(&X509::from_der(&cert_der).expect("test certificate")).expect("server certificate");
    acceptor.set_private_key(&PKey::private_key_from_der(&key_der).expect("test key")).expect("server key");
    let acceptor = acceptor.build();
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback");
    let address = listener.local_addr().expect("server address");
    let server = thread::spawn(move || {
        let (tcp, _) = listener.accept().expect("client");
        tcp.set_read_timeout(Some(Duration::from_secs(2))).expect("server timeout");
        let mut stream = acceptor.accept(tcp).expect("test TLS handshake");
        let mut request = [0; 4096];
        let count = stream.read(&mut request).expect("HTTP request");
        assert!(count > 0, "request must arrive before the response");
        stream.write_all(response).expect("HTTP response");
        stream.flush().expect("flush response");
        if clean_close {
            let _ = stream.shutdown();
        }
        let _ = stream.get_ref().shutdown(Shutdown::Both);
    });
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("runtime");
    let result = runtime.block_on(async {
        let tcp = TcpStream::connect(address).await.expect("loopback connect");
        let mut builder = ripdpi_tls_profiles::configure_builder("chrome_stable").expect("profile");
        builder.cert_store_mut().add_cert(X509::from_der(&cert_der).expect("test root")).expect("trust test root");
        builder.set_alpn_protos(b"\x08http/1.1").expect("HTTP/1.1 ALPN");
        let tls = tokio_boring::connect(
            builder.build().configure().expect("TLS config"),
            "localhost",
            TlsTransport::new(tcp),
        )
        .await
        .expect("verified test TLS");
        let request: NativeOwnedTlsHttpRequest =
            serde_json::from_value(serde_json::json!({"url": "https://localhost/", "readTimeoutMs": 1000}))
                .expect("fetch request");
        send_request(&Method::GET, "/", "localhost", 443, true, &request, TokioIo::new(tls)).await
    });
    server.join().expect("server joined");
    result
}

#[test]
fn close_delimited_body_requires_authenticated_tls_close() {
    let reply = b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nprefix";
    let error = fetch_response(reply, false).expect_err("an unframed TLS prefix is incomplete without close_notify");
    assert_eq!(error.kind(), io::ErrorKind::ConnectionAborted);
    assert_eq!(&fetch_response(reply, true).expect("authenticated close").body[..], b"prefix");
}

#[test]
fn complete_content_length_body_does_not_need_tls_close_notify() {
    let reply = b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nprefix";
    for clean_close in [false, true] {
        assert_eq!(&fetch_response(reply, clean_close).expect("complete framed body").body[..], b"prefix");
    }
}

#[test]
fn truncated_content_length_body_is_rejected_with_either_tls_close() {
    let reply = b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nprefix";
    for clean_close in [false, true] {
        assert!(fetch_response(reply, clean_close).is_err(), "clean_close={clean_close}");
    }
}

#[test]
fn complete_chunked_body_does_not_need_tls_close_notify() {
    let reply = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n6\r\nprefix\r\n0\r\n\r\n";
    for clean_close in [false, true] {
        assert_eq!(&fetch_response(reply, clean_close).expect("complete chunked body").body[..], b"prefix");
    }
}

#[test]
fn truncated_chunked_body_is_rejected_with_either_tls_close() {
    let reply = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n6\r\nprefix\r\n";
    for clean_close in [false, true] {
        assert!(fetch_response(reply, clean_close).is_err(), "clean_close={clean_close}");
    }
}
