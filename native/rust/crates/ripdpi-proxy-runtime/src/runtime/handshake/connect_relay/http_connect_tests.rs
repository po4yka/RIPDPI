use super::*;
use crate::runtime::config::RuntimeConfig;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::time::Duration;

fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    (client, listener.accept().unwrap().0)
}

#[test]
fn refused_delayed_http_connect_never_sends_success() {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let target = origin.local_addr().unwrap();
    drop(origin);
    let (mut peer, client) = pair();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let mut config = RuntimeConfig::default();
    config.network.http_connect = true;
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTP;
    write!(peer, "CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\nGET / HTTP/1.0\r\n\r\n").unwrap();
    let result = crate::runtime::handshake::handle_client(client, &RuntimeState::test(config));
    assert!(result.is_err());
    let mut response = Vec::new();
    match peer.read_to_end(&mut response) {
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::ConnectionReset => {}
        Err(error) => panic!("HTTP response failed: {error}"),
    }
    assert!(response.starts_with(b"HTTP/1.1 502 Bad Gateway\r\n"), "response={response:?}");
    assert!(!response.windows(3).any(|bytes| bytes == b"200"));
}

fn http_client(target: SocketAddr, config: RuntimeConfig) -> (TcpStream, std::thread::JoinHandle<io::Result<()>>) {
    let (mut peer, client) = pair();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let worker =
        std::thread::spawn(move || crate::runtime::handshake::handle_client(client, &RuntimeState::test(config)));
    write!(peer, "CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n").unwrap();
    (peer, worker)
}

fn accept_before_payload(listener: &TcpListener, peer: TcpStream) -> (TcpStream, TcpStream) {
    listener.set_nonblocking(true).unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(1);
    loop {
        match listener.accept() {
            Ok((server, _)) => {
                server.set_nonblocking(false).unwrap();
                return (server, peer);
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock && std::time::Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(5));
            }
            other => panic!("upstream must connect without client payload: {other:?}"),
        }
    }
}

#[test]
fn delayed_http_connect_delivers_server_first_on_an_arbitrary_port() {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut config = RuntimeConfig::default();
    config.network.http_connect = true;
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTPS;
    let (peer, worker) = http_client(origin.local_addr().unwrap(), config);
    let (mut server, mut peer) = accept_before_payload(&origin, peer);
    server.write_all(b"220 independent greeting\r\n").unwrap();
    drop(server);
    let mut response = Vec::new();
    peer.read_to_end(&mut response).unwrap();
    assert_eq!(response, b"HTTP/1.1 200 OK\r\n\r\n220 independent greeting\r\n");
    drop(peer);
    assert!(worker.join().unwrap().is_ok());
}

#[test]
fn delayed_http_connect_reuses_the_acknowledged_socket_and_preserves_bytes() {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut config = RuntimeConfig::default();
    config.network.http_connect = true;
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTP;
    let (peer, worker) = http_client(origin.local_addr().unwrap(), config);
    let (mut server, mut peer) = accept_before_payload(&origin, peer);
    server.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let mut success = [0; 19];
    peer.read_exact(&mut success).unwrap();
    assert_eq!(&success, b"HTTP/1.1 200 OK\r\n\r\n");
    let payload = b"GET /payload HTTP/1.0\r\n\r\n\x00\xff";
    peer.write_all(payload).unwrap();
    peer.shutdown(std::net::Shutdown::Write).unwrap();
    let mut delivered = Vec::new();
    server.read_to_end(&mut delivered).unwrap();
    assert_eq!(delivered, payload);
    server.write_all(b"after FIN").unwrap();
    drop(server);
    let mut response = Vec::new();
    peer.read_to_end(&mut response).unwrap();
    assert_eq!(response, b"after FIN");
    assert!(worker.join().unwrap().is_ok());
    assert_eq!(origin.accept().unwrap_err().kind(), io::ErrorKind::WouldBlock);
}

