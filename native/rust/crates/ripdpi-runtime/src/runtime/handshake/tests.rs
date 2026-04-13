use super::protocol_io::*;
use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use crate::adaptive_tuning::AdaptivePlannerResolver;
use crate::retry_stealth::RetryPacer;
use crate::runtime::state::RuntimeState;
use crate::runtime_policy::RuntimePolicy;
use crate::sync::{Arc, AtomicBool, AtomicUsize, Mutex};
use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_session::{
    encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, parse_http_connect_request,
    parse_socks4_request, parse_socks5_request, SessionConfig, SocketType, S_ATP_I4, S_ATP_I6, S_CMD_CONN, S_ER_GEN,
    S_VER5,
};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::time::Duration;

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
    let addr = listener.local_addr().expect("listener addr");
    let client = TcpStream::connect(addr).expect("connect client");
    let (server, _) = listener.accept().expect("accept client");
    (client, server)
}

fn runtime_state(config: RuntimeConfig) -> RuntimeState {
    runtime_state_with_context(config, None)
}

fn runtime_state_with_context(config: RuntimeConfig, runtime_context: Option<ProxyRuntimeContext>) -> RuntimeState {
    RuntimeState {
        config: Arc::new(config.clone()),
        cache: Arc::new(Mutex::new(RuntimePolicy::load(&config))),
        adaptive_fake_ttl: Arc::new(Mutex::new(AdaptiveFakeTtlResolver::default())),
        adaptive_tuning: Arc::new(Mutex::new(AdaptivePlannerResolver::default())),
        retry_stealth: Arc::new(crate::sync::RwLock::new(RetryPacer::default())),
        strategy_evolver: Arc::new(crate::sync::RwLock::new(crate::strategy_evolver::StrategyEvolver::new(false, 0.0))),
        active_clients: Arc::new(AtomicUsize::new(0)),
        telemetry: None,
        runtime_context,
        control: None,
        ttl_unavailable: Arc::new(AtomicBool::new(false)),
        reprobe_tracker: std::sync::Arc::new(crate::runtime::reprobe::ReprobeTracker::new()),
        #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
        io_uring: None,
    }
}

fn resolve_ip_literal(host: &str, _socket_type: SocketType) -> Option<SocketAddr> {
    host.parse::<IpAddr>().ok().map(|ip| SocketAddr::new(ip, 0))
}

