use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Instant;

use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, ReadBuf, copy_bidirectional};
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;

use crate::backend::RelayBackend;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::SocksTelemetry;
use crate::telemetry::TcpConnectObservation;

struct CountingIo<S> {
    inner: S,
    read_bytes: u64,
    written_bytes: u64,
}

impl<S> CountingIo<S> {
    const fn new(inner: S) -> Self {
        Self { inner, read_bytes: 0, written_bytes: 0 }
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
                self.read_bytes = self.read_bytes.saturating_add((buf.filled().len() - before) as u64);
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
/// Cancel-safe. `cancel` is the session's shutdown token (a child of the
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
    telemetry: &T,
    cancel: CancellationToken,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    let connect_start = Instant::now();
    // Pre-reply: abandoning the dial by drop is safe — no reply is on the wire.
    let upstream_result = tokio::select! {
        biased;
        () = cancel.cancelled() => return Ok(()),
        result = backend.connect_tcp(&target) => result,
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
            write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(error);
        }
    };

    // Atomic from the client's view: the success reply and the entry into the
    // cancel-aware relay are not separated by any drop point a canceller can
    // observe. A confirmed `CONNECT` therefore always implies the relay started.
    write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
    let target_label = target.to_string();
    let mut client = CountingIo::new(client);
    let mut upstream = CountingIo::new(upstream);
    tokio::select! {
        biased;
        () = cancel.cancelled() => {
            // Graceful close: signal FIN on the client write half so the peer
            // sees an ordinary end-of-session rather than a reset. Sending FIN
            // does not wait on the peer reading, so this cannot stall the drain
            // grace window; any error is ignored since the socket is closing.
            let _ = client.shutdown().await;
            Ok(())
        }
        result = copy_bidirectional(&mut client, &mut upstream) => {
            match result {
                Ok(_) => Ok(()),
                Err(error) => {
                    if confirm_good_eligible && matches!(error.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock) {
                        telemetry.record_confirm_good_passive_stall(
                            &target_label,
                            upstream.written_bytes,
                            upstream.read_bytes,
                            true,
                        );
                    }
                    Err(error)
                }
            }
        },
    }
}

#[cfg(test)]
mod tests {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;

    #[tokio::test]
    async fn counting_io_tracks_read_and_written_bytes_independently() {
        let (stream, mut peer) = tokio::io::duplex(64);
        let mut counted = CountingIo::new(stream);

        peer.write_all(b"response").await.expect("write response fixture");
        let mut response = [0_u8; 8];
        counted.read_exact(&mut response).await.expect("read response fixture");
        counted.write_all(b"request").await.expect("write request fixture");
        let mut request = [0_u8; 7];
        peer.read_exact(&mut request).await.expect("read request fixture");

        assert_eq!(counted.read_bytes, 8);
        assert_eq!(counted.written_bytes, 7);
    }
}
