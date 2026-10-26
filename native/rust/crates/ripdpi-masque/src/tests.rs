use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use bytes::Bytes;
use http::{HeaderMap, Request, StatusCode};
use hyper::ext::Protocol as H2Protocol;
use local_network_fixture::{MasqueH2ConnectUdpFixture, MasqueH3ClassicConnectFixture, MasqueH3ConnectUdpFixture};
use serde_json::to_string;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::Mutex;

use super::*;
use crate::auth::PrivacyPassProviderResponse;
use crate::capsule::CapsuleReader;
use crate::config::{MasqueAuthMode, MasqueConfig, MasqueTcpProtocol};
use crate::h2::{build_h2_connect_udp_request, encode_h2_datagram_capsule};
use crate::h3::decode_udp_payload;
use crate::migration::{H3FallbackReason, MigrationStatus};
use crate::request::apply_request_headers;
use crate::response::{AttemptError, validate_connect_udp_response, validate_proxy_response};
use crate::url::{
    ProxyOrigin, TargetAuthority, build_connect_udp_path, parse_proxy_origin, parse_target, resolve_proxy_socket_addr,
};

const BORING_ECH_CONFIG_LIST: &[u8] = &[
    0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e, 0x04,
    0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07, 0x9b, 0x93,
    0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00, 0x07, 0x65, 0x63,
    0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
];

fn sha256_hex(payload: &[u8]) -> String {
    ring::digest::digest(&ring::digest::SHA256, payload).as_ref().iter().map(|byte| format!("{byte:02x}")).collect()
}

fn emit_pmtud_measurement(value: serde_json::Value) {
    println!("PMTUD_MEASUREMENT {}", serde_json::to_string(&value).expect("serialize PMTUD measurement"));
}

#[test]
fn unsupported_auth_mode_fails_before_connect() {
    let mut config = privacy_pass_test_config("https://provider.example/token".to_string(), None);
    config.auth_mode = Some("silent-fallback".to_string());
    config.privacy_pass_provider_url = None;
    let error = MasqueClient::new(config).err().expect("unknown auth mode must not become unauthenticated");
    assert_eq!(io::ErrorKind::InvalidInput, error.kind());
    assert!(error.to_string().contains("unsupported MASQUE auth mode"));
}

fn privacy_pass_test_config(provider_url: String, provider_auth_token: Option<&str>) -> MasqueConfig {
    MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example/".to_string(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: false,
        auth_mode: Some("privacy_pass".to_string()),
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: Some(provider_url),
        privacy_pass_provider_auth_token: provider_auth_token.map(ToOwned::to_owned),
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    }
}

#[test]
fn privacy_pass_vpn_mode_fails_before_unprotectable_provider_dial() {
    let mut config = privacy_pass_test_config("https://provider.example/token".to_string(), None);
    config.socket_protection = ripdpi_native_protect::SocketProtectionPolicy::VpnRequired;

    let Err(error) = MasqueClient::new(config) else {
        panic!("unprotectable provider fetch must fail closed");
    };
    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
}

#[test]
fn debug_output_redacts_masque_credentials_and_urls() {
    let mut config =
        privacy_pass_test_config("https://provider.example/token?query-secret".to_string(), Some("provider-secret"));
    config.url = "https://masque.example/path?relay-secret".to_string();
    config.auth_token = Some("proxy-secret".to_string());
    config.client_private_key_pem = Some("private-key-secret".to_string());

    let debug = format!("{config:?}");

    for secret in ["query-secret", "relay-secret", "provider-secret", "proxy-secret", "private-key-secret"] {
        assert!(!debug.contains(secret), "Debug output leaked {secret}");
    }
    assert!(debug.contains("<redacted>"));
}

async fn start_provider_stub(
    responses: Vec<(u16, PrivacyPassProviderResponse)>,
) -> io::Result<(String, Arc<Mutex<Vec<String>>>, tokio::task::JoinHandle<io::Result<()>>)> {
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let address = listener.local_addr()?;
    let requests = Arc::new(Mutex::new(Vec::new()));
    let request_log = Arc::clone(&requests);

    let handle = tokio::spawn(async move {
        for (status, payload) in responses {
            let (mut socket, _) = listener.accept().await?;
            let request = read_http_request(&mut socket).await?;
            request_log.lock().await.push(request);

            let body =
                to_string(&payload).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()))?;
            let status_text = http_status_text(status);
            let response = format!(
                "HTTP/1.1 {status} {status_text}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                body.len()
            );
            socket.write_all(response.as_bytes()).await?;
        }
        Ok(())
    });

    Ok((format!("http://{address}/token"), requests, handle))
}

async fn read_http_request(stream: &mut tokio::net::TcpStream) -> io::Result<String> {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 512];
    loop {
        let read = stream.read(&mut chunk).await?;
        if read == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "provider request ended before the full body arrived",
            ));
        }
        buffer.extend_from_slice(&chunk[..read]);
        if let Some(headers_end) = find_headers_end(&buffer) {
            let content_length = parse_content_length(&buffer[..headers_end])?;
            if buffer.len() >= headers_end + content_length {
                return String::from_utf8(buffer)
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()));
            }
        }
    }
}

fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n").map(|index| index + 4)
}

fn parse_content_length(headers: &[u8]) -> io::Result<usize> {
    let headers =
        std::str::from_utf8(headers).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()))?;
    Ok(headers
        .lines()
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            if name.trim().eq_ignore_ascii_case("content-length") { value.trim().parse::<usize>().ok() } else { None }
        })
        .unwrap_or(0))
}

fn http_status_text(status: u16) -> &'static str {
    match status {
        200 => "OK",
        403 => "Forbidden",
        _ => "Test",
    }
}

#[test]
fn connect_udp_path_percent_encodes_ipv6_hosts() {
    let path = build_connect_udp_path(
        &ProxyOrigin {
            host: "masque.example".to_string(),
            authority: "masque.example".to_string(),
            udp_base_path: "/.well-known/masque".to_string(),
        },
        &TargetAuthority { host: "2001:db8::42".to_string(), port: 443 },
    );

    assert_eq!(path, "/.well-known/masque/udp/2001%3Adb8%3A%3A42/443/");
}

#[test]
fn new_client_starts_with_not_attempted_quic_snapshot() {
    let client = MasqueClient::new(MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example/".to_string(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: Some("bearer".to_string()),
        auth_token: Some("secret".to_string()),
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    })
    .expect("client");

    assert_eq!((Some("not_attempted".to_string()), None), client.quic_migration_snapshot(),);
}

fn grease_test_config(tcp_protocol: MasqueTcpProtocol) -> MasqueConfig {
    MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example/".to_string(),
        proxy_socket_addr: None,
        tcp_protocol,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "chrome_stable".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        // What the ECH bootstrap yields when the proxy publishes no ECH config.
        ech_config: None,
    }
}

