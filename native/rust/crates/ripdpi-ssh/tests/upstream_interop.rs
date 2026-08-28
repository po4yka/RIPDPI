//! Interoperability against pinned golang.org/x/crypto/ssh, independent of russh.
use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_ssh::{SshAuth, SshConfig, SshError, SshHostKeyPolicy, connect};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

fn config(auth: SshAuth) -> SshConfig {
    let endpoint: SocketAddr =
        std::env::var("RIPDPI_OUTBOUND_INTEROP_ENDPOINT").expect("upstream runner").parse().expect("loopback socket");
    assert!(endpoint.ip().is_loopback() && endpoint.port() != 0);
    SshConfig {
        host: endpoint.ip().to_string(),
        port: endpoint.port(),
        username: "outbound-interop".into(),
        auth,
        host_key_policy: SshHostKeyPolicy::Strict {
            fingerprint: std::env::var("RIPDPI_OUTBOUND_FINGERPRINT").expect("oracle fingerprint"),
        },
    }
}

/// # Cancel safety
/// Conditional: the runner owns and terminates the independent peer on timeout.
async fn exchange(config: SshConfig) {
    let client = connect(&config).expect("owned connection");
    let result = tokio::time::timeout(Duration::from_secs(12), async {
        client.ready().await.expect("authenticate to independent SSH server");
        let target: SocketAddr =
            std::env::var("RIPDPI_OUTBOUND_TCP").expect("separate upstream TCP target").parse().expect("target socket");
        assert!(target.ip().is_loopback() && target.port() != 0);
        let mut stream = client.tcp_connect(&target.to_string()).await.expect("direct-tcpip forwarding");
        let payload: Vec<u8> = (0..65536).map(|index| (index % 251) as u8).collect();
        stream.write_all(&payload).await.expect("write");
        let mut echoed = vec![0; payload.len()];
        stream.read_exact(&mut echoed).await.expect("echo");
        assert_eq!(echoed, payload);
        stream.shutdown().await.expect("channel shutdown");
    })
    .await;
    client.close().await.expect("join SSH session");
    result.expect("upstream SSH deadline");
}

#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn password_auth_exchanges_payload_with_upstream() {
    exchange(config(SshAuth::Password("loopback-test-password".into()))).await;
}

#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn encrypted_private_key_auth_exchanges_payload_with_upstream() {
    let pem = std::fs::read_to_string(std::env::var("RIPDPI_OUTBOUND_PRIVATE_KEY").expect("private key path"))
        .expect("test-only private key");
    exchange(config(SshAuth::PrivateKey { pem, passphrase: Some("loopback-key-passphrase".into()) })).await;
}

#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn changed_host_key_is_rejected_before_authentication() {
    let mut config = config(SshAuth::Password("loopback-test-password".into()));
    config.host_key_policy =
        SshHostKeyPolicy::Strict { fingerprint: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".into() };
    let client = connect(&config).expect("owned connection");
    let result = tokio::time::timeout(Duration::from_secs(12), client.ready()).await;
    client.close().await.expect("join rejected session");
    let result = result.expect("rejection deadline");
    assert!(matches!(result, Err(SshError::HostKeyMismatch { .. })));
    // The Go peer checks its authentication counter after every connection has
    // ended, not while an authentication packet might still be buffered.
}
