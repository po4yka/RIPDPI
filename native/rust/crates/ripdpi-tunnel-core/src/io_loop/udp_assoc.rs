use std::cmp::Ordering;
use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant as StdInstant};

use android_support::bounded_heap::BoundedHeap;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::session::{Auth, UdpSession};
use crate::TunDevice;

use super::bridge::enqueue_tun_packet;
use super::packet::build_udp_response;

pub(super) struct UdpAssociation {
    pub(super) id: u64,
    pub(super) session: UdpSession,
    pub(super) cancel: CancellationToken,
    pub(super) last_activity: Arc<Mutex<StdInstant>>,
    pub(super) worker: tokio::task::JoinHandle<()>,
}

#[derive(Debug)]
pub(super) enum UdpEvent {
    Packet { src: SocketAddr, association_id: u64, raw: Vec<u8> },
    Closed { src: SocketAddr, association_id: u64 },
}

/// Default maximum number of concurrent UDP associations.
pub(super) const DEFAULT_MAX_UDP_ASSOCIATIONS: usize = 512;

/// Eviction priority entry for UDP associations.
///
/// Orders by `last_activity` (oldest first = smallest = evicted first).
#[derive(Debug, Eq, PartialEq)]
pub(super) struct UdpEvictionEntry {
    pub addr: SocketAddr,
    pub last_activity_epoch: u64, // milliseconds since process start
}

impl Ord for UdpEvictionEntry {
    fn cmp(&self, other: &Self) -> Ordering {
        self.last_activity_epoch.cmp(&other.last_activity_epoch)
    }
}

