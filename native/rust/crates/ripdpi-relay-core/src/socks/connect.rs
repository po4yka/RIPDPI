use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, ReadBuf, copy_bidirectional};
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;

use crate::backend::RelayBackend;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::{RelaySessionTimeouts, SocksTelemetry};
use crate::telemetry::TcpConnectObservation;

const RELAY_IDLE_TIMEOUT_MESSAGE: &str = "relay session exceeded the idle timeout with no traffic";

struct CountingIo<S> {
    inner: S,
    read_bytes: u64,
    written_bytes: u64,
    /// Shared byte total feeding the relay idle watchdog; `None` when the
    /// wrapper is used without a watchdog.
    progress: Option<Arc<AtomicU64>>,
}

struct RelayStageGuard {
    enabled: bool,
    stage: &'static str,
    started: Instant,
    finished: bool,
}

impl RelayStageGuard {
    fn start(enabled: bool, stage: &'static str) -> Self {
        let started = Instant::now();
        emit_relay_stage(enabled, stage, "started", started, None, None);
        Self { enabled, stage, started, finished: false }
    }

    fn finish(&mut self, outcome: &'static str, error: Option<&io::Error>, peer_close_phase: Option<&'static str>) {
        if self.finished {
            return;
        }
        emit_relay_stage(self.enabled, self.stage, outcome, self.started, error, peer_close_phase);
        self.finished = true;
    }
}

impl Drop for RelayStageGuard {
    fn drop(&mut self) {
        if !self.finished {
            emit_relay_stage(self.enabled, self.stage, "cancelled", self.started, None, None);
            self.finished = true;
        }
    }
}

impl<S> CountingIo<S> {
    fn tracked(inner: S, progress: Arc<AtomicU64>) -> Self {
        Self { inner, read_bytes: 0, written_bytes: 0, progress: Some(progress) }
    }

    fn note_progress(&mut self, bytes: u64) {
        if let Some(progress) = &self.progress {
            progress.fetch_add(bytes, Ordering::Relaxed);
        }
    }
}

impl<S> AsyncRead for CountingIo<S>
where
    S: AsyncRead + Unpin,
{
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let before = buf.filled().len();
        match Pin::new(&mut self.inner).poll_read(cx, buf) {
            Poll::Ready(Ok(())) => {
                let advanced = (buf.filled().len() - before) as u64;
                self.read_bytes = self.read_bytes.saturating_add(advanced);
                self.note_progress(advanced);
                Poll::Ready(Ok(()))
            }
            other => other,
        }
    }
}

