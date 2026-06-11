use crate::TunDevice;

pub(in crate::io_loop) fn enqueue_tun_packet(device: &mut TunDevice, raw: Vec<u8>) {
    if raw.is_empty() {
        return;
    }
    device.tx_queue.push_back(raw);
}
