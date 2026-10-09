use super::*;
use bytes::Bytes;
use std::sync::mpsc;

#[derive(Clone, Copy)]
enum Reply {
    Body(u16, usize),
    LengthMismatch,
    EarlyHints(usize),
    HeaderStall,
    BodyStall,
}
struct Server {
    address: SocketAddr,
    verifier: Arc<dyn ServerCertVerifier>,
    stop: tokio::sync::oneshot::Sender<()>,
    worker: std::thread::JoinHandle<()>,
}
impl Server {
    fn start(reply: Reply, alpn: &[u8]) -> Self {
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert.cert.der().clone()).unwrap();
        let verifier = rustls::client::WebPkiServerVerifier::builder_with_provider(
            Arc::new(roots),
            Arc::new(rustls::crypto::ring::default_provider()),
        )
        .build()
        .unwrap();
        let key = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());
        let mut tls = rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_protocol_versions(&[&rustls::version::TLS13])
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert.cert.der().clone()], key.into())
            .unwrap();
        tls.alpn_protocols = vec![alpn.to_vec()];
        let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).unwrap();
        let config = quinn::ServerConfig::with_crypto(Arc::new(crypto));
        let (send, recv) = mpsc::channel();
        let (stop, stopped) = tokio::sync::oneshot::channel();
        let worker = std::thread::spawn(move || {
            tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
                let endpoint = quinn::Endpoint::server(config, "127.0.0.1:0".parse().unwrap()).unwrap();
                send.send(endpoint.local_addr().unwrap()).unwrap();
                tokio::select! {
                    _ = stopped => (),
                    _ = serve(&endpoint, reply) => (),
                }
                endpoint.close(0u32.into(), b"test done");
            });
        });
        Self { address: recv.recv().unwrap(), verifier, stop, worker }
    }
    fn config(&self) -> Http3ProbeConfig {
        Http3ProbeConfig {
            host: "localhost".into(),
            connect_ip: Some(self.address.ip().to_string()),
            port: self.address.port(),
            path: "/probe".into(),
            timeout_ms: 1500,
            ..Http3ProbeConfig::default()
        }
    }
    fn finish(self) {
        let _ = self.stop.send(());
        self.worker.join().unwrap();
    }
}
/// # Cancel safety:
/// conditionally cancel-safe: accept, handshake, H3 accept/resolve, read/write,
/// finish and closed awaits discard local partial frames on cancellation. The
/// test owner closes the endpoint and drops the dedicated runtime.
// cancel-safe: all server state is scoped to the test runtime.
async fn serve(endpoint: &quinn::Endpoint, reply: Reply) {
    let Some(incoming) = endpoint.accept().await else {
        return;
    };
    let Ok(connection) = incoming.await else {
        return;
    };
    let mut server = h3::server::Connection::new(h3_quinn::Connection::new(connection.clone())).await.unwrap();
    let Some(resolver) = server.accept().await.unwrap() else {
        return;
    };
    let (request, mut stream) = resolver.resolve_request().await.unwrap();
    assert_eq!(request.method(), "GET");
    assert_eq!(request.uri().host(), Some("localhost"));
    assert_eq!(request.uri().path(), "/probe");
    assert!(stream.recv_data().await.unwrap().is_none());
    if matches!(reply, Reply::HeaderStall) {
        std::future::pending::<()>().await;
    }
    if let Reply::EarlyHints(count) = reply {
        for _ in 0..count {
            if stream.send_response(http::Response::builder().status(103).body(()).unwrap()).await.is_err() {
                return;
            }
        }
    }
    let status = match reply {
        Reply::Body(code, _) => code,
        _ => 200,
    };
    let mut response = http::Response::builder().status(status);
    if matches!(reply, Reply::LengthMismatch) {
        response = response.header(http::header::CONTENT_LENGTH, "100");
    }
    stream.send_response(response.body(()).unwrap()).await.unwrap();
    let size = match reply {
        Reply::Body(_, size) => size,
        _ => 5,
    };
    if size > 0 {
        stream.send_data(Bytes::from(vec![b'x'; size])).await.unwrap();
    }
    if matches!(reply, Reply::BodyStall) {
        std::future::pending::<()>().await;
    }
    stream.finish().await.unwrap();
    connection.closed().await;
}
fn run(server: &Server, config: &Http3ProbeConfig) -> Http3Evidence {
    probe_http3(
        config,
        &TransportConfig::Direct { route_experiment: None },
        true,
        &AtomicBool::new(false),
        Some(&server.verifier),
    )
}
#[test]
fn verified_get_data_fin_and_empty_204_are_complete() {
    for (status, size) in [(200, 12), (204, 0), (403, 7)] {
        let server = Server::start(Reply::Body(status, size), b"h3");
        let result = run(&server, &server.config());
        assert_eq!(result.status, if status == 403 { "HTTP_ERROR" } else { "COMPLETE" }, "{result:?}");
        assert_eq!(result.http_status, Some(status));
        assert_eq!(result.response_bytes, size as u64);
        assert!(result.body_complete && result.http3_validated && result.tls_validated && result.request_sent);
        assert_eq!(result.alpn.as_deref(), Some("h3"));
        assert_eq!(result.dns_status, "PINNED");
        assert!(result.headers_elapsed_ms >= result.handshake_elapsed_ms);
        server.finish();
    }
}
#[test]
fn stalled_headers_and_body_preserve_the_last_observation() {
    for reply in [Reply::HeaderStall, Reply::BodyStall] {
        let server = Server::start(reply, b"h3");
        let result = run(&server, &Http3ProbeConfig { timeout_ms: 250, ..server.config() });
        assert_eq!(result.status, "TIMEOUT", "{result:?}");
        assert!(!result.body_complete);
        assert_eq!(result.http3_validated, matches!(reply, Reply::BodyStall));
        assert_eq!(result.response_bytes, if matches!(reply, Reply::BodyStall) { 5 } else { 0 });
        server.finish();
    }
}
#[test]
fn body_cap_is_not_a_completed_response() {
    let server = Server::start(Reply::Body(200, 20), b"h3");
    let result = run(&server, &Http3ProbeConfig { max_response_bytes: 10, ..server.config() });
    assert_eq!(result.status, "BODY_LIMIT", "{result:?}");
    assert_eq!(result.response_bytes, 10);
    assert!(!result.body_complete);
    server.finish();
}
#[test]
fn bad_certificate_hostname_and_alpn_never_validate_http3() {
    for mode in [0, 1, 2] {
        let server = Server::start(Reply::Body(200, 0), if mode == 2 { b"h2" } else { b"h3" });
        let mut config = server.config();
        if mode == 1 {
            config.host = "wrong.example".into();
        }
        let result = probe_http3(
            &config,
            &TransportConfig::Direct { route_experiment: None },
            true,
            &AtomicBool::new(false),
            (mode != 0).then_some(&server.verifier),
        );
        assert!(!result.tls_validated && !result.http3_validated && !result.body_complete, "{result:?}");
        assert_eq!(result.status, "FAILED");
        server.finish();
    }
}
#[test]
fn cancelled_request_and_absolute_deadline_stop_a_live_stream() {
    for deadline in [false, true] {
        let server = Server::start(Reply::BodyStall, b"h3");
        let cancel = AtomicBool::new(false);
        let started = Instant::now();
        let result = std::thread::scope(|scope| {
            if !deadline {
                scope.spawn(|| {
                    std::thread::sleep(Duration::from_millis(150));
                    cancel.store(true, Ordering::Release);
                });
            }
            ripdpi_diagnostics_contracts::util::with_scan_io_deadline(
                deadline.then(|| Instant::now() + Duration::from_millis(150)),
                || {
                    probe_http3(
                        &server.config(),
                        &TransportConfig::Direct { route_experiment: None },
                        true,
                        &cancel,
                        Some(&server.verifier),
                    )
                },
            )
        });
        assert_eq!(result.status, if deadline { "TIMEOUT" } else { "CANCELLED" });
        assert!(started.elapsed() < Duration::from_millis(750));
        assert!(!result.body_complete);
        server.finish();
    }
}
#[test]
fn invalid_and_proxy_requests_never_connect() {
    let config = Http3ProbeConfig::default();
    let cancel = AtomicBool::new(false);
    let result = probe_http3(&config, &TransportConfig::Direct { route_experiment: None }, false, &cancel, None);
    assert_eq!(result.status, "UNSUPPORTED");
    assert_eq!(result.attempt_count, 0);
    let result = probe_http3(
        &Http3ProbeConfig { path: "//other.example".into(), ..config },
        &TransportConfig::Direct { route_experiment: None },
        true,
        &cancel,
        None,
    );
    assert_eq!(result.status, "INVALID");
    assert_eq!(result.attempt_count, 0);
}
#[test]
fn initial_packet_or_random_udp_reply_does_not_prove_http3() {
    let socket = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
    socket.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    let address = socket.local_addr().unwrap();
    let worker = std::thread::spawn(move || {
        let mut buffer = [0u8; 2048];
        let (size, peer) = socket.recv_from(&mut buffer).unwrap();
        socket.send_to(&buffer[..size], peer).unwrap();
        std::thread::sleep(Duration::from_millis(300));
    });
    let config = Http3ProbeConfig {
        connect_ip: Some(address.ip().to_string()),
        port: address.port(),
        timeout_ms: 250,
        ..Http3ProbeConfig::default()
    };
    let result =
        probe_http3(&config, &TransportConfig::Direct { route_experiment: None }, true, &AtomicBool::new(false), None);
    assert!(!result.tls_validated && !result.http3_validated && !result.request_sent);
    assert_eq!(result.attempt_count, 1);
    worker.join().unwrap();
}