impl<S> AsyncWrite for CountingIo<S>
where
    S: AsyncWrite + Unpin,
{
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        match Pin::new(&mut self.inner).poll_write(cx, buf) {
            Poll::Ready(Ok(written)) => {
                self.written_bytes = self.written_bytes.saturating_add(written as u64);
                self.note_progress(written as u64);
                Poll::Ready(Ok(written))
            }
            other => other,
        }
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

/// Drive a SOCKS5 `CONNECT`: dial the upstream, send the reply, then relay.
///
/// # Cancel safety
///
/// NOT cancel-safe: an external drop during either `write_reply(...).await`
/// can leave a partial SOCKS reply on the client socket, and a drop of the
/// relay `select!` can discard buffered `copy_bidirectional` bytes. Production
/// callers must await this future directly and request cooperative shutdown
/// through `cancel`; forced task abort is a last-resort socket abandonment.
///
/// `cancel` is the session's shutdown token (a child of the
/// runtime shutdown token); this function — not the caller's `select!` — owns
/// every cancellation point, which is what keeps the success reply atomic with
/// the start of relaying:
///
/// - **Before the success reply** (upstream dial): the dial is raced against
///   `cancel` and abandoned by drop on shutdown. No SOCKS5 reply has been
///   written yet, so an abandoned dial closes the client connection with no
///   reply — never a confirmed `CONNECT` on a socket that never relayed.
/// - **Success reply → relay** is atomic: `write_reply(0x00)` and the entry
///   into the cancel-aware copy are separated by no externally-cancellable
///   drop point (the historic outer `select!` that dropped the whole future
///   here was removed — see `runtime/session.rs`). Once `REP=0x00` is on the
///   wire the only cancellation observed is *inside* the copy `select!`.
/// - **During relay**: a `cancel` win gracefully shuts the client write half
///   (FIN) and returns `Ok(())`. A confirmed `CONNECT` followed by an ordinary
///   connection close is a legitimate end-of-session from the client's view.
///
/// RTT measurement uses wall-clock `Instant`, so a mid-cancel drop of the dial
/// loses at most one in-flight quality sample; the observation is emitted
/// synchronously, with no `.await` between the `Instant` read and emission.
pub(crate) async fn handle_connect<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    target: RelayTargetAddr,
    confirm_good_eligible: bool,
    trace_stages: bool,
    timeouts: RelaySessionTimeouts,
    telemetry: &T,
    cancel: CancellationToken,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    let connect_start = Instant::now();
    // Pre-reply: abandoning the dial by drop is safe — no reply is on the wire.
    // The dial is bounded by `connect_deadline` so a backend that never
    // completes its handshake cannot pin an admission permit indefinitely.
    let upstream_result = tokio::select! {
        biased;
        () = cancel.cancelled() => return Ok(()),
        result = with_deadline(
            backend.connect_tcp(&target),
            timeouts.connect_deadline,
            "relay upstream connect timed out",
        ) => result,
    };
    let rtt_ms = connect_start.elapsed().as_millis() as u64;

    let upstream = match upstream_result {
        Ok(stream) => {
            telemetry.emit_connect_observation(TcpConnectObservation { rtt_ms, succeeded: true });
            stream
        }
        Err(error) => {
            telemetry.emit_connect_observation(TcpConnectObservation { rtt_ms, succeeded: false });
            telemetry.record_handshake_error(error.to_string());
            let mut reply_stage = RelayStageGuard::start(trace_stages, "socks_reply");
            if let Err(reply_error) = write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await
            {
                reply_stage.finish("failed", Some(&reply_error), Some("before_socks_failure_reply"));
                return Err(reply_error);
            }
            reply_stage.finish("succeeded", None, None);
            return Err(error);
        }
    };

    // Atomic from the client's view: the success reply and the entry into the
    // cancel-aware relay are not separated by any drop point a canceller can
    // observe. A confirmed `CONNECT` therefore always implies the relay started.
    let mut reply_stage = RelayStageGuard::start(trace_stages, "socks_reply");
    if let Err(error) = write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await {
        reply_stage.finish("failed", Some(&error), None);
        return Err(error);
    }
    reply_stage.finish("succeeded", None, None);
    let mut relay_stage = RelayStageGuard::start(trace_stages, "relay_stream");
    let target_label = target.to_string();
    let transfer = relay_bidirectional(client, upstream, timeouts.tcp_idle, cancel).await;
    match transfer.result {
        Ok(()) => {
            if transfer.cancelled {
                relay_stage.finish("cancelled", None, None);
            } else {
                relay_stage.finish("closed", None, None);
            }
            Ok(())
        }
        Err(error) => {
            if confirm_good_eligible && matches!(error.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock) {
                telemetry.record_confirm_good_passive_stall(
                    &target_label,
                    transfer.upstream_written_bytes,
                    transfer.upstream_read_bytes,
                    true,
                );
            }
            relay_stage.finish("failed", Some(&error), None);
            Err(error)
        }
    }
}

/// Bound `future` by `deadline`, mapping expiry to a `TimedOut` error.
///
/// Cancel-safe: dropping the returned future drops both the inner future and
/// the deadline sleep with no side effects.
pub(crate) async fn with_deadline<T>(
    future: impl Future<Output = io::Result<T>>,
    deadline: Duration,
    message: &'static str,
) -> io::Result<T> {
    tokio::select! {
        biased;
        result = future => result,
        () = tokio::time::sleep(deadline) => Err(io::Error::new(io::ErrorKind::TimedOut, message)),
    }
}

/// Outcome of a completed relay copy between the SOCKS client and upstream.
pub(crate) struct RelayTransfer {
    pub result: io::Result<()>,
    /// True when the shutdown token ended the transfer gracefully (FIN).
    pub cancelled: bool,
    pub upstream_read_bytes: u64,
    pub upstream_written_bytes: u64,
}

