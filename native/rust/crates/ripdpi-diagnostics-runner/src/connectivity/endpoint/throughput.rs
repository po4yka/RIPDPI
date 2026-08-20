use std::io::{ErrorKind, Read, Write};
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::{
    HttpObservation, classify_http_response, parse_http_response, read_http_headers,
    try_http_request_targets_with_key_log,
};
use crate::connectivity::adapters::tls::{
    TlsClientProfile, TlsKeyLogCallback, open_probe_stream_targets, open_probe_stream_targets_with_key_log,
};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::connectivity::adapters::util::{MAX_HTTP_BYTES, find_headers_end};
use crate::types::ThroughputTarget;

use super::target_parse::parse_http_target;
use super::types::ThroughputSample;

pub(super) fn measure_throughput_window(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> ThroughputSample {
    measure_throughput_window_with_verifier(target, transport, key_log, None)
}

fn measure_throughput_window_with_verifier(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ThroughputSample {
    let parsed = match parse_http_target(&target.url, target.connect_ip.as_deref(), &target.connect_ips, target.port) {
        Ok(parsed) => parsed,
        Err(err) => {
            return ThroughputSample { status: "invalid_target".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let started = std::time::Instant::now();
    let tls_name = if parsed.secure { Some(parsed.host.as_str()) } else { None };
    let stream_result = match key_log {
        Some(key_log) => open_probe_stream_targets_with_key_log(
            &parsed.connect_targets,
            parsed.port,
            transport,
            tls_name,
            parsed.secure,
            TlsClientProfile::AutoHttp11,
            tls_verifier,
            Some(key_log),
        ),
        None => open_probe_stream_targets(
            &parsed.connect_targets,
            parsed.port,
            transport,
            tls_name,
            parsed.secure,
            TlsClientProfile::AutoHttp11,
            tls_verifier,
        ),
    };
    let mut stream = match stream_result {
        Ok(result) => result.stream,
        Err(err) => {
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let request =
        format!("GET {} HTTP/1.1\r\nHost: {}\r\nAccept: */*\r\nConnection: close\r\n\r\n", parsed.path, parsed.host);
    if let Err(err) = stream.write_all(request.as_bytes()).and_then(|_| stream.flush()) {
        stream.shutdown();
        return ThroughputSample {
            status: "http_unreachable".to_string(),
            bytes_read: 0,
            bps: 0,
            error: err.to_string(),
        };
    }
    let headers = match read_http_headers(&mut stream, MAX_HTTP_BYTES) {
        Ok(headers) => headers,
        Err(err) => {
            stream.shutdown();
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let Some(header_end) = find_headers_end(&headers) else {
        stream.shutdown();
        return ThroughputSample {
            status: "http_unreachable".to_string(),
            bytes_read: 0,
            bps: 0,
            error: "response_missing_headers".to_string(),
        };
    };
    let response = match parse_http_response(&headers[..header_end], headers[header_end + 4..].to_vec()) {
        Ok(response) => response,
        Err(err) => {
            stream.shutdown();
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let status = classify_http_response(&response);
    let mut bytes_read = response.body.len().min(target.window_bytes);
    let mut last_error = "none".to_string();
    while bytes_read < target.window_bytes {
        let remaining = target.window_bytes - bytes_read;
        let mut chunk = vec![0u8; remaining.min(16 * 1024)];
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => {
                bytes_read += read;
            }
            Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                last_error = err.to_string();
                break;
            }
            Err(err) => {
                last_error = err.to_string();
                break;
            }
        }
    }
    stream.shutdown();
    let duration_ms = started.elapsed().as_millis().max(1) as u64;
    let bps = (bytes_read as u64).saturating_mul(8).saturating_mul(1000) / duration_ms;
    ThroughputSample { status, bytes_read, bps, error: last_error }
}

pub(super) fn probe_http_url(
    url: &str,
    connect_ip: Option<&str>,
    connect_ips: &[String],
    port_override: Option<u16>,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> HttpObservation {
    match parse_http_target(url, connect_ip, connect_ips, port_override) {
        Ok(parsed) => try_http_request_targets_with_key_log(
            &parsed.connect_targets,
            parsed.port,
            transport,
            &parsed.host,
            &parsed.path,
            parsed.secure,
            key_log,
        ),
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
    }
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};
    use std::sync::Arc;
    use std::thread::JoinHandle;

    use ripdpi_diagnostics_protocols::transport::TransportConfig;
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime};
    use rustls::{
        DigitallySignedStruct, Error as TlsError, ServerConfig, ServerConnection, SignatureScheme, StreamOwned,
    };

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
        assert_eq!(server.join(), Some("http/1.1".to_string()));
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
}
