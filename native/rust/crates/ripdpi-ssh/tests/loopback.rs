//! In-process loopback integration tests for `ripdpi-ssh`.
//!
//! Stands up a minimal `russh` server on a random loopback port, then drives
//! the public `ripdpi_ssh::connect` + `SshClient::tcp_connect` API through the
//! real russh crypto stack.
//!
//! Tests:
//! 1. `password_auth_echo` — password auth, correct host-key pin, direct-tcpip
//!    channel opened, payload written and echo'd back.
//! 2. `wrong_host_key_pin_rejected` — a different valid fingerprint string causes
//!    `connect` to return `SshError::HostKeyMismatch`.
//! 3. `tofu_no_pin_rejects_untrusted` — TOFU with no pin causes `connect` to
//!    return `SshError::HostKeyUntrusted`.

use std::sync::Arc;

use russh::Channel;
use russh::keys::ssh_key::private::Ed25519Keypair;
use russh::keys::{HashAlg, PrivateKey};
use russh::server::{Auth, Msg, Session};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

use ripdpi_ssh::{SshAuth, SshConfig, SshError, SshHostKeyPolicy, connect};

// ---------------------------------------------------------------------------
// Fixed deterministic server key
// ---------------------------------------------------------------------------

/// 32-byte Ed25519 seed used to build a deterministic host key for tests.
/// Deterministic so the fingerprint can be computed once and reused across
/// all test helpers without RNG dependencies.
const SERVER_KEY_SEED: [u8; 32] = [
    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13,
    0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
];

/// A second distinct seed for the "wrong fingerprint" test.
const WRONG_KEY_SEED: [u8; 32] = [
    0xff, 0xfe, 0xfd, 0xfc, 0xfb, 0xfa, 0xf9, 0xf8, 0xf7, 0xf6, 0xf5, 0xf4, 0xf3, 0xf2, 0xf1, 0xf0, 0xef, 0xee, 0xed,
    0xec, 0xeb, 0xea, 0xe9, 0xe8, 0xe7, 0xe6, 0xe5, 0xe4, 0xe3, 0xe2, 0xe1, 0xe0,
];

/// Build an Ed25519 `PrivateKey` deterministically from a 32-byte seed.
/// Uses `ssh_key::private::Ed25519Keypair::from_seed` — no RNG required.
fn make_host_key(seed: &[u8; 32]) -> PrivateKey {
    let keypair = Ed25519Keypair::from_seed(seed);
    PrivateKey::from(keypair)
}

/// Compute the `"SHA256:<base64>"` fingerprint string for a `PrivateKey`,
/// matching the format that `russh`'s `check_server_key` callback receives.
fn fingerprint_of(key: &PrivateKey) -> String {
    key.public_key().fingerprint(HashAlg::Sha256).to_string()
}

// ---------------------------------------------------------------------------
// Minimal echo server
// ---------------------------------------------------------------------------

/// Minimal russh server handler that:
/// - accepts any password credential,
/// - opens `direct-tcpip` channels and echoes every byte back to the client.
#[derive(Clone)]
struct EchoHandler;

impl russh::server::Server for EchoHandler {
    type Handler = Self;

    fn new_client(&mut self, _: Option<std::net::SocketAddr>) -> Self {
        self.clone()
    }
}

impl russh::server::Handler for EchoHandler {
    type Error = russh::Error;

    // cancel-safe: no awaits; returns immediately.
    async fn auth_password(&mut self, _user: &str, _password: &str) -> Result<Auth, russh::Error> {
        Ok(Auth::Accept)
    }

    // cancel-safe: spawns a detached echo task and returns Ok(true) immediately;
    // the spawned task is independent and its lifecycle is not observed.
    async fn channel_open_direct_tcpip(
        &mut self,
        channel: Channel<Msg>,
        _host_to_connect: &str,
        _port_to_connect: u32,
        _originator_address: &str,
        _originator_port: u32,
        _session: &mut Session,
    ) -> Result<bool, russh::Error> {
        tokio::spawn(async move {
            let mut stream = channel.into_stream();
            let mut buf = [0u8; 4096];
            loop {
                match stream.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if stream.write_all(&buf[..n]).await.is_err() {
                            break;
                        }
                    }
                }
            }
        });
        Ok(true)
    }
}

// ---------------------------------------------------------------------------
// Server harness
// ---------------------------------------------------------------------------

