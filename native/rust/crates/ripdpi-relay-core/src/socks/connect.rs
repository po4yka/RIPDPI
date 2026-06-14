use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Instant;

use tokio::io::{AsyncWriteExt, copy_bidirectional};
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;

use crate::backend::RelayBackend;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::SocksTelemetry;
use crate::telemetry::TcpConnectObservation;

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

    let mut upstream = match upstream_result {
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
        result = copy_bidirectional(&mut client, &mut upstream) => result.map(|_| ()),
    }
}
