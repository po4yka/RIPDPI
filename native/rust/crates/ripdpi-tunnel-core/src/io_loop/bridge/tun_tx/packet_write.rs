use std::future::Future;
use std::io;
use std::sync::Arc;

use crate::device::PacketFd;
use tokio_util::sync::CancellationToken;
use tracing::warn;

use crate::Stats;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::io_loop) enum TunFlushOutcome {
    Drained,
    Pending,
    Cancelled,
}

/// # Cancel safety:
///
/// cancel-safe: the packet remains borrowed while waiting for write readiness.
/// A successful synchronous write returns without another await.
pub(super) async fn write_tun_packet(
    tun: &PacketFd,
    stats: &Arc<Stats>,
    packet: &[u8],
    cancel: &CancellationToken,
) -> io::Result<bool> {
    loop {
        if cancel.is_cancelled() {
            return Ok(false);
        }
        match tun.try_send(packet) {
            Ok(written) if written == packet.len() => {
                stats.record_tun_write_success(packet.len());
                return Ok(true);
            }
            Ok(_) => {
                stats.record_tun_write_error();
                return Err(io::Error::new(io::ErrorKind::WriteZero, "incomplete TUN packet write"));
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                if !wait_for_tun_writable(tun.writable(), cancel).await? {
                    return Ok(false);
                }
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => {
                warn!("TUN write error: {error}");
                stats.record_tun_write_error();
                return Err(error);
            }
        }
    }
}

/// # Cancel safety:
/// cancel-safe: both arms only wait for readiness or cancellation and do not consume a packet.
async fn wait_for_tun_writable(
    writable: impl Future<Output = io::Result<()>>,
    cancel: &CancellationToken,
) -> io::Result<bool> {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => Ok(false),
        result = writable => result.map(|()| true),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// # Cancel safety:
    /// cancel-safe: the test owns both endpoints and the first write completes synchronously.
    #[tokio::test]
    async fn short_packet_write_is_an_error_without_success_counters() {
        let (sender, _receiver) = std::os::unix::net::UnixStream::pair().expect("stream pair");
        socket2::SockRef::from(&sender).set_send_buffer_size(4096).expect("bounded stream send buffer");
        let tun = PacketFd::new(sender.into()).expect("adopt stream for short-write fault");
        let stats = Arc::new(Stats::new());
        let packet = vec![0x45; 1024 * 1024];
        let result = write_tun_packet(&tun, &stats, &packet, &CancellationToken::new()).await;

        assert_eq!(result.expect_err("a partial packet must fail").kind(), io::ErrorKind::WriteZero);
        let evidence = stats.tun_forwarding_evidence_snapshot();
        assert_eq!(evidence.tun_write_packets, 0);
        assert_eq!(evidence.tun_write_bytes, 0);
        assert_eq!(evidence.tun_write_errors, 1);
    }

    /// # Cancel safety:
    /// cancel-safe: the only await observes cancellation without changing packet ownership.
    #[tokio::test]
    async fn writable_wait_returns_immediately_when_cancelled() {
        let cancel = CancellationToken::new();
        cancel.cancel();
        let writable = std::future::pending::<io::Result<()>>();
        assert!(!wait_for_tun_writable(writable, &cancel).await.expect("cancel wait"));
    }
}
