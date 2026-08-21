use std::io::{ErrorKind, Read, Write};
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::{
    HttpObservation, classify_http_response, parse_http_response, read_http_headers,
    try_http_request_targets_with_key_log,
};
use crate::connectivity::adapters::tls::{
    ApplicationProtocolPolicy, ProbeStreamError, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
    open_probe_stream_targets_with_options,
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
    let started = std::time::Instant::now();
    let parsed = match parse_http_target(&target.url, target.connect_ip.as_deref(), &target.connect_ips, target.port) {
        Ok(parsed) => parsed,
        Err(err) => {
            return failed_sample(started, "target_parse", err);
        }
    };
    let tls_name = if parsed.secure { Some(parsed.host.as_str()) } else { None };
    let options = ProbeStreamOptions {
        verify_certificates: parsed.secure,
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier,
        key_log,
    };
    let stream_result =
        open_probe_stream_targets_with_options(&parsed.connect_targets, parsed.port, transport, tls_name, &options);
    let stream_result = match stream_result {
        Ok(result) => result,
        Err(err) => {
            return failed_stream_sample(err, transport);
        }
    };
    let negotiated_alpn = stream_result.negotiated_alpn.clone();
    let address_family = stream_result
        .connected_addr
        .map(|address| if address.is_ipv4() { "ipv4" } else { "ipv6" }.to_string())
        .or_else(|| match transport {
            TransportConfig::Socks5 { .. } => Some("proxy_resolved_unknown".to_string()),
            TransportConfig::Direct { .. } => None,
        });
    let tcp_connect_ms = Some(stream_result.tcp_connect_ms);
    let tls_handshake_ms = parsed.secure.then_some(stream_result.tls_handshake_ms);
    let mut stream = stream_result.stream;
    let request =
        format!("GET {} HTTP/1.1\r\nHost: {}\r\nAccept: */*\r\nConnection: close\r\n\r\n", parsed.path, parsed.host);
    if let Err(err) = stream.write_all(request.as_bytes()).and_then(|_| stream.flush()) {
        stream.shutdown();
        return failed_sample_with_connection(
            started,
            "request_write",
            err.to_string(),
            negotiated_alpn,
            address_family,
            tcp_connect_ms,
            tls_handshake_ms,
        );
    }
    let headers = match read_http_headers(&mut stream, MAX_HTTP_BYTES) {
        Ok(headers) => headers,
        Err(err) => {
            stream.shutdown();
            return failed_sample_with_connection(
                started,
                "response_headers",
                err,
                negotiated_alpn,
                address_family,
                tcp_connect_ms,
                tls_handshake_ms,
            );
        }
    };
    let Some(header_end) = find_headers_end(&headers) else {
        stream.shutdown();
        return failed_sample_with_connection(
            started,
            "response_parse",
            "response_missing_headers".to_string(),
            negotiated_alpn,
            address_family,
            tcp_connect_ms,
            tls_handshake_ms,
        );
    };
    let response = match parse_http_response(&headers[..header_end], headers[header_end + 4..].to_vec()) {
        Ok(response) => response,
        Err(err) => {
            stream.shutdown();
            return failed_sample_with_connection(
                started,
                "response_parse",
                err,
                negotiated_alpn,
                address_family,
                tcp_connect_ms,
                tls_handshake_ms,
            );
        }
    };
    let status = classify_http_response(&response);
    let http_status_code = Some(response.status_code);
    let mut bytes_read = response.body.len().min(target.window_bytes);
    let mut last_error = "none".to_string();
    let mut read_completion = if bytes_read >= target.window_bytes { "window_complete" } else { "not_started" };
    let mut failure_stage = "none";
    while bytes_read < target.window_bytes {
        let remaining = target.window_bytes - bytes_read;
        let mut chunk = vec![0u8; remaining.min(16 * 1024)];
        match stream.read(&mut chunk) {
            Ok(0) => {
                read_completion = "clean_eof_before_window";
                failure_stage = "body_read";
                break;
            }
            Ok(read) => {
                bytes_read += read;
                if bytes_read >= target.window_bytes {
                    read_completion = "window_complete";
                }
            }
            Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                last_error = err.to_string();
                read_completion = "read_timeout";
                failure_stage = "body_read";
                break;
            }
            Err(err) => {
                last_error = err.to_string();
                read_completion = "read_error";
                failure_stage = "body_read";
                break;
            }
        }
    }
    stream.shutdown();
    let duration_ms = started.elapsed().as_millis().max(1) as u64;
    let bps = (bytes_read as u64).saturating_mul(8).saturating_mul(1000) / duration_ms;
    ThroughputSample {
        status,
        bytes_read,
        bps,
        error: last_error,
        failure_stage: failure_stage.to_string(),
        failure_class: classify_failure(failure_stage, read_completion).to_string(),
        duration_ms,
        http_status_code,
        negotiated_alpn,
        address_family,
        tcp_connect_ms,
        tls_handshake_ms,
        read_completion: read_completion.to_string(),
        retry_attempt: 0,
    }
}

