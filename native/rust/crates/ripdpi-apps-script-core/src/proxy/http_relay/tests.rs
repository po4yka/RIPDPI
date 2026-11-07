use super::*;
use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};

/// # Cancel safety:
/// cancel-safe: the test owns both endpoints and the server future.
async fn local_response(request: &str, host: &str, port: u16, scheme: &str) -> String {
    let config =
        crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).expect("config");
    let telemetry = Arc::new(crate::telemetry::RelayTelemetryState::new(&config));
    let relay = AppsScriptDomainFronter::new(&config, telemetry.clone());
    let (mut client, server) = duplex(4096);
    let request = request.replacen("HTTP/1.1\r\n", "HTTP/1.1\r\nConnection: close\r\n", 1);
    let client_job = async {
        client.write_all(request.as_bytes()).await.expect("request");
        let mut response = String::new();
        client.read_to_string(&mut response).await.expect("response");
        response
    };
    let (response, result) = tokio::join!(client_job, serve(server, host, port, scheme, &relay, &telemetry));
    result.expect("serve");
    response
}

#[tokio::test(flavor = "current_thread")]
/// # Cancel safety:
/// cancel-safe: the helper drops both transport ends with its server future.
async fn cross_site_preflight_is_not_approved_with_credentials() {
    let response = local_response(
        "OPTIONS /x HTTP/1.1\r\nOrigin: https://evil.example\r\naccess-control-request-Method: PUT\r\n\r\n",
        "api.victim.example",
        443,
        "https",
    )
    .await;
    assert!(response.starts_with("HTTP/1.1 204 "), "{response}");
    assert!(response.contains("access-control-allow-origin: https://evil.example\r\n"), "{response}");
    assert!(!response.contains("access-control-allow-credentials"), "{response}");
    assert!(!response.contains("content-length"), "RFC 9110 8.6: no content-length on 204: {response}");
}

#[tokio::test(flavor = "current_thread")]
/// # Cancel safety:
/// cancel-safe: the helper drops both transport ends with its server future.
async fn same_site_preflight_keeps_credentials() {
    let response = local_response(
        "OPTIONS /x HTTP/1.1\r\nOrigin: https://www.example.co.uk\r\naccess-control-request-Method: PUT\r\n\r\n",
        "api.example.co.uk",
        443,
        "https",
    )
    .await;
    assert!(response.contains("access-control-allow-credentials: true\r\n"), "{response}");

    let other_site = local_response(
        "OPTIONS /x HTTP/1.1\r\nOrigin: https://evil.co.uk\r\naccess-control-request-Method: PUT\r\n\r\n",
        "victim.co.uk",
        443,
        "https",
    )
    .await;
    assert!(!other_site.contains("access-control-allow-credentials"), "{other_site}");
}

/// # Cancel safety:
/// cancel-safe: each helper call owns and drops its connection.
#[tokio::test(flavor = "current_thread")]
async fn private_suffix_tenants_do_not_share_credentials() {
    for (origin, target) in [("alice.github.io", "bob.github.io"), ("alice.fly.dev", "bob.fly.dev")] {
        let request =
            format!("OPTIONS /x HTTP/1.1\r\nOrigin: https://{origin}\r\nAccess-Control-Request-Method: GET\r\n\r\n");
        let response = local_response(&request, target, 443, "https").await;
        assert!(response.starts_with("HTTP/1.1 204 "), "{response}");
        assert!(!response.contains("access-control-allow-credentials"), "{response}");
    }
}

#[test]
fn short_registrable_domains_under_two_letter_tlds_stay_one_site() {
    for (origin, target) in [
        ("music.ya.ru", "api.ya.ru"),
        ("m.ok.ru", "api.ok.ru"),
        ("www.vc.ru", "api.vc.ru"),
        ("www.gmx.de", "api.gmx.de"),
        ("console.x.ai", "api.x.ai"),
        ("app.fly.io", "api.fly.io"),
        ("www.example.co.uk", "api.example.co.uk"),
        ("www.city.go.jp", "api.city.go.jp"),
    ] {
        assert_eq!(site_key(origin), site_key(target), "{origin} and {target} share a site");
    }
    for (origin, target) in
        [("a.co.uk", "b.co.uk"), ("a.com.au", "b.com.au"), ("a.ne.jp", "b.ne.jp"), ("a.gob.mx", "b.gob.mx")]
    {
        assert_ne!(site_key(origin), site_key(target), "{origin} and {target} are different sites");
    }
}