fn fixture_runtime_context(dns_http_port: u16) -> ProxyRuntimeContext {
    ProxyRuntimeContext {
        encrypted_dns: Some(ProxyEncryptedDnsContext {
            resolver_id: Some("fixture-doh".to_string()),
            protocol: "doh".to_string(),
            host: "127.0.0.1".to_string(),
            port: dns_http_port,
            tls_server_name: None,
            bootstrap_ips: vec!["127.0.0.1".to_string()],
            doh_url: Some(format!("http://127.0.0.1:{dns_http_port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }),
        protect_path: None,
        preferred_edges: std::collections::BTreeMap::default(),
        direct_path_capabilities: Vec::new(),
        morph_policy: None,
    }
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

#[test]
fn send_success_reply_emits_protocol_specific_payloads() {
    let cases = [
        (HandshakeKind::Socks4, encode_socks4_reply(true).as_bytes().to_vec()),
        (
            HandshakeKind::Socks5,
            encode_socks5_reply(0, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).as_bytes().to_vec(),
        ),
        (HandshakeKind::HttpConnect, encode_http_connect_reply(true).as_bytes().to_vec()),
    ];

    for (handshake, expected) in cases {
        let (mut writer, mut reader) = connected_pair();
        reader.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");

        send_success_reply(&mut writer, handshake).expect("send success reply");

        let mut actual = vec![0u8; expected.len()];
        reader.read_exact(&mut actual).expect("read success reply");
        assert_eq!(actual, expected);
    }
}

#[test]
fn read_socks5_request_reads_domain_target() {
    let (mut reader, mut writer) = connected_pair();
    let request =
        [S_VER5, S_CMD_CONN, 0, 0x03, 11, b'e', b'x', b'a', b'm', b'p', b'l', b'e', b'.', b'c', b'o', b'm', 0x01, 0xbb];
    writer.write_all(&request).expect("write socks5 request");

    assert_eq!(read_socks5_request(&mut reader).expect("read socks5 request"), request);
}

#[test]
fn parse_shadowsocks_target_handles_ipv4_and_resolved_domain_targets() {
    let config = RuntimeConfig::default();
    let ipv4_packet = [S_ATP_I4, 127, 0, 0, 1, 0x01, 0xbb];
    let (ipv4_target, ipv4_header_len) =
        parse_shadowsocks_target(&ipv4_packet, &config, resolve_ip_literal).expect("parse ipv4 target");
    assert_eq!(ipv4_target, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
    assert_eq!(ipv4_header_len, ipv4_packet.len());

    let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0x00, 0x50];
    let (domain_target, domain_header_len) =
        parse_shadowsocks_target(&domain_packet, &config, resolve_ip_literal).expect("parse domain target");
    assert_eq!(domain_target, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 80));
    assert_eq!(domain_header_len, domain_packet.len());
}

#[test]
fn parse_shadowsocks_target_respects_ipv6_and_resolve_flags() {
    let mut config = RuntimeConfig::default();
    config.network.ipv6 = false;
    config.network.resolve = false;
    let ipv6_packet = [S_ATP_I6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 53];
    let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0, 80];

    assert!(parse_shadowsocks_target(&ipv6_packet, &config, resolve_ip_literal).is_none());
    assert!(parse_shadowsocks_target(&domain_packet, &config, resolve_ip_literal).is_none());
}

#[test]
fn domain_protocols_resolve_through_encrypted_dns_runtime_context() {
    let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");
    let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);
    let state = runtime_state_with_context(RuntimeConfig::default(), Some(runtime_context));
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, &state);
    let session = SessionConfig { resolve: state.config.network.resolve, ipv6: state.config.network.ipv6 };
    let expected_ip = stack.manifest().dns_answer_ipv4.parse::<IpAddr>().expect("fixture ip");

    let socks4_request = [
        0x04, 0x01, 0x01, 0xbb, 0, 0, 0, 1, 0, b'f', b'i', b'x', b't', b'u', b'r', b'e', b'.', b't', b'e', b's', b't',
        0,
    ];
    let socks4_target = parse_socks4_request(&socks4_request, session, &resolver).expect("parse socks4 request");
    let ripdpi_session::ClientRequest::Socks4Connect(socks4_target) = socks4_target else {
        panic!("expected SOCKS4 connect request");
    };
    assert_eq!(socks4_target.addr.ip(), expected_ip);

    let socks5_request = [
        S_VER5, S_CMD_CONN, 0, 0x03, 12, b'f', b'i', b'x', b't', b'u', b'r', b'e', b'.', b't', b'e', b's', b't', 0x01,
        0xbb,
    ];
    let socks5_target =
        parse_socks5_request(&socks5_request, SocketType::Stream, session, &resolver).expect("parse socks5 request");
    let ripdpi_session::ClientRequest::Socks5Connect(socks5_target) = socks5_target else {
        panic!("expected SOCKS5 connect request");
    };
    assert_eq!(socks5_target.addr.ip(), expected_ip);

    let http_request = b"CONNECT fixture.test:443 HTTP/1.1\r\nHost: fixture.test:443\r\n\r\n";
    let http_target = parse_http_connect_request(http_request, &resolver).expect("parse http connect request");
    let ripdpi_session::ClientRequest::HttpConnect(http_target) = http_target else {
        panic!("expected HTTP CONNECT request");
    };
    assert_eq!(http_target.addr.ip(), expected_ip);

    let shadowsocks_request = [0x03, 12, b'f', b'i', b'x', b't', b'u', b'r', b'e', b'.', b't', b'e', b's', b't', 0, 80];
    let (shadowsocks_target, header_len) =
        parse_shadowsocks_target(&shadowsocks_request, &state.config, resolver).expect("parse shadowsocks target");
    assert_eq!(shadowsocks_target.ip(), expected_ip);
    assert_eq!(header_len, shadowsocks_request.len());
}

#[test]
fn localhost_resolves_to_loopback_without_runtime_context() {
    let mut config = RuntimeConfig::default();
    config.network.resolve = false;
    config.network.ipv6 = false;
    let state = runtime_state(config);

    assert_eq!(
        resolve_name("localhost", SocketType::Stream, &state),
        Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0))
    );
}

#[test]
fn localhost_prefers_ipv6_loopback_when_enabled() {
    let mut config = RuntimeConfig::default();
    config.network.resolve = false;
    config.network.ipv6 = true;
    let state = runtime_state(config);

    assert_eq!(
        resolve_name("LOCALHOST.", SocketType::Stream, &state),
        Some(SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST), 0))
    );
}

