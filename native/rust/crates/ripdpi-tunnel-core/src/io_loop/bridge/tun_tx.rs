use std::io;
use std::sync::atomic::Ordering;
use std::sync::Arc;

use tracing::warn;
use tun_rs::AsyncDevice;

use crate::{Stats, TunDevice};
use ripdpi_tunnel_intercept::ingress::{SynAckPacketInjector, TunIngressInterceptor};

pub(in crate::io_loop) async fn flush_device_tx_queue(
    tun: &AsyncDevice,
    stats: &Arc<Stats>,
    device: &mut TunDevice,
    synack_interceptor: &mut TunIngressInterceptor<impl SynAckPacketInjector>,
) -> io::Result<()> {
    while let Some(pkt) = device.tx_queue.pop_front() {
        synack_interceptor.handle_packet(&pkt);
        loop {
            match tun.try_send(&pkt) {
                Ok(_) => {
                    stats.rx_packets.fetch_add(1, Ordering::Relaxed);
                    stats.rx_bytes.fetch_add(pkt.len() as u64, Ordering::Relaxed);
                    break;
                }
                Err(err) if err.kind() == io::ErrorKind::WouldBlock => tun.writable().await?,
                Err(err) => {
                    warn!("TUN write error: {err} (packet dropped)");
                    break;
                }
            }
        }
    }
    Ok(())
}
