use std::io;
use std::sync::Arc;

use bytes::Bytes;
use http::{HeaderMap, Request, StatusCode};
use serde_json::to_string;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::Mutex;

use super::*;
use crate::auth::PrivacyPassProviderResponse;
use crate::config::{MasqueAuthMode, MasqueConfig};
use crate::h3::decode_udp_payload;
use crate::request::apply_request_headers;
use crate::response::{validate_proxy_response, AttemptError};
use crate::url::{build_connect_udp_path, parse_proxy_origin, parse_target, ProxyOrigin, TargetAuthority};

fn privacy_pass_test_config(provider_url: String, provider_auth_token: Option<&str>) -> MasqueConfig {
    MasqueConfig {
        url: "https://masque.example/".to_string(),
        use_http2_fallback: false,
        auth_mode: Some("privacy_pass".to_string()),
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: Some(provider_url),
        privacy_pass_provider_auth_token: provider_auth_token.map(ToOwned::to_owned),
        tls_fingerprint_profile: "native_default".to_string(),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
    }
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
            if name.trim().eq_ignore_ascii_case("content-length") {
                value.trim().parse::<usize>().ok()
            } else {
                None
            }
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
            request_uri: "/.well-known/masque/ip".to_string(),
            udp_base_path: "/.well-known/masque".to_string(),
        },
        &TargetAuthority { host: "2001:db8::42".to_string(), port: 443 },
    );

    assert_eq!(path, "/.well-known/masque/udp/2001%3Adb8%3A%3A42/443/");
}

#[test]
fn new_client_starts_with_not_attempted_quic_snapshot() {
    let client = MasqueClient::new(MasqueConfig {
        url: "https://masque.example/".to_string(),
        use_http2_fallback: true,
        auth_mode: Some("bearer".to_string()),
        auth_token: Some("secret".to_string()),
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
    })
    .expect("client");

    assert_eq!((Some("not_attempted".to_string()), None), client.quic_migration_snapshot(),);
}

#[test]
fn parse_proxy_origin_preserves_request_path_and_query() {
    let origin = parse_proxy_origin(&MasqueConfig {
        url: "https://masque.example/.well-known/masque/ip?cf=1".to_string(),
        use_http2_fallback: true,
        auth_mode: Some("bearer".to_string()),
        auth_token: Some("secret".to_string()),
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
    })
    .expect("origin");

    assert_eq!("/.well-known/masque/ip?cf=1", origin.request_uri);
    assert_eq!("/.well-known/masque", origin.udp_base_path);
}

#[test]
fn decode_udp_payload_requires_context_zero() {
    let error = decode_udp_payload(Bytes::from_static(&[1, 2, 3])).expect_err("must reject non-zero context");
    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
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
fn apply_request_headers_adds_geohash_without_auth() {
    let config = MasqueConfig {
        url: "https://masque.example/".to_string(),
        use_http2_fallback: true,
        auth_mode: Some("cloudflare_mtls".to_string()),
        auth_token: None,
        client_certificate_chain_pem: Some("placeholder certificate".to_string()),
        client_private_key_pem: Some("placeholder private key".to_string()),
        cloudflare_geohash_header: Some("u4pruyd-GB".to_string()),
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
    };

    let request = apply_request_headers(Request::builder().method("CONNECT").uri("example.com:443"), &config, None)
        .expect("builder")
        .body(())
        .expect("request");

    assert_eq!(request.headers().get("sec-ch-geohash").unwrap(), "u4pruyd-GB");
    assert!(request.headers().get("authorization").is_none());
    assert!(request.headers().get("proxy-authorization").is_none());
}

#[test]
fn cloudflare_mtls_auth_rejection_does_not_require_privacy_pass_challenge() {
    let error = validate_proxy_response(StatusCode::FORBIDDEN, &HeaderMap::new(), MasqueAuthMode::CloudflareMtls)
        .expect_err("expected rejection");

    let AttemptError::Io(error) = error else {
        panic!("expected io rejection");
    };
    assert_eq!(io::ErrorKind::PermissionDenied, error.kind());
    assert!(error.to_string().contains("client identity"));
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
async fn quic_migration_snapshot_records_http2_fallback_reason() {
    let client = MasqueClient::new(MasqueConfig {
        url: "https://masque.example/".to_string(),
        use_http2_fallback: true,
        auth_mode: Some("bearer".to_string()),
        auth_token: Some("secret".to_string()),
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
    })
    .expect("client");

    client.inner.record_quic_migration_status("http2_fallback", Some("http3_connect_failed_connect")).await;

    assert_eq!(
        (Some("http2_fallback".to_string()), Some("http3_connect_failed_connect".to_string()),),
        client.quic_migration_snapshot(),
    );
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
