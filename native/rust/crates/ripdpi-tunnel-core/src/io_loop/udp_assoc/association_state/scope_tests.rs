use std::cmp::Reverse;
use std::collections::BinaryHeap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_flow_app_attribution::{
    FlowRegistrationId, note_flow, store_uid_and_scope_resolution, take_pending_request,
};
use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;

use super::super::event_handling::UdpEvent;
use super::super::eviction::UdpEvictionEntry;
use super::super::shutdown::shutdown_udp_associations;
use super::{UdpAssociations, UdpDestination};

type Associations = UdpAssociations;

struct Fixture {
    associations: Associations,
    heap: BinaryHeap<Reverse<UdpEvictionEntry>>,
    budget: UdpMemoryBudget,
    next_id: u64,
    cancel: CancellationToken,
    tx: tokio::sync::mpsc::Sender<UdpEvent>,
    stats: Arc<Stats>,
}

impl Fixture {
    fn new() -> Self {
        let (tx, _rx) = tokio::sync::mpsc::channel(16);
        Self {
            associations: Associations::new(),
            heap: BinaryHeap::new(),
            budget: UdpMemoryBudget::for_tunnel_mtu(1500),
            next_id: 1,
            cancel: CancellationToken::new(),
            tx,
            stats: Arc::new(Stats::new()),
        }
    }

    fn forward(&mut self, src: SocketAddr, dst: SocketAddr, registration: FlowRegistrationId) {
        self.forward_mapped(src, dst, registration, None, &mut None);
    }

    fn forward_mapped(
        &mut self,
        src: SocketAddr,
        dst: SocketAddr,
        registration: FlowRegistrationId,
        synthetic_ip: Option<u32>,
        cache: &mut Option<crate::dns_cache::DnsCache>,
    ) {
        assert!(
            super::super::forward_udp_payload(
                "127.0.0.1:9".parse().expect("loopback proxy"),
                &Auth::UserPass { username: "control".into(), password: "session-token".into() },
                src,
                dst,
                dst,
                None,
                synthetic_ip,
                b"owned",
                None,
                cache,
                &mut Vec::new(),
                &mut self.associations,
                &mut self.heap,
                &self.budget,
                &mut self.next_id,
                Duration::from_secs(60),
                Duration::from_secs(1),
                None,
                &self.cancel,
                &self.tx,
                &self.stats,
                registration,
            )
            .is_some()
        );
    }

    fn destination(&self, dst: SocketAddr) -> Arc<UdpDestination> {
        self.associations
            .values()
            .find_map(|association| association.destinations.get(&dst).cloned())
            .expect("queued destination")
    }

    /// # Cancel safety:
    /// cancel-safe: shutdown cancels and joins every worker created by this fixture.
    async fn finish(&mut self) {
        shutdown_udp_associations(&mut self.associations, &mut None).await;
    }
}

fn resolved(src: SocketAddr, dst: SocketAddr, uid: Option<u32>, scope: Option<&str>) -> FlowRegistrationId {
    let id = note_flow(17, src, dst).registration_id;
    let job = take_pending_request(id.request()).expect("new owner lookup");
    store_uid_and_scope_resolution(&job, uid, scope);
    id
}

/// # Cancel safety:
/// cancel-safe: each fixture owns and joins all spawned loopback workers.
#[tokio::test(flavor = "current_thread")]
async fn same_source_distinct_app_scopes_keep_both_udp_flows() {
    let src = "192.0.2.201:48101".parse().unwrap();
    let first = "198.51.100.201:443".parse().unwrap();
    let second = "198.51.100.202:443".parse().unwrap();
    let mut fixture = Fixture::new();
    fixture.forward(src, first, resolved(src, first, Some(10123), Some("9e5e69e1-9107-43fc-bf17-f4d14aed4331")));
    let first_destination = fixture.destination(first);
    fixture.forward(src, second, resolved(src, second, Some(10124), Some("3ef294c9-1f36-40c7-86af-da29763a8817")));
    assert_eq!(fixture.associations.len(), 2, "different app scopes need separate authenticated transports");
    assert!(first_destination.is_live(), "the second app must not retire the first app's other tuple");
    fixture.finish().await;
}

