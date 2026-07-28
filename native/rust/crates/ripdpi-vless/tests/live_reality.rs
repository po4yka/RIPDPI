use std::env;
use std::time::Duration;

use boring::ssl::{SslConnector, SslMethod, SslVerifyMode};
use ripdpi_vless::VlessRealityClient;
use ripdpi_vless::config::VlessRealityConfig;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

fn required(name: &str) -> String {
    env::var(name).unwrap_or_else(|_| panic!("missing live-test environment variable: {name}"))
}

fn live_config() -> VlessRealityConfig {
    let port = required("RIPDPI_LIVE_VLESS_PORT").parse::<i32>().expect("live relay port must be an integer");
    VlessRealityConfig::from_strings(
        &required("RIPDPI_LIVE_VLESS_SERVER"),
        port,
        &required("RIPDPI_LIVE_VLESS_UUID"),
        &required("RIPDPI_LIVE_VLESS_SNI"),
        &required("RIPDPI_LIVE_VLESS_PUBLIC_KEY"),
        &required("RIPDPI_LIVE_VLESS_SHORT_ID"),
        &required("RIPDPI_LIVE_VLESS_FINGERPRINT"),
    )
    .expect("live VLESS+Reality profile must parse")
    .with_flow_str(&required("RIPDPI_LIVE_VLESS_FLOW"))
    .expect("live VLESS flow must parse")
}

/// Exercises an owner-provided VLESS+Reality endpoint without persisting its
/// credentials in the repository. The caller supplies the profile through the
/// environment and opts in explicitly with `--ignored`.
#[tokio::test]
#[ignore = "requires an owner-provided live VLESS+Reality profile"]
async fn owner_provided_reality_profile_carries_http_traffic() {
    let config = live_config();

    let stream =
        tokio::time::timeout(Duration::from_secs(20), VlessRealityClient::connect(&config, "www.google.com:443"))
            .await
            .expect("live VLESS+Reality connect timed out")
            .expect("live VLESS+Reality connect failed");

    let mut inner_builder = SslConnector::builder(SslMethod::tls()).expect("build inner TLS connector");
    inner_builder.set_verify(SslVerifyMode::NONE);
    let inner_ssl = inner_builder
        .build()
        .configure()
        .expect("configure inner TLS")
        .into_ssl("www.google.com")
        .expect("create inner TLS client");
    let mut stream =
        tokio::time::timeout(Duration::from_secs(10), tokio_boring::SslStreamBuilder::new(inner_ssl, stream).connect())
            .await
            .expect("inner TLS through live VLESS+Reality timed out")
            .unwrap_or_else(|error| panic!("inner TLS through live VLESS+Reality failed: {error}"));

    stream
        .write_all(b"GET /generate_204 HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
        .await
        .expect("write HTTP request through live VLESS+Reality tunnel");
    let mut response = [0_u8; 64];
    let read = tokio::time::timeout(Duration::from_secs(10), stream.read(&mut response))
        .await
        .expect("live VLESS+Reality response timed out")
        .expect("read HTTP response through live VLESS+Reality tunnel");
    assert!(read > 0, "live VLESS+Reality tunnel returned EOF before an HTTP response");
    assert!(response[..read].starts_with(b"HTTP/"), "live VLESS+Reality tunnel returned a non-HTTP response");
}

/// Exercises two destinations in one owner-provided XUDP association: a
/// non-443 UDP echo and a DNS resolver. Targets and credentials stay in the
/// caller's environment and are never included in assertion messages.
#[tokio::test]
#[ignore = "requires owner-provided Reality XUDP echo and DNS targets"]
async fn owner_provided_reality_profile_carries_xudp_echo_and_dns() {
    let config = live_config();
    let mut session = tokio::time::timeout(Duration::from_secs(20), VlessRealityClient::connect_xudp(&config, None))
        .await
        .expect("live Reality XUDP connect timed out")
        .expect("live Reality XUDP connect failed");

    let echo_payload = b"ripdpi-xudp-live-echo";
    session
        .send_to(&required("RIPDPI_LIVE_XUDP_ECHO_TARGET"), echo_payload)
        .await
        .expect("send live XUDP echo datagram");
    let (_, echoed) = tokio::time::timeout(Duration::from_secs(10), session.recv_from())
        .await
        .expect("live XUDP echo timed out")
        .expect("receive live XUDP echo datagram");
    assert_eq!(echoed, echo_payload, "live XUDP echo payload mismatch");

    let dns_query = [
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, b'e', b'x', b'a', b'm', b'p',
        b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
    ];
    session.send_to(&required("RIPDPI_LIVE_XUDP_DNS_TARGET"), &dns_query).await.expect("send live XUDP DNS datagram");
    let (_, dns_response) = tokio::time::timeout(Duration::from_secs(10), session.recv_from())
        .await
        .expect("live XUDP DNS timed out")
        .expect("receive live XUDP DNS datagram");
    assert!(dns_response.len() >= 12, "live XUDP DNS response is truncated");
    assert_eq!(&dns_response[..2], &dns_query[..2], "live XUDP DNS transaction ID mismatch");
    assert_ne!(dns_response[2] & 0x80, 0, "live XUDP DNS response flag is absent");
}
