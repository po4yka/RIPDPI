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
