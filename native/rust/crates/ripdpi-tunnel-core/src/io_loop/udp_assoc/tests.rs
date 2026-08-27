use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;
use crate::{Stats, TunDevice};

use super::association_state::{
    OutboundDatagram, UdpAssociation, now_millis, touch_udp_activity, udp_association_is_idle,
};
use super::event_handling::{UdpEvent, handle_udp_event};
use super::eviction::{UdpEvictionEntry, evict_if_at_capacity};
use super::forwarding::lease_udp_mapping;
use super::shutdown::shutdown_udp_associations;
use super::worker::spawn_udp_association;

struct WorkerAlive(Arc<AtomicBool>);

impl Drop for WorkerAlive {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

fn stalled_udp_association(
    id: u64,
    _dest: SocketAddr,
) -> (UdpAssociation, Arc<AtomicBool>, tokio::sync::oneshot::Receiver<()>) {
    let (outbound, _outbound_rx) = tokio::sync::mpsc::channel(1);
    let cancel = CancellationToken::new();
    let alive = Arc::new(AtomicBool::new(true));
    let worker_alive = WorkerAlive(alive.clone());
    let (started_tx, started_rx) = tokio::sync::oneshot::channel();
    let worker = tokio::spawn(async move {
        let _worker_alive = worker_alive;
        let _ = started_tx.send(());
        std::future::pending::<()>().await;
    });
    let association = UdpAssociation {
        id,
        activity_generation: 0,
        outbound,
        cancel,
        last_activity: Arc::new(AtomicU64::new(now_millis())),
        worker,
        leased_synthetic_ips: std::collections::HashSet::new(),
        attribution_tokens: lru::LruCache::new(super::association_state::UDP_ATTRIBUTION_TOKEN_CAPACITY),
    };
    (association, alive, started_rx)
}

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

async fn spawn_udp_recording_stub() -> (SocketAddr, tokio::sync::oneshot::Receiver<Vec<u8>>) {
    let relay = UdpSocket::bind("127.0.0.1:0").await.expect("bind UDP relay");
    let relay_addr = relay.local_addr().expect("relay address");
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind proxy listener");
    let proxy_addr = listener.local_addr().expect("proxy address");
    let (datagram_tx, datagram_rx) = tokio::sync::oneshot::channel();

    tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.expect("accept proxy");
        let mut buf = [0u8; 64];
        let _ = stream.read(&mut buf).await.expect("read greeting");
        stream.write_all(&[0x05, 0x00]).await.expect("write no-auth");
        let _ = stream.read(&mut buf).await.expect("read UDP associate");
        let port = relay_addr.port().to_be_bytes();
        stream
            .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, port[0], port[1]])
            .await
            .expect("write UDP associate reply");
        let mut datagram = vec![0u8; 2048];
        let (len, _) = relay.recv_from(&mut datagram).await.expect("receive queued datagram");
        datagram.truncate(len);
        let _ = datagram_tx.send(datagram);
        std::future::pending::<()>().await;
    });

    (proxy_addr, datagram_rx)
}

async fn spawn_udp_echo_stub() -> SocketAddr {
    let relay = UdpSocket::bind("127.0.0.1:0").await.expect("bind UDP echo relay");
    let relay_addr = relay.local_addr().expect("relay address");
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind proxy listener");
    let proxy_addr = listener.local_addr().expect("proxy address");

    tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.expect("accept proxy");
        let mut buf = [0u8; 64];
        let _ = stream.read(&mut buf).await.expect("read greeting");
        stream.write_all(&[0x05, 0x00]).await.expect("write no-auth");
        let _ = stream.read(&mut buf).await.expect("read UDP associate");
        let port = relay_addr.port().to_be_bytes();
        stream
            .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, port[0], port[1]])
            .await
            .expect("write UDP associate reply");

        let mut datagram = vec![0u8; 2048];
        let (len, peer) = relay.recv_from(&mut datagram).await.expect("receive UDP frame");
        relay.send_to(&datagram[..len], peer).await.expect("echo UDP frame");
        std::future::pending::<()>().await;
    });

    proxy_addr
}