/// Resolve once no relayed byte has moved for a full `idle_timeout` window.
///
/// The watchdog compares the shared byte total across sleeps, so any read or
/// write in either direction restarts the window. Detection latency is bounded
/// by `2 * idle_timeout`; dropping the watchdog loses nothing (it owns only an
/// atomic counter).
async fn idle_watchdog(progress: Arc<AtomicU64>, idle_timeout: Duration) {
    let mut last_seen = progress.load(Ordering::Relaxed);
    loop {
        tokio::time::sleep(idle_timeout).await;
        let current = progress.load(Ordering::Relaxed);
        if current == last_seen {
            return;
        }
        last_seen = current;
    }
}

/// Copy bytes bidirectionally between the confirmed SOCKS client and the
/// upstream stream until either side closes, the shutdown token fires, or no
/// byte flows for `idle_timeout`.
///
/// # Cancel safety
///
/// NOT cancel-safe externally by contract: an external drop can discard
/// buffered `copy_bidirectional` bytes mid-copy. Production callers await this
/// future directly (`runtime/session.rs`) and observe shutdown through
/// `cancel`, which ends the transfer gracefully with a client FIN. Dropping is
/// valid only as last-resort socket abandonment.
pub(crate) async fn relay_bidirectional<A, B>(
    client: A,
    upstream: B,
    idle_timeout: Duration,
    cancel: CancellationToken,
) -> RelayTransfer
where
    A: AsyncRead + AsyncWrite + Unpin + Send,
    B: AsyncRead + AsyncWrite + Unpin + Send,
{
    let progress = Arc::new(AtomicU64::new(0));
    let mut client = CountingIo::tracked(client, Arc::clone(&progress));
    let mut upstream = CountingIo::tracked(upstream, Arc::clone(&progress));
    let mut cancelled = false;
    let result = tokio::select! {
        biased;
        () = cancel.cancelled() => {
            cancelled = true;
            // Graceful close: signal FIN on the client write half so the peer
            // sees an ordinary end-of-session rather than a reset. Sending FIN
            // does not wait on the peer reading, so this cannot stall the drain
            // grace window; any error is ignored since the socket is closing.
            let _ = client.shutdown().await;
            Ok(())
        }
        result = copy_bidirectional(&mut client, &mut upstream) => result.map(|_| ()),
        () = idle_watchdog(progress, idle_timeout) => {
            Err(io::Error::new(io::ErrorKind::TimedOut, RELAY_IDLE_TIMEOUT_MESSAGE))
        }
    };
    RelayTransfer {
        result,
        cancelled,
        upstream_read_bytes: upstream.read_bytes,
        upstream_written_bytes: upstream.written_bytes,
    }
}

fn emit_relay_stage(
    enabled: bool,
    stage: &'static str,
    outcome: &'static str,
    started: Instant,
    error: Option<&io::Error>,
    peer_close_phase: Option<&'static str>,
) {
    if !enabled {
        return;
    }
    let duration_ms = u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX);
    let io_error_kind = error.map(|value| format!("{:?}", value.kind()).to_ascii_lowercase());
    let os_error_code = error.and_then(io::Error::raw_os_error);
    tracing::info!(
        kind = "relay_attempt_stage",
        stage,
        outcome,
        duration_ms,
        failure_stage = error.map(|_| stage),
        failure_class = error.map(|_| "io_error"),
        io_error_kind,
        os_error_code,
        peer_close_phase,
        "relay attempt stage transition"
    );
}

#[cfg(test)]
mod tests {
    use android_support::{EventRingBuffers, EventRingLayer};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tracing_subscriber::prelude::*;

    use super::*;

    #[tokio::test]
    async fn counting_io_tracks_read_and_written_bytes_independently() {
        let (stream, mut peer) = tokio::io::duplex(64);
        let mut counted = CountingIo::tracked(stream, Arc::new(AtomicU64::new(0)));

        peer.write_all(b"response").await.expect("write response fixture");
        let mut response = [0_u8; 8];
        counted.read_exact(&mut response).await.expect("read response fixture");
        counted.write_all(b"request").await.expect("write request fixture");
        let mut request = [0_u8; 7];
        peer.read_exact(&mut request).await.expect("read request fixture");

        assert_eq!(counted.read_bytes, 8);
        assert_eq!(counted.written_bytes, 7);
    }

