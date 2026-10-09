use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener};
use std::sync::Arc;
use std::thread::JoinHandle;

use ripdpi_diagnostics_transport::transport::TransportConfig;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, Error as TlsError, ServerConfig, ServerConnection, SignatureScheme, StreamOwned};

use crate::types::ThroughputTarget;

use super::{measure_throughput_window, measure_throughput_window_with_verifier};

#[test]
fn throughput_window_keeps_plain_http_targets_plain() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
    let addr = listener.local_addr().expect("fixture addr");
    let handle = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept fixture connection");
        let mut request = [0u8; 1024];
        let read = socket.read(&mut request).expect("read HTTP request");
        assert!(request[..read].starts_with(b"GET /payload HTTP/1.1\r\n"));
        assert!(String::from_utf8_lossy(&request[..read]).contains(&format!("Host: localhost:{}\r\n", addr.port())));
        socket
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
            .expect("write HTTP response");
    });
    let target = ThroughputTarget {
        id: "plain-http-fixture".to_string(),
        label: "Plain HTTP fixture".to_string(),
        url: format!("http://localhost:{}/payload", addr.port()),
        connect_ip: Some(Ipv4Addr::LOCALHOST.to_string()),
        connect_ips: Vec::new(),
        port: None,
        is_control: true,
        window_bytes: 2,
        runs: 1,
    };
    let transport = TransportConfig::Direct { route_experiment: None };

    let sample = measure_throughput_window(&target, &transport, None);

    assert_eq!(sample.status, "http_ok", "error={}", sample.error);
    assert_eq!(sample.bytes_read, 2);
    handle.join().expect("fixture thread");
}

#[test]
fn throughput_window_uses_http11_alpn_with_h2_capable_peer() {
    let server = H2PreferringHttp1Server::spawn();
    let target = ThroughputTarget {
        id: "h2-capable-fixture".to_string(),
        label: "H2-capable fixture".to_string(),
        url: format!("https://localhost:{}/payload", server.addr.port()),
        connect_ip: Some(Ipv4Addr::LOCALHOST.to_string()),
        connect_ips: Vec::new(),
        port: None,
        is_control: false,
        window_bytes: 2,
        runs: 1,
    };
    let transport = TransportConfig::Direct { route_experiment: None };

    let verifier: Arc<dyn ServerCertVerifier> = Arc::new(TestCertificateVerifier);
    let sample = measure_throughput_window_with_verifier(&target, &transport, None, Some(&verifier));

    assert_eq!(sample.status, "http_ok", "error={}", sample.error);
    assert_eq!(sample.bytes_read, 2);
    assert_eq!(sample.negotiated_alpn.as_deref(), Some("http/1.1"));
    assert_eq!(server.join(), Some("http/1.1".to_string()));
}

#[test]
fn throughput_window_attributes_tls_handshake_failure() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
    let addr = listener.local_addr().expect("fixture addr");
    let handle = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept fixture connection");
        let mut client_hello = [0u8; 1024];
        let _ = socket.read(&mut client_hello).expect("read client hello");
    });
    let target = ThroughputTarget {
        id: "tls-failure-fixture".to_string(),
        label: "TLS failure fixture".to_string(),
        url: format!("https://localhost:{}/payload", addr.port()),
        connect_ip: Some(Ipv4Addr::LOCALHOST.to_string()),
        connect_ips: Vec::new(),
        port: None,
        is_control: false,
        window_bytes: 2,
        runs: 1,
    };

    let sample = measure_throughput_window(&target, &TransportConfig::Direct { route_experiment: None }, None);

    assert_eq!(sample.status, "http_unreachable");
    assert_eq!(sample.failure_stage, "tls_handshake");
    assert_eq!(sample.failure_class, "tls_handshake");
    assert_eq!(sample.address_family.as_deref(), Some("ipv4"));
    assert!(sample.tcp_connect_ms.is_some());
    handle.join().expect("fixture thread");
}

#[test]
fn throughput_window_attributes_early_eof_before_window() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
    let addr = listener.local_addr().expect("fixture addr");
    let handle = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept fixture connection");
        let mut request = [0u8; 1024];
        let _ = socket.read(&mut request).expect("read HTTP request");
        socket
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
            .expect("write short HTTP response");
    });
    let target = ThroughputTarget {
        id: "early-eof-fixture".to_string(),
        label: "Early EOF fixture".to_string(),
        url: format!("http://localhost:{}/payload", addr.port()),
        connect_ip: Some(Ipv4Addr::LOCALHOST.to_string()),
        connect_ips: Vec::new(),
        port: None,
        is_control: true,
        window_bytes: 1024,
        runs: 1,
    };

    let sample = measure_throughput_window(&target, &TransportConfig::Direct { route_experiment: None }, None);

    assert_eq!(sample.failure_stage, "body_read");
    assert_eq!(sample.failure_class, "early_eof");
    assert_eq!(sample.read_completion, "clean_eof_before_window");
    handle.join().expect("fixture thread");
}

