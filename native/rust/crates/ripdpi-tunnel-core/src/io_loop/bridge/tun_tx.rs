use std::future::Future;
use std::io;
use std::sync::Arc;
use std::sync::atomic::Ordering;

use tokio_util::sync::CancellationToken;
use tracing::warn;
use tun_rs::AsyncDevice;

use super::super::IO_PHASE_WORK_BUDGET;
use crate::{Stats, TunDevice};
use ripdpi_tunnel_intercept::ingress::{SynAckPacketInjector, TunIngressInterceptor};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::io_loop) enum TunFlushOutcome {
    Drained,
    Pending,
    Cancelled,
}

/// Flush one bounded packet batch from the userspace queue to TUN.
///
/// NOT cancel-safe when the returned future is dropped directly: the current
/// packet has already been removed from the queue. Callers must signal the
/// supplied token and keep polling until [`TunFlushOutcome::Cancelled`].
pub(in crate::io_loop) async fn flush_device_tx_queue(
    tun: &AsyncDevice,
    stats: &Arc<Stats>,
    device: &mut TunDevice,
    synack_interceptor: &mut TunIngressInterceptor<impl SynAckPacketInjector>,
    cancel: &CancellationToken,
) -> io::Result<TunFlushOutcome> {
    let mut flushed = 0;
    while flushed < IO_PHASE_WORK_BUDGET {
        if cancel.is_cancelled() {
            return Ok(TunFlushOutcome::Cancelled);
        }
        let Some(pkt) = device.tx_queue.pop_front() else {
            return Ok(TunFlushOutcome::Drained);
        };
        synack_interceptor.handle_packet(&pkt);
        // Feed the outbound (userspace -> TUN) packet to the optional
        // synchronous observer (e.g. PCAP capture-set) before the write. We
        // emit before `try_send` because the observer captures intent-to-write.
        // A WouldBlock readiness wait retries the SAME packet without invoking
        // the observer again.
        stats.on_outbound_packet(&pkt);
        loop {
            match tun.try_send(&pkt) {
                Ok(_) => {
                    stats.rx_packets.fetch_add(1, Ordering::Relaxed);
                    stats.rx_bytes.fetch_add(pkt.len() as u64, Ordering::Relaxed);
                    break;
                }
                Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                    if !wait_for_tun_writable(tun.writable(), cancel).await? {
                        return Ok(TunFlushOutcome::Cancelled);
                    }
                }
                Err(err) => {
                    warn!("TUN write error: {err} (packet dropped)");
                    break;
                }
            }
        }
        flushed += 1;
    }
    if device.tx_queue.is_empty() { Ok(TunFlushOutcome::Drained) } else { Ok(TunFlushOutcome::Pending) }
}

/// Cancel-safe: both arms are cancel-safe readiness notifications and no
/// packet state is mutated while this helper is suspended.
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