/// The extension types of the ClientHello in one TLS handshake record.
fn client_hello_extension_types(record: &[u8]) -> Vec<u16> {
    let read_u16 = |at: usize| usize::from(u16::from_be_bytes([record[at], record[at + 1]]));
    // Record header (5), handshake header (4), legacy version (2), random (32).
    let mut at = 5 + 4 + 2 + 32;
    at += 1 + usize::from(record[at]);
    at += 2 + read_u16(at);
    at += 1 + usize::from(record[at]);
    let end = at + 2 + read_u16(at);
    at += 2;
    let mut types = Vec::new();
    while at + 4 <= end {
        types.push(u16::from_be_bytes([record[at], record[at + 1]]));
        at += 4 + read_u16(at + 2);
    }
    types
}

const ECH_EXTENSION_TYPE: u16 = 0xfe0d;

#[tokio::test]
async fn h2_proxy_handshake_sends_grease_ech_without_a_published_config() {
    let config = grease_test_config(MasqueTcpProtocol::Http2);
    let (client, mut proxy) = tokio::io::duplex(16 * 1024);
    let attempt = tokio::spawn(async move {
        let _ = crate::h2::attempt_h2_connect_tcp_over_transport(&config, client, "example.com:443", None).await;
    });

    let mut header = [0u8; 5];
    proxy.read_exact(&mut header).await.expect("TLS record header");
    let mut record = header.to_vec();
    record.resize(5 + usize::from(u16::from_be_bytes([header[3], header[4]])), 0);
    proxy.read_exact(&mut record[5..]).await.expect("ClientHello");
    drop(proxy);
    attempt.abort();

    assert!(client_hello_extension_types(&record).contains(&ECH_EXTENSION_TYPE), "no encrypted_client_hello");
}

#[test]
fn h3_proxy_handshake_sends_grease_ech_without_a_published_config() {
    let config = grease_test_config(MasqueTcpProtocol::Http3);
    let tls_config = crate::h3::h3_tls_config(&config, rustls::RootCertStore::empty()).expect("H3 TLS config");
    let server_name = rustls::pki_types::ServerName::try_from("masque.example").expect("server name");
    let mut connection = rustls::ClientConnection::new(Arc::new(tls_config), server_name).expect("client");
    let mut record = Vec::new();
    connection.write_tls(&mut record).expect("ClientHello");

    assert!(client_hello_extension_types(&record).contains(&ECH_EXTENSION_TYPE), "no encrypted_client_hello");
}

#[test]
fn masque_config_accepts_ech_and_boring_h2_backend_can_apply_it() {
    let ech = ripdpi_tls_profiles::OutboundEchConfig::new("ech.com", BORING_ECH_CONFIG_LIST.to_vec()).expect("ech");
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example/".to_string(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: Some(ech),
    };

    let connector_builder =
        ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile).expect("connector builder");
    let connector = connector_builder.build();
    let mut ssl = connector.configure().expect("connect config");
    ripdpi_tls_profiles::configure_boring_ech(&mut ssl, config.ech_config.as_ref()).expect("ECH applied");
}

#[test]
fn parse_proxy_origin_derives_connect_udp_base_path() {
    let origin = parse_proxy_origin(&MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example/.well-known/masque/ip?cf=1".to_string(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: Some("bearer".to_string()),
        auth_token: Some("secret".to_string()),
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    })
    .expect("origin");

    assert_eq!("/.well-known/masque", origin.udp_base_path);
}

#[tokio::test]
async fn proxy_socket_addr_prefers_bootstrapped_endpoint_without_rewriting_origin_host() {
    let bootstrapped_addr: SocketAddr = "203.0.113.8:8443".parse().expect("socket addr");
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example:8443/.well-known/masque/ip".to_string(),
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: Some("bearer".to_string()),
        auth_token: Some("secret".to_string()),
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
        proxy_socket_addr: Some(bootstrapped_addr),
    };

    let origin = parse_proxy_origin(&config).expect("proxy origin");

    assert_eq!(origin.host, "masque.example");
    assert_eq!(resolve_proxy_socket_addr(&config, &origin).await.expect("proxy socket addr"), bootstrapped_addr);
}

#[test]
fn decode_udp_payload_requires_context_zero() {
    assert!(decode_udp_payload(Bytes::from_static(&[1, 2, 3])).expect("decode").is_none());
}

#[test]
fn parse_target_supports_domain_and_ipv6_authorities() {
    let domain = parse_target("example.com:53").expect("domain");
    assert_eq!(domain.host, "example.com");
    assert_eq!(domain.port, 53);

    let ipv6 = parse_target("[2001:db8::1]:443").expect("ipv6");
    assert_eq!(ipv6.host, "2001:db8::1");
    assert_eq!(ipv6.port, 443);
}

#[test]
fn apply_request_headers_does_not_add_proprietary_geohash() {
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example/".to_string(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: Some("cloudflare_mtls".to_string()),
        auth_token: None,
        client_certificate_chain_pem: Some("placeholder certificate".to_string()),
        client_private_key_pem: Some("placeholder private key".to_string()),
        cloudflare_geohash_header: Some("u4pruyd-GB".to_string()),
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };

    let request = apply_request_headers(Request::builder().method("CONNECT").uri("example.com:443"), &config, None)
        .expect("builder")
        .body(())
        .expect("request");

    assert!(request.headers().get("sec-ch-geohash").is_none());
    assert!(request.headers().get("authorization").is_none());
    assert!(request.headers().get("proxy-authorization").is_none());
}

#[test]
fn tls_client_auth_rejection_does_not_require_privacy_pass_challenge() {
    let error = validate_proxy_response(StatusCode::FORBIDDEN, &HeaderMap::new(), MasqueAuthMode::CloudflareMtls)
        .expect_err("expected rejection");

    let AttemptError::Io(error) = error else {
        panic!("expected io rejection");
    };
    assert_eq!(io::ErrorKind::PermissionDenied, error.kind());
    assert!(error.to_string().contains("TLS client identity"));
}

#[test]
fn privacy_pass_challenge_requires_private_token_header() {
    let mut headers = HeaderMap::new();
    headers.insert("www-authenticate", "Basic realm=test".parse().expect("header"));

    let error = validate_proxy_response(StatusCode::UNAUTHORIZED, &headers, MasqueAuthMode::PrivacyPass)
        .expect_err("expected challenge failure");

    let AttemptError::Io(error) = error else {
        panic!("expected io error");
    };
    assert_eq!(io::ErrorKind::InvalidData, error.kind());
}

#[test]
fn connect_udp_success_response_does_not_require_capsule_protocol_header() {
    validate_connect_udp_response(StatusCode::OK, &HeaderMap::new(), MasqueAuthMode::None)
        .expect("RFC 9298 3.5 requires only a 2xx status");
}

#[test]
fn connect_udp_success_response_accepts_capsule_protocol_with_parameters() {
    for value in ["?1", "?1;x=y"] {
        let mut headers = HeaderMap::new();
        headers.insert("capsule-protocol", value.parse().expect("header"));

        validate_connect_udp_response(StatusCode::OK, &headers, MasqueAuthMode::None)
            .unwrap_or_else(|_| panic!("Capsule-Protocol {value:?} must be accepted"));
    }
}

