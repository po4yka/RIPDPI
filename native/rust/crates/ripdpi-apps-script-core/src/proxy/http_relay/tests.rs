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
    assert!(response.starts_with("HTTP/1.1 501 "), "{response}");
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
/// cancel-safe: each helper owns and drops its full exchange.
#[tokio::test(flavor = "current_thread")]
async fn rly15_preflight_never_grants_origin_permission() {
    for origin in ["https://untrusted.example.com", "https://api.example.com", "https://evil.test", "null"] {
        let request = format!(
            "OPTIONS /delete HTTP/1.1\r\nOrigin: {origin}\r\nAccess-Control-Request-Method: DELETE\r\nAccess-Control-Request-Headers: content-type,x-token\r\n\r\n"
        );
        let response = local_response(&request, "api.example.com", 443, "https").await;
        assert!(response.starts_with("HTTP/1.1 501 "), "{response}");
        assert!(!response.contains("access-control-"), "{response}");
    }
}