/// Binds a `TcpListener` on `127.0.0.1:0`, spawns an `EchoHandler` russh
/// server backed by `host_key`, and returns the assigned port.
// cancel-safe: binds and spawns without shared mutable state; dropping before
// await resolves leaves the runtime clean.
async fn spawn_echo_server(host_key: PrivateKey) -> u16 {
    let server_config = Arc::new(russh::server::Config {
        keys: vec![host_key],
        auth_rejection_time: std::time::Duration::from_millis(0),
        auth_rejection_time_initial: Some(std::time::Duration::from_millis(0)),
        ..Default::default()
    });

    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind must succeed");
    let port = listener.local_addr().expect("must have local addr").port();

    tokio::spawn(async move {
        if let Ok((tcp_stream, _)) = listener.accept().await {
            let _running = russh::server::run_stream(server_config, tcp_stream, EchoHandler)
                .await
                .expect("run_stream must succeed");
            // Keep _running alive so the session stays open for the test.
            tokio::time::sleep(std::time::Duration::from_secs(30)).await;
        }
    });

    port
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/// Test 1: password auth + correct host-key pin + direct-tcpip echo.
#[tokio::test(flavor = "multi_thread")]
async fn password_auth_echo() {
    let host_key = make_host_key(&SERVER_KEY_SEED);
    let fingerprint = fingerprint_of(&host_key);
    let port = spawn_echo_server(host_key).await;

    let config = SshConfig {
        host: "127.0.0.1".to_string(),
        port,
        username: "testuser".to_string(),
        auth: SshAuth::Password("any-password".to_string()),
        host_key_policy: SshHostKeyPolicy::Strict { fingerprint },
    };

    let client = connect(&config).await.expect("connect must succeed with correct pin");
    let mut stream = client.tcp_connect("127.0.0.1:9").await.expect("tcp_connect must open channel");

    let payload = b"hello ripdpi-ssh loopback";
    stream.write_all(payload).await.expect("write must succeed");

    let mut echo_buf = vec![0u8; payload.len()];
    stream.read_exact(&mut echo_buf).await.expect("read_exact must receive the echo");

    assert_eq!(echo_buf, payload, "echoed bytes must match the payload");
}

/// Test 2: wrong host-key pin → `SshError::HostKeyMismatch`.
///
/// Server uses `SERVER_KEY_SEED`; client pins the fingerprint of a key built
/// from `WRONG_KEY_SEED`.  The hashes differ, so the host-key check must fire.
#[tokio::test(flavor = "multi_thread")]
async fn wrong_host_key_pin_rejected() {
    let host_key = make_host_key(&SERVER_KEY_SEED);
    let port = spawn_echo_server(host_key).await;

    // Pin the fingerprint of a *different* valid key — will never match the server.
    let wrong_fp = fingerprint_of(&make_host_key(&WRONG_KEY_SEED));

    let config = SshConfig {
        host: "127.0.0.1".to_string(),
        port,
        username: "testuser".to_string(),
        auth: SshAuth::Password("any-password".to_string()),
        host_key_policy: SshHostKeyPolicy::Strict { fingerprint: wrong_fp },
    };

    let error = connect(&config).await.expect_err("mismatched pin must cause connect to fail");
    assert!(matches!(error, SshError::HostKeyMismatch { .. }), "expected HostKeyMismatch, got {error:?}",);
}

/// Test 3: TOFU with no pin → `SshError::HostKeyUntrusted`.
///
/// The engine must never silently trust a first-use key; it surfaces the
/// fingerprint as `HostKeyUntrusted` so the UI can prompt the user.
#[tokio::test(flavor = "multi_thread")]
async fn tofu_no_pin_rejects_untrusted() {
    let host_key = make_host_key(&SERVER_KEY_SEED);
    let expected_b64 =
        fingerprint_of(&host_key).strip_prefix("SHA256:").expect("fingerprint must start with SHA256:").to_string();
    let port = spawn_echo_server(host_key).await;

    let config = SshConfig {
        host: "127.0.0.1".to_string(),
        port,
        username: "testuser".to_string(),
        auth: SshAuth::Password("any-password".to_string()),
        host_key_policy: SshHostKeyPolicy::Tofu { pinned_fingerprint: None },
    };

    let error = connect(&config).await.expect_err("TOFU with no pin must reject the key");

    // The error must carry the exact base64 digest (without the "SHA256:" prefix)
    // so the UI can display it for user confirmation.
    match error {
        SshError::HostKeyUntrusted(presented_fp) => {
            assert_eq!(presented_fp, expected_b64, "HostKeyUntrusted must carry the server's actual fingerprint",);
        }
        other => panic!("expected HostKeyUntrusted, got {other:?}"),
    }
}