#[test]
fn connect_udp_success_response_rejects_statuses_without_a_tunnel() {
    for status in [StatusCode::NO_CONTENT, StatusCode::RESET_CONTENT, StatusCode::PARTIAL_CONTENT] {
        let mut headers = HeaderMap::new();
        headers.insert("capsule-protocol", "?1".parse().expect("header"));

        let error = validate_connect_udp_response(status, &headers, MasqueAuthMode::None)
            .expect_err("RFC 9297 3.2 makes this response malformed");
        let AttemptError::Io(error) = error else {
            panic!("expected io error");
        };
        assert_eq!(io::ErrorKind::InvalidData, error.kind());
    }
}

#[test]
fn connect_udp_rejection_includes_proxy_status_details() {
    let mut headers = HeaderMap::new();
    headers.insert("proxy-status", "masque.example; error=dns_error; details=\"blocked\"".parse().expect("header"));

    let error = validate_connect_udp_response(StatusCode::BAD_GATEWAY, &headers, MasqueAuthMode::None)
        .expect_err("CONNECT-UDP rejection must fail");

    let AttemptError::Io(error) = error else {
        panic!("expected io error");
    };
    assert_eq!(io::ErrorKind::PermissionDenied, error.kind());
    assert!(error.to_string().contains("dns_error"));
    assert!(error.to_string().contains("blocked"));
}

#[test]
fn h2_connect_udp_datagrams_use_capsule_protocol_tlv() {
    let encoded = encode_h2_datagram_capsule(&[0x00, 0xde, 0xad]).expect("encode");

    assert_eq!(encoded, [0x00, 0x03, 0x00, 0xde, 0xad]);
    assert_eq!(CapsuleReader::default().push(&encoded), vec![vec![0x00, 0xde, 0xad]]);
}

#[test]
fn h2_connect_udp_request_uses_extended_connect_and_capsule_protocol() {
    let proxy_origin = ProxyOrigin {
        host: "masque.example".to_string(),
        authority: "masque.example".to_string(),
        udp_base_path: "/.well-known/masque".to_string(),
    };
    let target = TargetAuthority { host: "example.com".to_string(), port: 443 };
    let request = build_h2_connect_udp_request(&proxy_origin, &target, None).expect("request");

    assert_eq!(request.method(), "CONNECT");
    assert_eq!(request.uri(), "https://masque.example/.well-known/masque/udp/example.com/443/");
    assert_eq!(request.extensions().get::<H2Protocol>().expect("protocol").as_ref(), b"connect-udp",);
    assert_eq!(request.uri().scheme_str(), Some("https"));
    assert_eq!(request.uri().authority().map(http::uri::Authority::as_str), Some("masque.example"));
    assert_eq!(request.headers().get("capsule-protocol").expect("capsule"), "?1");
}

#[tokio::test]
async fn privacy_pass_provider_fetch_caches_spare_headers() {
    let (provider_url, requests, provider_task) = start_provider_stub(vec![(
        200,
        PrivacyPassProviderResponse {
            authorization_headers: Some(vec![
                "PrivateToken token-one".to_string(),
                "PrivateToken token-two".to_string(),
            ]),
            authorization_header: None,
            proxy_authorization_headers: None,
            proxy_authorization_header: None,
            expires_at_epoch_ms: None,
        },
    )])
    .await
    .expect("provider stub");
    let client = MasqueClient::new(privacy_pass_test_config(provider_url, Some("provider-secret"))).expect("client");

    let first = client
        .inner
        .fetch_privacy_pass_header("example.com:443", "PrivateToken challenge=AAAA, token-key=BBBB")
        .await
        .expect("first provider header");
    assert_eq!(first.name, "authorization");
    assert_eq!(first.value, "PrivateToken token-one");

    let cached = client.inner.cached_privacy_pass_header("example.com:443").await.expect("cached provider header");
    assert_eq!(cached.name, "authorization");
    assert_eq!(cached.value, "PrivateToken token-two");
    assert!(client.inner.cached_privacy_pass_header("example.com:443").await.is_none());

    provider_task.await.expect("provider task").expect("provider result");
    let requests = requests.lock().await.clone();
    assert_eq!(requests.len(), 1);
    let request = &requests[0];
    let request_lower = request.to_ascii_lowercase();
    assert!(request.starts_with("POST /token HTTP/1.1"));
    assert!(request_lower.contains("authorization: bearer provider-secret"));
    assert!(request.contains("\"proxyUrl\":\"https://masque.example/\""));
    assert!(request.contains("\"target\":\"example.com:443\""));
    assert!(request.contains("\"challengeHeader\":\"PrivateToken challenge=AAAA, token-key=BBBB\""));
}

#[tokio::test]
async fn privacy_pass_challenge_fetches_a_token_and_retries_the_attempt_once() {
    let (provider_url, _requests, provider_task) = start_provider_stub(vec![(
        200,
        PrivacyPassProviderResponse {
            authorization_headers: None,
            authorization_header: Some("PrivateToken token-one".to_string()),
            proxy_authorization_headers: None,
            proxy_authorization_header: None,
            expires_at_epoch_ms: None,
        },
    )])
    .await
    .expect("provider stub");
    let client = MasqueClient::new(privacy_pass_test_config(provider_url, None)).expect("client");
    let seen = Arc::new(Mutex::new(Vec::new()));

    let result = client
        .inner
        .attempt_with_privacy_pass_retry("example.com:443", None, |header| {
            let seen = Arc::clone(&seen);
            async move {
                let value = header.map(|header| header.value);
                seen.lock().await.push(value.clone());
                match value {
                    None => Err(AttemptError::PrivacyPassChallenge("PrivateToken challenge=AAAA".to_string())),
                    Some(value) => Ok(value),
                }
            }
        })
        .await
        .expect("the retry with the fetched token succeeds");

    assert_eq!(result, "PrivateToken token-one");
    assert_eq!(*seen.lock().await, vec![None, Some("PrivateToken token-one".to_string())]);
    provider_task.await.expect("provider task").expect("provider result");
}

#[tokio::test]
async fn quic_migration_snapshot_records_http2_fallback_reason() {
    let client = MasqueClient::new(MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example/".to_string(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: Some("bearer".to_string()),
        auth_token: Some("secret".to_string()),
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    })
    .expect("client");

    client.inner.record_quic_migration_status("http2_fallback", Some("http3_connect_failed_connect")).await;

    assert_eq!(
        (Some("http2_fallback".to_string()), Some("http3_connect_failed_connect".to_string()),),
        client.quic_migration_snapshot(),
    );
}

fn fallback_snapshot_test_client() -> MasqueClient {
    MasqueClient::new(MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: "https://masque.example/".to_string(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: Some("bearer".to_string()),
        auth_token: Some("secret".to_string()),
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    })
    .expect("client")
}