#[tokio::test(flavor = "current_thread")]
/// # Cancel safety:
/// cancel-safe: the helper drops both transport ends with its server future.
async fn options_without_preflight_headers_is_not_faked() {
    for headers in
        ["Host: dav.example\r\n", "Origin: https://dav.example\r\n", "Access-Control-Request-Method: GET\r\n"]
    {
        let request = format!("OPTIONS * HTTP/1.1\r\n{headers}\r\n");
        let response = local_response(&request, "dav.example", 443, "https").await;
        assert!(response.starts_with("HTTP/1.1 501 "), "{response}");
        assert!(!response.contains("access-control-"), "{response}");
    }
}

#[test]
fn ipv6_targets_are_bracketed_in_the_relayed_url() {
    assert_eq!(request_url("http", "2001:db8::1", 80, "/a"), "http://[2001:db8::1]/a");
    assert_eq!(request_url("https", "2001:db8::1", 8443, "/a"), "https://[2001:db8::1]:8443/a");
    assert_eq!(request_url("https", "example.com", 443, "/a"), "https://example.com/a");
    assert_eq!(request_url("http", "192.0.2.1", 8080, "/a"), "http://192.0.2.1:8080/a");
}

/// # Cancel safety:
/// cancel-safe: both endpoints and the server are scoped to the test.
#[tokio::test]
async fn ambiguous_request_is_normalized_and_closes_before_pipeline() {
    let config =
        crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).unwrap();
    let telemetry = Arc::new(crate::telemetry::RelayTelemetryState::new(&config));
    let relay = AppsScriptDomainFronter::new(&config, telemetry.clone());
    let (mut client, server) = duplex(4096);
    let client_job = async {
        client.write_all(b"OPTIONS /one HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\nContent-Length: 999\r\nOrigin: https://local\r\nAccess-Control-Request-Method: GET\r\n\r\n3\r\nabc\r\n0\r\n\r\nOPTIONS /two HTTP/1.1\r\nHost: local\r\n\r\n").await.unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).await.unwrap();
        response
    };
    let (response, result) = tokio::join!(client_job, serve(server, "local", 80, "http", &relay, &telemetry));
    result.unwrap();
    assert_eq!(response.matches("HTTP/1.1").count(), 1, "{response}");
    assert!(response.starts_with("HTTP/1.1 204 "), "{response}");
    assert!(response.contains("connection: close"), "{response}");
}

/// # Cancel safety:
/// cancel-safe: all transport state and futures are scoped to this helper.
async fn wire_exchange(wire: &[u8]) -> (String, io::Result<()>) {
    let config =
        crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).unwrap();
    let telemetry = Arc::new(crate::telemetry::RelayTelemetryState::new(&config));
    let relay = AppsScriptDomainFronter::new(&config, telemetry.clone());
    let (mut client, server) = duplex(4096);
    let client_job = async {
        client.write_all(wire).await.unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).await.unwrap();
        response
    };
    tokio::time::timeout(Duration::from_secs(3), async {
        tokio::join!(client_job, serve(server, "local", 80, "http", &relay, &telemetry))
    })
    .await
    .expect("connection ends")
}

/// # Cancel safety:
/// cancel-safe: every exchange owns its transports.
#[tokio::test]
async fn chunked_extensions_trailers_and_pipeline_keep_boundaries() {
    let (response, result) = wire_exchange(b"OPTIONS /one HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n3;ext=yes\r\nabc\r\n0\r\nX-Trailer: yes\r\n\r\nOPTIONS /two HTTP/1.1\r\nHost: local\r\nConnection: close\r\nContent-Length: 3\r\n\r\nxyz").await;
    result.unwrap();
    assert_eq!(response.matches("HTTP/1.1 501 ").count(), 2, "{response}");
}

/// # Cancel safety:
/// cancel-safe: every exchange owns its transports.
#[tokio::test]
async fn malformed_chunks_and_conflicting_lengths_never_dispatch() {
    for wire in [
        &b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nTransfer-Encoding: gzip, chunked\r\n\r\n1\r\nA\r\n0\r\n\r\n"[..],
        &b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nAX0\r\n\r\n"[..],
        b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nAX",
        b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nContent-Length: 16777217\r\n\r\n",
    ] {
        let (response, result) = wire_exchange(wire).await;
        assert!(result.is_err() || response.starts_with("HTTP/1.1 400 "));
        assert!(!response.contains("501"), "{response}");
    }
}