/// # Cancel safety:
/// cancel-safe: the fixture owns and joins all spawned loopback workers.
#[tokio::test(flavor = "current_thread")]
async fn tuple_owner_change_retires_only_that_destination_and_queued_context() {
    let src = "192.0.2.202:48102".parse().unwrap();
    let reused = "198.51.100.203:443".parse().unwrap();
    let surviving = "198.51.100.204:443".parse().unwrap();
    let mut fixture = Fixture::new();
    let old = resolved(src, reused, Some(10123), Some("b459efef-1909-4f56-904d-74fef370dfea"));
    fixture.forward(src, reused, old);
    let old_destination = fixture.destination(reused);
    fixture.forward(
        src,
        surviving,
        resolved(src, surviving, Some(10123), Some("b459efef-1909-4f56-904d-74fef370dfea")),
    );
    let surviving_destination = fixture.destination(surviving);
    assert!(ripdpi_flow_app_attribution::evict_flow_if_current(old));
    fixture.forward(src, reused, resolved(src, reused, Some(10124), Some("37dc774c-0dd9-48f8-ae2d-ae094886fce9")));
    assert!(!old_destination.is_live(), "queued work must lose the reused tuple's old identity");
    assert!(surviving_destination.is_live(), "an unrelated live tuple of the first app must survive");
    assert_eq!(fixture.associations.len(), 2);
    fixture.finish().await;
}

/// # Cancel safety:
/// cancel-safe: the fixture owns and joins all spawned loopback workers.
#[tokio::test(flavor = "current_thread")]
async fn late_scope_resolution_preserves_the_admitted_uid_generation() {
    let src = "192.0.2.203:48103".parse().unwrap();
    let dst = "198.51.100.205:443".parse().unwrap();
    let id = note_flow(17, src, dst).registration_id;
    let job = take_pending_request(id.request()).expect("pending owner");
    let mut fixture = Fixture::new();
    fixture.forward(src, dst, id);
    let unscoped_destination = fixture.destination(dst);
    store_uid_and_scope_resolution(&job, Some(10125), Some("ba415cf6-91b0-43a3-9bba-61da8dff207f"));
    fixture.forward(src, dst, id);
    assert!(!unscoped_destination.is_live(), "a scoped transport must not share unscoped queued bytes");
    assert_eq!(
        ripdpi_flow_app_attribution::lookup_registered_flow_uid(&id),
        ripdpi_flow_app_attribution::FlowUidLookup::Resolved(Some(10125))
    );
    assert_eq!(fixture.associations.len(), 1);
    fixture.finish().await;
}

/// # Cancel safety:
/// cancel-safe: the fixture owns and joins all spawned loopback workers.
#[tokio::test(flavor = "current_thread")]
async fn refreshing_same_app_tuple_retires_old_responses_without_replacing_other_flows() {
    let src = "[2001:db8:203::1]:48104".parse().unwrap();
    let dst = "[2001:db8:203::2]:443".parse().unwrap();
    let scope = "451f0f59-ef53-4937-aafa-a14d6227f75d";
    let old = resolved(src, dst, Some(10126), Some(scope));
    let mut fixture = Fixture::new();
    fixture.forward(src, dst, old);
    let old_destination = fixture.destination(dst);
    let association_id = fixture.associations.values().next().unwrap().id;
    assert!(ripdpi_flow_app_attribution::evict_flow_if_current(old));
    let current = resolved(src, dst, Some(10126), Some(scope));
    fixture.forward(src, dst, current);
    let mut device = crate::TunDevice::new(1500);
    let mut closed = Vec::new();
    super::super::handle_udp_event(
        &mut device,
        &fixture.stats,
        &mut fixture.associations,
        &mut fixture.heap,
        &mut None,
        &mut closed,
        UdpEvent::Packet { src, association_id, destination: old_destination.clone(), raw: b"obsolete".to_vec() },
        |_| true,
    );
    assert!(!old_destination.is_live());
    assert!(device.tx_queue.is_empty(), "an earlier generation's queued response must not reach the TUN");
    assert!(closed.is_empty());
    assert_eq!(fixture.associations.len(), 1);
    fixture.finish().await;
}

/// # Cancel safety:
/// cancel-safe: the fixture owns and joins all spawned loopback workers.
#[tokio::test(flavor = "current_thread")]
async fn known_uids_without_package_scopes_do_not_share_unknown_transport() {
    let src = "[2001:db8:204::1]:48105".parse().unwrap();
    let mut fixture = Fixture::new();
    for (suffix, uid) in [(1, Some(10127)), (2, Some(10128)), (3, None), (4, None)] {
        let dst = format!("[2001:db8:204::{suffix}]:443").parse().unwrap();
        fixture.forward(src, dst, resolved(src, dst, uid, None));
    }
    assert_eq!(fixture.associations.len(), 3, "known UIDs stay separate; unknown flows remain unscoped");
    fixture.finish().await;
}