/// Drives one [`H3FallbackReason`] through the real telemetry path
/// (`record_quic_migration_status` -> `quic_migration_snapshot`) and asserts the
/// snapshot captures the `http2_fallback` status plus the reason's documented
/// string.
///
/// The `match` below has NO wildcard arm: adding a variant to
/// [`H3FallbackReason`] makes this helper fail to compile until a case is added,
/// which in turn forces a dedicated per-reason test alongside it. That is the
/// compile-time gate behind this task's definition of done — a new fallback
/// reason cannot land without snapshot-capture coverage. The expected string is
/// recomputed here independently (not by calling `as_string`) so the assertion
/// is a genuine oracle rather than a tautology.
async fn assert_snapshot_captures_fallback_reason(reason: &H3FallbackReason) {
    let expected_reason = match reason {
        H3FallbackReason::H3ConnectFailedInner { inner } => format!("http3_connect_failed_{inner}"),
        H3FallbackReason::H3ConnectTimedOut => "http3_connect_timed_out".to_string(),
        H3FallbackReason::H3ConnectFailed => "http3_connect_failed".to_string(),
    };
    assert_eq!(expected_reason, reason.as_string(), "documented reason string drifted from the enum rendering");

    let client = fallback_snapshot_test_client();
    client.inner.record_quic_migration_status(MigrationStatus::Http2Fallback.as_str(), Some(&reason.as_string())).await;

    assert_eq!(
        (Some("http2_fallback".to_string()), Some(expected_reason)),
        client.quic_migration_snapshot(),
        "snapshot must capture fallback reason {reason:?}",
    );
}

#[tokio::test]
async fn snapshot_captures_h3_connect_failed_inner_reason() {
    assert_snapshot_captures_fallback_reason(&H3FallbackReason::H3ConnectFailedInner { inner: "connect".to_string() })
        .await;
}

#[tokio::test]
async fn snapshot_captures_h3_connect_timed_out_reason() {
    assert_snapshot_captures_fallback_reason(&H3FallbackReason::H3ConnectTimedOut).await;
}

#[tokio::test]
async fn snapshot_captures_h3_connect_failed_reason() {
    assert_snapshot_captures_fallback_reason(&H3FallbackReason::H3ConnectFailed).await;
}

#[tokio::test]
async fn privacy_pass_provider_non_success_is_permission_denied() {
    let (provider_url, requests, provider_task) = start_provider_stub(vec![(
        403,
        PrivacyPassProviderResponse {
            authorization_headers: None,
            authorization_header: None,
            proxy_authorization_headers: None,
            proxy_authorization_header: None,
            expires_at_epoch_ms: None,
        },
    )])
    .await
    .expect("provider stub");
    let client = MasqueClient::new(privacy_pass_test_config(provider_url, None)).expect("client");

    let error = client
        .inner
        .fetch_privacy_pass_header("forbidden.example:443", "PrivateToken challenge=AAAA, token-key=BBBB")
        .await
        .expect_err("403 must fail");
    assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);

    provider_task.await.expect("provider task").expect("provider result");
    assert_eq!(requests.lock().await.len(), 1);
}

#[tokio::test]
async fn udp_session_round_trips_through_conformant_h2_connect_udp_fixture() {
    let fixture = MasqueH2ConnectUdpFixture::start().await.expect("start MASQUE fixture");
    let client = MasqueClient::new(MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    })
    .expect("client");

    let mut udp = client.udp_session();
    udp.send_to(&fixture.udp_echo_target(), b"masque-rfc9298").await.expect("send via MASQUE");
    let (target, payload) = tokio::time::timeout(std::time::Duration::from_secs(10), udp.recv_from())
        .await
        .expect("receive timeout")
        .expect("receive via MASQUE");

    assert_eq!(target, fixture.udp_echo_target());
    assert_eq!(payload, b"masque-rfc9298");
    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert_eq!(observed[0].protocol.as_deref(), Some("connect-udp"));
    assert_eq!(observed[0].capsule_protocol.as_deref(), Some("?1"));
}

fn h3_connect_udp_fixture_config(fixture: &MasqueH3ConnectUdpFixture) -> MasqueConfig {
    MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: Some(fixture.proxy_address()),
        tcp_protocol: MasqueTcpProtocol::Http3,
        use_http2_fallback: false,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: Some(fixture.certificate_pem().to_string()),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: send, echo validation, evidence emission, and joined fixture
/// shutdown form one transaction that must be driven to completion.
#[tokio::test]
async fn h3_connect_udp_honors_root_certificate_and_echoes_context_zero_datagrams() {
    let fixture = MasqueH3ConnectUdpFixture::start().expect("start H3 CONNECT-UDP fixture");
    let client = MasqueClient::new(h3_connect_udp_fixture_config(&fixture)).expect("client");
    let mut udp = client.udp_session();

    udp.send_to(fixture.udp_target(), b"h3-pinned-root").await.expect("send H3 DATAGRAM");
    let received = tokio::time::timeout(std::time::Duration::from_secs(10), udp.recv_from()).await;
    let (target, payload) = received
        .unwrap_or_else(|error| {
            panic!(
                "H3 echo timeout: {error}; fixture_echoes={} observed={:?}",
                fixture.echoed_datagram_count(),
                (fixture.observed_requests(), udp.quic_path_snapshot(fixture.udp_target())),
            )
        })
        .expect("receive H3 echo");

    assert_eq!(target, fixture.udp_target());
    assert_eq!(payload, b"h3-pinned-root");
    assert_eq!(fixture.echoed_datagram_count(), 1);
    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert!(observed[0].accepted);
    assert_eq!(observed[0].protocol.as_deref(), Some("connect-udp"));
    assert_eq!(observed[0].capsule_protocol.as_deref(), Some("?1"));
    let snapshot = udp.quic_path_snapshot(fixture.udp_target()).expect("H3 QUIC path measurement");
    let payload = b"h3-pinned-root";
    let payload_sha256 = sha256_hex(payload);
    assert_eq!(payload.len(), 14);
    assert_eq!(payload_sha256, "ab312dcb9c2b8ffca5aed6e8b80469592c3183408403752f8e8450b3c065cbdd");
    emit_pmtud_measurement(serde_json::json!({
        "blackHoleDelta": 0,
        "caseId": "masque_h3_datagram_payload",
        "highMtu": snapshot.current_mtu,
        "integrity": true,
        "oversizedDropDelta": 0,
        "payloadLength": payload.len(),
        "payloadSha256": payload_sha256,
        "postCliffMtu": null,
        "preMtu": snapshot.current_mtu,
        "targetFamily": "ipv4",
        "version": "pmtud_measurement_v1",
    }));
    drop(udp);
    drop(client);
    fixture.shutdown().await;
}

/// # Cancel safety
///
/// NOT cancel-safe: boundary discovery, post-error liveness, evidence emission,
/// and joined fixture shutdown form one transaction.
#[tokio::test]
async fn h3_connect_udp_boundary_rejects_one_byte_over_limit_without_closing_flow() {
    let fixture = MasqueH3ConnectUdpFixture::start().expect("start H3 CONNECT-UDP fixture");
    let client = MasqueClient::new(h3_connect_udp_fixture_config(&fixture)).expect("client");
    let mut udp = client.udp_session();
    udp.send_to(fixture.udp_target(), b"open").await.expect("open H3 flow");
    let _ = tokio::time::timeout(std::time::Duration::from_secs(10), udp.recv_from())
        .await
        .expect("open echo timeout")
        .expect("open echo");
    let pre_mtu = udp.quic_path_snapshot(fixture.udp_target()).expect("initial QUIC path").current_mtu;

    let discovery_deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        let snapshot = udp.quic_path_snapshot(fixture.udp_target()).expect("active QUIC path");
        if snapshot.current_mtu == crate::h3::H3_MTU_DISCOVERY_UPPER_BOUND {
            break;
        }
        assert!(tokio::time::Instant::now() < discovery_deadline, "PMTUD did not converge: {snapshot:?}");
        udp.send_to(fixture.udp_target(), b"pmtud-probe").await.expect("drive PMTUD");
        let _ = tokio::time::timeout(std::time::Duration::from_secs(1), udp.recv_from())
            .await
            .expect("PMTUD probe echo timeout")
            .expect("PMTUD probe echo");
        tokio::task::yield_now().await;
    }

    let max_datagram = udp
        .quic_path_snapshot(fixture.udp_target())
        .and_then(|snapshot| snapshot.max_datagram_size)
        .expect("negotiated QUIC DATAGRAM limit");
    let boundary = vec![0xA5; max_datagram.saturating_sub(2)];
    udp.send_to(fixture.udp_target(), &boundary).await.expect("boundary H3 DATAGRAM");
    let (_, echoed_boundary) = tokio::time::timeout(std::time::Duration::from_secs(10), udp.recv_from())
        .await
        .expect("boundary echo timeout")
        .expect("boundary echo");
    assert_eq!(echoed_boundary, boundary);

    let oversized = vec![0x5A; boundary.len() + 1];
    let error = udp.send_to(fixture.udp_target(), &oversized).await.expect_err("one byte over limit must fail");
    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    assert!(error.to_string().contains("masque_h3_datagram_too_large"));

    udp.send_to(fixture.udp_target(), b"still-open").await.expect("flow remains open");
    let (_, final_payload) = tokio::time::timeout(std::time::Duration::from_secs(10), udp.recv_from())
        .await
        .expect("final echo timeout")
        .expect("final echo");
    assert_eq!(final_payload, b"still-open");
    let high_mtu = udp.quic_path_snapshot(fixture.udp_target()).expect("final QUIC path").current_mtu;
    let payload_sha256 = sha256_hex(&boundary);
    assert!(pre_mtu >= 1_200 && high_mtu >= 1_400 && high_mtu >= pre_mtu);
    assert_eq!(payload_sha256.len(), 64);
    emit_pmtud_measurement(serde_json::json!({
        "blackHoleDelta": 0,
        "caseId": "masque_h3_datagram_boundary",
        "highMtu": high_mtu,
        "integrity": true,
        "oversizedDropDelta": 1,
        "payloadLength": boundary.len(),
        "payloadSha256": payload_sha256,
        "postCliffMtu": null,
        "preMtu": pre_mtu,
        "targetFamily": "ipv4",
        "version": "pmtud_measurement_v1",
    }));
    drop(udp);
    drop(client);
    fixture.shutdown().await;
}