/// # Cancel safety:
/// cancel-safe: both endpoints and futures are scoped to this test.
#[tokio::test]
async fn expect_continue_precedes_body_and_final_response() {
    let config =
        crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).unwrap();
    let telemetry = Arc::new(crate::telemetry::RelayTelemetryState::new(&config));
    let relay = AppsScriptDomainFronter::new(&config, telemetry.clone());
    let (mut client, server) = duplex(4096);
    let client_job = async {
        client.write_all(b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nExpect: 100-continue\r\nContent-Length: 3\r\n\r\n").await.unwrap();
        let mut interim = [0; 25];
        client.read_exact(&mut interim).await.unwrap();
        assert_eq!(&interim, b"HTTP/1.1 100 Continue\r\n\r\n");
        client.write_all(b"abc").await.unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).await.unwrap();
        assert!(response.starts_with("HTTP/1.1 501 "), "{response}");
    };
    let ((), result) = tokio::join!(client_job, serve(server, "local", 80, "http", &relay, &telemetry));
    result.unwrap();
}

/// # Cancel safety:
/// cancel-safe: each helper call owns and drops its connection.
#[tokio::test(flavor = "current_thread")]
async fn credentials_require_matching_scheme_port_and_approved_site() {
    for (origin, host, port, scheme, allowed) in [
        ("https://www.example.co.uk", "api.example.co.uk", 443, "https", true),
        ("https://api.example.co.uk:443", "api.example.co.uk", 443, "https", true),
        ("https://www.alice.github.io", "api.alice.github.io", 443, "https", true),
        ("https://app.fly.io", "api.fly.io", 443, "https", true),
        ("http://www.example.co.uk", "api.example.co.uk", 443, "https", false),
        ("https://www.example.co.uk:8443", "api.example.co.uk", 443, "https", false),
        ("https://www.example.co.uk:8443", "api.example.co.uk", 8443, "https", true),
        ("http://localhost:8080", "localhost", 8080, "http", true),
        ("http://a.localhost:8080", "b.localhost", 8080, "http", false),
        ("http://a.foo.localhost:8080", "b.foo.localhost", 8080, "http", false),
        ("https://192.0.2.1", "192.0.2.1", 443, "https", true),
        ("https://192.0.2.1", "192.0.2.2", 443, "https", false),
        ("https://[2001:db8::1]:8443", "2001:db8::1", 8443, "https", true),
    ] {
        let request = format!("OPTIONS /x HTTP/1.1\r\nOrigin: {origin}\r\nAccess-Control-Request-Method: GET\r\n\r\n");
        let response = local_response(&request, host, port, scheme).await;
        assert!(response.starts_with("HTTP/1.1 204 "), "{response}");
        assert_eq!(response.contains("access-control-allow-credentials: true\r\n"), allowed, "{origin}: {response}");
        assert!(response.contains(&format!("access-control-allow-origin: {origin}\r\n")), "{response}");
    }
}

/// # Cancel safety:
/// cancel-safe: each helper call owns and drops its connection.
#[tokio::test(flavor = "current_thread")]
async fn malformed_or_multiple_origins_receive_no_cors_headers() {
    for origin in [
        "null",
        concat!("ht", "\t", "tps://example.com"),
        "https:///example.com",
        "https://user@example.com",
        "https://example.com/",
        "https://example.com/path",
        "https://example.com?token=secret",
        "https://example.com#fragment",
        "https://example.com:",
        "https://example.com:invalid",
        "https://example.com:65536",
        "https://example.com https://other.example",
        "https://example.com, https://other.example",
        "file://example.com",
        "https://héllo.example",
    ] {
        let request = format!("OPTIONS /x HTTP/1.1\r\nOrigin: {origin}\r\nAccess-Control-Request-Method: GET\r\n\r\n");
        let response = local_response(&request, "example.com", 443, "https").await;
        assert!(response.starts_with("HTTP/1.1 400 "), "{origin}: {response}");
        assert!(!response.contains("access-control-"), "{response}");
    }
    let response = local_response(
        "OPTIONS /x HTTP/1.1\r\nOrigin: https://example.com\r\nOrigin: https://other.example\r\nAccess-Control-Request-Method: GET\r\n\r\n",
        "example.com", 443, "https",
    ).await;
    assert!(response.starts_with("HTTP/1.1 400 "), "{response}");
    assert!(!response.contains("access-control-"), "{response}");
}

/// # Cancel safety:
/// cancel-safe: the helper owns and drops its connection.
#[tokio::test(flavor = "current_thread")]
async fn credentials_follow_the_absolute_request_target() {
    let response = local_response(
        "OPTIONS https://bob.github.io/x HTTP/1.1\r\nOrigin: https://alice.github.io\r\nAccess-Control-Request-Method: GET\r\n\r\n",
        "alice.github.io", 443, "https",
    ).await;
    assert!(response.starts_with("HTTP/1.1 204 "), "{response}");
    assert!(!response.contains("access-control-allow-credentials"), "{response}");
}