fn failed_stream_sample(error: ProbeStreamError, transport: &TransportConfig) -> ThroughputSample {
    let address_family = error
        .connected_addr
        .map(|address| if address.is_ipv4() { "ipv4" } else { "ipv6" }.to_string())
        .or_else(|| match transport {
            TransportConfig::Socks5 { .. } => Some("proxy_resolved_unknown".to_string()),
            TransportConfig::Direct { .. } => None,
        });
    ThroughputSample {
        status: "http_unreachable".to_string(),
        bytes_read: 0,
        bps: 0,
        error: error.message,
        failure_stage: error.stage.as_str().to_string(),
        failure_class: error.stage.as_str().to_string(),
        duration_ms: error.duration_ms,
        http_status_code: None,
        negotiated_alpn: None,
        address_family,
        tcp_connect_ms: error.tcp_connect_ms,
        tls_handshake_ms: None,
        read_completion: "not_started".to_string(),
        retry_attempt: 0,
    }
}

fn failed_sample(started: std::time::Instant, failure_stage: &str, error: String) -> ThroughputSample {
    let failure_class = classify_failure(failure_stage, "not_started").to_string();
    ThroughputSample {
        status: if failure_stage == "target_parse" { "invalid_target" } else { "http_unreachable" }.to_string(),
        bytes_read: 0,
        bps: 0,
        error,
        failure_stage: failure_stage.to_string(),
        failure_class,
        duration_ms: started.elapsed().as_millis().max(1) as u64,
        http_status_code: None,
        negotiated_alpn: None,
        address_family: None,
        tcp_connect_ms: None,
        tls_handshake_ms: None,
        read_completion: "not_started".to_string(),
        retry_attempt: 0,
    }
}

fn classify_failure(failure_stage: &str, read_completion: &str) -> &'static str {
    match failure_stage {
        "none" => "none",
        "target_parse" => "target_parse",
        "request_write" => "request_io",
        "response_headers" | "response_parse" => "http_response",
        "body_read" if read_completion == "read_timeout" => "read_timeout",
        "body_read" if read_completion == "clean_eof_before_window" => "early_eof",
        "body_read" => "body_read",
        _ => "unknown",
    }
}

#[allow(clippy::too_many_arguments)]
fn failed_sample_with_connection(
    started: std::time::Instant,
    failure_stage: &str,
    error: String,
    negotiated_alpn: Option<String>,
    address_family: Option<String>,
    tcp_connect_ms: Option<u64>,
    tls_handshake_ms: Option<u64>,
) -> ThroughputSample {
    let mut sample = failed_sample(started, failure_stage, error);
    sample.negotiated_alpn = negotiated_alpn;
    sample.address_family = address_family;
    sample.tcp_connect_ms = tcp_connect_ms;
    sample.tls_handshake_ms = tls_handshake_ms;
    sample
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

    use ripdpi_diagnostics_transport::transport::TransportConfig;
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