/// Integration coverage for the provider-adapter decoupling: the auth header the
/// `MasqueProviderAdapter` selects for a config is exactly what lands on the wire
/// of a relayed CONNECT-UDP request against a conformant RFC 9298 proxy — for
/// both HTTP-layer auth modes (`Bearer` -> `authorization`, `Preshared` ->
/// `proxy-authorization`). Closes the integration criterion on
/// `extract-masque-provider-adapter-trait-to-decouple-cloudflare` for the
/// HTTP-auth path; Privacy Pass retry and TLS client-certificate setup are
/// covered by their own component tests (`privacy_pass_provider_fetch_caches_spare_headers`,
/// `provider_adapter::tests`).
#[tokio::test]
async fn adapter_selected_http_auth_lands_on_connect_udp_request() {
    use crate::provider_adapter::adapter_for_config;

    for (auth_mode, token) in [("bearer", "bearer-secret"), ("preshared", "preshared-secret")] {
        let fixture = MasqueH2ConnectUdpFixture::start_refusing_quic().await.expect("start MASQUE fixture");
        let config = MasqueConfig {
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            url: fixture.masque_url(),
            proxy_socket_addr: None,
            tcp_protocol: MasqueTcpProtocol::Http2,
            use_http2_fallback: true,
            auth_mode: Some(auth_mode.to_string()),
            auth_token: Some(token.to_string()),
            client_certificate_chain_pem: None,
            client_private_key_pem: None,
            cloudflare_geohash_header: None,
            privacy_pass_provider_url: None,
            privacy_pass_provider_auth_token: None,
            tls_fingerprint_profile: "native_default".to_string(),
            root_certificate_pem: None,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            ech_config: None,
        };

        // What the decoupled adapter says it will apply for this config.
        let expected = adapter_for_config(&config)
            .auth_header(&config)
            .expect("adapter auth header")
            .expect("http auth mode yields a header");

        let client = MasqueClient::new(config.clone()).expect("client");
        let mut udp = client.udp_session();
        tokio::time::timeout(
            std::time::Duration::from_secs(5),
            udp.send_to(&fixture.udp_echo_target(), b"masque-adapter-auth"),
        )
        .await
        .expect("QUIC refusal must reach HTTP/2 without an idle timeout")
        .expect("send via MASQUE");
        let (target, payload) = tokio::time::timeout(std::time::Duration::from_secs(10), udp.recv_from())
            .await
            .expect("receive timeout")
            .expect("receive via MASQUE");
        assert_eq!(target, fixture.udp_echo_target());
        assert_eq!(payload, b"masque-adapter-auth");

        let observed = fixture.observed_requests();
        assert_eq!(observed.len(), 1, "{auth_mode}: exactly one CONNECT-UDP request expected");
        let on_wire = match expected.name {
            "authorization" => observed[0].authorization.as_deref(),
            "proxy-authorization" => observed[0].proxy_authorization.as_deref(),
            other => panic!("unexpected adapter header name {other}"),
        };
        assert_eq!(
            on_wire,
            Some(expected.value.as_str()),
            "{auth_mode}: adapter-selected auth header not applied on the CONNECT-UDP request",
        );
    }
}

#[tokio::test]
async fn connect_over_h2_transport_tunnels_tcp_to_target() {
    let fixture = MasqueH2ConnectUdpFixture::start().await.expect("start MASQUE fixture");
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };
    let proxy_origin = parse_proxy_origin(&config).expect("proxy origin");
    let transport =
        tokio::net::TcpStream::connect(resolve_proxy_socket_addr(&config, &proxy_origin).await.expect("proxy addr"))
            .await
            .expect("connect proxy transport");

    let mut stream = MasqueClient::connect_over(&config, transport, &fixture.tcp_echo_target())
        .await
        .expect("connect over existing transport");

    stream.write_all(b"chain-ping").await.expect("write chained stream");
    let mut reply = [0u8; 10];
    stream.read_exact(&mut reply).await.expect("read chained stream");

    assert_eq!(&reply, b"chain-ping");
    let observed = fixture.observed_requests();
    assert_eq!(observed[0].method, "CONNECT");
    assert_eq!(observed[0].path, "");
    assert_eq!(observed[0].protocol, None);
    assert_eq!(observed[0].target.as_deref(), Some(fixture.tcp_echo_target().as_str()));
}