#[test]
fn handle_client_sends_socks5_failure_reply_when_upstream_connect_fails() {
    let probe = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind probe listener");
    let target = probe.local_addr().expect("probe addr");
    drop(probe);

    let mut config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    config.network.resolve = false;
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");

    let mut request = vec![S_VER5, 1, 0];
    request.extend([S_VER5, S_CMD_CONN, 0, S_ATP_I4]);
    request.extend_from_slice(&Ipv4Addr::LOCALHOST.octets());
    request.extend_from_slice(&target.port().to_be_bytes());
    client.write_all(&request).expect("write socks5 connect request");

    let err = super::handle_client(server, &state).expect_err("upstream connect should fail");
    assert!(
        matches!(
            err.kind(),
            std::io::ErrorKind::ConnectionRefused | std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::TimedOut
        ),
        "unexpected connect failure kind: {err}"
    );

    let mut auth = [0u8; 2];
    client.read_exact(&mut auth).expect("read socks5 auth reply");
    assert_eq!(auth, [S_VER5, 0]);

    let mut failure = [0u8; 10];
    client.read_exact(&mut failure).expect("read socks5 failure reply");
    assert_eq!(failure[0], S_VER5);
    assert_eq!(failure[1], S_ER_GEN);
}

#[test]
fn validate_http_proxy_auth_valid() {
    use base64::engine::{general_purpose::STANDARD, Engine};
    let token = "abc123";
    let encoded = STANDARD.encode(format!("ripdpi:{token}"));
    let request = format!(
        "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Authorization: Basic {encoded}\r\n\r\n"
    );
    assert!(validate_http_proxy_auth(request.as_bytes(), token));
}

#[test]
fn validate_http_proxy_auth_missing_header() {
    let request = b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n";
    assert!(!validate_http_proxy_auth(request, "abc123"));
}

#[test]
fn validate_http_proxy_auth_wrong_token() {
    use base64::engine::{general_purpose::STANDARD, Engine};
    let encoded = STANDARD.encode("ripdpi:wrong_token");
    let request = format!("CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic {encoded}\r\n\r\n");
    assert!(!validate_http_proxy_auth(request.as_bytes(), "correct_token"));
}

#[test]
fn validate_http_proxy_auth_invalid_base64() {
    let request = b"CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic !!!invalid!!!\r\n\r\n";
    assert!(!validate_http_proxy_auth(request, "abc123"));
}

#[test]
fn negotiate_socks5_rejects_unauthenticated_method_when_token_required() {
    let (mut client, mut server) = connected_pair();
    client.write_all(&[0x01, 0x00]).expect("write socks5 methods");

    let err = negotiate_socks5(&mut server, Some("alpha-123")).expect_err("missing userpass method should fail");
    assert_eq!(err.kind(), std::io::ErrorKind::PermissionDenied);

    let mut reply = [0u8; 2];
    client.read_exact(&mut reply).expect("read auth method reply");
    assert_eq!(reply, [S_VER5, ripdpi_session::S_AUTH_BAD]);
}

#[test]
fn negotiate_socks5_rejects_wrong_password() {
    let (mut client, mut server) = connected_pair();
    let mut request = vec![0x01, ripdpi_session::S_AUTH_USERPASS];
    request.extend([0x01, 0x06]);
    request.extend_from_slice(b"ripdpi");
    request.push(0x05);
    request.extend_from_slice(b"wrong");
    client.write_all(&request).expect("write socks5 auth exchange");

    let err = negotiate_socks5(&mut server, Some("alpha-123")).expect_err("wrong password should fail");
    assert_eq!(err.kind(), std::io::ErrorKind::PermissionDenied);

    let mut method_reply = [0u8; 2];
    client.read_exact(&mut method_reply).expect("read method reply");
    assert_eq!(method_reply, [S_VER5, ripdpi_session::S_AUTH_USERPASS]);

    let mut auth_reply = [0u8; 2];
    client.read_exact(&mut auth_reply).expect("read auth status");
    assert_eq!(auth_reply, [0x01, 0x01]);
}

#[test]
fn handle_http_connect_rejects_missing_proxy_auth_when_token_required() {
    let mut config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    config.network.http_connect = true;
    config.network.listen.auth_token = Some("alpha-123".to_string());
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");
    client
        .write_all(b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")
        .expect("write http connect request");

    let err = super::handle_client(server, &state).expect_err("missing auth should fail");
    assert_eq!(err.kind(), std::io::ErrorKind::PermissionDenied);

    let mut reply = Vec::new();
    client.read_to_end(&mut reply).expect("read http auth failure reply");
    let reply = String::from_utf8(reply).expect("utf8 reply");
    assert!(reply.contains("407 Proxy Authentication Required"));
    assert!(reply.contains("Proxy-Authenticate: Basic realm=\"ripdpi\""));
}
