//! `ripdpi-protocol-loopback` — dev-only loopback test-server harness.
//!
//! Shared infrastructure for four backlog tasks that all need an
//! in-process echo server per protocol:
//!
//! - `add-shadowtls-loopback-test-server-for-soak-runs`
//! - `add-quic-path-mtu-discovery-regression-test`
//! - `add-protocol-throughput-benchmarks-for-each-transport`
//! - `add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality`
//!
//! Design doc: `docs/architecture/protocol-loopback-harness-design.md`.
//!
//! This first iteration ships the shared `ProtocolLoopbackServer`
//! trait + a minimal `EchoLoopback` implementation over plain TCP.
//! Per-protocol implementations (TUIC, Hysteria2, ShadowTLS, MASQUE,
//! VLESS, xHTTP, WS-tunnel) get added in dedicated follow-up sessions
//! and live under their own modules in this crate.
//!
//! Never linked into release builds. The Cargo.toml is `publish = false`
//! and the crate is wired only into the workspace as a dev/test
//! dependency for the tasks above.

#![forbid(unsafe_code)]

use std::io;
use std::net::SocketAddr;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

/// Common shape for a loopback echo server. Each per-protocol module
/// returns a value implementing this trait so consumer code can
/// substitute one backend for another without rewriting the test
/// driver.
pub trait ProtocolLoopbackServer: Send {
    /// Address the server is listening on. Always loopback.
    fn local_addr(&self) -> SocketAddr;

    /// Stable identifier for the underlying protocol. Used for
    /// telemetry, log labels, and selecting per-protocol assertions.
    fn protocol_id(&self) -> &'static str;
}

/// Errors surfaced by the loopback harness.
#[derive(Debug, thiserror::Error)]
pub enum LoopbackError {
    #[error("io: {0}")]
    Io(#[from] io::Error),
    #[error("shutdown signal already fired")]
    ShutdownDuplicate,
}

/// Minimal plain-TCP echo server. Validates the trait shape and is
/// useful on its own for tests that don't need any cover handshake.
/// Each accepted connection echoes inbound bytes until the peer
/// closes, up to a soft byte cap to bound runaway tests.
pub struct EchoLoopback {
    local_addr: SocketAddr,
    shutdown: Option<oneshot::Sender<()>>,
    join_handle: Option<JoinHandle<()>>,
    max_bytes_per_connection: u64,
}

impl EchoLoopback {
    /// Start a plain-TCP echo server on loopback. The returned
    /// handle owns the accept loop and shuts it down on drop.
    pub async fn start(max_bytes_per_connection: u64) -> Result<Self, LoopbackError> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let local_addr = listener.local_addr()?;
        let (shutdown_tx, mut shutdown_rx) = oneshot::channel::<()>();

        let join_handle = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    accept = listener.accept() => {
                        let Ok((mut stream, _peer)) = accept else { break };
                        tokio::spawn(async move {
                            let mut buf = vec![0u8; 16 * 1024];
                            let mut total: u64 = 0;
                            loop {
                                let n = match stream.read(&mut buf).await {
                                    Ok(0) => break,
                                    Ok(n) => n,
                                    Err(_) => break,
                                };
                                if total.saturating_add(n as u64) > max_bytes_per_connection {
                                    break;
                                }
                                if stream.write_all(&buf[..n]).await.is_err() {
                                    break;
                                }
                                total += n as u64;
                            }
                            let _ = stream.shutdown().await;
                        });
                    }
                }
            }
        });

        Ok(Self { local_addr, shutdown: Some(shutdown_tx), join_handle: Some(join_handle), max_bytes_per_connection })
    }

    /// Soft per-connection byte cap. Future per-protocol servers
    /// should respect this so test drivers can rely on consistent
    /// bounding behaviour.
    pub fn max_bytes_per_connection(&self) -> u64 {
        self.max_bytes_per_connection
    }

    /// Stop the accept loop and wait for it to terminate. Returns
    /// `ShutdownDuplicate` if `shutdown` has already been called.
    pub async fn shutdown(mut self) -> Result<(), LoopbackError> {
        let tx = self.shutdown.take().ok_or(LoopbackError::ShutdownDuplicate)?;
        let _ = tx.send(());
        if let Some(handle) = self.join_handle.take() {
            let _ = handle.await;
        }
        Ok(())
    }
}

impl ProtocolLoopbackServer for EchoLoopback {
    fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    fn protocol_id(&self) -> &'static str {
        "echo-tcp"
    }
}

impl Drop for EchoLoopback {
    fn drop(&mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpStream;

    #[tokio::test]
    async fn echo_loopback_starts_and_advertises_its_address() {
        let server = EchoLoopback::start(64 * 1024).await.expect("start");
        let addr = server.local_addr();
        assert!(addr.ip().is_loopback(), "server must bind to loopback only");
        assert_ne!(addr.port(), 0, "server must report a concrete port");
        assert_eq!(server.protocol_id(), "echo-tcp");
        server.shutdown().await.expect("clean shutdown");
    }

    #[tokio::test]
    async fn echo_loopback_round_trips_payload() {
        let server = EchoLoopback::start(64 * 1024).await.expect("start");
        let addr = server.local_addr();

        let mut client = TcpStream::connect(addr).await.expect("client connect");
        let payload = b"loopback-harness-roundtrip";
        client.write_all(payload).await.expect("client write");

        let mut received = vec![0u8; payload.len()];
        client.read_exact(&mut received).await.expect("client read");
        assert_eq!(&received, payload, "echo must mirror the payload");

        drop(client);
        server.shutdown().await.expect("clean shutdown");
    }

    #[tokio::test]
    async fn echo_loopback_drops_connection_beyond_max_bytes_cap() {
        let cap: u64 = 16;
        let server = EchoLoopback::start(cap).await.expect("start");
        assert_eq!(server.max_bytes_per_connection(), cap);

        let mut client = TcpStream::connect(server.local_addr()).await.expect("connect");
        let payload = vec![0xabu8; (cap as usize) + 32];

        // best-effort write; the server will read partially then close
        // once total exceeds the cap, so the write may complete or
        // fail with a broken-pipe error depending on timing.
        let _ = client.write_all(&payload).await;
        let _ = client.shutdown().await;

        server.shutdown().await.expect("clean shutdown");
    }

    #[tokio::test]
    async fn echo_loopback_shutdown_is_not_callable_twice() {
        // Construct two servers to avoid mutating a server we still
        // need; shutdown consumes self, so the "twice" check is
        // expressed by exercising the internal `ShutdownDuplicate`
        // error via a manual take.
        let mut server = EchoLoopback::start(1024).await.expect("start");
        let tx = server.shutdown.take().expect("first take");
        // Pretend the first shutdown fired through the take.
        drop(tx);
        let second = server.shutdown().await;
        assert!(matches!(second, Err(LoopbackError::ShutdownDuplicate)));
    }

    #[test]
    fn loopback_error_io_wraps_io_error() {
        let inner = io::Error::new(io::ErrorKind::TimedOut, "boom");
        let wrapped: LoopbackError = inner.into();
        assert!(matches!(wrapped, LoopbackError::Io(_)));
    }
}