#[tokio::test]
async fn association_worker_sends_datagram_queued_during_setup() {
    let (proxy_addr, datagram_rx) = spawn_udp_recording_stub().await;
    let src = "10.0.0.2:53009".parse().expect("source address");
    let dest = "203.0.113.20:443".parse().expect("destination address");
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let memory_budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let association = spawn_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        dest,
        9,
        Duration::from_secs(1),
        None,
        memory_budget.clone(),
        CancellationToken::new(),
        udp_tx,
    );

    association
        .outbound
        .try_send(
            OutboundDatagram::try_new(
                crate::session::TargetAddr::Ip(dest),
                dest,
                dest,
                b"queued-during-setup",
                &memory_budget,
            )
            .expect("reserve queue bytes"),
        )
        .expect("queue datagram");
    let datagram = tokio::time::timeout(Duration::from_secs(1), datagram_rx)
        .await
        .expect("worker should finish setup")
        .expect("recording relay should receive datagram");

    assert!(datagram.ends_with(b"queued-during-setup"));
    association.cancel.cancel();
    association.worker.abort();
}

#[tokio::test]
async fn mapdns_udp_response_uses_synthetic_source_tuple() {
    let proxy_addr = spawn_udp_echo_stub().await;
    let src = "10.0.0.2:53010".parse().expect("source address");
    let resolved = "203.0.113.20:3478".parse().expect("resolved destination");
    let synthetic = "198.18.0.9:3478".parse().expect("synthetic destination");
    let (udp_tx, mut udp_rx) = tokio::sync::mpsc::channel(1);
    let memory_budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let association = spawn_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        resolved,
        10,
        Duration::from_secs(1),
        None,
        memory_budget.clone(),
        CancellationToken::new(),
        udp_tx,
    );

    association
        .outbound
        .try_send(
            OutboundDatagram::try_new(
                crate::session::TargetAddr::ResolvedDomain("stun.example".into(), resolved),
                resolved,
                synthetic,
                b"stun",
                &memory_budget,
            )
            .expect("reserve queue bytes"),
        )
        .expect("queue datagram");
    let event = tokio::time::timeout(Duration::from_secs(1), udp_rx.recv())
        .await
        .expect("worker response deadline")
        .expect("worker response event");
    let UdpEvent::Packet { raw, .. } = event else {
        panic!("expected UDP packet event");
    };

    assert_eq!(&raw[12..16], &Ipv4Addr::new(198, 18, 0, 9).octets());
    assert_eq!(u16::from_be_bytes([raw[20], raw[21]]), 3478);
    association.cancel.cancel();
    association.worker.abort();
}

#[tokio::test]
async fn handle_udp_event_queues_matching_association_packet() {
    let proxy_addr = spawn_udp_associate_stub().await;
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let cancel = CancellationToken::new();
    let association = spawn_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        "203.0.113.20:443".parse().expect("valid test addr"),
        7,
        Duration::from_secs(1),
        None,
        UdpMemoryBudget::for_tunnel_mtu(1500),
        cancel.child_token(),
        udp_tx,
    );
    let worker = association.worker.abort_handle();
    let mut associations = HashMap::from([(src, association)]);
    let mut device = TunDevice::new(1500);
    let stats = Stats::new();
    let mut eviction_heap = BoundedHeap::new(4);

    handle_udp_event(
        &mut device,
        &stats,
        &mut associations,
        &mut eviction_heap,
        &mut None,
        UdpEvent::Packet { src, association_id: 7, raw: vec![1, 2, 3, 4] },
    );

    assert_eq!(device.tx_queue.front().expect("queued udp packet"), &vec![1, 2, 3, 4]);
    assert_eq!(stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 0);
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
    let association = spawn_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        "203.0.113.20:443".parse().expect("valid test addr"),
        10,
        Duration::from_secs(1),
        None,
        UdpMemoryBudget::for_tunnel_mtu(1500),
        cancel.child_token(),
        udp_tx,
    );
    let worker = association.worker.abort_handle();
    let mut associations = HashMap::from([(src, association)]);
    let mut device = TunDevice::new(1500);
    let stats = Stats::new();
    let mut eviction_heap = BoundedHeap::new(4);

    handle_udp_event(
        &mut device,
        &stats,
        &mut associations,
        &mut eviction_heap,
        &mut None,
        UdpEvent::Packet { src, association_id: 99, raw: vec![5, 6, 7] },
    );

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
    let association = spawn_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        "203.0.113.20:443".parse().expect("valid test addr"),
        20,
        Duration::from_secs(1),
        None,
        UdpMemoryBudget::for_tunnel_mtu(1500),
        cancel.child_token(),
        udp_tx,
    );
    let worker = association.worker.abort_handle();
    let mut associations = HashMap::from([(src, association)]);
    let mut device = TunDevice::new(1500);
    let stats = Stats::new();
    let mut eviction_heap = BoundedHeap::new(4);

    handle_udp_event(
        &mut device,
        &stats,
        &mut associations,
        &mut eviction_heap,
        &mut None,
        UdpEvent::Closed { src, association_id: 20 },
    );

    assert!(associations.is_empty(), "closed event should remove association");
    worker.abort();
}