#[tokio::test]
async fn h2_tcp_selection_uses_classic_connect_without_h3_fallback() {
    let fixture = MasqueH2ConnectUdpFixture::start().await.expect("start MASQUE fixture");
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: false,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: Some(fixture.certificate_pem().to_string()),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };

    let client = MasqueClient::new(config).expect("client");
    let mut stream = client.connect_tcp(&fixture.tcp_echo_target()).await.expect("connect TCP through H2");
    stream.write_all(b"classic-connect").await.expect("write H2 tunnel");
    let mut reply = [0_u8; 15];
    stream.read_exact(&mut reply).await.expect("read H2 tunnel");

    assert_eq!(&reply, b"classic-connect");
    assert_eq!(
        client.quic_migration_snapshot(),
        (Some("http2_selected".to_string()), Some("rfc9113_classic_connect".to_string()))
    );
    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert_eq!(observed[0].method, "CONNECT");
    assert_eq!(observed[0].path, "");
    assert_eq!(observed[0].protocol, None);
    assert_eq!(observed[0].target.as_deref(), Some(fixture.tcp_echo_target().as_str()));
}

#[tokio::test]
async fn h3_tcp_selection_fails_unsupported_before_quic_dial() {
    let fixture = MasqueH3ClassicConnectFixture::start().await.expect("start H3 classic CONNECT fixture");
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: Some(fixture.proxy_address()),
        tcp_protocol: MasqueTcpProtocol::Http3,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };

    let error = MasqueClient::connect(&config, fixture.tcp_target())
        .await
        .err()
        .expect("H3 TCP must fail until classic CONNECT can be encoded");

    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    assert!(error.to_string().contains("masque_h3_tcp_unsupported"));
    assert_eq!(fixture.accepted_connection_count(), 0, "unsupported H3 TCP must fail before QUIC network I/O");
    assert!(fixture.observed_requests().is_empty());
}

#[tokio::test]
async fn h3_classic_connect_fixture_rejects_scheme_and_path_from_pinned_encoder() {
    let fixture = MasqueH3ClassicConnectFixture::start().await.expect("start H3 classic CONNECT fixture");
    let mut endpoint =
        quinn::Endpoint::client("127.0.0.1:0".parse().expect("client address")).expect("create QUIC client endpoint");
    endpoint.set_default_client_config(fixture.client_config().expect("fixture client config"));
    let connection = endpoint
        .connect(fixture.proxy_address(), "localhost")
        .expect("start QUIC connect")
        .await
        .expect("QUIC handshake");
    let (mut driver, mut sender) = ::h3::client::new(h3_quinn::Connection::new(connection)).await.expect("H3 client");
    let request = Request::builder()
        .method("CONNECT")
        .uri(format!("https://{}/", fixture.tcp_target()))
        .body(())
        .expect("request representable by h3 0.0.8");
    let mut stream = sender.send_request(request).await.expect("send nonconforming H3 request");
    let response = tokio::time::timeout(std::time::Duration::from_secs(3), stream.recv_response())
        .await
        .expect("H3 response timeout")
        .expect("H3 response");

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert_eq!(observed[0].method, "CONNECT");
    assert_eq!(observed[0].scheme.as_deref(), Some("https"));
    assert_eq!(observed[0].path_and_query.as_deref(), Some("/"));
    assert_eq!(observed[0].protocol, None);
    assert!(!observed[0].accepted);

    endpoint.close(0_u32.into(), b"test complete");
    let _ = std::future::poll_fn(|cx| driver.poll_close(cx)).await;
}

/// `root_certificate_pem` PINS the proxy's self-signed cert as a trust anchor
/// with TLS verification left ON. Setting it suppresses the cfg(test)
/// loopback-verification relax (see `h2.rs`), so this exercises the real
/// pin-and-verify path: the handshake succeeds only because the fixture's cert
/// is trusted AND its `127.0.0.1` SAN matches the connect authority.
#[tokio::test]
async fn connect_over_h2_with_pinned_root_certificate_verifies_and_tunnels() {
    let fixture = MasqueH2ConnectUdpFixture::start().await.expect("start MASQUE fixture");
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: Some(fixture.certificate_pem().to_string()),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };
    let proxy_origin = parse_proxy_origin(&config).expect("proxy origin");
    let transport =
        tokio::net::TcpStream::connect(resolve_proxy_socket_addr(&config, &proxy_origin).await.expect("proxy addr"))
            .await
            .expect("connect proxy transport");

    let mut stream = MasqueClient::connect_over(&config, transport, &fixture.tcp_echo_target())
        .await
        .expect("pinned-cert handshake must verify and tunnel");

    stream.write_all(b"pinned-ping").await.expect("write pinned stream");
    let mut reply = [0u8; 11];
    stream.read_exact(&mut reply).await.expect("read pinned stream");
    assert_eq!(&reply, b"pinned-ping");
}

/// A wrong/unrelated pinned trust anchor must FAIL the handshake — proves the
/// pin path actually verifies rather than silently trusting anything.
#[tokio::test]
async fn connect_over_h2_with_unrelated_root_certificate_fails_verification() {
    let fixture = MasqueH2ConnectUdpFixture::start().await.expect("start MASQUE fixture");
    // A syntactically valid but unrelated self-signed cert: a second fixture's.
    let other = MasqueH2ConnectUdpFixture::start().await.expect("start second MASQUE fixture");
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: Some(other.certificate_pem().to_string()),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };
    let proxy_origin = parse_proxy_origin(&config).expect("proxy origin");
    let transport =
        tokio::net::TcpStream::connect(resolve_proxy_socket_addr(&config, &proxy_origin).await.expect("proxy addr"))
            .await
            .expect("connect proxy transport");

    let result = MasqueClient::connect_over(&config, transport, &fixture.tcp_echo_target()).await;
    assert!(result.is_err(), "pinning an unrelated cert must fail TLS verification, not succeed");
}

