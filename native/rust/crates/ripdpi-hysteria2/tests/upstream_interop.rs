//! Exact tests against the pinned upstream binary started by acceptance/peers/run.py.
//! Payload/auth cases use explicit insecure mode. Secure default rejection is separate.

use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_hysteria2::{Config, HysteriaError};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::time::timeout;

fn local_endpoint(name: &str) -> String {
    let value = std::env::var(name).expect("run through test-lab/acceptance/peers/run.py");
    let address: SocketAddr = value.parse().expect("numeric socket address");
    assert!(address.ip().is_loopback() && address.port() != 0, "peer must bind loopback");
    value
}

fn config() -> Config {
    let endpoint = local_endpoint("RIPDPI_HYSTERIA_ENDPOINT");
    let address: SocketAddr = endpoint.parse().expect("validated endpoint");
    let auth = std::env::var("RIPDPI_HYSTERIA_AUTH").expect("peer test auth required");
    let mut config = Config::from_parts(auth, &address.ip().to_string(), i32::from(address.port()), "localhost".into())
        .expect("local Hysteria config");
    config.insecure = true;
    config
}

/// # Cancel safety:
/// Dropping the test future drops the client, owned streams, and datagram session.
// cancel-safe: all network resources belong to this future; no detached test workers.
#[tokio::test(flavor = "current_thread")]
#[ignore = "requires the pinned local independent Hysteria peer"]
async fn independent_peer_exchanges_tcp_and_udp() {
    timeout(Duration::from_secs(15), async {
        let config = config();
        let client = ripdpi_hysteria2::connect(&config).await.expect("independent Hysteria handshake");
        assert!(client.udp_supported(), "upstream peer must permit UDP");
        let payload = std::env::var("RIPDPI_HYSTERIA_PAYLOAD").expect("run-specific payload").into_bytes();
        assert!(!payload.is_empty());
        let mut stream =
            client.tcp_connect(&local_endpoint("RIPDPI_HYSTERIA_TCP")).await.expect("upstream TCP connect");
        stream.write_all(&payload).await.expect("write TCP payload");
        stream.flush().await.expect("flush TCP payload");
        let mut echoed = vec![0; payload.len()];
        stream.read_exact(&mut echoed).await.expect("read upstream TCP response");
        assert_eq!(echoed, payload, "TCP payload must come from the destination");
        let mut udp = client.udp_session().await.expect("create upstream UDP session");
        let destination = local_endpoint("RIPDPI_HYSTERIA_UDP");
        udp.send_to(&destination, &payload).await.expect("send upstream UDP datagram");
        let (source, echoed) = udp.recv_from().await.expect("receive upstream UDP datagram");
        assert_eq!(source, destination);
        assert_eq!(echoed, payload, "UDP payload must come from the destination");
    })
    .await
    .expect("independent Hysteria payload deadline");
}

/// # Cancel safety:
/// Each connection is owned by the future and dropped on its deadline.
// cancel-safe: cancellation does not reuse a partial connection.
#[tokio::test(flavor = "current_thread")]
#[ignore = "requires the pinned local independent Hysteria peer"]
async fn independent_peer_rejects_wrong_auth() {
    timeout(Duration::from_secs(15), async {
        let mut config = config();
        let baseline = ripdpi_hysteria2::connect(&config).await.expect("correct auth must work first");
        drop(baseline);
        config.auth.push_str("-wrong");
        let result = ripdpi_hysteria2::connect(&config).await;
        assert!(matches!(result, Err(HysteriaError::AuthFailed)), "wrong auth must fail at authentication");
    })
    .await
    .expect("independent Hysteria auth deadline");
}

