use tracing::debug;

use crate::TunDevice;

pub(in crate::io_loop) fn enqueue_tun_packet(device: &mut TunDevice, raw: Vec<u8>, context: &str) {
    if raw.is_empty() {
        return;
    }
    debug!(bytes = raw.len(), "{context} response queued for tun flush");
    device.tx_queue.push_back(raw);
}
