use crate::{Stats, TunDevice};
use smoltcp::phy::Device as _;
use std::sync::Arc;

use super::super::enqueue_tun_packet;
use super::support::establish_tcp_connection;

#[test]
fn u20_enqueue_tun_packet_adds_to_queue() {
    let mut device = TunDevice::new(1500);
    let stats = Stats::new();
    enqueue_tun_packet(&mut device, &stats, vec![10, 20, 30]);
    assert_eq!(device.tx_queue.len(), 1);
    assert_eq!(device.tx_queue.front().unwrap(), &vec![10, 20, 30]);
}

#[test]
fn u21_enqueue_tun_packet_ignores_empty() {
    let mut device = TunDevice::new(1500);
    let stats = Stats::new();
    enqueue_tun_packet(&mut device, &stats, vec![]);
    assert!(device.tx_queue.is_empty(), "empty packet should not be enqueued");
}

#[test]
fn tun_tx_queue_tail_drops_at_packet_budget() {
    let mut device = crate::TunDevice::new(1500);
    let stats = Arc::new(Stats::new());
    device.set_tun_queue_drop_stats(Arc::clone(&stats));
    for _ in 0..=4096 {
        enqueue_tun_packet(&mut device, &stats, vec![1]);
    }

    assert_eq!(device.tx_queue.len(), 4096, "TUN TX packet backlog must be bounded");
    assert_eq!(device.tx_queue.dropped_packets(), 1);
    assert_eq!(stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 1);
}

#[test]
fn tun_tx_queue_tail_drops_at_byte_budget() {
    let mut device = crate::TunDevice::new(1500);
    let stats = Arc::new(Stats::new());
    device.set_tun_queue_drop_stats(Arc::clone(&stats));
    for _ in 0..9 {
        enqueue_tun_packet(&mut device, &stats, vec![0; 1024 * 1024]);
    }

    assert_eq!(device.tx_queue.len(), 8, "TUN TX byte backlog must be bounded independently of packet count");
    assert_eq!(device.tx_queue.queued_bytes(), 8 * 1024 * 1024);
    assert_eq!(device.tx_queue.dropped_packets(), 1);

    let drained = device.tx_queue.pop_front().expect("drain one queued packet");
    assert_eq!(drained.len(), 1024 * 1024);
    assert_eq!(device.tx_queue.queued_bytes(), 7 * 1024 * 1024);
    enqueue_tun_packet(&mut device, &stats, vec![0; 1024 * 1024]);
    assert_eq!(device.tx_queue.len(), 8, "draining must release byte budget for a later packet");
    assert_eq!(stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 1);
}

#[test]
fn smoltcp_tx_token_queue_rejection_records_evidence_drop() {
    let mut device = crate::TunDevice::new(1500);
    let stats = Arc::new(Stats::new());
    device.set_tun_queue_drop_stats(Arc::clone(&stats));
    for _ in 0..4096 {
        assert!(device.tx_queue.push_back(vec![1]));
    }
    // A reply to a received packet still takes a token and is dropped when the queue is full.
    device.rx_queue.push_back(vec![0x45]);
    let (_rx, tx) = device.receive(smoltcp::time::Instant::now()).expect("rx and tx tokens");

    smoltcp::phy::TxToken::consume(tx, 1, |buf| {
        buf[0] = 7;
    });

    assert_eq!(device.tx_queue.dropped_packets(), 1);
    assert_eq!(stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 1);
}

#[test]
fn full_tx_queue_holds_smoltcp_segments_until_the_queue_drains() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);
    for _ in 0..4096 {
        assert!(device.tx_queue.push_back(vec![1]));
    }

    let payload = b"downstream data";
    socket_set.get_mut::<smoltcp::socket::tcp::Socket>(handle).send_slice(payload).expect("queue data");
    iface.poll(smoltcp::time::Instant::now(), &mut device, &mut socket_set);
    assert_eq!(device.tx_queue.dropped_packets(), 0, "a full queue must stop smoltcp, not drop its segment");

    device.tx_queue.clear();
    iface.poll(smoltcp::time::Instant::now(), &mut device, &mut socket_set);
    // The test peer advertised a zero window, so smoltcp sends the first byte as a window probe.
    let sent = std::iter::from_fn(|| device.tx_queue.pop_front()).any(|pkt| pkt.get(40) == Some(&payload[0]));
    assert!(sent, "the held segment must go out at the next poll, not after a retransmission timeout");
}
