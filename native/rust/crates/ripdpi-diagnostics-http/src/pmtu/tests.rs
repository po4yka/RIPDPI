use super::*;
use quic_mtu_test_util::MtuDropSocket;
use std::sync::mpsc;

struct Server {
    address: SocketAddr,
    verifier: Arc<dyn ServerCertVerifier>,
    stop: tokio::sync::oneshot::Sender<()>,
    worker: std::thread::JoinHandle<()>,
}
impl Server {
    fn start(ipv6: bool, alpn: &[u8]) -> Self {
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
                let endpoint =
                    quinn::Endpoint::server(config, if ipv6 { "[::1]:0" } else { "127.0.0.1:0" }.parse().unwrap())
                        .unwrap();
                send.send(endpoint.local_addr().unwrap()).unwrap();
                tokio::select! { _ = stopped => (), _ = serve(&endpoint) => () }
                endpoint.close(0u32.into(), b"test done");
            });
        });
        Self { address: recv.recv().unwrap(), verifier, stop, worker }
    }
    fn config(&self) -> PmtuProbeConfig {
        PmtuProbeConfig {
            host: "localhost".into(),
            port: self.address.port(),
            timeout_ms: 2500,
            observation_ms: 1000,
            connect_ipv4: self.address.is_ipv4().then(|| self.address.ip().to_string()),
            connect_ipv6: self.address.is_ipv6().then(|| self.address.ip().to_string()),
            ..Default::default()
        }
    }
    fn finish(self) {
        let _ = self.stop.send(());
        self.worker.join().unwrap();
    }
}
/// # Cancel safety:
/// conditionally cancel-safe: accept, handshake, H3 setup and accept waits own local resources;
/// the fixture closes its endpoint and drops its dedicated runtime on cancellation.
// cancel-safe: no detached child connection tasks.
async fn serve(endpoint: &quinn::Endpoint) {
    if let Some(incoming) = endpoint.accept().await
        && let Ok(connection) = incoming.await
    {
        let mut h3: h3::server::Connection<_, bytes::Bytes> =
            h3::server::Connection::new(h3_quinn::Connection::new(connection)).await.unwrap();
        let _ = h3.accept().await;
    }
}
fn run(server: &Server, config: &PmtuProbeConfig) -> PmtuEvidence {
    probe_pmtu(
        config,
        server.address.is_ipv6(),
        &TransportConfig::Direct { route_experiment: None },
        true,
        &AtomicBool::new(false),
        Some(&server.verifier),
    )
}
#[test]
fn active_protected_socket_confirms_growth_for_both_families() {
    for ipv6 in [false, true] {
        let server = Server::start(ipv6, b"h3");
        let evidence = run(&server, &server.config());
        assert_eq!(evidence.status, "OBSERVED", "{evidence:?}");
        assert!(evidence.fragmentation_prevented && evidence.tls_validated && evidence.observation_window_complete);
        assert!(evidence.acknowledged_udp_payload_lower_bound_bytes.unwrap() > 1200);
        assert!(evidence.sent_probe_count > 0);
        assert_eq!(evidence.lost_probe_count, 0);
        server.finish();
    }
}
#[test]
fn real_ack_size_cliffs_constrain_both_families_without_claiming_black_holes() {
    for ipv6 in [false, true] {
        for threshold in [1200, 1300, 65535] {
            let server = Server::start(ipv6, b"h3");
            let config = server.config();
            let mut facts = initial(&config, ipv6);
            let dropped = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
                let (socket, cap, _) = if ipv6 {
                    MtuDropSocket::bind_localhost_v6().unwrap()
                } else {
                    MtuDropSocket::bind_localhost().unwrap()
                };
                cap.set(threshold);
                tokio::time::timeout(
                    Duration::from_secs(3),
                    observe(
                        &config,
                        Some(&server.verifier),
                        server.address,
                        socket.clone(),
                        Instant::now(),
                        &mut facts,
                    ),
                )
                .await
                .unwrap()
                .unwrap();
                socket.evidence().dropped_tx
            });
            assert!(facts.observation_window_complete && facts.tls_validated, "{facts:?}");
            assert_eq!(facts.black_hole_count, 0);
            assert!(usize::from(facts.current_udp_payload_bytes.unwrap()) <= threshold);
            if threshold == 1200 {
                assert_eq!(facts.status, "INCONCLUSIVE");
                assert!(facts.acknowledged_udp_payload_lower_bound_bytes.is_none());
            } else {
                assert_eq!(facts.status, "OBSERVED", "{facts:?}");
                assert!(facts.acknowledged_udp_payload_lower_bound_bytes.unwrap() > 1200);
            }
            if threshold < 1472 {
                assert!(dropped > 0 && facts.lost_probe_count > 0, "{facts:?}");
            }
            server.finish();
        }
    }
}
#[test]
fn tls_or_alpn_failure_does_not_become_mtu_evidence() {
    for alpn in [b"h3".as_slice(), b"other".as_slice()] {
        let server = Server::start(false, alpn);
        let evidence = probe_pmtu(
            &server.config(),
            false,
            &TransportConfig::Direct { route_experiment: None },
            true,
            &AtomicBool::new(false),
            None,
        );
        assert_eq!(evidence.status, "INCONCLUSIVE", "{evidence:?}");
        assert!(!evidence.tls_validated);
        assert!(evidence.acknowledged_udp_payload_lower_bound_bytes.is_none());
        server.finish();
    }
}
#[test]
fn observation_deadline_and_cancellation_preserve_partial_facts() {
    for cancel_test in [false, true] {
        let server = Server::start(false, b"h3");
        let config = PmtuProbeConfig { timeout_ms: 500, observation_ms: 500, ..server.config() };
        let flag = Arc::new(AtomicBool::new(false));
        let other = flag.clone();
        let worker = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(250));
            if cancel_test {
                other.store(true, Ordering::Release);
            }
        });
        let started = Instant::now();
        let facts = probe_pmtu(
            &config,
            false,
            &TransportConfig::Direct { route_experiment: None },
            true,
            &flag,
            Some(&server.verifier),
        );
        worker.join().unwrap();
        assert_eq!(facts.status, if cancel_test { "CANCELLED" } else { "TIMEOUT" }, "{facts:?}");
        assert!(facts.tls_validated && facts.sent_probe_count > 0);
        assert!(!facts.observation_window_complete);
        assert!(started.elapsed() < Duration::from_secs(2));
        server.finish();
    }
}
#[test]
fn invalid_wrong_family_cancelled_and_unsupported_paths_do_not_probe() {
    let direct = TransportConfig::Direct { route_experiment: None };
    let config = PmtuProbeConfig { host: "127.0.0.1".into(), ..Default::default() };
    let flag = AtomicBool::new(false);
    assert_eq!(probe_pmtu(&config, true, &direct, true, &flag, None).reason.as_deref(), Some("no_family_address"));
    assert_eq!(probe_pmtu(&config, false, &direct, false, &flag, None).status, "UNSUPPORTED");
    assert_eq!(probe_pmtu(&config, false, &direct, true, &AtomicBool::new(true), None).status, "CANCELLED");
    assert_eq!(
        probe_pmtu(&PmtuProbeConfig { version: 9, ..config }, false, &direct, true, &flag, None).status,
        "INVALID"
    );
}

