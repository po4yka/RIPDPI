use super::*;
use rustls::pki_types::PrivateKeyDer;
use rustls::{RootCertStore, ServerConfig, ServerConnection, StreamOwned};
use std::net::{Ipv4Addr, TcpListener};
use std::thread::JoinHandle;

struct Fixture {
    port: u16,
    verifier: Arc<dyn ServerCertVerifier>,
    thread: JoinHandle<()>,
}
impl Fixture {
    fn start(socks: bool, stall: bool) -> Self {
        let certificate = rcgen::generate_simple_self_signed(vec!["example.com".into()]).unwrap();
        let cert = certificate.cert.der().clone();
        let key = PrivateKeyDer::Pkcs8(certificate.signing_key.serialize_der().into());
        let mut roots = RootCertStore::empty();
        roots.add(cert.clone()).unwrap();
        let verifier = rustls::client::WebPkiServerVerifier::builder_with_provider(
            Arc::new(roots),
            rustls::crypto::ring::default_provider().into(),
        )
        .build()
        .unwrap();
        let mut config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert], key)
            .unwrap();
        config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let thread = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            socket.set_write_timeout(Some(Duration::from_secs(3))).unwrap();
            if socks {
                let mut greeting = [0; 2];
                socket.read_exact(&mut greeting).unwrap();
                assert_eq!(greeting[0], 5);
                let mut methods = vec![0; greeting[1] as usize];
                socket.read_exact(&mut methods).unwrap();
                socket.write_all(&[5, 0]).unwrap();
                let mut request = [0; 10];
                socket.read_exact(&mut request).unwrap();
                assert_eq!(
                    request,
                    [5, 1, 0, 1, 8, 8, 8, 8, 1, 187],
                    "SOCKS must receive admitted IP, not original host"
                );
                socket.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 1]).unwrap();
            }
            let mut connection = ServerConnection::new(Arc::new(config)).unwrap();
            while connection.is_handshaking() {
                if connection.complete_io(&mut socket).is_err() {
                    return;
                }
            }
            assert_eq!(connection.alpn_protocol(), Some(b"http/1.1".as_slice()));
            let mut stream = StreamOwned::new(connection, socket);
            let mut request = Vec::new();
            let mut byte = [0];
            while !request.ends_with(b"\r\n\r\n") {
                if stream.read_exact(&mut byte).is_err() {
                    return;
                }
                request.push(byte[0]);
            }
            assert!(String::from_utf8_lossy(&request).contains("Host: example.com\r\n"));
            if stall {
                std::thread::sleep(Duration::from_millis(450));
                return;
            }
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").unwrap();
            stream.flush().unwrap();
        });
        Self { port, verifier, thread }
    }
    fn join(self) {
        self.thread.join().unwrap();
    }
}
fn config() -> SelectiveMatrixConfig {
    SelectiveMatrixConfig {
        version: 1,
        catalog_version: "test".into(),
        targets: vec![SelectiveMatrixTarget {
            id: "fixture".into(),
            label: "Fixture".into(),
            url: "https://example.com/".into(),
            cohort: "global".into(),
            infrastructure_group: "fixture".into(),
            source_url: String::new(),
            source_date: String::new(),
            last_verified_at: None,
            connect_ips: vec!["8.8.8.8".into()],
        }],
        repetitions: 2,
        timeout_ms: 1000,
        max_response_bytes: 1024,
    }
}
#[test]
fn socks_uses_pinned_ip_and_verified_tls_original_hostname() {
    let server = Fixture::start(true, false);
    let config = config();
    let result = run_selective_matrix_attempt(
        &config,
        &config.targets[0],
        1,
        &TransportConfig::Socks5 { host: "127.0.0.1".into(), port: server.port, credentials: None },
        &AtomicBool::new(false),
        Some(&server.verifier),
    );
    assert_eq!(result.outcome, "matrix_target_available", "{:?}", result.details);
    server.join();
}
#[test]
fn verified_direct_tls_rejects_untrusted_certificate() {
    let server = Fixture::start(false, false);
    let config = config();
    let mut parsed = parse_http_target("https://example.com/", None, &[], None).unwrap();
    parsed.port = server.port;
    let mut result = base_result(&config, &config.targets[0], 1);
    with_scan_io_deadline(Some(Instant::now() + Duration::from_secs(2)), || {
        execute_https(
            &parsed,
            &[TargetAddress::Ip(Ipv4Addr::LOCALHOST.into())],
            1024,
            &TransportConfig::Direct { route_experiment: None },
            &AttemptControl {
                cancel: &AtomicBool::new(false),
                deadline: Instant::now() + Duration::from_secs(2),
                scan_deadline: None,
            },
            None,
            &mut result,
        );
    });
    assert_eq!(result.outcome, "matrix_target_tls_error");
    server.join();
}
#[test]
fn verified_direct_tls_completes_body() {
    let server = Fixture::start(false, false);
    let config = config();
    let mut parsed = parse_http_target("https://example.com/", None, &[], None).unwrap();
    parsed.port = server.port;
    let mut result = base_result(&config, &config.targets[0], 1);
    with_scan_io_deadline(Some(Instant::now() + Duration::from_secs(2)), || {
        execute_https(
            &parsed,
            &[TargetAddress::Ip(Ipv4Addr::LOCALHOST.into())],
            1024,
            &TransportConfig::Direct { route_experiment: None },
            &AttemptControl {
                cancel: &AtomicBool::new(false),
                deadline: Instant::now() + Duration::from_secs(2),
                scan_deadline: None,
            },
            Some(&server.verifier),
            &mut result,
        );
    });
    assert_eq!(result.outcome, "matrix_target_available", "{:?}", result.details);
    server.join();
}
#[test]
fn body_stall_obeys_deadline_and_cancellation() {
    for cancel_early in [false, true] {
        let server = Fixture::start(true, true);
        let mut config = config();
        config.timeout_ms = 250;
        let cancel = Arc::new(AtomicBool::new(false));
        let flag = cancel.clone();
        let trigger = std::thread::spawn(move || {
            if cancel_early {
                std::thread::sleep(Duration::from_millis(80));
                flag.store(true, Ordering::Release);
            }
        });
        let start = Instant::now();
        let result = run_selective_matrix_attempt(
            &config,
            &config.targets[0],
            1,
            &TransportConfig::Socks5 { host: "127.0.0.1".into(), port: server.port, credentials: None },
            &cancel,
            Some(&server.verifier),
        );
        assert_eq!(
            result.outcome,
            if cancel_early { "matrix_target_cancelled" } else { "matrix_target_body_incomplete" },
            "{:?}",
            result.details
        );
        assert!(start.elapsed() < Duration::from_millis(700));
        trigger.join().unwrap();
        server.join();
    }
}
#[test]
fn private_pin_is_rejected_before_any_connection() {
    let mut config = config();
    config.targets[0].connect_ips = vec!["127.0.0.1".into()];
    let result = run_selective_matrix_attempt(
        &config,
        &config.targets[0],
        1,
        &TransportConfig::Direct { route_experiment: None },
        &AtomicBool::new(false),
        None,
    );
    assert_eq!(result.outcome, "matrix_target_invalid");
}
