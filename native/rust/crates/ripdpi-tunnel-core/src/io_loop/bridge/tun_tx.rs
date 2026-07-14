use std::io;
use std::sync::Arc;

use tokio_util::sync::CancellationToken;
use tun_rs::AsyncDevice;

use super::super::IO_PHASE_WORK_BUDGET;
use crate::{Stats, TunDevice};
use ripdpi_tunnel_intercept::ingress::{SynAckPacketInjector, TunIngressInterceptor};

mod packet_write;

pub(in crate::io_loop) use packet_write::TunFlushOutcome;
use packet_write::write_tun_packet;

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
        if !write_tun_packet(tun, stats, &pkt, cancel).await? {
            return Ok(TunFlushOutcome::Cancelled);
        }
        flushed += 1;
    }
    if device.tx_queue.is_empty() { Ok(TunFlushOutcome::Drained) } else { Ok(TunFlushOutcome::Pending) }
}
