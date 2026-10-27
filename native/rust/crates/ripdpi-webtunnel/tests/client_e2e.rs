use std::io::{Read, Write};

use local_network_fixture::WebTunnelFixture;
use ripdpi_webtunnel::bridge_line::{WebTunnelBridgeConfig, parse_bridge_line};
use ripdpi_webtunnel::client::{WebTunnelClientError, connect_webtunnel, connect_webtunnel_async};

/// Bridge config for the loopback fixture, with the given `servername` (SNI and
/// certificate name check).
fn fixture_bridge(fixture: &WebTunnelFixture, servername: &str) -> WebTunnelBridgeConfig {
    parse_bridge_line(&format!(
        "Bridge webtunnel 192.0.2.3:1 url={} addr={} servername={servername} utls=hellochrome_auto",
        fixture.url(),
        fixture.addr(),
    ))
    .expect("bridge line")
}

/// Same as [`fixture_bridge`], but the fixture's self-signed certificate is a
/// configured trust root, so the verified client can accept it.
fn trusted_fixture_bridge(fixture: &WebTunnelFixture, servername: &str) -> WebTunnelBridgeConfig {
    let mut bridge = fixture_bridge(fixture, servername);
    bridge.root_certificate_pem = Some(fixture.root_certificate_pem().to_owned());
    bridge
}

#[test]
fn webtunnel_client_round_trips_over_tls_upgrade_fixture() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = trusted_fixture_bridge(&fixture, "localhost");

    let mut stream = connect_webtunnel(&bridge).expect("connect WebTunnel fixture");
    stream.write_all(b"ping-over-webtunnel").expect("write tunnel payload");
    stream.flush().expect("flush tunnel payload");

    let mut echoed = [0_u8; 19];
    stream.read_exact(&mut echoed).expect("read echoed tunnel payload");
    assert_eq!(&echoed, b"ping-over-webtunnel");

    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert_eq!(observed[0].path, "/secret");
    assert_eq!(observed[0].host, "localhost");
}

#[tokio::test]
async fn webtunnel_async_client_round_trips_over_tls_upgrade_fixture() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = trusted_fixture_bridge(&fixture, "localhost");

    let mut stream = connect_webtunnel_async(&bridge).await.expect("connect WebTunnel fixture (async)");
    stream.write_all(b"ping-over-webtunnel").await.expect("write tunnel payload");
    stream.flush().await.expect("flush tunnel payload");

    let mut echoed = [0_u8; 19];
    stream.read_exact(&mut echoed).await.expect("read echoed tunnel payload");
    assert_eq!(&echoed, b"ping-over-webtunnel");

    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert_eq!(observed[0].path, "/secret");
    assert_eq!(observed[0].host, "localhost");
}

/// A self-signed bridge certificate that no bundled root trusts must fail the
/// TLS handshake before the secret path is sent.
#[test]
fn webtunnel_client_rejects_untrusted_self_signed_certificate() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = fixture_bridge(&fixture, "localhost");

    let error = connect_webtunnel(&bridge).expect_err("untrusted certificate must be rejected");

    assert!(matches!(error, WebTunnelClientError::TlsHandshake(_)), "unexpected error: {error}");
    assert!(fixture.observed_requests().is_empty(), "secret path must not be sent");
}

#[tokio::test]
async fn webtunnel_async_client_rejects_untrusted_self_signed_certificate() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = fixture_bridge(&fixture, "localhost");

    let error = connect_webtunnel_async(&bridge).await.expect_err("untrusted certificate must be rejected");

    assert!(matches!(error, WebTunnelClientError::TlsHandshake(_)), "unexpected error: {error}");
    assert!(fixture.observed_requests().is_empty(), "secret path must not be sent");
}

/// A trusted certificate issued for another name must also be rejected: the
/// client checks the certificate name against the bridge `servername`.
#[test]
fn webtunnel_client_rejects_trusted_certificate_for_other_servername() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = trusted_fixture_bridge(&fixture, "bridge.example");

    let error = connect_webtunnel(&bridge).expect_err("certificate name mismatch must be rejected");

    assert!(matches!(error, WebTunnelClientError::TlsHandshake(_)), "unexpected error: {error}");
    assert!(fixture.observed_requests().is_empty(), "secret path must not be sent");
}

#[tokio::test]
async fn webtunnel_async_client_rejects_trusted_certificate_for_other_servername() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = trusted_fixture_bridge(&fixture, "bridge.example");

    let error = connect_webtunnel_async(&bridge).await.expect_err("certificate name mismatch must be rejected");

    assert!(matches!(error, WebTunnelClientError::TlsHandshake(_)), "unexpected error: {error}");
    assert!(fixture.observed_requests().is_empty(), "secret path must not be sent");
}

/// Upstream `cert=<chain hash>`: the pinned chain hash replaces CA
/// validation, so a self-signed bridge with its pin connects without any
/// trust root, and a wrong pin is rejected before the secret path is sent.
#[test]
fn webtunnel_client_trusts_a_pinned_self_signed_certificate_chain() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let certificate = boring::x509::X509::from_pem(fixture.root_certificate_pem().as_bytes()).expect("fixture cert");
    // A one-certificate chain hashes to SHA-256 of that certificate.
    let pin = boring::base64::encode_block(&boring::sha::sha256(&certificate.to_der().expect("der")));

    let mut pinned = fixture_bridge(&fixture, "localhost");
    pinned.cert = Some(pin);
    let mut stream = connect_webtunnel(&pinned).expect("a pinned self-signed bridge must connect");
    stream.write_all(b"pinned").expect("write tunnel payload");
    let mut echoed = [0_u8; 6];
    stream.read_exact(&mut echoed).expect("read echoed payload");
    assert_eq!(&echoed, b"pinned");

    let mut wrong = fixture_bridge(&fixture, "localhost");
    wrong.cert = Some(boring::base64::encode_block(&[0; 32]));
    let error = connect_webtunnel(&wrong).expect_err("a wrong pin must be rejected");
    assert!(matches!(error, WebTunnelClientError::TlsHandshake(_)), "unexpected error: {error}");
    assert_eq!(fixture.observed_requests().len(), 1, "the secret path goes only to the pinned bridge");
}

/// Upstream `cert-domain` (and `sni-imitation`): SNI carries a cover name and
/// the certificate is checked against another name.
#[test]
fn webtunnel_client_checks_the_certificate_against_cert_domain() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let mut bridge = trusted_fixture_bridge(&fixture, "cover.example");
    let mismatch = connect_webtunnel(&bridge).expect_err("the certificate does not name the SNI cover name");
    assert!(matches!(mismatch, WebTunnelClientError::TlsHandshake(_)), "unexpected error: {mismatch}");

    bridge.cert_domain = Some("localhost".to_owned());
    connect_webtunnel(&bridge).expect("the certificate names cert-domain");
}
