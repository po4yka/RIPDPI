mod control;
mod dns;
mod echo;
mod event;
mod fault;
mod http;
mod socks;
mod types;
mod util;

pub use self::event::*;
pub use self::fault::*;
pub use self::types::*;

use std::fmt;
use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use rcgen::generate_simple_self_signed;
use rustls::pki_types::PrivateKeyDer;
use rustls::ServerConfig;

pub struct FixtureStack {
    manifest: FixtureManifest,
    events: EventLog,
    faults: FaultController,
    stop: Arc<AtomicBool>,
    handles: Vec<JoinHandle<()>>,
}

impl FixtureStack {
    pub fn start(config: FixtureConfig) -> io::Result<Self> {
        let certificate = generate_simple_self_signed(vec![
            config.fixture_domain.clone(),
            "localhost".to_string(),
            "127.0.0.1".to_string(),
        ])
        .map_err(util::other_io)?;
        let cert_der = certificate.cert.der().clone();
        let cert_pem = certificate.cert.pem();
        let key_der = PrivateKeyDer::Pkcs8(certificate.key_pair.serialize_der().into());
        let tls_server_config = Arc::new(
            ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("ring provider supports default TLS versions")
                .with_no_client_auth()
                .with_single_cert(vec![cert_der], key_der)
                .map_err(util::other_io)?,
        );

        let stop = Arc::new(AtomicBool::new(false));
        let events = EventLog::new();
        let faults = FaultController::new();
        let (tcp_echo_handle, tcp_echo_port) = echo::start_tcp_echo_server(
            config.bind_host.clone(),
            config.tcp_echo_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
        )?;
        let (udp_echo_handle, udp_echo_port) = echo::start_udp_echo_server(
            config.bind_host.clone(),
            config.udp_echo_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
        )?;
        let (tls_echo_handle, tls_echo_port) = echo::start_tls_echo_server(
            config.bind_host.clone(),
            config.tls_echo_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            tls_server_config.clone(),
        )?;
        let (dns_udp_handle, dns_udp_port) = dns::start_dns_udp_server(
            config.bind_host.clone(),
            config.dns_udp_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            config.dns_answer_ipv4.clone(),
        )?;
        let (dns_http_handle, dns_http_port) = dns::start_dns_http_server(
            config.bind_host.clone(),
            config.dns_http_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            config.dns_answer_ipv4.clone(),
        )?;
        let (dns_dot_handle, dns_dot_port) = dns::start_dns_dot_server(
            config.bind_host.clone(),
            config.dns_dot_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            config.dns_answer_ipv4.clone(),
            tls_server_config.clone(),
        )?;
        let (dns_dnscrypt_handle, dns_dnscrypt_port) = dns::start_dns_dnscrypt_server(
            config.bind_host.clone(),
            config.dns_dnscrypt_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            config.dns_answer_ipv4.clone(),
            config.dnscrypt_provider_name.clone(),
            config.dnscrypt_public_key.clone(),
        )?;
        let (dns_doq_handle, dns_doq_port) = dns::start_dns_doq_server(
            config.bind_host.clone(),
            config.dns_doq_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            config.dns_answer_ipv4.clone(),
            tls_server_config.clone(),
        )?;
        let (socks5_handle, socks5_port) =
            socks::start_socks5_server(config.clone(), stop.clone(), events.clone(), faults.clone())?;

        let mut manifest = FixtureManifest {
            bind_host: config.bind_host.clone(),
            android_host: config.android_host.clone(),
            tcp_echo_port,
            udp_echo_port,
            tls_echo_port,
            dns_udp_port,
            dns_http_port,
            dns_dot_port,
            dns_dnscrypt_port,
            dns_doq_port,
            socks5_port,
            control_port: 0,
            fixture_domain: config.fixture_domain.clone(),
            fixture_ipv4: config.fixture_ipv4.clone(),
            dns_answer_ipv4: config.dns_answer_ipv4.clone(),
            tls_certificate_pem: cert_pem,
            dnscrypt_provider_name: config.dnscrypt_provider_name.clone(),
            dnscrypt_public_key: config.dnscrypt_public_key.clone(),
        };
        let shared_manifest = Arc::new(Mutex::new(manifest.clone()));
        let (control_handle, control_port) = control::start_control_server(
            config.bind_host,
            config.control_port,
            stop.clone(),
            events.clone(),
            faults.clone(),
            shared_manifest.clone(),
        )?;
        manifest.control_port = control_port;
        if let Ok(mut current) = shared_manifest.lock() {
            *current = manifest.clone();
        }

        let handles = vec![
            tcp_echo_handle,
            udp_echo_handle,
            tls_echo_handle,
            dns_udp_handle,
            dns_http_handle,
            dns_dot_handle,
            dns_dnscrypt_handle,
            dns_doq_handle,
            socks5_handle,
            control_handle,
        ];

        Ok(Self { manifest, events, faults, stop, handles })
    }

