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

/// # Cancel safety
/// NOT cancel-safe: the runner owns the peer, and this helper joins the client after the deadline.
async fn rekey_exchange(reject_changed_key: bool) {
    let client = connect(&config(SshAuth::Password("loopback-test-password".into()))).expect("owned connection");
    client.ready().await.expect("authentication");
    let target = std::env::var("RIPDPI_OUTBOUND_TCP").expect("owned target");
    let mut stream = client.tcp_connect(&target).await.expect("direct-tcpip");
    let payload: Vec<u8> = (0..4 * 1024 * 1024).map(|index| (index % 251) as u8).collect();
    let (mut read, mut write) = tokio::io::split(&mut stream);
    let result = tokio::time::timeout(Duration::from_secs(12), async {
        let (written, received) = tokio::join!(write.write_all(&payload), async {
            let mut echoed = vec![0; payload.len()];
            read.read_exact(&mut echoed).await?;
            assert_eq!(echoed, payload);
            Ok::<_, std::io::Error>(())
        });
        written.and(received)
    })
    .await;
    drop(read);
    drop(write);
    drop(stream);
    client.close().await.expect("join SSH session");
    let result = result.expect("rekey completes or rejects within deadline");
    if reject_changed_key {
        assert!(result.is_err(), "a key outside the strict pin must terminate forwarding");
    } else {
        result.expect("same-key rekey must preserve all payload bytes");
    }
}

/// # Cancel safety
/// NOT cancel-safe: the helper owns and joins the complete client exchange.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn same_host_key_rekey_preserves_payload() {
    rekey_exchange(false).await;
}

/// # Cancel safety
/// NOT cancel-safe: the helper owns and joins the complete client exchange.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn changed_host_key_rekey_aborts_payload() {
    rekey_exchange(true).await;
}
/// # Cancel safety
/// NOT cancel-safe: the runner terminates the peer on failure; close must join with a retained receiver.
async fn close_unread_channel(concurrent_open: bool) {
    let client = std::sync::Arc::new(
        connect(&config(SshAuth::Password("loopback-test-password".into()))).expect("owned connection"),
    );
    client.ready().await.expect("authentication");
    let target = std::env::var("RIPDPI_OUTBOUND_TCP").expect("owned target");
    let mut stream = client.tcp_connect(&target).await.expect("direct-tcpip");
    // The independent peer floods more than the initial channel window and bounded receive queue.
    tokio::time::sleep(Duration::from_millis(500)).await;
    let pending_open = if concurrent_open {
        let client = std::sync::Arc::clone(&client);
        Some(tokio::spawn(async move { client.tcp_connect(&target).await.map(drop) }))
    } else {
        None
    };
    tokio::time::sleep(Duration::from_millis(50)).await;
    let pending_blocked = pending_open.as_ref().is_none_or(|open| !open.is_finished());
    let stopped = tokio::time::timeout(Duration::from_secs(2), client.close()).await;
    // The stream stays alive through close: closing the receiver first would hide the regression.
    if let Some(open) = pending_open {
        open.abort();
        let _ = open.await;
    }
    let write = tokio::time::timeout(Duration::from_secs(1), stream.write_all(b"after close")).await;
    drop(stream);
    client.close().await.expect("repeat close joins retained owner");
    assert!(pending_blocked, "the flooded channel must block the second open");
    stopped.expect("close must not wait for channel consumption").expect("join SSH worker");
    assert!(matches!(write, Ok(Err(_))), "closed owner must leave no live channel writer");
}

/// # Cancel safety
/// NOT cancel-safe: the helper owns the client and its cleanup.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn unread_channel_close_is_bounded() {
    close_unread_channel(false).await;
}

/// # Cancel safety
/// NOT cancel-safe: the helper owns the client and its cleanup.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn unread_channel_close_is_bounded_multithread() {
    close_unread_channel(true).await;
}
