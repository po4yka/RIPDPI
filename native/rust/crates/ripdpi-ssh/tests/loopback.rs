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
use std::sync::atomic::{AtomicUsize, Ordering};

use russh::Channel;
use russh::keys::ssh_key::private::Ed25519Keypair;
use russh::keys::{HashAlg, PrivateKey};
use russh::server::{Auth, ChannelOpenHandle, Msg, Session};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

use ripdpi_ssh::{SshAuth, SshConfig, SshError, SshHostKeyPolicy, connect};

#[derive(Clone)]
struct ProbeServer {
    authentication_attempts: Arc<AtomicUsize>,
}

impl russh::server::Handler for ProbeServer {
    type Error = russh::Error;

    /// # Cancel safety
    /// Cancel-safe: the counter update contains no suspension point.
    async fn auth_none(&mut self, _user: &str) -> Result<Auth, russh::Error> {
        self.authentication_attempts.fetch_add(1, Ordering::Relaxed);
        Ok(Auth::Accept)
    }

    /// # Cancel safety
    /// Cancel-safe: the counter update contains no suspension point.
    async fn auth_password(&mut self, _user: &str, _password: &str) -> Result<Auth, russh::Error> {
        self.authentication_attempts.fetch_add(1, Ordering::Relaxed);
        Ok(Auth::Accept)
    }
}

struct ProbeSocketController(AtomicUsize);

impl ripdpi_native_protect::ProtectCallback for ProbeSocketController {
    fn protect(&self, fd: std::os::fd::RawFd) -> std::io::Result<()> {
        assert!(fd >= 0, "controller must receive a live socket");
        self.0.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
}

/// # Cancel safety
/// Not cancel-safe: this complete test owns and explicitly joins its server task.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn key_only_probe_observes_fingerprint_without_authentication() {
    let key = make_host_key(&SERVER_KEY_SEED);
    let expected = fingerprint_of(&key);
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind probe peer");
    let address = listener.local_addr().expect("probe peer address");
    let attempts = Arc::new(AtomicUsize::new(0));
    let server_attempts = Arc::clone(&attempts);
    let mut server = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.expect("accept probe");
        let config = Arc::new(russh::server::Config { keys: vec![key], ..Default::default() });
        if let Ok(session) =
            russh::server::run_stream(config, stream, ProbeServer { authentication_attempts: server_attempts }).await
        {
            let _ = session.await;
        }
    });
    let controller = Arc::new(ProbeSocketController(AtomicUsize::new(0)));
    let probe_controller = Arc::clone(&controller);
    let outcome = tokio::task::spawn_blocking(move || {
        ripdpi_ssh::probe_host_key(address, std::time::Duration::from_secs(2), probe_controller)
    })
    .await;
    // Let the peer consume every buffered packet and finish naturally before
    // asserting zero authentication attempts; aborting first could hide one.
    let stopped = tokio::time::timeout(std::time::Duration::from_secs(1), &mut server).await;
    if stopped.is_err() {
        server.abort();
        let _ = server.await;
    }
    stopped.expect("probe peer must observe EOF").expect("probe peer must not panic");
    let observation =
        outcome.expect("probe thread must not panic").expect("key-only probe must return the peer's observed key");
    assert_eq!(observation.fingerprint_sha256, expected);
    assert_eq!(observation.algorithm, "ssh-ed25519");
    assert_eq!(attempts.load(Ordering::Relaxed), 0, "probe must stop before any authentication request");
    assert_eq!(controller.0.load(Ordering::Relaxed), 1, "every probe socket requires the call-scoped controller");
}

/// # Cancel safety
/// Not cancel-safe: this test joins both the blocking probe and its socket peer.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn key_only_probe_timeout_closes_spawned_key_exchange_socket_before_return() {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind stalled peer");
    let address = listener.local_addr().expect("stalled peer address");
    let mut server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.expect("accept probe");
        // Supplying an SSH banner makes russh spawn its KEX worker. Never reply
        // to its key-exchange packet: the probe must own that worker on timeout.
        stream.write_all(b"SSH-2.0-stalled-probe-peer\r\n").await.expect("send SSH banner");
        let mut received = Vec::new();
        stream.read_to_end(&mut received).await.expect("read until probe closes socket");
        received
    });
    let result = tokio::task::spawn_blocking(move || {
        ripdpi_ssh::probe_host_key(
            address,
            std::time::Duration::from_millis(100),
            Arc::new(ProbeSocketController(AtomicUsize::new(0))),
        )
    })
    .await
    .expect("probe thread must not panic");
    let closed = tokio::time::timeout(std::time::Duration::from_secs(1), &mut server).await;
    if closed.is_err() {
        server.abort();
        let _ = server.await;
    }
    assert_eq!(result, Err(ripdpi_ssh::SshHostKeyProbeError::Timeout));
    let received = closed.expect("timed-out KEX must leave no live socket").expect("peer task must not panic");
    let banner_end = received.iter().position(|byte| *byte == b'\n').expect("client SSH banner");
    assert!(received.len() > banner_end + 1, "test must reach the spawned KEX worker, not only TCP connect");
}