/// # Cancel safety:
/// Each connection is owned by the future and dropped on its deadline.
// cancel-safe: cancellation drops the QUIC handshake and does not retry it.
#[tokio::test(flavor = "current_thread")]
#[ignore = "requires the pinned local independent Hysteria peer"]
async fn independent_peer_rejects_untrusted_certificate() {
    timeout(Duration::from_secs(15), async {
        let mut config = config();
        let baseline = ripdpi_hysteria2::connect(&config).await.expect("local peer must be available first");
        drop(baseline);
        config.insecure = false;
        let error = ripdpi_hysteria2::connect(&config).await.err().expect("untrusted peer must fail TLS");
        assert!(matches!(&error, HysteriaError::QuicConnection(_)));
        assert!(error.to_string().contains("UnknownIssuer"), "expected certificate trust failure: {error}");
    })
    .await
    .expect("independent Hysteria TLS deadline");
}

/// # Cancel safety:
/// The deadline owns the QUIC client and all datagram sessions.
// cancel-safe: no detached worker or connection survives the test future.
#[tokio::test(flavor = "current_thread")]
#[ignore = "requires scripts/ci/check_hysteria_dns_fixture.py"]
async fn android_fixture_routes_synthetic_dns_and_rejects_wrong_pin_and_destination() {
    timeout(Duration::from_secs(20), async {
        let endpoint = local_endpoint("RIPDPI_HYSTERIA_ENDPOINT");
        let auth = std::env::var("RIPDPI_HYSTERIA_AUTH").expect("fixture auth");
        let pin = std::env::var("RIPDPI_HYSTERIA_PIN").expect("fixture certificate pin");
        let obfs = std::env::var("RIPDPI_HYSTERIA_OBFS").expect("fixture Salamander key");
        let config = Config::from_url(&format!(
            "hysteria2://{auth}@{endpoint}/?sni=fixture.test&insecure=1&pinSHA256={pin}&obfs-password={obfs}"
        ))
        .expect("pinned fixture config");
        let port: u16 = std::env::var("RIPDPI_HYSTERIA_DNS_PORT").expect("fixture DNS port").parse().expect("port");
        assert_ne!(port, 0);
        let destination = format!("192.0.2.53:{port}");
        let client = ripdpi_hysteria2::connect(&config).await.expect("pinned fixture handshake");
        assert!(client.udp_supported());
        let mut udp = client.udp_session().await.expect("UDP session");
        let query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x05owned\x04test\x00\x00\x01\x00\x01";
        for cycle in 0..2 {
            udp.send_to(&destination, query).await.expect("send synthetic DNS query");
            let (source, answer) = timeout(Duration::from_secs(3), udp.recv_from())
                .await
                .expect("owned DNS response deadline")
                .expect("owned DNS response");
            assert_eq!(source, destination);
            assert!(answer.len() > query.len());
            assert_eq!(&answer[..2], &query[..2]);
            assert_ne!(answer[2] & 0x80, 0, "must be a DNS response");
            assert_eq!(&answer[6..8], &[0, 1], "must contain one answer");
            assert!(answer.ends_with(&[192, 0, 2, 77]), "must contain the owned A record");
            if cycle == 0 {
                let wrong_port = if port == u16::MAX { 1 } else { port + 1 };
                for denied in
                    [format!("192.0.2.54:{port}"), format!("127.0.0.1:{port}"), format!("192.0.2.53:{wrong_port}")]
                {
                    udp.send_to(&denied, query).await.expect("send denied destination");
                    assert!(
                        timeout(Duration::from_millis(500), udp.recv_from()).await.is_err(),
                        "non-owned destination answered"
                    );
                }
            }
        }
        let mut wrong_pin = config;
        wrong_pin.pin_sha256 = Some([0; 32]);
        let error = timeout(Duration::from_secs(3), ripdpi_hysteria2::connect(&wrong_pin))
            .await
            .expect("wrong pin rejection deadline")
            .err()
            .expect("wrong pin must fail TLS");
        assert!(matches!(error, HysteriaError::QuicConnection(_)), "wrong pin must fail before authentication");
    })
    .await
    .expect("Android DNS fixture deadline");
}
