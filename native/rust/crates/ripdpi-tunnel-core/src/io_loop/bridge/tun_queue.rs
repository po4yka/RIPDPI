use crate::{Stats, TunDevice};

pub(in crate::io_loop) fn enqueue_tun_packet(device: &mut TunDevice, _stats: &Stats, raw: Vec<u8>) {
    if raw.is_empty() {
        return;
    }
    let _ = device.tx_queue.push_back(raw);
}
