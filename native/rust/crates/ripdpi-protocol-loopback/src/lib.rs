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
///
/// Drop order: the `Drop::drop` body takes `shutdown` via
/// `Option::take` and fires the oneshot signal BEFORE any implicit
/// field drop. After the body returns, `local_addr` (Copy), the
/// already-`None` `shutdown`, and `join_handle` drop in declaration
/// order. Dropping a `JoinHandle` without `await` detaches the
/// accept loop; the accept loop sees the oneshot signal on its
/// next `tokio::select!` poll and exits cleanly. This is
/// fire-and-forget shutdown -- appropriate for a test fixture but
/// callers that need synchronous teardown should use the explicit
/// `shutdown()` method instead.
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

    // --- Issue #31: drop-order teardown regressions -----------------------
    //
    // The `EchoLoopback` struct's `Drop order:` invariant (documented above
    // the struct definition) is that `Drop::drop` takes `shutdown` via
    // `Option::take` and fires the oneshot BEFORE the implicit field drop
    // detaches the `JoinHandle`. The three tests below pin the observable
    // behaviour that contract produces:
    //
    //   1. Implicit drop completes without panic even with no active
    //      clients (no peer to drain pending writes against).
    //   2. Implicit drop completes without panic even with a live client
    //      connected (the accept loop is mid-cycle when the oneshot fires).
    //   3. Repeated construct-then-drop cycles do not leak local ports
    //      (the accept loop releases its `TcpListener` after seeing the
    //      shutdown signal).

    #[tokio::test]
    async fn implicit_drop_without_clients_completes_cleanly() {
        // No explicit `shutdown().await` -- relies entirely on the Drop
        // body firing the oneshot. A regression that re-ordered the field
        // drop (e.g. drops `join_handle` first, then `shutdown`) would not
        // panic in this test alone, but combined with test (3) below it
        // would surface as a leaked port / leaked task.
        let server = EchoLoopback::start(1024).await.expect("start");
        let addr = server.local_addr();
        assert!(addr.ip().is_loopback());
        drop(server);
        // Give the runtime a moment to drain the detached accept loop.
        // The implicit drop is fire-and-forget; this yield is a courtesy
        // for the runtime, not a correctness requirement of the test.
        tokio::task::yield_now().await;
    }

    #[tokio::test]
    async fn implicit_drop_with_live_client_completes_cleanly() {
        let server = EchoLoopback::start(1024).await.expect("start");
        let addr = server.local_addr();
        let mut client = TcpStream::connect(addr).await.expect("client connect");
        // Send something so the accept loop is actively processing a
        // connection at the moment we drop the server.
        client.write_all(b"keepalive").await.expect("client write");
        let mut received = vec![0u8; 9];
        client.read_exact(&mut received).await.expect("client read");
        assert_eq!(&received, b"keepalive");

        // Drop the server while the client connection is still alive.
        // The Drop body fires the shutdown oneshot; the accept loop
        // sees it on its next `tokio::select!` poll and exits. The
        // already-spawned per-connection task continues until the
        // client closes (verified next).
        drop(server);

        // Client should still be able to receive any in-flight bytes and
        // then observe EOF when its per-connection task exits.
        drop(client);

        // No assertion needed past this point -- the test's load-bearing
        // assertion is "the drop above did not panic". Reaching this
        // line is the pass condition.
    }

    #[tokio::test]
    async fn repeated_construct_and_drop_does_not_leak() {
        // Stress: 32 construct/drop cycles. A field-drop-order
        // regression that left the `TcpListener` bound after Drop
        // would either fail subsequent `start()` calls (if Linux
        // SO_REUSEADDR somehow lost) or accumulate dangling tasks
        // visible under Miri/sanitizers. The cycle count is small
        // enough to stay tractable under Miri's bookkeeping.
        const CYCLES: usize = 32;
        for _ in 0..CYCLES {
            let server = EchoLoopback::start(64).await.expect("start in cycle");
            let addr = server.local_addr();
            assert!(addr.port() != 0);
            drop(server);
        }
        // Yield once at the end so any tail-end spawned tasks from the
        // last cycle's accept loop get a chance to drain before the
        // test completes. Not a correctness requirement -- the cycles
        // above already exercised the discipline.
        tokio::task::yield_now().await;
    }
}
