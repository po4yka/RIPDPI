use super::protocol_io::*;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeClientRequest;
use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_proxy_runtime_adapter::model::config::{DesyncGroup, RuntimeConfig};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::session::{
    S_ATP_I4, S_ATP_I6, S_CMD_AUDP, S_CMD_CONN, S_ER_DENY, S_VER4, S_VER5, S4_OK, encode_http_connect_reply,
    encode_socks4_reply, encode_socks5_reply,
};
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::thread;
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
    RuntimeState::test_with_context(config, runtime_context)
}

fn resolve_ip_literal(host: &str) -> Option<SocketAddr> {
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
        connection_concurrency: None,
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
        dns_odoh_proxy_port: 0,
        dns_odoh_target_port: 0,
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
fn owned_stack_required_maps_to_socks5_ruleset_denied() {
    let (mut writer, mut reader) = connected_pair();
    reader.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");

    let error = super::handle_socks5_connect_error(
        &mut writer,
        super::connect_relay::ConnectRelayError::owned_stack_required(),
    )
    .expect_err("owned-stack-only rejection must remain an error");

    let mut reply = [0_u8; 10];
    reader.read_exact(&mut reply).expect("read SOCKS5 rejection");
    assert_eq!(reply[1], S_ER_DENY);
    assert_eq!(error.kind(), ErrorKind::PermissionDenied);
}

#[test]
fn owned_stack_required_maps_to_http_forbidden_with_reason() {
    let (mut writer, mut reader) = connected_pair();
    reader.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");

    let error =
        super::handle_http_connect_error(&mut writer, super::connect_relay::ConnectRelayError::owned_stack_required())
            .expect_err("owned-stack-only rejection must remain an error");

    let mut buffer = [0_u8; 160];
    let count = reader.read(&mut buffer).expect("read HTTP rejection");
    let reply = std::str::from_utf8(&buffer[..count]).expect("HTTP rejection is UTF-8");
    assert!(reply.starts_with("HTTP/1.1 403 Forbidden\r\n"), "unexpected response: {reply:?}");
    assert!(reply.contains("X-RIPDPI-Reason: OWNED_STACK_REQUIRED\r\n"));
    assert!(reply.ends_with("Content-Length: 0\r\n\r\n"));
    assert_eq!(error.kind(), ErrorKind::PermissionDenied);
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
fn read_http_connect_request_reads_delimiter_split_across_chunks() {
    let (mut reader, mut writer) = connected_pair();
    let mut request = b"CONNECT example.com:443 HTTP/1.1\r\n".to_vec();
    let padding_len = 510usize.checked_sub(request.len()).expect("request prefix fits in first chunk");
    request.extend(vec![b'A'; padding_len]);
    request.extend_from_slice(b"\r\n\r\n");
    writer.write_all(&request).expect("write http connect request");

    assert_eq!(read_http_connect_request(&mut reader).expect("read http connect request"), request);
}

#[test]
fn parse_shadowsocks_target_handles_ipv4_and_resolved_domain_targets() {
    let config = RuntimeConfig::default();
    let state = runtime_state(config);
    let ipv4_packet = [S_ATP_I4, 127, 0, 0, 1, 0x01, 0xbb];
    let (ipv4_target, ipv4_header_len) =
        state.parse_shadowsocks_target(&ipv4_packet, resolve_ip_literal).expect("parse ipv4 target");
    assert_eq!(ipv4_target.addr, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
    assert_eq!(ipv4_target.host, None);
    assert_eq!(ipv4_header_len, ipv4_packet.len());

    let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0x00, 0x50];
    let (domain_target, domain_header_len) =
        state.parse_shadowsocks_target(&domain_packet, resolve_ip_literal).expect("parse domain target");
    assert_eq!(domain_target.addr, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 80));
    assert_eq!(domain_target.host.as_deref(), Some("127.0.0.1"));
    assert_eq!(domain_header_len, domain_packet.len());
}

#[test]
fn parse_shadowsocks_target_respects_ipv6_and_resolve_flags() {
    let mut config = RuntimeConfig::default();
    config.network.ipv6 = false;
    config.network.resolve = false;
    let state = runtime_state(config);
    let ipv6_packet = [S_ATP_I6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 53];
    let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0, 80];

    assert!(state.parse_shadowsocks_target(&ipv6_packet, resolve_ip_literal).is_none());
    assert!(state.parse_shadowsocks_target(&domain_packet, resolve_ip_literal).is_none());
}

