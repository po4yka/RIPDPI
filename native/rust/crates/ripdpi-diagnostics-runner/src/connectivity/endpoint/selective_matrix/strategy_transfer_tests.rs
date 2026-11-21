use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

use rustls::client::WebPkiServerVerifier;
use rustls::{RootCertStore, ServerConfig, ServerConnection, StreamOwned};

use crate::test_fixtures::{make_cert, read_until_marker};
use ripdpi_diagnostics_transport::transport::direct_transport;

use super::*;

#[test]
fn authenticated_control_reports_headers_when_the_body_stalls() {
    assert_eq!(control_stage(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n", true), "http_headers");
}

#[test]
fn authenticated_control_distinguishes_a_first_byte_from_a_complete_short_body() {
    assert_eq!(control_stage(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\no", false), "first_body_byte");
    assert_eq!(control_stage(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", false), "body");
    assert_eq!(control_stage(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nok", false), "first_body_byte");
    assert_eq!(
        control_stage(b"HTTP/1.1 200 OK\r\nContent-Length: 26\r\n\r\nthis site has been blocked", false),
        "first_body_byte"
    );
}

fn control_stage(response: &[u8], hold_open: bool) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (cert, key) = make_cert(&["localhost".into()]);
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = ServerConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], key)
        .unwrap();
    let mut roots = RootCertStore::empty();
    roots.add(cert).unwrap();
    let verifier: Arc<dyn ServerCertVerifier> =
        WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider).build().unwrap();
    let target = DomainTarget {
        host: "localhost".into(),
        connect_ip: Some("127.0.0.1".into()),
        connect_ips: vec![],
        https_port: Some(port),
        http_port: None,
        http_path: "/control".into(),
        is_control: true,
        concurrency_probe: None,
    };
    thread::scope(|scope| {
        let peer = scope.spawn(|| {
            let (socket, _) = listener.accept().unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(6))).unwrap();
            let mut stream = StreamOwned::new(ServerConnection::new(Arc::new(config)).unwrap(), socket);
            let request = read_until_marker(&mut stream, b"\r\n\r\n");
            assert!(request.starts_with(b"GET /control HTTP/1.1\r\n"));
            stream.write_all(response).unwrap();
            stream.flush().unwrap();
            if hold_open {
                let _ = stream.read(&mut [0]);
            } else {
                stream.conn.send_close_notify();
                stream.flush().unwrap();
            }
        });
        let result =
            run_strategy_control_transfer(&target, &direct_transport(), &AtomicBool::new(false), Some(&verifier));
        peer.join().unwrap();
        result.unwrap().value
    })
}