/// # Cancel safety:
/// cancel-safe: closed and surviving workers observe cancellation and the fixture drains its tasks.
#[tokio::test(flavor = "current_thread")]
async fn closing_one_scope_preserves_another_scope_with_the_same_source() {
    let src = "192.0.2.204:48106".parse().unwrap();
    let first = "198.51.100.206:443".parse().unwrap();
    let second = "198.51.100.207:443".parse().unwrap();
    let mut fixture = Fixture::new();
    fixture.forward(src, first, resolved(src, first, Some(10129), Some("d673cbfb-2adc-4798-8efe-0f61cf775dce")));
    let first_id = fixture.associations.values().next().unwrap().id;
    fixture.forward(src, second, resolved(src, second, Some(10130), Some("4bbab0d6-7652-487c-90da-9d66d65896ec")));
    let survivor = fixture.destination(second);
    let mut closed = Vec::new();
    super::super::handle_udp_event(
        &mut crate::TunDevice::new(1500),
        &fixture.stats,
        &mut fixture.associations,
        &mut fixture.heap,
        &mut None,
        &mut closed,
        UdpEvent::Closed { src, association_id: first_id },
        |_| true,
    );
    assert_eq!(fixture.associations.len(), 1);
    assert!(fixture.associations.contains_key(&src));
    assert!(survivor.is_live());
    assert!(closed.is_empty(), "the source still has a surviving owner");
    fixture.finish().await;
}

/// # Cancel safety:
/// cancel-safe: the fixture owns and joins the worker and all queue permits are released.
#[tokio::test(flavor = "current_thread")]
async fn destination_index_survives_uid_lease_eviction_and_shrinks_on_close() {
    let src = "192.0.2.205:48107".parse().unwrap();
    let reused = "198.51.100.208:40000".parse().unwrap();
    let scope = "9c58ba66-16d2-445e-8ba0-84027c0c1d9d";
    let first = resolved(src, reused, Some(10131), Some(scope));
    let mut fixture = Fixture::new();
    fixture.forward(src, reused, first);
    let retired_destination = fixture.destination(reused);
    let association_id = fixture.associations.values().next().unwrap().id;
    let (outbound, mut queued) = tokio::sync::mpsc::channel(1);
    fixture.associations.get_registration_mut(&first).unwrap().outbound = outbound;
    for port in 41000..41070 {
        let dst = SocketAddr::new(reused.ip(), port);
        fixture.forward(src, dst, resolved(src, dst, Some(10131), Some(scope)));
        assert_eq!(queued.try_recv().expect("queued datagram").payload, b"owned");
    }
    assert_eq!(fixture.associations.values().next().unwrap().attribution_ids.len(), 64);
    assert_eq!(fixture.associations.destination_count(), 71);
    assert_eq!(
        ripdpi_flow_app_attribution::lookup_registered_flow_uid(&first),
        ripdpi_flow_app_attribution::FlowUidLookup::Missing
    );
    fixture.forward(src, reused, resolved(src, reused, Some(10132), Some("fe82bd45-a589-4a12-9e8c-441d2e8a3bbb")));
    assert!(
        !retired_destination.is_live(),
        "a retired UID lease must not hide a still-queued destination from ownership replacement"
    );
    assert_eq!(fixture.associations.destination_count(), 71);
    let mut closed = Vec::new();
    super::super::handle_udp_event(
        &mut crate::TunDevice::new(1500),
        &fixture.stats,
        &mut fixture.associations,
        &mut fixture.heap,
        &mut None,
        &mut closed,
        UdpEvent::Closed { src, association_id },
        |_| true,
    );
    assert_eq!(fixture.associations.destination_count(), 1);
    assert!(closed.is_empty());
    fixture.finish().await;
    assert_eq!(fixture.associations.destination_count(), 0);
}