#[test]
fn read_shadowsocks_request_returns_fragmented_target_and_first_payload() {
    let config = RuntimeConfig::default();
    let state = runtime_state(config);
    let (mut reader, mut writer) = connected_pair();
    let payload = b"GET / HTTP/1.1\r\n\r\n";

    writer.write_all(&[127, 0]).expect("write first fragment");
    writer.write_all(&[0, 1, 0x01, 0xbb]).expect("write second fragment");
    writer.write_all(payload).expect("write first payload");

    let (target, first_payload) =
        read_shadowsocks_request(&mut reader, S_ATP_I4, &state, resolve_ip_literal).expect("read shadowsocks request");

    assert_eq!(target.addr, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
    assert_eq!(first_payload, payload);
}

#[test]
fn read_shadowsocks_request_reports_eof_before_complete_target() {
    let config = RuntimeConfig::default();
    let state = runtime_state(config);
    let (mut reader, writer) = connected_pair();
    drop(writer);

    let err = read_shadowsocks_request(&mut reader, 0x03, &state, resolve_ip_literal).expect_err("expected eof");

    assert_eq!(err.kind(), ErrorKind::UnexpectedEof);
}

#[test]
fn read_shadowsocks_request_rejects_unbounded_unresolved_target() {
    let config = RuntimeConfig::default();
    let state = runtime_state(config);
    let (mut reader, mut writer) = connected_pair();
    let writer_thread = thread::spawn(move || {
        writer.write_all(&vec![b'a'; 70 * 1024]).expect("write oversized unresolved request");
    });

    let err = read_shadowsocks_request(&mut reader, 0x03, &state, |_| None).expect_err("expected request too large");

    assert_eq!(err.kind(), ErrorKind::InvalidData);
    writer_thread.join().expect("writer thread finished");
}

#[test]
fn domain_protocols_resolve_through_encrypted_dns_runtime_context() {
    let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");
    let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);
    let state = runtime_state_with_context(RuntimeConfig::default(), Some(runtime_context));
    let resolver = |host: &str| state.resolve_handshake_name(host);
    let expected_ip = stack.manifest().dns_answer_ipv4.parse::<IpAddr>().expect("fixture ip");

    let socks4_request = [
        0x04, 0x01, 0x01, 0xbb, 0, 0, 0, 1, 0, b'f', b'i', b'x', b't', b'u', b'r', b'e', b'.', b't', b'e', b's', b't',
        0,
    ];
    let RuntimeClientRequest::Socks4Connect(socks4_target) =
        state.parse_socks4_client_request(&socks4_request, resolver).expect("parse socks4 request")
    else {
        panic!("expected SOCKS4 connect request");
    };
    assert_eq!(socks4_target.addr.ip(), expected_ip);

    let socks5_request = [
        S_VER5, S_CMD_CONN, 0, 0x03, 12, b'f', b'i', b'x', b't', b'u', b'r', b'e', b'.', b't', b'e', b's', b't', 0x01,
        0xbb,
    ];
    let RuntimeClientRequest::Socks5Connect(socks5_target) =
        state.parse_socks5_client_request(&socks5_request, resolver).expect("parse socks5 request")
    else {
        panic!("expected SOCKS5 connect request");
    };
    assert_eq!(socks5_target.addr.ip(), expected_ip);

    let http_request = b"CONNECT fixture.test:443 HTTP/1.1\r\nHost: fixture.test:443\r\n\r\n";
    let RuntimeClientRequest::HttpConnect(http_target) =
        RuntimeState::parse_http_connect_client_request(http_request, resolver).expect("parse http connect request")
    else {
        panic!("expected HTTP CONNECT request");
    };
    assert_eq!(http_target.addr.ip(), expected_ip);

    let shadowsocks_request = [0x03, 12, b'f', b'i', b'x', b't', b'u', b'r', b'e', b'.', b't', b'e', b's', b't', 0, 80];
    let (shadowsocks_target, header_len) =
        state.parse_shadowsocks_target(&shadowsocks_request, resolver).expect("parse shadowsocks target");
    assert_eq!(shadowsocks_target.addr.ip(), expected_ip);
    assert_eq!(shadowsocks_target.host.as_deref(), Some("fixture.test"));
    assert_eq!(header_len, shadowsocks_request.len());
}

#[test]
fn localhost_resolves_to_loopback_without_runtime_context() {
    let mut config = RuntimeConfig::default();
    config.network.resolve = false;
    config.network.ipv6 = false;
    let state = runtime_state(config);

    assert_eq!(state.resolve_handshake_name("localhost"), Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)));
}