/// # Cancel safety
/// The test joins its client request and peer even when the EOF assertion fails.
#[tokio::test]
async fn cancelled_connection_owner_closes_spawned_key_exchange() {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("stalled peer");
    let address = listener.local_addr().expect("peer address");
    let (reached, kex) = tokio::sync::oneshot::channel();
    let mut server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.expect("accept");
        stream.write_all(b"SSH-2.0-stalled-owned-peer\r\n").await.expect("banner");
        let mut received = Vec::new();
        let mut buffer = [0; 2048];
        loop {
            let n = stream.read(&mut buffer).await.expect("KEX read");
            assert!(n > 0, "must reach KEX before cancellation");
            received.extend_from_slice(&buffer[..n]);
            if received.iter().position(|b| *b == b'\n').is_some_and(|end| received.len() > end + 5) {
                break;
            }
        }
        reached.send(()).expect("KEX observer");
        stream.read_to_end(&mut received).await.expect("EOF");
    });
    let config = SshConfig {
        host: address.ip().to_string(),
        port: address.port(),
        username: "outbound-interop".into(),
        auth: SshAuth::Password("loopback-test-password".into()),
        host_key_policy: SshHostKeyPolicy::Strict { fingerprint: fingerprint_of(&make_host_key(&SERVER_KEY_SEED)) },
    };
    let client = Arc::new(connect(&config).expect("owned connection"));
    let waiter = Arc::clone(&client);
    let pending = tokio::spawn(async move { waiter.ready().await });
    let started = tokio::time::timeout(std::time::Duration::from_secs(1), kex).await;
    pending.abort();
    let _ = pending.await;
    tokio::time::timeout(std::time::Duration::from_secs(1), client.close())
        .await
        .expect("close deadline")
        .expect("join KEX");
    client.close().await.expect("repeat close");
    let closed = tokio::time::timeout(std::time::Duration::from_millis(200), &mut server).await;
    if closed.is_err() {
        server.abort();
        let _ = server.await;
    }
    started.expect("reach KEX deadline").expect("KEX signal");
    closed.expect("cancelling the connection must not strand the spawned KEX socket").expect("peer task");
}

struct DenyProbeSocket;

impl ripdpi_native_protect::ProtectCallback for DenyProbeSocket {
    fn protect(&self, _fd: std::os::fd::RawFd) -> std::io::Result<()> {
        Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "test protection refused"))
    }
}

#[test]
fn key_only_probe_protection_denial_prevents_connect() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind probe peer");
    listener.set_nonblocking(true).expect("nonblocking peer");
    let result = ripdpi_ssh::probe_host_key(
        listener.local_addr().expect("peer address"),
        std::time::Duration::from_secs(1),
        Arc::new(DenyProbeSocket),
    );
    assert_eq!(result, Err(ripdpi_ssh::SshHostKeyProbeError::ProtectionDenied));
    assert_eq!(
        listener.accept().expect_err("no connection may precede protection").kind(),
        std::io::ErrorKind::WouldBlock
    );
}

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
        reply: ChannelOpenHandle,
        _session: &mut Session,
    ) -> Result<(), russh::Error> {
        reply.accept().await;
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
        Ok(())
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

    let client = connect(&config).expect("owned connection");
    client.ready().await.expect("connect must succeed with correct pin");
    let mut stream = client.tcp_connect("127.0.0.1:9").await.expect("tcp_connect must open channel");

    let payload = b"hello ripdpi-ssh loopback";
    stream.write_all(payload).await.expect("write must succeed");

    let mut echo_buf = vec![0u8; payload.len()];
    stream.read_exact(&mut echo_buf).await.expect("read_exact must receive the echo");

    client.close().await.expect("close session");
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

    let client = connect(&config).expect("owned connection");
    let error = client.ready().await.expect_err("mismatched pin must cause connect to fail");
    client.close().await.expect("failed connection cleanup");
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

    let client = connect(&config).expect("owned connection");
    let error = client.ready().await.expect_err("TOFU with no pin must reject the key");

    // The error must carry the exact base64 digest (without the "SHA256:" prefix)
    // so the UI can display it for user confirmation.
    match error {
        SshError::HostKeyUntrusted(presented_fp) => {
            assert_eq!(presented_fp, expected_b64, "HostKeyUntrusted must carry the server's actual fingerprint",);
        }
        other => panic!("expected HostKeyUntrusted, got {other:?}"),
    }
}