impl PartialOrd for UdpEvictionEntry {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// Evict the least-recently-active UDP association if the map exceeds capacity.
pub(super) fn evict_if_over_capacity(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
) {
    if let Some(evicted) = eviction_heap.pop() {
        if let Some(association) = associations.remove(&evicted.addr) {
            debug!("UDP association {} evicted (idle eviction, capacity exceeded)", evicted.addr);
            association.cancel.cancel();
        }
    }
}

pub(super) fn touch_udp_activity(last_activity: &Arc<Mutex<StdInstant>>) {
    if let Ok(mut guard) = last_activity.lock() {
        *guard = StdInstant::now();
    }
}

fn activity_epoch(last_activity: &Arc<Mutex<StdInstant>>) -> u64 {
    last_activity.lock().map(|guard| guard.elapsed().as_millis() as u64).unwrap_or(u64::MAX)
}

fn udp_association_is_idle(last_activity: &Arc<Mutex<StdInstant>>, idle_timeout: Duration) -> bool {
    last_activity.lock().map(|guard| guard.elapsed() >= idle_timeout).unwrap_or(true)
}

#[allow(clippy::too_many_arguments)]
pub(super) async fn create_udp_association(
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    association_id: u64,
    idle_timeout: Duration,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
) -> io::Result<UdpAssociation> {
    let session = UdpSession::connect(proxy_addr, auth).await?.with_recv_timeout(idle_timeout);
    let last_activity = Arc::new(Mutex::new(StdInstant::now()));
    let worker_session = session.clone();
    let worker_last_activity = Arc::clone(&last_activity);
    let worker_cancel = cancel.clone();
    let worker_udp_tx = udp_tx.clone();
    let worker = tokio::spawn(async move {
        loop {
            match worker_session.recv_from(worker_cancel.clone()).await {
                Ok(Some((resp_payload, from))) => {
                    touch_udp_activity(&worker_last_activity);
                    let raw = build_udp_response(from, src, &resp_payload);
                    if raw.is_empty() {
                        continue;
                    }
                    if worker_udp_tx.send(UdpEvent::Packet { src, association_id, raw }).await.is_err() {
                        break;
                    }
                }
                Ok(None) => {
                    if worker_cancel.is_cancelled() {
                        break;
                    }
                    if udp_association_is_idle(&worker_last_activity, idle_timeout) {
                        let _ = worker_udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                        break;
                    }
                }
                Err(err) => {
                    debug!("UDP association {} for {} failed: {}", association_id, src, err);
                    let _ = worker_udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                    break;
                }
            }
        }
    });

    Ok(UdpAssociation { id: association_id, session, cancel, last_activity, worker })
}

pub(super) fn handle_udp_event(
    device: &mut TunDevice,
    udp_associations: &mut HashMap<SocketAddr, UdpAssociation>,
    event: UdpEvent,
) {
    match event {
        UdpEvent::Packet { src, association_id, raw } => {
            let current_id = udp_associations.get(&src).map(|association| association.id);
            if current_id == Some(association_id) {
                enqueue_tun_packet(device, raw, "udp");
            }
        }
        UdpEvent::Closed { src, association_id } => {
            if udp_associations.get(&src).is_some_and(|association| association.id == association_id) {
                udp_associations.remove(&src);
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub(super) async fn forward_udp_payload(
    proxy_addr: SocketAddr,
    auth: &Auth,
    src: SocketAddr,
    resolved_dst: SocketAddr,
    payload: &[u8],
    udp_associations: &mut HashMap<SocketAddr, UdpAssociation>,
    udp_eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    next_udp_association_id: &mut u64,
    udp_idle_timeout: Duration,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
) {
    #[allow(clippy::map_entry)]
    if !udp_associations.contains_key(&src) {
        // Evict least-recently-active if at capacity.
        if udp_eviction_heap.is_full() {
            evict_if_over_capacity(udp_associations, udp_eviction_heap);
        }

        let association_id = *next_udp_association_id;
        *next_udp_association_id = next_udp_association_id.wrapping_add(1);
        match create_udp_association(
            proxy_addr,
            auth.clone(),
            src,
            association_id,
            udp_idle_timeout,
            cancel.child_token(),
            udp_tx.clone(),
        )
        .await
        {
            Ok(association) => {
                let epoch = activity_epoch(&association.last_activity);
                udp_eviction_heap.push(UdpEvictionEntry { addr: src, last_activity_epoch: epoch });
                udp_associations.insert(src, association);
            }
            Err(err) => {
                debug!("Failed to create UDP association for {}: {}", src, err);
                return;
            }
        }
    }

    let Some((session, last_activity)) = udp_associations
        .get(&src)
        .map(|association| (association.session.clone(), Arc::clone(&association.last_activity)))
    else {
        return;
    };

    touch_udp_activity(&last_activity);
    if let Err(err) = session.send_to(resolved_dst, payload).await {
        debug!("UDP association send to {} from {} failed: {}", resolved_dst, src, err);
        if let Some(association) = udp_associations.remove(&src) {
            association.cancel.cancel();
        }

        let association_id = *next_udp_association_id;
        *next_udp_association_id = next_udp_association_id.wrapping_add(1);
        match create_udp_association(
            proxy_addr,
            auth.clone(),
            src,
            association_id,
            udp_idle_timeout,
            cancel.child_token(),
            udp_tx.clone(),
        )
        .await
        {
            Ok(association) => {
                let retry_session = association.session.clone();
                touch_udp_activity(&association.last_activity);
                udp_associations.insert(src, association);
                if let Err(retry_err) = retry_session.send_to(resolved_dst, payload).await {
                    debug!("UDP association retry to {} from {} failed: {}", resolved_dst, src, retry_err);
                    if let Some(association) = udp_associations.remove(&src) {
                        association.cancel.cancel();
                    }
                }
            }
            Err(recreate_err) => {
                debug!("Failed to recreate UDP association for {} after send error: {}", src, recreate_err);
            }
        }
    }
}

pub(super) async fn shutdown_udp_associations(udp_associations: &mut HashMap<SocketAddr, UdpAssociation>) {
    for (_src, association) in udp_associations.drain() {
        association.cancel.cancel();
        let _ = tokio::time::timeout(Duration::from_secs(5), association.worker).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{IpAddr, Ipv4Addr};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    async fn spawn_udp_associate_stub() -> SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind proxy listener");
        let proxy_addr = listener.local_addr().expect("proxy addr");

        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("accept proxy");
            let mut buf = [0u8; 64];

            let _ = stream.read(&mut buf).await.expect("read greeting");
            stream.write_all(&[0x05, 0x00]).await.expect("write no-auth");

            let _ = stream.read(&mut buf).await.expect("read udp associate");
            stream
                .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x13, 0x88])
                .await
                .expect("write udp associate reply");
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

        // Send a packet with a stale association_id (99 != 10)
        handle_udp_event(
            &mut device,
            &mut associations,
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
        let association = create_udp_association(
            proxy_addr,
            Auth::NoAuth,
            src,
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

        // Close event with wrong association_id should be ignored
        handle_udp_event(&mut device, &mut associations, UdpEvent::Closed { src, association_id: 999 });

        assert_eq!(associations.len(), 1, "stale close should not remove current association");
        if let Some(association) = associations.remove(&src) {
            association.cancel.cancel();
            worker.abort();
        }
    }

    #[test]
    fn touch_udp_activity_updates_timestamp() {
        let last_activity = Arc::new(Mutex::new(StdInstant::now() - Duration::from_secs(60)));
        let before = *last_activity.lock().unwrap();

        touch_udp_activity(&last_activity);

        let after = *last_activity.lock().unwrap();
        assert!(after > before, "timestamp should be refreshed after touch");
        assert!(after.elapsed() < Duration::from_secs(1), "timestamp should be very recent");
    }

    #[test]
    fn idle_detection_true_after_timeout() {
        let last_activity = Arc::new(Mutex::new(StdInstant::now() - Duration::from_secs(60)));
        assert!(udp_association_is_idle(&last_activity, Duration::from_secs(30)), "should be idle after timeout");
    }

    #[test]
    fn idle_detection_false_when_fresh() {
        let last_activity = Arc::new(Mutex::new(StdInstant::now()));
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
            1,
            Duration::from_secs(1),
            cancel.child_token(),
            udp_tx.clone(),
        )
        .await
        .expect("association 1");

        // Need a second proxy stub for the second association
        let proxy_addr2 = spawn_udp_associate_stub().await;
        let a2 = create_udp_association(
            proxy_addr2,
            Auth::NoAuth,
            src2,
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
}