    #[tokio::test]
    async fn with_deadline_returns_timed_out_error_when_the_inner_future_stalls() {
        let started = Instant::now();
        let result = tokio::time::timeout(
            Duration::from_secs(2),
            with_deadline(std::future::pending::<io::Result<()>>(), Duration::from_millis(50), "dial deadline"),
        )
        .await
        .expect("with_deadline must resolve instead of hanging");

        let error = result.expect_err("a stalled future must surface the deadline");
        assert_eq!(io::ErrorKind::TimedOut, error.kind());
        assert!(started.elapsed() < Duration::from_secs(1), "deadline must fire near 50ms");
    }

    #[tokio::test]
    async fn with_deadline_returns_inner_value_before_the_deadline() {
        let result = with_deadline(std::future::ready(Ok(7_u8)), Duration::from_secs(5), "unused").await;
        assert_eq!(7, result.expect("inner value must pass through"));
    }

    #[tokio::test]
    async fn relay_bidirectional_times_out_when_neither_side_sends_bytes() {
        let (client, _client_peer) = tokio::io::duplex(64);
        let (upstream, _upstream_peer) = tokio::io::duplex(64);
        let cancel = CancellationToken::new();

        let outcome = tokio::time::timeout(
            Duration::from_secs(2),
            relay_bidirectional(client, upstream, Duration::from_millis(50), cancel),
        )
        .await
        .expect("relay idle watchdog did not fire; transfer hung past the outer guard");

        let error = outcome.result.expect_err("a fully silent relay must surface the idle timeout");
        assert_eq!(io::ErrorKind::TimedOut, error.kind());
        assert!(!outcome.cancelled);
    }

    #[tokio::test]
    async fn relay_bidirectional_survives_while_bytes_keep_flowing() {
        let (client, mut client_peer) = tokio::io::duplex(64);
        let (upstream, mut upstream_peer) = tokio::io::duplex(64);
        let cancel = CancellationToken::new();

        let pump = tokio::spawn(async move {
            for _ in 0..20 {
                client_peer.write_all(b"payload").await.expect("write relay payload");
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            let mut seen = [0_u8; 140];
            upstream_peer.read_exact(&mut seen).await.expect("client payload must reach the upstream peer");
            // Close both peers so the relay observes EOF in both directions.
            drop(client_peer);
            drop(upstream_peer);
        });

        let outcome = tokio::time::timeout(
            Duration::from_secs(2),
            relay_bidirectional(client, upstream, Duration::from_millis(50), cancel),
        )
        .await
        .expect("transfer must end once both peers close");

        pump.await.expect("pump task");
        outcome.result.expect("steady traffic must outlive the idle window");
        assert_eq!(140, outcome.upstream_written_bytes, "every forwarded byte must be counted");
    }

    #[tokio::test]
    async fn relay_bidirectional_reports_cancelled_outcome_on_shutdown_token() {
        let (client, _client_peer) = tokio::io::duplex(64);
        let (upstream, _upstream_peer) = tokio::io::duplex(64);
        let cancel = CancellationToken::new();
        cancel.cancel();

        let outcome = tokio::time::timeout(
            Duration::from_secs(2),
            relay_bidirectional(client, upstream, Duration::from_secs(60), cancel),
        )
        .await
        .expect("cancelled transfer must resolve promptly");

        assert!(outcome.cancelled, "shutdown token must mark the transfer cancelled");
        outcome.result.expect("graceful cancellation is not an error");
    }

    #[test]
    fn dropped_reached_stage_emits_exactly_one_cancelled_terminal() {
        let buffers = EventRingBuffers::default();
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        tracing::subscriber::with_default(subscriber, || {
            let attempt = tracing::info_span!(
                "relay_attempt",
                ring = "relay",
                source = "ripdpi-relay-core",
                subsystem = "relay",
                runtime_id = "runtime-forced-abort",
                attempt_id = 1_u64,
            );
            let _entered = attempt.enter();
            drop(RelayStageGuard::start(true, "socks_reply"));
        });

        let outcomes = buffers
            .drain_relay_for_runtime("runtime-forced-abort")
            .events
            .into_iter()
            .map(|event| event.outcome)
            .collect::<Vec<_>>();
        assert_eq!(outcomes, [Some("started".to_string()), Some("cancelled".to_string())]);
    }
}