#[test]
fn transfer_chunked_counts_payload_not_framing() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind");
    let addr = listener.local_addr().expect("addr");
    let handle = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept");
        let mut request = [0; 1024];
        assert!(socket.read(&mut request).expect("request") > 0);
        socket
            .write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n")
            .expect("response");
    });
    let target = ThroughputTarget {
        id: "chunked".into(),
        label: "Chunked".into(),
        url: format!("http://localhost:{}/", addr.port()),
        connect_ip: Some(Ipv4Addr::LOCALHOST.to_string()),
        connect_ips: Vec::new(),
        port: None,
        is_control: true,
        window_bytes: 1024,
        runs: 1,
    };
    let sample = measure_throughput_window(&target, &TransportConfig::Direct { route_experiment: None }, None);
    assert_eq!(sample.bytes_read, 2);
    handle.join().expect("join");
}

struct H2PreferringHttp1Server {
    addr: SocketAddr,
    handle: JoinHandle<Option<String>>,
}

impl H2PreferringHttp1Server {
    fn spawn() -> Self {
        let certificate =
            rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).expect("self-signed certificate");
        let cert_der = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(certificate.signing_key.serialize_der().into());
        let mut config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![cert_der], key_der)
            .expect("server config");
        config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
        let addr = listener.local_addr().expect("fixture addr");
        let handle = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept fixture connection");
            let mut connection = ServerConnection::new(Arc::new(config)).expect("server connection");
            while connection.is_handshaking() {
                connection.complete_io(&mut socket).expect("server handshake");
            }
            let selected_alpn =
                connection.alpn_protocol().map(|protocol| String::from_utf8_lossy(protocol).into_owned());
            if selected_alpn.as_deref() != Some("http/1.1") {
                return selected_alpn;
            }

            let mut stream = StreamOwned::new(connection, socket);
            let mut request = [0u8; 1024];
            let read = stream.read(&mut request).expect("read HTTP/1.1 request");
            assert!(request[..read].starts_with(b"GET /payload HTTP/1.1\r\n"));
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                .expect("write HTTP/1.1 response");
            stream.flush().expect("flush HTTP/1.1 response");
            selected_alpn
        });

        Self { addr, handle }
    }

    fn join(self) -> Option<String> {
        self.handle.join().expect("fixture thread")
    }
}

#[derive(Debug)]
struct TestCertificateVerifier;

impl ServerCertVerifier for TestCertificateVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ECDSA_NISTP256_SHA256]
    }
}

fn response_fixture(response: &'static [u8], window_bytes: usize) -> super::ThroughputSample {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind");
    let addr = listener.local_addr().expect("addr");
    let handle = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept");
        let mut request = [0; 1024];
        assert!(socket.read(&mut request).expect("request") > 0);
        socket.write_all(response).expect("response");
    });
    let target = ThroughputTarget {
        id: "response".into(),
        label: "Response".into(),
        url: format!("http://localhost:{}/", addr.port()),
        connect_ip: Some(Ipv4Addr::LOCALHOST.to_string()),
        connect_ips: Vec::new(),
        port: None,
        is_control: true,
        window_bytes,
        runs: 1,
    };
    let sample = measure_throughput_window(&target, &TransportConfig::Direct { route_experiment: None }, None);
    handle.join().expect("join");
    sample
}

#[test]
fn transfer_blockpage_body_is_not_successful_throughput() {
    let sample = response_fixture(b"HTTP/1.1 200 OK\r\nContent-Length: 24\r\n\r\nblocked by your provider", 24);
    assert_eq!(sample.status, "http_blockpage");
    assert_eq!(sample.bytes_read, 24);
}

#[test]
fn transfer_http_error_is_not_request_io_failure() {
    let sample = response_fixture(b"HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n", 24);
    assert_eq!(sample.failure_stage, "response_status");
    assert_eq!(sample.failure_class, "http_status");
    assert_eq!(sample.error, "http_status_503");
    assert_eq!(sample.measurement.expect("measurement").termination_reason.as_deref(), Some("http_error"));
}
