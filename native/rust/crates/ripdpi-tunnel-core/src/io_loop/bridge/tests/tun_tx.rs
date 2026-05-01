use crate::TunDevice;

use super::super::enqueue_tun_packet;

#[test]
fn u20_enqueue_tun_packet_adds_to_queue() {
    let mut device = TunDevice::new(1500);
    enqueue_tun_packet(&mut device, vec![10, 20, 30], "test");
    assert_eq!(device.tx_queue.len(), 1);
    assert_eq!(device.tx_queue.front().unwrap(), &vec![10, 20, 30]);
}

#[test]
fn u21_enqueue_tun_packet_ignores_empty() {
    let mut device = TunDevice::new(1500);
    enqueue_tun_packet(&mut device, vec![], "test");
    assert!(device.tx_queue.is_empty(), "empty packet should not be enqueued");
}