#[tokio::test]
async fn handle_udp_event_ignores_stale_close() {
    let proxy_addr = spawn_udp_associate_stub().await;
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53003);
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let cancel = CancellationToken::new();
    let association = spawn_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src,
        "203.0.113.20:443".parse().expect("valid test addr"),
        30,
        Duration::from_secs(1),
        None,
        UdpMemoryBudget::for_tunnel_mtu(1500),
        cancel.child_token(),
        udp_tx,
    );
    let worker = association.worker.abort_handle();
    let mut associations = HashMap::from([(src, association)]);
    let mut device = TunDevice::new(1500);
    let stats = Stats::new();
    let mut eviction_heap = BoundedHeap::new(4);

    handle_udp_event(
        &mut device,
        &stats,
        &mut associations,
        &mut eviction_heap,
        &mut None,
        UdpEvent::Closed { src, association_id: 999 },
    );

    assert_eq!(associations.len(), 1, "stale close should not remove current association");
    if let Some(association) = associations.remove(&src) {
        association.cancel.cancel();
        worker.abort();
    }
}

#[tokio::test]
async fn udp_forwarding_budget_rejection_records_queue_drop_evidence() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53110);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443);
    let (outbound, _outbound_rx) = tokio::sync::mpsc::channel(1);
    let association = UdpAssociation {
        id: 11,
        activity_generation: 0,
        outbound,
        cancel: CancellationToken::new(),
        last_activity: Arc::new(AtomicU64::new(now_millis())),
        worker: tokio::spawn(std::future::pending()),
        leased_synthetic_ips: std::collections::HashSet::new(),
        attribution_tokens: lru::LruCache::new(super::association_state::UDP_ATTRIBUTION_TOKEN_CAPACITY),
    };
    let mut associations = HashMap::from([(src, association)]);
    let mut eviction_heap = BoundedHeap::new(4);
    let memory_budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let _reserved = memory_budget.try_reserve_queued_bytes(4 * 1024 * 1024).expect("reserve full queued-byte budget");
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let stats = Arc::new(Stats::new());
    let mut next_id = 1;

    super::forward_udp_payload(
        "127.0.0.1:1080".parse().expect("proxy"),
        &Auth::NoAuth,
        src,
        dst,
        dst,
        None,
        None,
        b"drop",
        &mut None,
        &mut associations,
        &mut eviction_heap,
        &memory_budget,
        &mut next_id,
        Duration::from_secs(1),
        None,
        &CancellationToken::new(),
        &udp_tx,
        &stats,
        ripdpi_flow_app_attribution::note_flow(crate::uid_policy::PROTO_UDP, src, dst).token,
    );

    assert_eq!(stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 1);
    associations.remove(&src).expect("association").worker.abort();
}

