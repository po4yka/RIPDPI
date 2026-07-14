use std::future::Future;
use std::io;
use std::sync::Arc;
use std::sync::atomic::Ordering;

use tokio_util::sync::CancellationToken;
use tracing::warn;
use tun_rs::AsyncDevice;

use crate::Stats;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::io_loop) enum TunFlushOutcome {
    Drained,
    Pending,
    Cancelled,
}

pub(super) async fn write_tun_packet(
    tun: &AsyncDevice,
    stats: &Arc<Stats>,
    packet: &[u8],
    cancel: &CancellationToken,
) -> io::Result<bool> {
    loop {
        match tun.try_send(packet) {
            Ok(_) => {
                stats.rx_packets.fetch_add(1, Ordering::Relaxed);
                stats.rx_bytes.fetch_add(packet.len() as u64, Ordering::Relaxed);
                return Ok(true);
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                if !wait_for_tun_writable(tun.writable(), cancel).await? {
                    return Ok(false);
                }
            }
            Err(error) => {
                warn!("TUN write error: {error} (packet dropped)");
                return Ok(true);
            }
        }
    }
}

/// Cancel-safe: both arms are cancel-safe readiness notifications and no packet state is mutated while this helper is suspended.
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

    #[tokio::test]
    async fn writable_wait_returns_immediately_when_cancelled() {
        let cancel = CancellationToken::new();
        cancel.cancel();
        let writable = std::future::pending::<io::Result<()>>();
        assert!(!wait_for_tun_writable(writable, &cancel).await.expect("cancel wait"));
    }
}
