#![cfg(not(feature = "loom"))]

#[path = "support/stalled_dns.rs"]
mod stalled_dns;

use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::Duration;

use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_proxy_runtime::{create_listener, run_proxy_with_embedded_control_receipt};
use ripdpi_proxy_runtime_adapter::model::config::{RuntimeConfig, RuntimeWsTunnelWorkerRoute, WsTunnelMode};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;

// OpenSSL AES-256-CTR: key 01..20, IV a1..b0, zero plaintext except
// eeeeeeee at 56, signed DC4 at 60, and random tail 9c3f at 62.
const INIT: [u8; 64] = [
    0xdc, 0x9a, 0x31, 0x46, 0x7e, 0x1d, 0x21, 0x53, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b,
    0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e,
    0x1f, 0x20, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb0, 0x29,
    0x92, 0xcf, 0xda, 0x1c, 0xa7, 0x2c, 0xcc,
];

fn config(timeout_ms: u32) -> RuntimeConfig {
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_ip = Ipv4Addr::LOCALHOST.into();
    config.network.listen.listen_port = 0;
    config.network.ipv6 = false;
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
    config.timeouts.connect_timeout_ms = timeout_ms;
    config
}

fn client(address: std::net::SocketAddr) -> TcpStream {
    let mut client = TcpStream::connect(address).unwrap();
    client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    client.write_all(&[5, 1, 0]).unwrap();
    let mut method = [0; 2];
    client.read_exact(&mut method).unwrap();
    assert_eq!(method, [5, 0]);
    client.write_all(&[5, 1, 0, 1, 149, 154, 167, 91, 1, 187]).unwrap();
    let mut reply = [0; 10];
    client.read_exact(&mut reply).unwrap();
    assert_eq!(reply[1], 0);
    client.write_all(&INIT).unwrap();
    client
}

#[test]
fn runtime_stop_cancels_socks5_ws_dns() {
    check_dns_stop(false);
}

#[test]
fn absolute_ws_bootstrap_deadline_cancels_dns() {
    check_dns_stop(true);
}

fn check_dns_stop(deadline: bool) {
    let dns = stalled_dns::StalledDns::start();
    let config = config(if deadline { 120 } else { 0 });
    let listener = create_listener(&config).unwrap();
    let address = listener.local_addr().unwrap();
    let control = Arc::new(EmbeddedProxyControl::new_with_context(None, Some(dns.context())));
    let running = control.clone();
    let runtime = thread::spawn(move || {
        run_proxy_with_embedded_control_receipt(
            config,
            listener,
            running,
            Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
        )
    });
    let _client = client(address);
    dns.started.recv_timeout(Duration::from_secs(2)).expect("WS DNS started");
    if !deadline {
        control.request_shutdown();
    }
    let closed = dns.closed.recv_timeout(Duration::from_secs(1));
    control.request_shutdown();
    let _ = dns.release.send(());
    let receipt = runtime.join().unwrap().unwrap();
    assert!(closed.expect("DNS close notification"), "DNS must close before fixture release");
    assert!(!receipt.forced_abort());
    assert!(!receipt.worker_panicked());
}

#[test]
fn runtime_stop_cancels_socks5_ws_tls_with_and_without_connect_timeout() {
    let fixture = FixtureStack::start(FixtureConfig {
        tcp_echo_port: 0,
        udp_echo_port: 0,
        tls_echo_port: 0,
        dns_udp_port: 0,
        dns_http_port: 0,
        dns_dot_port: 0,
        dns_dnscrypt_port: 0,
        dns_doq_port: 0,
        dns_odoh_proxy_port: 0,
        dns_odoh_target_port: 0,
        socks5_port: 0,
        control_port: 0,
        dns_answer_ipv4: "127.0.0.1".into(),
        ..Default::default()
    })
    .unwrap();
    let dns_port = fixture.manifest().dns_http_port;
    for timeout_ms in [10_000, 0] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let mut config = config(timeout_ms);
        config.adaptive.ws_tunnel_worker_route = Some(
            RuntimeWsTunnelWorkerRoute::parse(
                format!("https://worker.test:{}/relay", origin.local_addr().unwrap().port()),
                "fixture-token".into(),
            )
            .unwrap(),
        );
        let context = ProxyRuntimeContext {
            encrypted_dns: Some(ProxyEncryptedDnsContext {
                resolver_id: None,
                protocol: "doh".into(),
                host: "127.0.0.1".into(),
                port: dns_port,
                tls_server_name: None,
                bootstrap_ips: vec!["127.0.0.1".into()],
                doh_url: Some(format!("http://127.0.0.1:{dns_port}/dns-query")),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            }),
            ..Default::default()
        };
        let listener = create_listener(&config).unwrap();
        let address = listener.local_addr().unwrap();
        let control = Arc::new(EmbeddedProxyControl::new_with_context(None, Some(context)));
        let running = control.clone();
        let runtime = thread::spawn(move || {
            run_proxy_with_embedded_control_receipt(
                config,
                listener,
                running,
                Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
            )
        });
        let (started_tx, started_rx) = mpsc::channel();
        let (closed_tx, closed_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let backend = thread::spawn(move || {
            let (mut tcp, _) = origin.accept().unwrap();
            tcp.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
            let mut header = [0; 5];
            tcp.read_exact(&mut header).unwrap();
            assert_eq!(header[0], 0x16, "real TLS ClientHello");
            started_tx.send(()).unwrap();
            let result = tcp.read_to_end(&mut Vec::new());
            let closed = result.is_ok() || result.is_err_and(|e| e.kind() == std::io::ErrorKind::ConnectionReset);
            closed_tx.send(closed).unwrap();
            let _ = release_rx.recv_timeout(Duration::from_secs(3));
        });
        let _client = client(address);
        started_rx.recv_timeout(Duration::from_secs(2)).expect("TLS started");
        control.request_shutdown();
        let closed = closed_rx.recv_timeout(Duration::from_secs(2));
        let _ = release_tx.send(());
        backend.join().unwrap();
        let receipt = runtime.join().unwrap().unwrap();
        assert!(closed.expect("TLS close notification"), "TLS socket survived stop, timeout={timeout_ms}");
        assert!(!receipt.forced_abort());
        assert!(!receipt.worker_panicked());
    }
}