#[tokio::test]
async fn udp_forwarding_full_association_channel_records_queue_drop_evidence() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53111);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 21)), 443);
    let memory_budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let (outbound, _outbound_rx) = tokio::sync::mpsc::channel(1);
    outbound
        .try_send(
            OutboundDatagram::try_new(crate::session::TargetAddr::Ip(dst), dst, dst, b"already-queued", &memory_budget)
                .expect("reserve queued datagram"),
        )
        .expect("fill association queue");
    let association = UdpAssociation {
        id: 12,
        activity_generation: 0,
        outbound,
        cancel: CancellationToken::new(),
        last_activity: Arc::new(AtomicU64::new(now_millis())),
        worker: tokio::spawn(std::future::pending()),
        leased_synthetic_ips: std::collections::HashSet::new(),
        attribution_tokens: lru::LruCache::new(super::association_state::UDP_ATTRIBUTION_TOKEN_CAPACITY),
    };
    let mut associations = HashMap::from([(src, association)]);
    let mut eviction_heap = BoundedHeap::new(4);
    let (udp_tx, _udp_rx) = tokio::sync::mpsc::channel(1);
    let stats = Arc::new(Stats::new());
    let mut next_id = 1;

    super::forward_udp_payload(
        "127.0.0.1:1080".parse().expect("proxy"),
        &Auth::NoAuth,
        src,
        dst,
        dst,
        None,
        None,
        b"second",
        &mut None,
        &mut associations,
        &mut eviction_heap,
        &memory_budget,
        &mut next_id,
        Duration::from_secs(1),
        None,
        &CancellationToken::new(),
        &udp_tx,
        &stats,
        ripdpi_flow_app_attribution::note_flow(crate::uid_policy::PROTO_UDP, src, dst).token,
    );

    assert_eq!(stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 1);
    associations.remove(&src).expect("association").worker.abort();
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

#[tokio::test]
async fn udp_association_holds_one_lease_per_synthetic_mapping_until_close() {
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53100);
    let dest = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443);
    let (association, _alive, started) = stalled_udp_association(41, dest);
    started.await.expect("stalled association should start");
    let mut associations = HashMap::from([(src, association)]);
    let mut dns_cache = Some(DnsCache::new(0xC612_0000, 0xFFFE_0000, 1).expect("valid cache"));
    let synthetic_ip = dns_cache
        .as_mut()
        .expect("cache")
        .find("udp.test", u32::from(Ipv4Addr::new(203, 0, 113, 10)))
        .expect("mapping")
        .0;

    lease_udp_mapping(&mut associations, &mut dns_cache, src, Some(synthetic_ip));
    lease_udp_mapping(&mut associations, &mut dns_cache, src, Some(synthetic_ip));
    assert_eq!(dns_cache.as_ref().expect("cache").lease_count(synthetic_ip), 1);

    let mut device = TunDevice::new(1500);
    let stats = Stats::new();
    let mut eviction_heap = BoundedHeap::new(4);
    handle_udp_event(
        &mut device,
        &stats,
        &mut associations,
        &mut eviction_heap,
        &mut dns_cache,
        UdpEvent::Closed { src, association_id: 41 },
    );
    assert_eq!(dns_cache.as_ref().expect("cache").lease_count(synthetic_ip), 0);
    dns_cache
        .as_mut()
        .expect("cache")
        .find("replacement.test", u32::from(Ipv4Addr::new(203, 0, 113, 20)))
        .expect("released UDP lease must make the slot evictable");
}