#[derive(Debug)]
struct Fragmenting(Arc<dyn quinn::AsyncUdpSocket>);
impl quinn::AsyncUdpSocket for Fragmenting {
    fn create_io_poller(self: Arc<Self>) -> std::pin::Pin<Box<dyn quinn::UdpPoller>> {
        self.0.clone().create_io_poller()
    }
    fn try_send(&self, _: &quinn::udp::Transmit<'_>) -> std::io::Result<()> {
        panic!("fragmenting socket must never send");
    }
    fn poll_recv(
        &self,
        cx: &mut std::task::Context<'_>,
        bufs: &mut [std::io::IoSliceMut<'_>],
        meta: &mut [quinn::udp::RecvMeta],
    ) -> std::task::Poll<std::io::Result<usize>> {
        self.0.poll_recv(cx, bufs, meta)
    }
    fn local_addr(&self) -> std::io::Result<SocketAddr> {
        self.0.local_addr()
    }
}
#[test]
fn fragmentation_capability_is_required_before_outbound_use() {
    let config = PmtuProbeConfig::default();
    let mut facts = initial(&config, false);
    let result = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
        let (socket, _, address) = MtuDropSocket::bind_localhost().unwrap();
        observe(&config, None, address, Arc::new(Fragmenting(socket)), Instant::now(), &mut facts).await
    });
    assert_eq!(result, Err(("UNSUPPORTED", "fragmentation_unsupported")));
    assert!(!facts.fragmentation_prevented && !facts.tls_validated);
}

#[test]
fn scan_deadline_bounds_family_budget_before_io() {
    let result = ripdpi_diagnostics_contracts::util::with_scan_io_deadline(
        Some(Instant::now() - Duration::from_millis(1)),
        || {
            probe_pmtu(
                &PmtuProbeConfig::default(),
                false,
                &TransportConfig::Direct { route_experiment: None },
                true,
                &AtomicBool::new(false),
                None,
            )
        },
    );
    assert_eq!(result.status, "TIMEOUT");
    assert!(!result.fragmentation_prevented && !result.tls_validated);
    assert!(result.peer_address.is_none());
}