/// Self-signed `localhost` certificate (DER, PKCS#8 key DER, PEM) for an
/// in-test H3 proxy.
fn localhost_certificate() -> (Vec<u8>, Vec<u8>, String) {
    use boring::asn1::Asn1Time;
    use boring::bn::BigNum;
    use boring::ec::{EcGroup, EcKey};
    use boring::hash::MessageDigest;
    use boring::nid::Nid;
    use boring::pkey::PKey;
    use boring::x509::extension::SubjectAlternativeName;
    use boring::x509::{X509, X509NameBuilder};

    let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1).expect("P-256 group");
    let key = PKey::from_ec_key(EcKey::generate(&group).expect("EC key")).expect("PKey");
    let mut name = X509NameBuilder::new().expect("name builder");
    name.append_entry_by_nid(Nid::COMMONNAME, "localhost").expect("common name");
    let name = name.build();
    let mut builder = X509::builder().expect("X509 builder");
    builder.set_version(2).expect("version");
    builder.set_serial_number(&BigNum::from_u32(1).expect("serial").to_asn1_integer().expect("asn1")).expect("serial");
    builder.set_subject_name(&name).expect("subject");
    builder.set_issuer_name(&name).expect("issuer");
    builder.set_pubkey(&key).expect("public key");
    builder.set_not_before(&Asn1Time::days_from_now(0).expect("now")).expect("not before");
    builder.set_not_after(&Asn1Time::days_from_now(1).expect("tomorrow")).expect("not after");
    let san = SubjectAlternativeName::new().dns("localhost").build(&builder.x509v3_context(None, None)).expect("SAN");
    builder.append_extension(&san).expect("SAN extension");
    builder.sign(&key, MessageDigest::sha256()).expect("sign");
    let certificate = builder.build();
    let pem = String::from_utf8(certificate.to_pem().expect("PEM")).expect("PEM text");
    (certificate.to_der().expect("DER"), key.private_key_to_der_pkcs8().expect("PKCS#8"), pem)
}

/// RFC 9114 4.4 keeps a CONNECT request stream open, and RFC 9298 3.1 ties
/// the UDP socket to it. masque-go's proxy closes the socket as soon as the
/// request stream reaches EOF, so a client FIN black-holes every datagram.
#[tokio::test]
async fn h3_connect_udp_keeps_request_stream_open_after_the_response() {
    let (certificate_der, key_der, certificate_pem) = localhost_certificate();
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("TLS versions")
        .with_no_client_auth()
        .with_single_cert(
            vec![rustls::pki_types::CertificateDer::from(certificate_der)],
            rustls::pki_types::PrivatePkcs8KeyDer::from(key_der).into(),
        )
        .expect("server certificate");
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let quic = quinn::crypto::rustls::QuicServerConfig::try_from(tls).expect("QUIC server config");
    let endpoint = quinn::Endpoint::server(
        quinn::ServerConfig::with_crypto(Arc::new(quic)),
        "127.0.0.1:0".parse().expect("server address"),
    )
    .expect("server endpoint");
    let proxy_address = endpoint.local_addr().expect("proxy address");
    let server = tokio::spawn(async move {
        let connection = endpoint.accept().await.expect("incoming").await.expect("QUIC handshake");
        let mut builder = ::h3::server::builder();
        builder.enable_extended_connect(true);
        builder.enable_datagram(true);
        let mut h3_connection: ::h3::server::Connection<h3_quinn::Connection, Bytes> =
            builder.build(h3_quinn::Connection::new(connection)).await.expect("H3 server");
        let resolver = h3_connection.accept().await.expect("accept").expect("request");
        let (_request, mut stream) = resolver.resolve_request().await.expect("resolve request");
        let response = http::Response::builder()
            .status(StatusCode::OK)
            .header("capsule-protocol", "?1")
            .body(())
            .expect("response");
        stream.send_response(response).await.expect("send response");
        let request_body = tokio::time::timeout(std::time::Duration::from_millis(800), stream.recv_data()).await;
        assert!(
            request_body.is_err(),
            "the client must not close the CONNECT-UDP request stream: {:?}",
            request_body.map(|data| data.map(|chunk| chunk.is_some()))
        );
    });

    let mut config = h3_connect_udp_proxy_config(proxy_address, certificate_pem);
    config.url = format!("https://localhost:{}/", proxy_address.port());
    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();
    udp.send_to("127.0.0.1:9", b"open").await.expect("open H3 CONNECT-UDP flow");
    server.await.expect("server task");
    drop(udp);
}

/// RFC 9298 3.1: the proxy closes the request stream when it closes the UDP
/// socket (for example after ICMP unreachable). Datagrams sent after that are
/// dropped, so the client must open a new tunnel instead of reusing the flow.
#[tokio::test]
async fn h3_connect_udp_reopens_the_flow_after_the_proxy_closes_the_stream() {
    let (certificate_der, key_der, certificate_pem) = localhost_certificate();
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("TLS versions")
        .with_no_client_auth()
        .with_single_cert(
            vec![rustls::pki_types::CertificateDer::from(certificate_der)],
            rustls::pki_types::PrivatePkcs8KeyDer::from(key_der).into(),
        )
        .expect("server certificate");
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let quic = quinn::crypto::rustls::QuicServerConfig::try_from(tls).expect("QUIC server config");
    let endpoint = quinn::Endpoint::server(
        quinn::ServerConfig::with_crypto(Arc::new(quic)),
        "127.0.0.1:0".parse().expect("server address"),
    )
    .expect("server endpoint");
    let proxy_address = endpoint.local_addr().expect("proxy address");
    let (closed_tx, closed_rx) = tokio::sync::oneshot::channel();
    let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();
    let server = tokio::spawn(async move {
        let mut closed_tx = Some(closed_tx);
        let mut connections = Vec::new();
        for _ in 0..2 {
            let incoming = tokio::time::timeout(std::time::Duration::from_secs(5), endpoint.accept())
                .await
                .expect("the client opens a tunnel")
                .expect("incoming");
            let connection = incoming.await.expect("QUIC handshake");
            let mut builder = ::h3::server::builder();
            builder.enable_extended_connect(true);
            builder.enable_datagram(true);
            let mut h3_connection: ::h3::server::Connection<h3_quinn::Connection, Bytes> =
                builder.build(h3_quinn::Connection::new(connection)).await.expect("H3 server");
            let resolver = h3_connection.accept().await.expect("accept").expect("request");
            let (_request, mut stream) = resolver.resolve_request().await.expect("resolve request");
            let response = http::Response::builder()
                .status(StatusCode::OK)
                .header("capsule-protocol", "?1")
                .body(())
                .expect("response");
            stream.send_response(response).await.expect("send response");
            if let Some(closed_tx) = closed_tx.take() {
                stream.finish().await.expect("close the tunnel");
                let _ = closed_tx.send(());
            }
            connections.push((h3_connection, stream));
        }
        // Keep both tunnels up until the client has its second response.
        let _ = done_rx.await;
    });

    let mut config = h3_connect_udp_proxy_config(proxy_address, certificate_pem);
    config.url = format!("https://localhost:{}/", proxy_address.port());
    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();
    udp.send_to("127.0.0.1:9", b"open").await.expect("open the first tunnel");
    closed_rx.await.expect("proxy closed the first tunnel");
    // Let the reader task see the FIN.
    tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    udp.send_to("127.0.0.1:9", b"again").await.expect("open the second tunnel");
    let _ = done_tx.send(());

    server.await.expect("the proxy saw a second CONNECT-UDP request");
    drop(udp);
}