#[tokio::test]
async fn capacity_eviction_skips_stale_activity_generations() {
    let older = "10.0.0.2:53200".parse().expect("older source");
    let newer = "10.0.0.2:53201".parse().expect("newer source");
    let (mut older_association, _older_alive, older_started) =
        stalled_udp_association(51, "203.0.113.51:443".parse().expect("older destination"));
    let (newer_association, _newer_alive, newer_started) =
        stalled_udp_association(52, "203.0.113.52:443".parse().expect("newer destination"));
    older_started.await.expect("older worker started");
    newer_started.await.expect("newer worker started");
    older_association.activity_generation = 1;
    let mut associations = HashMap::from([(older, older_association), (newer, newer_association)]);
    let mut eviction_heap = BoundedHeap::new(4);
    assert!(eviction_heap.push(UdpEvictionEntry {
        last_activity_epoch: 1,
        association_id: 51,
        activity_generation: 0,
        addr: older,
    }));
    assert!(eviction_heap.push(UdpEvictionEntry {
        last_activity_epoch: 2,
        association_id: 52,
        activity_generation: 0,
        addr: newer,
    }));
    assert!(eviction_heap.push(UdpEvictionEntry {
        last_activity_epoch: 3,
        association_id: 51,
        activity_generation: 1,
        addr: older,
    }));

    evict_if_at_capacity(&mut associations, &mut eviction_heap, &mut None, 2);

    assert!(associations.contains_key(&older), "refreshed association must survive its stale heap entry");
    assert!(!associations.contains_key(&newer), "least-recent current association should be evicted");
    if let Some(association) = associations.remove(&older) {
        association.cancel.cancel();
        association.worker.abort();
    }
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

    let a1 = spawn_udp_association(
        proxy_addr,
        Auth::NoAuth,
        src1,
        "203.0.113.21:443".parse().expect("valid test addr"),
        1,
        Duration::from_secs(1),
        None,
        UdpMemoryBudget::for_tunnel_mtu(1500),
        cancel.child_token(),
        udp_tx.clone(),
    );

    let proxy_addr2 = spawn_udp_associate_stub().await;
    let a2 = spawn_udp_association(
        proxy_addr2,
        Auth::NoAuth,
        src2,
        "203.0.113.22:443".parse().expect("valid test addr"),
        2,
        Duration::from_secs(1),
        None,
        UdpMemoryBudget::for_tunnel_mtu(1500),
        cancel.child_token(),
        udp_tx,
    );

    let cancel1 = a1.cancel.clone();
    let cancel2 = a2.cancel.clone();
    let mut associations = HashMap::from([(src1, a1), (src2, a2)]);

    shutdown_udp_associations(&mut associations, &mut None).await;

    assert!(associations.is_empty(), "all associations should be drained");
    assert!(cancel1.is_cancelled(), "association 1 cancel token should be cancelled");
    assert!(cancel2.is_cancelled(), "association 2 cancel token should be cancelled");
}

#[tokio::test(start_paused = true)]
async fn shutdown_uses_one_grace_period_and_aborts_stalled_association_tasks() {
    let src1 = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53012);
    let src2 = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53013);
    let (a1, alive1, started1) = stalled_udp_association(1, "203.0.113.41:443".parse().expect("valid test address"));
    let (a2, alive2, started2) = stalled_udp_association(2, "203.0.113.42:443".parse().expect("valid test address"));
    let cancel1 = a1.cancel.clone();
    let cancel2 = a2.cancel.clone();
    let mut associations = HashMap::from([(src1, a1), (src2, a2)]);
    started1.await.expect("first stalled task should start");
    started2.await.expect("second stalled task should start");

    let started_at = Instant::now();
    shutdown_udp_associations(&mut associations, &mut None).await;

    assert_eq!(started_at.elapsed(), Duration::from_secs(5), "all tasks must share one shutdown grace period");
    assert!(cancel1.is_cancelled() && cancel2.is_cancelled(), "all association tokens must be cancelled together");
    assert!(!alive1.load(Ordering::Acquire), "first stalled worker must be aborted and joined");
    assert!(!alive2.load(Ordering::Acquire), "second stalled worker must be aborted and joined");
    assert!(associations.is_empty(), "all associations should be drained");
}

#[test]
fn record_quic_sni_records_server_name_from_quic_initial() {
    use crate::Stats;
    use ripdpi_packets::{QUIC_V1_VERSION, build_realistic_quic_initial};

    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.test")).expect("build quic initial");
    let stats = Stats::new();

    super::forwarding::record_quic_sni_if_present(&stats, &payload);

    let snapshot = stats.dns_snapshot();
    assert_eq!(snapshot.last_host.as_deref(), Some("example.test"), "QUIC SNI should be recorded");
}

#[test]
fn record_quic_sni_leaves_stats_unchanged_for_non_quic_datagram() {
    use crate::Stats;

    // A plain non-QUIC datagram: first byte has bit 7 clear.
    let payload = [0x00u8, 1, 2, 3, 4, 5, 6, 7];
    let stats = Stats::new();

    super::forwarding::record_quic_sni_if_present(&stats, &payload);

    let snapshot = stats.dns_snapshot();
    assert!(snapshot.last_host.is_none(), "non-QUIC datagram should not set last_host");
}