    pub fn manifest(&self) -> &FixtureManifest {
        &self.manifest
    }

    pub fn events(&self) -> EventLog {
        self.events.clone()
    }

    pub fn faults(&self) -> FaultController {
        self.faults.clone()
    }
}

impl Drop for FixtureStack {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.tcp_echo_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.tls_echo_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.dns_http_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.dns_dot_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.dns_dnscrypt_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.socks5_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.control_port);
        util::wake_udp(&self.manifest.bind_host, self.manifest.udp_echo_port);
        util::wake_udp(&self.manifest.bind_host, self.manifest.dns_udp_port);
        util::wake_udp(&self.manifest.bind_host, self.manifest.dns_doq_port);
        for handle in self.handles.drain(..) {
            let _ = handle.join();
        }
    }
}

impl fmt::Debug for FixtureStack {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("FixtureStack").field("manifest", &self.manifest).finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{ErrorKind, Read, Write};
    use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpStream, UdpSocket};
    use std::sync::Mutex;
    use std::time::Duration;

    use ripdpi_dns_resolver::{
        extract_ip_answers, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
    };
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
    use rustls::pki_types::{pem::PemObject, CertificateDer, ServerName, UnixTime};
    use rustls::{
        ClientConfig, ClientConnection, DigitallySignedStruct, Error as TlsError, SignatureScheme, StreamOwned,
    };

    use crate::dns::parse_dns_question_name;
    use crate::http::read_until_marker;
    use crate::socks::{map_socket_addr, map_target, SocksTarget};

    static FIXTURE_STACK_TEST_MUTEX: Mutex<()> = Mutex::new(());

    #[test]
    fn fixture_config_reads_env_override() {
        std::env::set_var("RIPDPI_FIXTURE_TCP_ECHO_PORT", "47001");
        let config = FixtureConfig::from_env();
        assert_eq!(config.tcp_echo_port, 47001);
        std::env::remove_var("RIPDPI_FIXTURE_TCP_ECHO_PORT");
    }

    #[test]
    fn fixture_config_reads_android_host_override() {
        std::env::set_var("RIPDPI_FIXTURE_ANDROID_HOST", "127.0.0.1");
        let config = FixtureConfig::from_env();
        assert_eq!(config.android_host, "127.0.0.1");
        std::env::remove_var("RIPDPI_FIXTURE_ANDROID_HOST");
    }

    #[test]
    fn manifest_serializes_and_control_url_uses_requested_host() {
        let manifest = FixtureManifest {
            bind_host: "127.0.0.1".to_string(),
            android_host: "10.0.2.2".to_string(),
            tcp_echo_port: 1,
            udp_echo_port: 2,
            tls_echo_port: 3,
            dns_udp_port: 4,
            dns_http_port: 5,
            dns_dot_port: 6,
            dns_dnscrypt_port: 7,
            dns_doq_port: 8,
            socks5_port: 9,
            control_port: 10,
            fixture_domain: "fixture.test".to_string(),
            fixture_ipv4: "198.18.0.10".to_string(),
            dns_answer_ipv4: "198.18.0.10".to_string(),
            tls_certificate_pem: "pem".to_string(),
            dnscrypt_provider_name: "2.dnscrypt-cert.fixture.test".to_string(),
            dnscrypt_public_key: "pub".to_string(),
        };

        assert_eq!(manifest.control_url_for_host("10.0.2.2"), "http://10.0.2.2:10");
        let json = serde_json::to_string(&manifest).expect("serialize manifest");
        assert!(json.contains("fixture.test"));
    }

    #[test]
    fn fault_controller_consumes_one_shot_and_keeps_persistent_faults() {
        let controller = FaultController::new();
        controller.set(FixtureFaultSpec {
            target: FixtureFaultTarget::TcpEcho,
            outcome: FixtureFaultOutcome::TcpReset,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        });
        controller.set(FixtureFaultSpec {
            target: FixtureFaultTarget::TcpEcho,
            outcome: FixtureFaultOutcome::TcpTruncate,
            scope: FixtureFaultScope::Persistent,
            delay_ms: Some(5),
        });

        let oneshot = controller
            .take_matching(FixtureFaultTarget::TcpEcho, |outcome| matches!(outcome, FixtureFaultOutcome::TcpReset))
            .expect("one-shot fault");
        assert_eq!(oneshot.scope, FixtureFaultScope::OneShot);
        assert!(controller
            .take_matching(FixtureFaultTarget::TcpEcho, |outcome| matches!(outcome, FixtureFaultOutcome::TcpReset))
            .is_none());

        let persistent_first = controller
            .take_matching(FixtureFaultTarget::TcpEcho, |outcome| matches!(outcome, FixtureFaultOutcome::TcpTruncate))
            .expect("persistent fault");
        let persistent_second = controller
            .take_matching(FixtureFaultTarget::TcpEcho, |outcome| matches!(outcome, FixtureFaultOutcome::TcpTruncate))
            .expect("persistent fault remains");

        assert_eq!(persistent_first.scope, FixtureFaultScope::Persistent);
        assert_eq!(persistent_second.delay_ms, Some(5));
    }

    #[test]
    fn event_log_records_snapshots_and_clears() {
        let log = EventLog::new();
        log.record(FixtureEvent {
            service: "control".to_string(),
            protocol: "http".to_string(),
            peer: "127.0.0.1:1".to_string(),
            target: "127.0.0.1:2".to_string(),
            detail: "manifest".to_string(),
            bytes: 42,
            sni: None,
            created_at: 1,
        });
        log.record(FixtureEvent {
            service: "tcp_echo".to_string(),
            protocol: "tcp".to_string(),
            peer: "127.0.0.1:3".to_string(),
            target: "127.0.0.1:4".to_string(),
            detail: "echo".to_string(),
            bytes: 7,
            sni: Some("fixture.test".to_string()),
            created_at: 2,
        });

        let snapshot = log.snapshot();
        assert_eq!(snapshot.len(), 2);
        assert_eq!(snapshot[0].detail, "manifest");
        assert_eq!(snapshot[1].sni.as_deref(), Some("fixture.test"));

        log.clear();
        assert!(log.snapshot().is_empty());
    }

    #[test]
    fn map_target_rewrites_fixture_domain_to_loopback() {
        let config = FixtureConfig::default();
        let mapped = map_target(SocksTarget::Domain(config.fixture_domain.clone(), 443), &config).expect("map target");

        match mapped {
            SocksTarget::Socket(address) => {
                assert_eq!(address, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
            }
            SocksTarget::Domain(_, _) => panic!("fixture domain should map to a loopback socket target"),
        }
    }

    #[test]
    fn map_socket_addr_rewrites_fixture_ipv4_to_loopback() {
        let config = FixtureConfig::default();
        let fixture_addr = SocketAddr::new(config.fixture_ipv4.parse().expect("fixture ip"), 8443);
        let regular_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)), 8443);

        assert_eq!(map_socket_addr(fixture_addr, &config), SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 8443),);
        assert_eq!(map_socket_addr(regular_addr, &config), regular_addr);
    }

    #[test]
    fn fixture_stack_control_routes_expose_manifest_and_events_on_dynamic_ports() {
        let _serial = lock_fixture_stack_tests();
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");

        let manifest_body = http_body(
            &stack.manifest().bind_host,
            stack.manifest().control_port,
            "GET /manifest HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
        );
        let manifest: FixtureManifest = serde_json::from_str(&manifest_body).expect("decode manifest");
        assert_eq!(manifest.control_port, stack.manifest().control_port);
        assert_eq!(manifest.tcp_echo_port, stack.manifest().tcp_echo_port);
        assert_eq!(manifest.fixture_domain, stack.manifest().fixture_domain);

        let events_body = http_body(
            &stack.manifest().bind_host,
            stack.manifest().control_port,
            "GET /events HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
        );
        let events: Vec<FixtureEvent> = serde_json::from_str(&events_body).expect("decode events");
        assert!(events.iter().any(|event| event.service == "control" && event.detail == "manifest"));
        assert!(events.iter().any(|event| event.service == "control" && event.detail == "events"));
    }

    #[test]
    fn fixture_stack_manifest_exposes_encrypted_dns_ports_and_dnscrypt_metadata() {
        let _serial = lock_fixture_stack_tests();
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");

        assert!(stack.manifest().dns_dot_port > 0);
        assert!(stack.manifest().dns_dnscrypt_port > 0);
        assert!(stack.manifest().dns_doq_port > 0);
        assert!(stack.manifest().dnscrypt_provider_name.starts_with("2.dnscrypt-cert."));
        assert_eq!(stack.manifest().dnscrypt_public_key.len(), 64);
    }

    #[test]
    fn fixture_stack_services_round_trip_and_record_events() {
        let _serial = lock_fixture_stack_tests();
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");

        let mut tcp = TcpStream::connect((&stack.manifest().bind_host[..], stack.manifest().tcp_echo_port))
            .expect("connect tcp echo");
        tcp.write_all(b"hello").expect("write tcp echo");
        let mut tcp_buf = [0u8; 5];
        tcp.read_exact(&mut tcp_buf).expect("read tcp echo");
        assert_eq!(&tcp_buf, b"hello");

        let udp = UdpSocket::bind((DEFAULT_BIND_HOST, 0)).expect("bind udp client");
        udp.set_read_timeout(Some(Duration::from_secs(1))).expect("set udp timeout");
        udp.send_to(b"ping", (&stack.manifest().bind_host[..], stack.manifest().udp_echo_port)).expect("send udp echo");
        let mut udp_buf = [0u8; 16];
        let (udp_read, _) = udp.recv_from(&mut udp_buf).expect("receive udp echo");
        assert_eq!(&udp_buf[..udp_read], b"ping");

        let dns_http_body = http_body(
            &stack.manifest().bind_host,
            stack.manifest().dns_http_port,
            "GET /dns-query?name=fixture.test HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
        );
        assert!(dns_http_body.contains(&stack.manifest().dns_answer_ipv4));

        let dns_udp = UdpSocket::bind((DEFAULT_BIND_HOST, 0)).expect("bind dns udp client");
        dns_udp.set_read_timeout(Some(Duration::from_secs(1))).expect("set dns udp timeout");
        let query = test_dns_query(&stack.manifest().fixture_domain);
        dns_udp
            .send_to(&query, (&stack.manifest().bind_host[..], stack.manifest().dns_udp_port))
            .expect("send dns udp query");
        let mut dns_buf = [0u8; 512];
        let (dns_read, _) = dns_udp.recv_from(&mut dns_buf).expect("receive dns udp response");
        assert_eq!(
            parse_dns_question_name(&dns_buf[..dns_read]).as_deref(),
            Some(stack.manifest().fixture_domain.as_str())
        );

        let events = stack.events().snapshot();
        assert!(events.iter().any(|event| event.service == "tcp_echo" && event.detail == "echo"));
        assert!(events.iter().any(|event| event.service == "udp_echo" && event.detail == "echo"));
        assert!(events.iter().any(|event| event.service == "dns_http"));
        assert!(events.iter().any(|event| event.service == "dns_udp"));
    }

    #[test]
    fn fixture_stack_encrypted_dns_services_round_trip_and_record_events() {
        let _serial = lock_fixture_stack_tests();
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");
        let manifest = stack.manifest();
        let certificate = CertificateDer::from_pem_slice(manifest.tls_certificate_pem.as_bytes()).expect("parse pem");
        let query = test_dns_query(&manifest.fixture_domain);
        let expected_answers = vec![manifest.dns_answer_ipv4.clone()];

        let dot_resolver = EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Dot,
                resolver_id: Some("fixture-dot".to_string()),
                host: manifest.fixture_domain.clone(),
                port: manifest.dns_dot_port,
                tls_server_name: Some(manifest.fixture_domain.clone()),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                doh_url: None,
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            },
            EncryptedDnsTransport::Direct,
            Duration::from_secs(2),
            vec![certificate.clone()],
        )
        .expect("build dot resolver");
        let dot_response = dot_resolver.exchange_blocking(&query).expect("dot exchange");
        assert_eq!(extract_ip_answers(&dot_response).expect("dot answers"), expected_answers);

        let dnscrypt_resolver = EncryptedDnsResolver::new(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::DnsCrypt,
                resolver_id: Some("fixture-dnscrypt".to_string()),
                host: manifest.fixture_domain.clone(),
                port: manifest.dns_dnscrypt_port,
                tls_server_name: None,
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                doh_url: None,
                dnscrypt_provider_name: Some(manifest.dnscrypt_provider_name.clone()),
                dnscrypt_public_key: Some(manifest.dnscrypt_public_key.clone()),
            },
            EncryptedDnsTransport::Direct,
        )
        .expect("build dnscrypt resolver");
        let dnscrypt_response = dnscrypt_resolver.exchange_blocking(&query).expect("dnscrypt exchange");
        assert_eq!(extract_ip_answers(&dnscrypt_response).expect("dnscrypt answers"), expected_answers);

        let doq_response = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("build doq test runtime")
            .block_on(async {
                let resolver = EncryptedDnsResolver::with_extra_tls_roots(
                    EncryptedDnsEndpoint {
                        protocol: EncryptedDnsProtocol::Doq,
                        resolver_id: Some("fixture-doq".to_string()),
                        host: manifest.fixture_domain.clone(),
                        port: manifest.dns_doq_port,
                        tls_server_name: Some(manifest.fixture_domain.clone()),
                        bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                        doh_url: None,
                        dnscrypt_provider_name: None,
                        dnscrypt_public_key: None,
                    },
                    EncryptedDnsTransport::Direct,
                    Duration::from_secs(2),
                    vec![certificate],
                )
                .expect("build doq resolver");
                resolver.exchange(&query).await.expect("doq exchange")
            });
        assert_eq!(extract_ip_answers(&doq_response).expect("doq answers"), expected_answers);

        let events = stack.events().snapshot();
        assert!(events.iter().any(|event| event.service == "dns_dot" && event.protocol == "dot"));
        assert!(events.iter().any(|event| event.service == "dns_dnscrypt" && event.protocol == "dnscrypt"));
        assert!(events.iter().any(|event| event.service == "dns_doq" && event.protocol == "doq"));
    }

    #[test]
    fn fixture_stack_control_routes_manage_faults_and_event_resets() {
        let _serial = lock_fixture_stack_tests();
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");

        assert_eq!(
            http_body(
                &stack.manifest().bind_host,
                stack.manifest().control_port,
                "GET /health HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
            ),
            "ok",
        );

        let fault = FixtureFaultSpec {
            target: FixtureFaultTarget::UdpEcho,
            outcome: FixtureFaultOutcome::UdpDrop,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        };
        assert_eq!(
            http_body(
                &stack.manifest().bind_host,
                stack.manifest().control_port,
                &http_post_json("/faults", &serde_json::to_string(&fault).expect("fault json")),
            ),
            "ok",
        );

        let faults_body = http_body(
            &stack.manifest().bind_host,
            stack.manifest().control_port,
            "GET /faults HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
        );
        let faults: Vec<FixtureFaultSpec> = serde_json::from_str(&faults_body).expect("decode faults");
        assert_eq!(faults, vec![fault.clone()]);

        let _ = http_body(
            &stack.manifest().bind_host,
            stack.manifest().control_port,
            "GET /manifest HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
        );
        assert_eq!(
            http_body(&stack.manifest().bind_host, stack.manifest().control_port, &http_post_json("/events/reset", ""),),
            "reset",
        );
        let events: Vec<FixtureEvent> = serde_json::from_str(&http_body(
            &stack.manifest().bind_host,
            stack.manifest().control_port,
            "GET /events HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
        ))
        .expect("decode events");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].service, "control");
        assert_eq!(events[0].detail, "events");

        assert_eq!(
            http_body(&stack.manifest().bind_host, stack.manifest().control_port, &http_post_json("/faults/reset", ""),),
            "reset",
        );
        let faults_after_reset: Vec<FixtureFaultSpec> = serde_json::from_str(&http_body(
            &stack.manifest().bind_host,
            stack.manifest().control_port,
            "GET /faults HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
        ))
        .expect("decode faults after reset");
        assert!(faults_after_reset.is_empty());
    }

    #[test]
    fn fixture_stack_faults_affect_echo_and_dns_services() {
        let _serial = lock_fixture_stack_tests();
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");

        stack.faults().set(FixtureFaultSpec {
            target: FixtureFaultTarget::TcpEcho,
            outcome: FixtureFaultOutcome::TcpTruncate,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        });
        stack.faults().set(FixtureFaultSpec {
            target: FixtureFaultTarget::UdpEcho,
            outcome: FixtureFaultOutcome::UdpDrop,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        });
        stack.faults().set(FixtureFaultSpec {
            target: FixtureFaultTarget::DnsHttp,
            outcome: FixtureFaultOutcome::DnsNxDomain,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        });
        stack.faults().set(FixtureFaultSpec {
            target: FixtureFaultTarget::DnsUdp,
            outcome: FixtureFaultOutcome::DnsServFail,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        });

        let mut tcp = TcpStream::connect((&stack.manifest().bind_host[..], stack.manifest().tcp_echo_port))
            .expect("connect tcp echo");
        tcp.write_all(b"abcdefgh").expect("write tcp echo");
        tcp.shutdown(Shutdown::Write).expect("shutdown tcp echo writer");
        let mut truncated = Vec::new();
        tcp.read_to_end(&mut truncated).expect("read truncated tcp echo");
        assert_eq!(truncated, b"abcd");

        let udp = UdpSocket::bind((DEFAULT_BIND_HOST, 0)).expect("bind udp client");
        udp.set_read_timeout(Some(Duration::from_millis(200))).expect("set udp timeout");
        udp.send_to(b"drop", (&stack.manifest().bind_host[..], stack.manifest().udp_echo_port)).expect("send udp echo");
        let mut udp_buf = [0u8; 16];
        let udp_err = udp.recv_from(&mut udp_buf).expect_err("udp drop should time out");
        assert!(matches!(udp_err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut));

        let dns_http_body = http_body(
            &stack.manifest().bind_host,
            stack.manifest().dns_http_port,
            "GET /dns-query?name=fixture.test HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n",
        );
        assert_eq!(dns_http_body, r#"{"Status":3,"Answer":[]}"#);

        let dns_udp = UdpSocket::bind((DEFAULT_BIND_HOST, 0)).expect("bind dns udp client");
        dns_udp.set_read_timeout(Some(Duration::from_secs(1))).expect("set dns udp timeout");
        let query = test_dns_query(&stack.manifest().fixture_domain);
        dns_udp
            .send_to(&query, (&stack.manifest().bind_host[..], stack.manifest().dns_udp_port))
            .expect("send dns udp query");
        let mut dns_buf = [0u8; 512];
        let (dns_read, _) = dns_udp.recv_from(&mut dns_buf).expect("receive dns udp response");
        assert_eq!(dns_rcode(&dns_buf[..dns_read]), Some(2));

        let events = stack.events().snapshot();
        assert!(events.iter().any(|event| event.detail.contains("fault:TcpTruncate")));
        assert!(events.iter().any(|event| event.detail.contains("fault:UdpDrop")));
        assert!(events.iter().any(|event| event.detail.contains("fault:DnsNxDomain")));
        assert!(events.iter().any(|event| event.detail.contains("fault:DnsServFail")));
    }

    #[test]
    fn fixture_stack_tls_echo_records_accept_success_and_handshake_errors() {
        let _serial = lock_fixture_stack_tests();
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");

        let response = tls_echo_request(stack.manifest()).expect("tls echo request");
        assert!(
            response.contains("fixture tls ok"),
            "unexpected tls response: {response:?}; events: {:?}",
            stack.events().snapshot()
        );

        let mut raw = TcpStream::connect((&stack.manifest().bind_host[..], stack.manifest().tls_echo_port))
            .expect("connect tls echo");
        raw.write_all(&[0x16, 0x03, 0x03, 0x00, 0x10, 0x01, 0x00]).expect("write partial tls record");
        raw.shutdown(Shutdown::Write).expect("shutdown partial tls writer");

        wait_for_event(&stack.events(), |events| {
            events.iter().any(|event| event.service == "tls_echo" && event.detail.starts_with("handshake_error:"))
        });

        let events = stack.events().snapshot();
        assert!(events.iter().any(|event| event.service == "tls_echo" && event.detail == "accept"));
        assert!(events.iter().any(|event| {
            event.service == "tls_echo" && event.detail == "handshake" && event.sni.as_deref() == Some("fixture.test")
        }));
        assert!(events.iter().any(|event| event.service == "tls_echo" && event.detail.starts_with("handshake_error:")));
    }

    #[test]
    fn fixture_stack_socks5_connect_and_udp_associate_round_trip() {
        let _serial = lock_fixture_stack_tests();
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");

        let mut tcp_stream = TcpStream::connect((&stack.manifest().bind_host[..], stack.manifest().socks5_port))
            .expect("connect socks5 server");
        tcp_stream.write_all(&[0x05, 0x01, 0x00]).expect("write socks greeting");
        let mut greeting_reply = [0u8; 2];
        tcp_stream.read_exact(&mut greeting_reply).expect("read socks greeting reply");
        assert_eq!(greeting_reply, [0x05, 0x00]);

        let fixture_ip = stack.manifest().fixture_ipv4.parse::<Ipv4Addr>().expect("fixture ipv4 address");
        let mut connect_request = vec![0x05, 0x01, 0x00, 0x01];
        connect_request.extend_from_slice(&fixture_ip.octets());
        connect_request.extend_from_slice(&stack.manifest().tcp_echo_port.to_be_bytes());
        tcp_stream.write_all(&connect_request).expect("write socks connect request");
        let mut connect_reply = [0u8; 10];
        tcp_stream.read_exact(&mut connect_reply).expect("read socks connect reply");
        assert_eq!(connect_reply[1], 0x00);
        tcp_stream.write_all(b"socks-tcp").expect("write socks tcp payload");
        let mut echoed = [0u8; 9];
        tcp_stream.read_exact(&mut echoed).expect("read socks tcp payload");
        assert_eq!(&echoed, b"socks-tcp");
        tcp_stream.shutdown(Shutdown::Both).expect("shutdown socks connect stream");

        let mut udp_assoc = TcpStream::connect((&stack.manifest().bind_host[..], stack.manifest().socks5_port))
            .expect("connect socks5 server for udp associate");
        udp_assoc.write_all(&[0x05, 0x01, 0x00]).expect("write udp greeting");
        udp_assoc.read_exact(&mut greeting_reply).expect("read udp greeting reply");
        assert_eq!(greeting_reply, [0x05, 0x00]);
        udp_assoc.write_all(&[0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).expect("write udp associate request");
        let mut udp_reply = [0u8; 10];
        udp_assoc.read_exact(&mut udp_reply).expect("read udp associate reply");
        assert_eq!(udp_reply[1], 0x00);
        let relay_addr = SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(udp_reply[4], udp_reply[5], udp_reply[6], udp_reply[7])),
            u16::from_be_bytes([udp_reply[8], udp_reply[9]]),
        );

        let udp_client = UdpSocket::bind((DEFAULT_BIND_HOST, 0)).expect("bind udp client");
        udp_client.set_read_timeout(Some(Duration::from_secs(1))).expect("set udp timeout");
        let frame = socks::encode_socks5_udp_frame(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), stack.manifest().udp_echo_port),
            b"socks-udp",
        );
        udp_client.send_to(&frame, relay_addr).expect("send socks udp frame");
        let mut udp_buf = [0u8; 128];
        let (udp_read, _) = udp_client.recv_from(&mut udp_buf).expect("receive socks udp reply");
        let (destination, payload) =
            socks::decode_socks5_udp_frame(&udp_buf[..udp_read]).expect("decode socks udp reply");
        assert_eq!(destination.port(), stack.manifest().udp_echo_port);
        assert_eq!(payload, b"socks-udp");

        let events = stack.events().snapshot();
        assert!(events.iter().any(|event| event.service == "socks5_relay" && event.protocol == "tcp"));
        assert!(events
            .iter()
            .any(|event| event.service == "tcp_echo" && event.protocol == "tcp" && event.detail == "echo"));
        assert!(events.iter().any(|event| event.service == "socks5_relay" && event.protocol == "udp"));
    }

    fn dynamic_fixture_config() -> FixtureConfig {
        FixtureConfig {
            tcp_echo_port: 0,
            udp_echo_port: 0,
            tls_echo_port: 0,
            dns_udp_port: 0,
            dns_http_port: 0,
            dns_dot_port: 0,
            dns_dnscrypt_port: 0,
            dns_doq_port: 0,
            socks5_port: 0,
            control_port: 0,
            ..FixtureConfig::default()
        }
    }

    fn http_body(host: &str, port: u16, request: &str) -> String {
        let mut stream = TcpStream::connect((host, port)).expect("connect control endpoint");
        stream.write_all(request.as_bytes()).expect("write http request");
        stream.flush().expect("flush http request");
        stream.shutdown(Shutdown::Write).expect("shutdown control request writer");

        let headers = read_until_marker(&mut stream, b"\r\n\r\n");
        let headers_text = String::from_utf8(headers).expect("decode response headers");
        let content_length = headers_text
            .lines()
            .find_map(|line| {
                let (name, value) = line.split_once(':')?;
                name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
            })
            .expect("response content-length");
        let mut body = vec![0u8; content_length];
        stream.read_exact(&mut body).expect("read response body");
        String::from_utf8(body).expect("decode response body")
    }

    fn http_post_json(path: &str, body: &str) -> String {
        format!(
            "POST {path} HTTP/1.1\r\nHost: fixture.test\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        )
    }

    fn lock_fixture_stack_tests() -> std::sync::MutexGuard<'static, ()> {
        FIXTURE_STACK_TEST_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn tls_echo_request(manifest: &FixtureManifest) -> Result<String, String> {
        let stream =
            TcpStream::connect((&manifest.bind_host[..], manifest.tls_echo_port)).map_err(|err| err.to_string())?;
        stream.set_read_timeout(Some(Duration::from_secs(1))).map_err(|err| err.to_string())?;
        stream.set_write_timeout(Some(Duration::from_secs(1))).map_err(|err| err.to_string())?;

        let config = ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
            .with_no_client_auth();
        let server_name = ServerName::try_from(manifest.fixture_domain.clone()).map_err(|err| err.to_string())?;
        let connection = ClientConnection::new(Arc::new(config), server_name).map_err(|err| err.to_string())?;
        let mut tls = StreamOwned::new(connection, stream);

        while tls.conn.is_handshaking() {
            tls.conn.complete_io(&mut tls.sock).map_err(|err| err.to_string())?;
        }

        let mut response = Vec::new();
        let mut chunk = [0u8; 256];
        loop {
            match tls.read(&mut chunk) {
                Ok(0) => break,
                Ok(read) => response.extend_from_slice(&chunk[..read]),
                Err(err)
                    if err.to_string().to_ascii_lowercase().contains("unexpected eof")
                        || err.kind() == ErrorKind::UnexpectedEof =>
                {
                    break;
                }
                Err(err) => return Err(err.to_string()),
            }
        }

        String::from_utf8(response).map_err(|err| err.to_string())
    }

    fn wait_for_event<F>(events: &EventLog, predicate: F)
    where
        F: Fn(&[FixtureEvent]) -> bool,
    {
        let started = std::time::Instant::now();
        while started.elapsed() < Duration::from_secs(1) {
            let snapshot = events.snapshot();
            if predicate(&snapshot) {
                return;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        panic!("fixture event predicate was not satisfied within timeout");
    }

    fn test_dns_query(domain: &str) -> Vec<u8> {
        let mut query = Vec::new();
        query.extend_from_slice(&0x1234u16.to_be_bytes());
        query.extend_from_slice(&0x0100u16.to_be_bytes());
        query.extend_from_slice(&1u16.to_be_bytes());
        query.extend_from_slice(&0u16.to_be_bytes());
        query.extend_from_slice(&0u16.to_be_bytes());
        query.extend_from_slice(&0u16.to_be_bytes());
        for label in domain.split('.') {
            query.push(label.len() as u8);
            query.extend_from_slice(label.as_bytes());
        }
        query.push(0);
        query.extend_from_slice(&1u16.to_be_bytes());
        query.extend_from_slice(&1u16.to_be_bytes());
        query
    }

    fn dns_rcode(response: &[u8]) -> Option<u16> {
        (response.len() >= 4).then(|| u16::from_be_bytes([response[2], response[3]]) & 0x000f)
    }

    #[derive(Debug)]
    struct NoCertificateVerification;

    impl ServerCertVerifier for NoCertificateVerification {
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
            vec![
                SignatureScheme::ECDSA_NISTP256_SHA256,
                SignatureScheme::ECDSA_NISTP384_SHA384,
                SignatureScheme::RSA_PSS_SHA256,
                SignatureScheme::RSA_PSS_SHA384,
                SignatureScheme::RSA_PKCS1_SHA256,
                SignatureScheme::RSA_PKCS1_SHA384,
                SignatureScheme::ED25519,
            ]
        }
    }
}
