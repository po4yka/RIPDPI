//! Runtime-path regression for the TUIC version-unsupported diagnostic.
//!
//! Wires the real `TuicClient` against a loopback fixture that rejects the v5
//! client the way a deployed TUIC v4 server would — completing QUIC/TLS, draining
//! the (fire-and-forget) auth stream, then application-closing the connection with
//! a v4 wire byte. The client must surface a typed
//! `TuicHandshakeError { VersionUnsupported }` (the seam the relay layer maps to
//! `FailureClass::TuicVersionUnsupported`), not a generic protocol error. This is
//! the only end-to-end exercise of `classify_failure_payload` on a live wire.

use std::time::Duration;

use local_network_fixture::TuicLoopback;
use ripdpi_tuic::{Config, TuicClient, TuicFailureKind, TuicHandshakeError};

fn config(port: u16, root_certificate_pem: Option<String>) -> Config {
    Config {
        server: "127.0.0.1".to_owned(),
        server_port: i32::from(port),
        server_name: "localhost".to_owned(),
        uuid: "11111111-1111-1111-1111-111111111111".to_owned(),
        password: "tuic-test-password".to_owned(),
        zero_rtt: false,
        congestion_control: "bbr".to_owned(),
        udp_enabled: false,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: None,
        keepalive_interval_ms: 0,
        root_certificate_pem,
    }
}

/// Pull the typed handshake error out of an `io::Error`, asserting the failure
/// classified as a version mismatch.
fn assert_version_unsupported(error: &std::io::Error) {
    let kind =
        error.get_ref().and_then(|inner| inner.downcast_ref::<TuicHandshakeError>()).map(TuicHandshakeError::kind);
    assert_eq!(
        kind,
        Some(TuicFailureKind::VersionUnsupported),
        "a v4-reject server must surface TuicHandshakeError::VersionUnsupported, got: {error}",
    );
}

#[tokio::test]
async fn v4_server_reject_surfaces_version_unsupported_diagnostic() {
    let server = TuicLoopback::start_rejecting_version().await.expect("start v4-reject fixture");
    let config = config(server.port(), Some(server.certificate_pem().to_string()));

    // v5 auth is fire-and-forget, so `connect` itself usually succeeds — the
    // server only rejects after draining auth. Accept the error at whichever
    // handshake seam observes the application-close.
    let error = match TuicClient::connect(config).await {
        Err(error) => error,
        Ok(client) => poll_until_handshake_error(&client).await,
    };

    assert_version_unsupported(&error);

    server.shutdown().await;
}

/// Drive `tcp_connect` until it errors. The fixture's application-close (reason
/// = v4 wire byte) propagates over loopback within milliseconds, but the exact
/// moment the client's endpoint driver observes it is not wall-clock bounded —
/// so poll instead of sleeping a fixed interval (which would be a CI flake). The
/// generous overall deadline only guards against a hang.
async fn poll_until_handshake_error(client: &TuicClient) -> std::io::Error {
    let deadline = Duration::from_secs(10);
    let poll = Duration::from_millis(20);
    let result = tokio::time::timeout(deadline, async {
        loop {
            match client.tcp_connect("127.0.0.1:9").await {
                Err(error) => break error,
                // A stream opened before the close was observed; retry until the
                // connection is seen closed. DuplexStream is not Debug.
                Ok(_) => tokio::time::sleep(poll).await,
            }
        }
    })
    .await;
    result.expect("v4-reject server must fail the relay handshake within the deadline")
}