/// # Cancel safety:
/// cancel-safe: all workers enter the guarded drain before this test first suspends.
#[tokio::test(flavor = "current_thread")]
async fn pre_admission_retirement_preserves_neighbor_mapping_and_rejects_stale_replay() {
    let src = "192.0.2.219:49219".parse().unwrap();
    let mut fixture = Fixture::new();
    let mut cache = Some(crate::dns_cache::DnsCache::new(0xc612_0000, 0xfffe_0000, 2).unwrap());
    let first_ip = cache.as_mut().unwrap().find("first.test", 0xcb00_7101).unwrap().0;
    let neighbor_ip = cache.as_mut().unwrap().find("neighbor.test", 0xcb00_7102).unwrap().0;
    let first = SocketAddr::new(std::net::Ipv4Addr::from(first_ip).into(), 443);
    let neighbor = SocketAddr::new(std::net::Ipv4Addr::from(neighbor_ip).into(), 443);
    let original = resolved(src, first, Some(10123), Some("81c5afbf-9a33-4680-880a-06c47840e52b"));
    let neighbor_id = resolved(src, neighbor, Some(10123), Some("81c5afbf-9a33-4680-880a-06c47840e52b"));
    fixture.forward_mapped(src, first, original, Some(first_ip), &mut cache);
    fixture.forward_mapped(src, neighbor, neighbor_id, Some(neighbor_ip), &mut cache);
    let old_destination = fixture.destination(first);
    assert_eq!(cache.as_ref().unwrap().lease_count(first_ip), 1);
    assert_eq!(cache.as_ref().unwrap().lease_count(neighbor_ip), 1);
    assert!(ripdpi_flow_app_attribution::evict_flow_if_current(original));
    let successor = note_flow(17, src, first).registration_id;
    super::super::retire_obsolete_udp_registration(&mut fixture.associations, &mut cache, successor);
    assert!(!old_destination.is_live());
    assert!(fixture.associations.contains_key(&src), "the neighboring destination still owns this source");
    assert_eq!(cache.as_ref().unwrap().lease_count(first_ip), 0);
    assert_eq!(cache.as_ref().unwrap().lease_count(neighbor_ip), 1);
    assert_eq!(cache.as_mut().unwrap().lookup(first_ip).unwrap().host, "first.test");
    assert!(fixture.associations.owns_registration(&neighbor_id));
    let job = take_pending_request(successor.request()).unwrap();
    store_uid_and_scope_resolution(&job, Some(20123), Some("43f1f3f7-3d3b-4658-8512-66c5f7c025ad"));
    fixture.forward_mapped(src, first, successor, Some(first_ip), &mut cache);
    let successor_destination = fixture.destination(first);
    super::super::retire_obsolete_udp_registration(&mut fixture.associations, &mut cache, original);
    assert!(successor_destination.is_live(), "stale G1 cannot retire current G2");
    assert!(fixture.associations.owns_registration(&successor));
    assert_eq!(cache.as_ref().unwrap().lease_count(first_ip), 1);
    shutdown_udp_associations(&mut fixture.associations, &mut cache).await;
    assert_eq!(cache.as_ref().unwrap().lease_count(first_ip), 0);
    assert_eq!(cache.as_ref().unwrap().lease_count(neighbor_ip), 0);
}

/// # Cancel safety:
/// cancel-safe: all retained workers enter the guarded shutdown before this test suspends.
#[tokio::test(flavor = "current_thread")]
async fn pre_admission_retirement_preserves_empty_transport_until_shutdown() {
    let src = "192.0.2.220:49220".parse().unwrap();
    let dst = "198.51.100.220:443".parse().unwrap();
    let mut fixture = Fixture::new();
    let old = resolved(src, dst, Some(10123), Some("81c5afbf-9a33-4680-880a-06c47840e52b"));
    fixture.forward(src, dst, old);
    assert!(ripdpi_flow_app_attribution::evict_flow_if_current(old));
    let successor = note_flow(17, src, dst).registration_id;
    super::super::retire_obsolete_udp_registration(&mut fixture.associations, &mut None, successor);
    assert!(fixture.associations.contains_key(&src));
    assert_eq!(fixture.associations.len(), 1, "empty transport remains within the normal association bound");
    ripdpi_flow_app_attribution::evict_flow_if_current(successor);
    fixture.finish().await;
}

/// # Cancel safety:
/// cancel-safe: the only suspension drains retained workers behind an abort-on-drop guard.
#[tokio::test(flavor = "current_thread")]
async fn same_owner_refresh_preserves_udp_transport_while_retiring_old_generation() {
    let src = "192.0.2.218:49218".parse().unwrap();
    let dst = "198.51.100.218:443".parse().unwrap();
    let scope = "81c5afbf-9a33-4680-880a-06c47840e52b";
    let mut fixture = Fixture::new();
    let original = resolved(src, dst, Some(10123), Some(scope));
    fixture.forward(src, dst, original);
    let (original_id, old_destination) = fixture.associations.test_destination_snapshot(&original).unwrap();
    assert!(ripdpi_flow_app_attribution::evict_flow_if_current(original));
    let successor = note_flow(17, src, dst).registration_id;
    super::super::retire_obsolete_udp_registration(&mut fixture.associations, &mut None, successor);
    let retained_pending_transport = fixture.associations.contains_key(&src);
    let retired_old_destination = !old_destination.is_live();
    let job = take_pending_request(successor.request()).unwrap();
    store_uid_and_scope_resolution(&job, Some(10123), Some(scope));
    fixture.forward(src, dst, successor);
    let (successor_id, _) = fixture.associations.test_destination_snapshot(&successor).unwrap();
    fixture.finish().await;
    assert!(retired_old_destination, "old replies must stop before successor admission");
    assert!(retained_pending_transport, "Pending UID refresh must preserve the existing transport");
    assert_eq!(successor_id, original_id, "same app refresh must preserve UDP association and NAT port");
}