#[test]
fn localhost_prefers_ipv6_loopback_when_enabled() {
    let mut config = RuntimeConfig::default();
    config.network.resolve = false;
    config.network.ipv6 = true;
    let state = runtime_state(config);

    assert_eq!(
        state.resolve_handshake_name("LOCALHOST."),
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
    // The SOCKS5 reply code must mirror the actual connect-failure kind per
    // RFC 1928 §6 (e.g. ConnectionRefused -> 0x05), not a hard-coded generic
    // failure — the accepted kinds above map to distinct REP codes.
    assert_eq!(failure[1], RuntimeState::socks5_reply_code_for_kind(err.kind()));
}

#[test]
fn handle_client_relays_successful_socks5_connect_flow() {
    let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream listener");
    let target = upstream.local_addr().expect("upstream addr");
    let upstream_thread = thread::spawn(move || {
        let (mut stream, _) = upstream.accept().expect("accept upstream");
        let mut request = [0u8; 4];
        stream.read_exact(&mut request).expect("read upstream payload");
        assert_eq!(&request, b"ping");
        stream.write_all(b"pong").expect("write upstream response");
        let _ = stream.shutdown(Shutdown::Both);
    });

    let mut config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    config.network.resolve = false;
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("set client timeout");
    let proxy_thread = thread::spawn(move || super::handle_client(server, &state));

    let mut request = vec![S_VER5, 1, 0];
    request.extend([S_VER5, S_CMD_CONN, 0, S_ATP_I4]);
    request.extend_from_slice(&Ipv4Addr::LOCALHOST.octets());
    request.extend_from_slice(&target.port().to_be_bytes());
    request.extend_from_slice(b"ping");
    client.write_all(&request).expect("write socks5 connect request");
    client.shutdown(Shutdown::Write).expect("finish client write side");

    let mut auth = [0u8; 2];
    client.read_exact(&mut auth).expect("read socks5 auth reply");
    assert_eq!(auth, [S_VER5, 0]);
    let mut success = [0u8; 10];
    client.read_exact(&mut success).expect("read socks5 success reply");
    assert_eq!(success[0], S_VER5);
    assert_eq!(success[1], 0);
    let mut response = [0u8; 4];
    client.read_exact(&mut response).expect("read relayed response");
    assert_eq!(&response, b"pong");

    upstream_thread.join().expect("upstream thread finished");
    proxy_thread.join().expect("proxy thread finished").expect("proxy flow succeeds");
}

#[test]
fn handle_client_relays_successful_socks4_connect_flow() {
    let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream listener");
    let target = upstream.local_addr().expect("upstream addr");
    let upstream_thread = thread::spawn(move || {
        let (mut stream, _) = upstream.accept().expect("accept upstream");
        let mut request = [0u8; 4];
        stream.read_exact(&mut request).expect("read upstream payload");
        assert_eq!(&request, b"ping");
        stream.write_all(b"pong").expect("write upstream response");
        let _ = stream.shutdown(Shutdown::Both);
    });

    let mut config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    config.network.resolve = false;
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("set client timeout");
    let proxy_thread = thread::spawn(move || super::handle_client(server, &state));

    let mut request = vec![S_VER4, S_CMD_CONN];
    request.extend_from_slice(&target.port().to_be_bytes());
    request.extend_from_slice(&Ipv4Addr::LOCALHOST.octets());
    request.push(0);
    request.extend_from_slice(b"ping");
    client.write_all(&request).expect("write socks4 connect request");
    client.shutdown(Shutdown::Write).expect("finish client write side");

    let mut success = [0u8; 8];
    client.read_exact(&mut success).expect("read socks4 success reply");
    assert_eq!(success[1], S4_OK);
    let mut response = [0u8; 4];
    client.read_exact(&mut response).expect("read relayed response");
    assert_eq!(&response, b"pong");

    upstream_thread.join().expect("upstream thread finished");
    proxy_thread.join().expect("proxy thread finished").expect("proxy flow succeeds");
}

#[test]
fn handle_client_rejects_unsupported_byte_prefixed_protocol() {
    let state = runtime_state(RuntimeConfig::default());
    let (mut client, server) = connected_pair();
    client.write_all(&[0x99]).expect("write unsupported protocol byte");

    let err = super::handle_client(server, &state).expect_err("unsupported protocol should fail");

    assert_eq!(err.kind(), ErrorKind::InvalidData);
}

#[test]
fn handle_client_sends_socks5_command_unsupported_when_udp_disabled() {
    let mut config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    config.network.udp = false;
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");

    let request = [S_VER5, 1, 0, S_VER5, S_CMD_AUDP, 0, S_ATP_I4, 0, 0, 0, 0, 0, 0];
    client.write_all(&request).expect("write socks5 udp associate request");

    super::handle_client(server, &state).expect("udp associate disabled returns a protocol reply");

    let mut auth = [0u8; 2];
    client.read_exact(&mut auth).expect("read socks5 auth reply");
    assert_eq!(auth, [S_VER5, 0]);
    let mut failure = [0u8; 10];
    client.read_exact(&mut failure).expect("read socks5 failure reply");
    assert_eq!(failure[0], S_VER5);
    assert_eq!(failure[1], RuntimeState::socks5_command_unsupported_code());
}

#[test]
fn handle_socks5_rejects_invalid_version_when_called_directly() {
    let state = runtime_state(RuntimeConfig::default());
    let (_client, server) = connected_pair();

    let err = super::handle_socks5(server, &state, S_VER4).expect_err("invalid socks5 version should fail");

    assert_eq!(err.kind(), ErrorKind::InvalidData);
}

#[test]
fn handle_socks4_writes_failure_for_unsupported_command() {
    let state = runtime_state(RuntimeConfig::default());
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");
    let request = [S_VER4, 0x02, 0, 80, 127, 0, 0, 1, 0];
    client.write_all(&request).expect("write socks4 bind request");

    super::handle_socks4(server, &state, S_VER4).expect("unsupported socks4 command returns reply");

    let mut failure = [0u8; 8];
    client.read_exact(&mut failure).expect("read socks4 failure reply");
    assert_ne!(failure[1], S4_OK);
}

#[test]
fn handle_http_connect_writes_failure_for_invalid_request() {
    let mut config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    config.network.http_connect = true;
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");
    client.write_all(b"GET / HTTP/1.1\r\n\r\n").expect("write invalid http connect request");

    super::handle_http_connect(server, &state).expect("invalid http connect returns reply");

    let mut reply = Vec::new();
    client.read_to_end(&mut reply).expect("read http failure reply");
    assert!(String::from_utf8(reply).expect("utf8 reply").starts_with("HTTP/1.1 503"));
}

#[test]
fn validate_http_proxy_auth_valid() {
    use base64::engine::{Engine, general_purpose::STANDARD};
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
    use base64::engine::{Engine, general_purpose::STANDARD};
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
    assert_eq!(reply, [S_VER5, ripdpi_proxy_runtime_adapter::model::session::S_AUTH_BAD]);
}

#[test]
fn negotiate_socks5_rejects_wrong_password() {
    let (mut client, mut server) = connected_pair();
    let mut request = vec![0x01, ripdpi_proxy_runtime_adapter::model::session::S_AUTH_USERPASS];
    request.extend([0x01, 0x06]);
    request.extend_from_slice(b"ripdpi");
    request.push(0x05);
    request.extend_from_slice(b"wrong");
    client.write_all(&request).expect("write socks5 auth exchange");

    let err = negotiate_socks5(&mut server, Some("alpha-123")).expect_err("wrong password should fail");
    assert_eq!(err.kind(), std::io::ErrorKind::PermissionDenied);

    let mut method_reply = [0u8; 2];
    client.read_exact(&mut method_reply).expect("read method reply");
    assert_eq!(method_reply, [S_VER5, ripdpi_proxy_runtime_adapter::model::session::S_AUTH_USERPASS]);

    let mut auth_reply = [0u8; 2];
    client.read_exact(&mut auth_reply).expect("read auth status");
    assert_eq!(auth_reply, [0x01, 0x01]);
}

#[test]
fn negotiate_socks5_rejects_wrong_password_prefix_and_suffix() {
    for password in ["Alpha-123", "alpha-124"] {
        let (mut client, mut server) = connected_pair();
        let mut request = vec![0x01, ripdpi_proxy_runtime_adapter::model::session::S_AUTH_USERPASS];
        request.extend([0x01, 0x06]);
        request.extend_from_slice(b"ripdpi");
        request.push(password.len() as u8);
        request.extend_from_slice(password.as_bytes());
        client.write_all(&request).expect("write socks5 auth exchange");

        let err = negotiate_socks5(&mut server, Some("alpha-123")).expect_err("wrong password should fail");
        assert_eq!(err.kind(), std::io::ErrorKind::PermissionDenied);
        let mut reply = [0u8; 4];
        client.read_exact(&mut reply).expect("read method and auth replies");
        assert_eq!(reply, [S_VER5, ripdpi_proxy_runtime_adapter::model::session::S_AUTH_USERPASS, 0x01, 0x01]);
    }
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