fn dns_context(port: u16) -> ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyRuntimeContext {
    use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
    ProxyRuntimeContext {
        encrypted_dns: Some(ProxyEncryptedDnsContext {
            resolver_id: None,
            protocol: "doh".into(),
            host: "127.0.0.1".into(),
            port,
            tls_server_name: None,
            bootstrap_ips: vec!["127.0.0.1".into()],
            doh_url: Some(format!("http://127.0.0.1:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }),
        ..Default::default()
    }
}

fn local_fallback_guard(config: &mut RuntimeConfig) -> socket2::Socket {
    // A regressed short-init fallback must never dial the public Telegram target.
    let egress_guard =
        socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::STREAM, Some(socket2::Protocol::TCP)).unwrap();
    egress_guard.bind(&SocketAddr::from((Ipv4Addr::LOCALHOST, 0)).into()).unwrap();
    let egress = egress_guard.local_addr().unwrap().as_socket().unwrap();
    config.groups[0].policy.ext_socks =
        Some(ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig { addr: egress, auth: None });
    config.timeouts.connect_timeout_ms = 100;
    egress_guard
}

#[test]
fn http_ws_bootstrap_failure_never_sends_success() {
    use ripdpi_proxy_runtime_adapter::model::config::WsTunnelMode;
    let closed = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let port = closed.local_addr().unwrap().port();
    drop(closed);
    let context = dns_context(port);
    let (mut peer, client) = pair();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let mut config = RuntimeConfig::default();
    config.network.http_connect = true;
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
    let _egress_guard = local_fallback_guard(&mut config);
    let state = RuntimeState::test_with_context(config, Some(context));
    peer.write_all(b"CONNECT 149.154.167.91:443 HTTP/1.1\r\nHost: 149.154.167.91:443\r\n\r\n").unwrap();
    assert!(crate::runtime::handshake::handle_client(client, &state).is_err());
    let mut response = Vec::new();
    match peer.read_to_end(&mut response) {
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::ConnectionReset => {}
        Err(error) => panic!("HTTP response failed: {error}"),
    }
    assert!(response.starts_with(b"HTTP/1.1 502 Bad Gateway\r\n"), "response={response:?}");
    assert!(!response.windows(3).any(|bytes| bytes == b"200"));
}

struct LoopbackWsTransport(SocketAddr);
struct LoopbackConnected(TcpStream);

impl ripdpi_ws_transport_port::WsTransport for LoopbackWsTransport {
    fn classify_mtproto_seed(&self, seed: &[u8]) -> ripdpi_ws_transport_port::MtprotoSeedClassification {
        ripdpi_ws_tunnel::classify_mtproto_seed(seed)
    }

    fn open(
        &self,
        dc: ripdpi_ws_transport_port::TelegramDc,
        config: &ripdpi_ws_transport_port::WsTunnelConfig,
        should_cancel: &dyn Fn() -> bool,
    ) -> io::Result<Box<dyn ripdpi_ws_transport_port::PreparedWsTunnel>> {
        assert_eq!(dc, ripdpi_ws_transport_port::TelegramDc::production(4));
        assert!(config.resolved_addr.unwrap().ip().is_loopback());
        let mut upstream = TcpStream::connect(self.0)?;
        upstream.set_read_timeout(Some(Duration::from_millis(20)))?;
        let mut ready = [0];
        loop {
            if should_cancel() {
                return Err(io::Error::new(io::ErrorKind::Interrupted, "fixture carrier stopped"));
            }
            match upstream.read_exact(&mut ready) {
                Ok(()) => break,
                Err(error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                Err(error) => return Err(error),
            }
        }
        assert_eq!(ready, [0xa5]);
        Ok(Box::new(LoopbackConnected(upstream)))
    }

    fn relay(
        &self,
        client: TcpStream,
        dc: ripdpi_ws_transport_port::TelegramDc,
        seed: Vec<u8>,
        config: &ripdpi_ws_transport_port::WsTunnelConfig,
    ) -> io::Result<()> {
        self.open(dc, config, &|| false)?.relay(client, seed)
    }
}

impl ripdpi_ws_transport_port::PreparedWsTunnel for LoopbackConnected {
    fn relay(mut self: Box<Self>, mut client: TcpStream, seed: Vec<u8>) -> io::Result<()> {
        self.0.set_read_timeout(Some(Duration::from_secs(2)))?;
        self.0.write_all(&seed)?;
        self.0.shutdown(std::net::Shutdown::Write)?;
        let mut response = Vec::new();
        self.0.read_to_end(&mut response)?;
        client.write_all(&response)
    }
}

#[test]
fn http_ws_connect_establishes_the_port_before_success_and_relays_the_validated_seed() {
    use local_network_fixture::{FixtureConfig, FixtureStack};
    use ripdpi_proxy_runtime_adapter::model::config::WsTunnelMode;
    use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
    use std::sync::Arc;
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
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut config = RuntimeConfig::default();
    config.network.http_connect = true;
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
    let _egress_guard = local_fallback_guard(&mut config);
    config.timeouts.connect_timeout_ms = 2_000;
    let control =
        Arc::new(EmbeddedProxyControl::new_with_context(None, Some(dns_context(fixture.manifest().dns_http_port))));
    let state =
        RuntimeState::new(config, Some(control), Arc::new(LoopbackWsTransport(origin.local_addr().unwrap()))).unwrap();
    let (mut peer, client) = pair();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let worker = std::thread::spawn(move || crate::runtime::handshake::handle_client(client, &state));
    peer.write_all(b"CONNECT 149.154.167.91:443 HTTP/1.1\r\nHost: 149.154.167.91:443\r\n\r\n").unwrap();
    let (mut backend, mut peer) = accept_before_payload(&origin, peer);
    backend.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    peer.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
    let error = peer.read(&mut [0; 19]).expect_err("CONNECT must wait for the backend readiness byte");
    assert!(matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut));
    backend.write_all(&[0xa5]).unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let mut success = [0; 19];
    peer.read_exact(&mut success).unwrap();
    assert_eq!(&success, b"HTTP/1.1 200 OK\r\n\r\n");
    // Literal MTProto init produced independently with OpenSSL AES-256-CTR.
    let init = [
        0xdc, 0x9a, 0x31, 0x46, 0x7e, 0x1d, 0x21, 0x53, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
        0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c,
        0x1d, 0x1e, 0x1f, 0x20, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae,
        0xaf, 0xb0, 0x29, 0x92, 0xcf, 0xda, 0x1c, 0xa7, 0x2c, 0xcc,
    ];
    peer.write_all(&init).unwrap();
    let mut received = [0; 64];
    backend.read_exact(&mut received).unwrap();
    assert_eq!(received, init);
    backend.write_all(b"validated-seed-relayed").unwrap();
    drop(backend);
    let mut response = Vec::new();
    peer.read_to_end(&mut response).unwrap();
    assert_eq!(response, b"validated-seed-relayed");
    drop(peer);
    assert!(worker.join().unwrap().is_ok());
}
