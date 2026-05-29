use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::session::Auth;
use crate::TunDevice;

use super::association_state::{now_millis, touch_udp_activity, udp_association_is_idle};
use super::event_handling::{handle_udp_event, UdpEvent};
use super::shutdown::shutdown_udp_associations;
use super::worker::create_udp_association;

async fn spawn_udp_associate_stub() -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind proxy listener");
    let proxy_addr = listener.local_addr().expect("proxy addr");

    tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.expect("accept proxy");
        let mut buf = [0u8; 64];

        let _ = stream.read(&mut buf).await.expect("read greeting");
        stream.write_all(&[0x05, 0x00]).await.expect("write no-auth");

        let _ = stream.read(&mut buf).await.expect("read udp associate");
        stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x13, 0x88]).await.expect("write udp associate reply");
        tokio::time::sleep(Duration::from_secs(1)).await;
    });

    proxy_addr
}

#[tokio::test]
async fn handle_udp_event_queues_matching_association_packet() {
    let proxy_addr = spawn_udp_associate_stub().await;
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let cancel = CancellationToken::new();
    let association = create_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        "203.0.113.20:443".parse().expect("valid test addr"),
        7,
        Duration::from_secs(1),
        cancel.child_token(),
        udp_tx,
    )
    .await
    .expect("udp association");
    let worker = association.worker.abort_handle();
    let mut associations = HashMap::from([(src, association)]);
    let mut device = TunDevice::new(1500);

    handle_udp_event(
        &mut device,
        &mut associations,
        UdpEvent::Packet { src, association_id: 7, raw: vec![1, 2, 3, 4] },
    );

    assert_eq!(device.tx_queue.front().expect("queued udp packet"), &vec![1, 2, 3, 4]);
    if let Some(association) = associations.remove(&src) {
        association.cancel.cancel();
        worker.abort();
    }
}

#[tokio::test]
async fn handle_udp_event_ignores_stale_association_id() {
    let proxy_addr = spawn_udp_associate_stub().await;
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53001);
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let cancel = CancellationToken::new();
    let association = create_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        "203.0.113.20:443".parse().expect("valid test addr"),
        10,
        Duration::from_secs(1),
        cancel.child_token(),
        udp_tx,
    )
    .await
    .expect("udp association");
    let worker = association.worker.abort_handle();
    let mut associations = HashMap::from([(src, association)]);
    let mut device = TunDevice::new(1500);

    handle_udp_event(&mut device, &mut associations, UdpEvent::Packet { src, association_id: 99, raw: vec![5, 6, 7] });

    assert!(device.tx_queue.is_empty(), "stale association_id should not enqueue packet");
    if let Some(association) = associations.remove(&src) {
        association.cancel.cancel();
        worker.abort();
    }
}

#[tokio::test]
async fn handle_udp_event_removes_closed_association() {
    let proxy_addr = spawn_udp_associate_stub().await;
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53002);
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let cancel = CancellationToken::new();
    let association = create_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        "203.0.113.20:443".parse().expect("valid test addr"),
        20,
        Duration::from_secs(1),
        cancel.child_token(),
        udp_tx,
    )
    .await
    .expect("udp association");
    let worker = association.worker.abort_handle();
    let mut associations = HashMap::from([(src, association)]);
    let mut device = TunDevice::new(1500);

    handle_udp_event(&mut device, &mut associations, UdpEvent::Closed { src, association_id: 20 });

    assert!(associations.is_empty(), "closed event should remove association");
    worker.abort();
}

#[tokio::test]
async fn handle_udp_event_ignores_stale_close() {
    let proxy_addr = spawn_udp_associate_stub().await;
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53003);
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let cancel = CancellationToken::new();
    let association = create_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        "203.0.113.20:443".parse().expect("valid test addr"),
        30,
        Duration::from_secs(1),
        cancel.child_token(),
        udp_tx,
    )
    .await
    .expect("udp association");
    let worker = association.worker.abort_handle();
    let mut associations = HashMap::from([(src, association)]);
    let mut device = TunDevice::new(1500);

    handle_udp_event(&mut device, &mut associations, UdpEvent::Closed { src, association_id: 999 });

    assert_eq!(associations.len(), 1, "stale close should not remove current association");
    if let Some(association) = associations.remove(&src) {
        association.cancel.cancel();
        worker.abort();
    }
}

#[test]
fn touch_udp_activity_updates_timestamp() {
    let last_activity = Arc::new(AtomicU64::new(now_millis() - 60_000));
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    let before = last_activity.load(Ordering::Relaxed);

    touch_udp_activity(&last_activity);

    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    let after = last_activity.load(Ordering::Relaxed);
    assert!(after > before, "timestamp should be refreshed after touch");
    assert!(now_millis().saturating_sub(after) < 1_000, "timestamp should be very recent");
}

#[test]
fn idle_detection_true_after_timeout() {
    let last_activity = Arc::new(AtomicU64::new(now_millis() - 60_000));
    assert!(udp_association_is_idle(&last_activity, Duration::from_secs(30)), "should be idle after timeout");
}

#[test]
fn idle_detection_false_when_fresh() {
    let last_activity = Arc::new(AtomicU64::new(now_millis()));
    assert!(
        !udp_association_is_idle(&last_activity, Duration::from_secs(30)),
        "should not be idle when recently active"
    );
}

#[tokio::test]
async fn shutdown_cancels_all_associations() {
    let proxy_addr = spawn_udp_associate_stub().await;
    let src1 = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53010);
    let src2 = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53011);
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(4);
    let cancel = CancellationToken::new();

    let a1 = create_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src1,
        "203.0.113.21:443".parse().expect("valid test addr"),
        1,
        Duration::from_secs(1),
        cancel.child_token(),
        udp_tx.clone(),
    )
    .await
    .expect("association 1");

    let proxy_addr2 = spawn_udp_associate_stub().await;
    let a2 = create_udp_association(
        proxy_addr2,
        Auth::NoAuth,
        src2,
        "203.0.113.22:443".parse().expect("valid test addr"),
        2,
        Duration::from_secs(1),
        cancel.child_token(),
        udp_tx,
    )
    .await
    .expect("association 2");

    let cancel1 = a1.cancel.clone();
    let cancel2 = a2.cancel.clone();
    let mut associations = HashMap::from([(src1, a1), (src2, a2)]);

    shutdown_udp_associations(&mut associations).await;

    assert!(associations.is_empty(), "all associations should be drained");
    assert!(cancel1.is_cancelled(), "association 1 cancel token should be cancelled");
    assert!(cancel2.is_cancelled(), "association 2 cancel token should be cancelled");
}