#[test]
fn failed_protection_closes_socket_before_any_attempt() {
    // Isolate process-global protection and fd ownership from parallel socket tests.
    // Otherwise another test may reuse the closed descriptor before the assertion.
    const CHILD: &str = "RIPDPI_HTTP3_PROTECT_CHILD";
    if std::env::var_os(CHILD).is_none() {
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "http3::tests::failed_protection_closes_socket_before_any_attempt", "--test-threads=1"])
            .env(CHILD, "1")
            .status()
            .unwrap();
        assert!(status.success());
        return;
    }
    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};
    use std::os::fd::RawFd;
    use std::sync::atomic::AtomicI32;
    struct Refuse(AtomicI32);
    impl ProtectCallback for Refuse {
        fn protect(&self, fd: RawFd) -> std::io::Result<()> {
            self.0.store(fd, Ordering::Release);
            Err(std::io::Error::other("test refuses protection"))
        }
    }
    let callback = Arc::new(Refuse(AtomicI32::new(-1)));
    register_protect_callback(callback.clone());
    let config = Http3ProbeConfig { connect_ip: Some("192.0.2.1".into()), ..Http3ProbeConfig::default() };
    let result =
        probe_http3(&config, &TransportConfig::Direct { route_experiment: None }, true, &AtomicBool::new(false), None);
    unregister_protect_callback();
    assert_eq!(result.reason.as_deref(), Some("protect_failed"));
    assert_eq!(result.attempt_count, 0);
    let fd = callback.0.load(Ordering::Acquire);
    assert!(fd >= 0);
    assert!(std::fs::metadata(format!("/dev/fd/{fd}")).is_err());
}

#[test]
fn early_fin_with_wrong_content_length_is_not_complete() {
    let server = Server::start(Reply::LengthMismatch, b"h3");
    let result = run(&server, &server.config());
    assert_eq!(result.status, "FAILED", "{result:?}");
    assert!(result.http3_validated);
    assert!(!result.body_complete);
    server.finish();
}

#[test]
fn informational_headers_require_a_bounded_final_response() {
    for count in [1, 8, 9] {
        let server = Server::start(Reply::EarlyHints(count), b"h3");
        let result = run(&server, &server.config());
        assert_eq!(result.status, if count <= 8 { "COMPLETE" } else { "FAILED" }, "{result:?}");
        assert_eq!(result.http_status, if count <= 8 { Some(200) } else { None });
        server.finish();
    }
}
