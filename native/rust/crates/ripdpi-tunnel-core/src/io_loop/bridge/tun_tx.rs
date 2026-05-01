use std::io;
use std::sync::atomic::Ordering;
use std::sync::Arc;

use tracing::{debug, warn};
use tun_rs::AsyncDevice;

use crate::{Stats, TunDevice};

pub(in crate::io_loop) fn enqueue_tun_packet(device: &mut TunDevice, raw: Vec<u8>, context: &str) {
    if raw.is_empty() {
        return;
    }
    debug!(bytes = raw.len(), "{context} response queued for tun flush");
    device.tx_queue.push_back(raw);
}

pub(in crate::io_loop) async fn flush_device_tx_queue(
    tun: &AsyncDevice,
    stats: &Arc<Stats>,
    device: &mut TunDevice,
) -> io::Result<()> {
    while let Some(pkt) = device.tx_queue.pop_front() {
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
