use std::collections::{BinaryHeap, HashMap, HashSet};
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use std::time::Duration;

use lru::LruCache;
use tokio_util::sync::CancellationToken;

use super::association_state::{UDP_ATTRIBUTION_ID_CAPACITY, UdpAssociation, now_millis};
use super::forward_udp_payload;
use super::shutdown::shutdown_udp_associations;
use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;

/// # Cancel safety:
/// NOT cancel-safe: the test must finish association shutdown to join its idle worker.
#[tokio::test]
async fn excess_destination_is_rejected_before_queueing_or_pinning() {
    let src: SocketAddr = "10.0.0.2:53991".parse().unwrap();
    let resolved: SocketAddr = "203.0.113.1:443".parse().unwrap();
    let (outbound, mut receiver) = tokio::sync::mpsc::channel(300);
    let worker = tokio::spawn(std::future::pending());
    let association = UdpAssociation {
        id: 1,
        activity_generation: 0,
        pending_error: None,
        outbound,
        cancel: CancellationToken::new(),
        last_activity: Arc::new(AtomicU64::new(now_millis())),
        worker,
        leased_synthetic_ips: HashSet::new(),
        destinations: std::collections::HashMap::new(),
        attribution_ids: LruCache::new(UDP_ATTRIBUTION_ID_CAPACITY),
    };
    let mut associations = HashMap::from([(src, association)]);
    let mut cache = Some(DnsCache::new(0xc612_0000, 0xfffe_0000, 300).unwrap());
    let mut heap = BinaryHeap::new();
    let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let (events, _events_rx) = tokio::sync::mpsc::channel(1);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());
    let mut next_id = 2;
    let mut synthetic = Vec::new();

    for index in 0..257 {
        let host = format!("capacity-{index}.example");
        let ip = cache.as_mut().unwrap().find(&host, 0xcb00_7101).unwrap().0;
        synthetic.push(ip);
        let dst = SocketAddr::new(Ipv4Addr::from(ip).into(), 443);
        let registration = ripdpi_flow_app_attribution::note_flow(17, src, dst).registration_id;
        forward_udp_payload(
            "127.0.0.1:1080".parse().unwrap(),
            &Auth::NoAuth,
            src,
            dst,
            resolved,
            Some(&host),
            Some(ip),
            b"x",
            None,
            &mut cache,
            &mut Vec::new(),
            &mut associations,
            &mut heap,
            &budget,
            &mut next_id,
            Duration::from_secs(60),
            Duration::from_secs(5),
            None,
            &cancel,
            &events,
            &stats,
            registration,
        );
    }

    let leases = synthetic.iter().map(|&ip| cache.as_ref().unwrap().lease_count(ip)).collect::<Vec<_>>();
    let mut queued = 0;
    while receiver.try_recv().is_ok() {
        queued += 1;
    }
    shutdown_udp_associations(&mut associations, &mut cache).await;

    assert_eq!(leases[..256], [1; 256]);
    assert_eq!(leases[256], 0, "an excess destination must not own a DNS mapping lease");
    assert_eq!(queued, 256, "an excess destination must not reach the outbound queue");
    assert_eq!(stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 1);
}