fn h3_connect_udp_proxy_config(proxy_address: SocketAddr, certificate_pem: String) -> MasqueConfig {
    MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: String::new(),
        proxy_socket_addr: Some(proxy_address),
        tcp_protocol: MasqueTcpProtocol::Http3,
        use_http2_fallback: false,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: Some(certificate_pem),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    }
}

/// A proxy address that accepts TCP and binds UDP but never answers.
fn silent_proxy() -> (std::net::TcpListener, std::net::UdpSocket, SocketAddr) {
    let tcp = std::net::TcpListener::bind("127.0.0.1:0").expect("silent TCP proxy");
    let address = tcp.local_addr().expect("silent proxy address");
    let udp = std::net::UdpSocket::bind(address).expect("silent UDP proxy");
    (tcp, udp, address)
}

#[tokio::test(start_paused = true)]
async fn h2_connect_to_a_silent_proxy_times_out() {
    let (_tcp, _udp, address) = silent_proxy();
    let mut config = h3_connect_udp_proxy_config(address, String::new());
    config.tcp_protocol = MasqueTcpProtocol::Http2;
    config.root_certificate_pem = None;
    config.url = format!("https://localhost:{}/", address.port());
    let client = MasqueClient::new(config).expect("client");

    let started = tokio::time::Instant::now();
    let error = client.connect_tcp("example.com:443").await.err().expect("silent proxy must fail");

    assert_eq!(error.kind(), io::ErrorKind::TimedOut, "error: {error}");
    assert_eq!(started.elapsed(), crate::response::CONNECT_ATTEMPT_TIMEOUT);
}

#[tokio::test(start_paused = true)]
async fn udp_flow_open_to_a_silent_proxy_times_out_on_h3_and_h2() {
    let (_tcp, _udp, address) = silent_proxy();
    let mut config = h3_connect_udp_proxy_config(address, String::new());
    config.use_http2_fallback = true;
    config.root_certificate_pem = None;
    config.url = format!("https://localhost:{}/", address.port());
    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();

    let started = tokio::time::Instant::now();
    let error = udp.send_to("127.0.0.1:9", b"open").await.expect_err("silent proxy must fail");

    assert_eq!(error.kind(), io::ErrorKind::TimedOut, "error: {error}");
    assert_eq!(started.elapsed(), crate::response::CONNECT_ATTEMPT_TIMEOUT * 2);
}

#[tokio::test]
async fn new_udp_flows_go_straight_to_http2_after_a_recent_h3_failure() {
    // The fixture never answers QUIC, so the first flow waits out the H3 bound.
    let fixture = MasqueH2ConnectUdpFixture::start().await.expect("start MASQUE fixture");
    let mut config = h3_connect_udp_proxy_config("127.0.0.1:0".parse().expect("address"), String::new());
    config.url = fixture.masque_url();
    config.proxy_socket_addr = None;
    config.root_certificate_pem = None;
    config.use_http2_fallback = true;
    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();
    udp.send_to(&fixture.udp_echo_target(), b"first").await.expect("first flow falls back to HTTP/2");

    let started = std::time::Instant::now();
    udp.send_to("127.0.0.1:9", b"second").await.expect("second flow opens over HTTP/2");

    assert!(
        started.elapsed() < crate::response::CONNECT_ATTEMPT_TIMEOUT / 2,
        "second flow retried HTTP/3 first: {:?}",
        started.elapsed()
    );
    assert_eq!(fixture.observed_requests().len(), 2);
}

fn keep_alive_pings(udp: &MasqueUdpRelay, target: &str) -> u64 {
    let connection = udp.flows.get(target).and_then(|flow| flow.path_connection.as_ref()).expect("H3 flow");
    let stats = connection.stats();
    // PMTUD probes are PING frames too; count only the other PINGs.
    stats.frame_tx.ping - stats.path.sent_plpmtud_probes
}

#[tokio::test]
async fn idle_h3_udp_flow_sends_quic_keep_alives() {
    let fixture = MasqueH3ConnectUdpFixture::start().expect("start H3 CONNECT-UDP fixture");
    let client = MasqueClient::new(h3_connect_udp_fixture_config(&fixture)).expect("client");
    let mut udp = client.udp_session();
    udp.send_to(fixture.udp_target(), b"open").await.expect("send H3 DATAGRAM");
    tokio::time::sleep(std::time::Duration::from_secs(1)).await;
    let before = keep_alive_pings(&udp, fixture.udp_target());

    // Well inside quinn's 30 s idle timeout, so the PING must come from keep-alive.
    tokio::time::sleep(std::time::Duration::from_secs(12)).await;

    assert!(keep_alive_pings(&udp, fixture.udp_target()) > before, "idle QUIC connection sent no keep-alive");
    drop(udp);
    drop(client);
    fixture.shutdown().await;
}

#[tokio::test]
async fn udp_send_reopens_a_flow_whose_carrier_died() {
    let fixture = MasqueH2ConnectUdpFixture::start_refusing_quic().await.expect("start MASQUE fixture");
    let mut config = h3_connect_udp_proxy_config("127.0.0.1:0".parse().expect("address"), String::new());
    config.url = fixture.masque_url();
    config.proxy_socket_addr = None;
    config.root_certificate_pem = None;
    config.use_http2_fallback = true;
    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();
    let target = fixture.udp_echo_target();
    // A flow whose H2 stream is gone but whose reader has not noticed yet.
    let (sender, receiver) = tokio::sync::mpsc::channel(1);
    drop(receiver);
    let dead = crate::udp::MasqueUdpFlow::new(
        crate::udp::MasqueUdpSender::H2(sender),
        None,
        tokio::spawn(std::future::pending()),
        tokio::spawn(std::future::pending()),
        None,
        0,
    );
    udp.flows.insert(target.clone(), dead);

    udp.send_to(&target, b"after-reopen").await.expect("send reopens the flow");
    let (from, payload) = tokio::time::timeout(std::time::Duration::from_secs(10), udp.recv_from())
        .await
        .expect("receive timeout")
        .expect("receive via the reopened flow");

    assert_eq!(from, target);
    assert_eq!(payload, b"after-reopen");
    assert_eq!(fixture.observed_requests().len(), 1);
}

#[tokio::test]
async fn receiving_on_a_flow_keeps_it_out_of_idle_eviction() {
    let mut config = h3_connect_udp_proxy_config("127.0.0.1:9".parse().expect("address"), String::new());
    config.url = "https://masque.example/".to_string();
    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();
    let (sender, _receiver) = tokio::sync::mpsc::channel(1);
    let mut flow = crate::udp::MasqueUdpFlow::new(
        crate::udp::MasqueUdpSender::H2(sender),
        None,
        tokio::spawn(std::future::pending()),
        tokio::spawn(std::future::pending()),
        None,
        0,
    );
    let long_ago = std::time::Instant::now() - std::time::Duration::from_secs(600);
    flow.last_used = long_ago;
    udp.flows.insert("receiver:53".to_string(), flow);
    udp.incoming_tx.send(("receiver:53".to_string(), b"reply".to_vec())).await.expect("queue reply");

    udp.recv_from().await.expect("receive");

    assert!(udp.flows["receiver:53"].last_used > long_ago);
}
