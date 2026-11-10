//! Local real-crypto regression for H1. The disposable key has mutated bcrypt
//! metadata (64 rounds); its ciphertext was generated at one round, so it cannot
//! authenticate. It exercises actual decryption cost without any real secret.
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};

use ripdpi_ssh::{SshAuth, SshConfig, SshHostKeyPolicy, connect};
use russh::keys::{HashAlg, PrivateKey, ssh_key::private::Ed25519Keypair};
use tokio::net::TcpListener;

struct Peer;
impl russh::server::Handler for Peer {
    type Error = russh::Error;
}

/// # Cancel safety:
/// NOT cancel-safe: this test joins the constructor, heartbeat, and peer.
#[tokio::test]
async fn admitted_key_decode_does_not_starve_timers() {
    let host = PrivateKey::from(Ed25519Keypair::from_seed(&[7; 32]));
    let fingerprint = host.public_key().fingerprint(HashAlg::Sha256).to_string();
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("listen");
    let address = listener.local_addr().expect("address");
    let mut peer = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.expect("accept");
        let config = Arc::new(russh::server::Config { keys: vec![host], ..Default::default() });
        if let Ok(session) = russh::server::run_stream(config, stream, Peer).await {
            let _ = session.await;
        }
    });
    let config = SshConfig {
        host: address.ip().to_string(),
        port: address.port(),
        username: "h1-local".into(),
        auth: SshAuth::PrivateKey {
            pem: include_str!("fixtures/kdf64.pem").into(),
            passphrase: Some("h1-local-passphrase".into()),
        },
        host_key_policy: SshHostKeyPolicy::Strict { fingerprint },
    };
    let client = Arc::new(connect(&config).expect("admitted connection"));
    let done = Arc::new(AtomicBool::new(false));
    let heartbeat_done = Arc::clone(&done);
    let heartbeat = tokio::spawn(async move {
        let mut gap = Duration::ZERO;
        loop {
            let start = Instant::now();
            tokio::time::sleep(Duration::from_millis(5)).await;
            gap = gap.max(start.elapsed());
            if heartbeat_done.load(Ordering::Relaxed) {
                break;
            }
        }
        gap
    });
    let ready = client.ready().await;
    client.close().await.expect("join owned decode and transport");
    client.close().await.expect("repeat close");
    done.store(true, Ordering::Relaxed);
    let gap = heartbeat.await.expect("heartbeat");
    let stopped = tokio::time::timeout(Duration::from_secs(1), &mut peer).await;
    if stopped.is_err() {
        peer.abort();
        let _ = peer.await;
    }
    stopped.expect("peer EOF").expect("peer join");
    let error = ready.expect_err("mutated ciphertext cannot authenticate").to_string();
    assert!(error.contains("private key decode failed"), "must reach actual decryption: {error}");
    assert!(gap < Duration::from_millis(400), "5 ms heartbeat stalled for {gap:?}");
}
