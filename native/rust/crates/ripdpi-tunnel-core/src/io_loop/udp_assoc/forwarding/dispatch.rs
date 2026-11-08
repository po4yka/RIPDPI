use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::TargetAddr;
use crate::session::udp::UdpMemoryBudget;

use super::super::association_state::{UdpAssociation, now_millis};
use super::super::event_handling::UdpEvent;
use super::super::eviction::{UdpEvictionEntry, record_udp_activity};
use super::admission::prepare_udp_destination;
use super::delivery::deliver_udp_datagram;
use super::ensure::ensure_udp_association;
use super::leases::{lease_udp_attribution, lease_udp_mapping};

#[allow(clippy::too_many_arguments)]
pub(in crate::io_loop) fn forward_udp_payload(
    proxy_addr: SocketAddr,
    auth: &Auth,
    src: SocketAddr,
    attribution_dst: SocketAddr,
    resolved_dst: SocketAddr,
    target_host: Option<&str>,
    synthetic_ip: Option<u32>,
    payload: &[u8],
    original: Option<&[u8]>,
    dns_cache: &mut Option<DnsCache>,
    closed_sources: &mut Vec<SocketAddr>,
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BinaryHeap<Reverse<UdpEvictionEntry>>,
    memory_budget: &UdpMemoryBudget,
    next_id: &mut u64,
    idle_timeout: Duration,
    connect_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
    stats: &Arc<Stats>,
    admitted_id: ripdpi_flow_app_attribution::FlowRegistrationId,
) -> Option<ripdpi_flow_app_attribution::FlowRegistrationId> {
    // The routing boundary admitted this exact packet before invoking raw hooks.
    // Do not resolve again: cache churn must not replay an already-admitted packet.
    ensure_udp_association(
        associations,
        eviction_heap,
        memory_budget,
        next_id,
        proxy_addr,
        auth,
        src,
        resolved_dst,
        payload,
        dns_cache,
        closed_sources,
        idle_timeout,
        connect_timeout,
        protect_path,
        cancel,
        udp_tx,
        stats,
    );

    let target = target_host
        .map_or(TargetAddr::Ip(resolved_dst), |host| TargetAddr::ResolvedDomain(host.to_owned(), resolved_dst));
    let Some(destination) = prepare_udp_destination(
        associations,
        dns_cache,
        src,
        target,
        resolved_dst,
        attribution_dst,
        synthetic_ip,
        idle_timeout,
        now_millis(),
    ) else {
        super::super::release_unowned_udp_attribution(associations, src, admitted_id);
        stats.record_tun_queue_drop();
        return None;
    };

    let Some((registration_id, destination)) = deliver_udp_datagram(
        proxy_addr,
        auth,
        src,
        destination,
        payload,
        original,
        dns_cache,
        closed_sources,
        associations,
        eviction_heap,
        memory_budget,
        next_id,
        idle_timeout,
        connect_timeout,
        protect_path,
        cancel,
        udp_tx,
        stats,
        admitted_id,
    ) else {
        super::super::release_unowned_udp_attribution(associations, src, admitted_id);
        return None;
    };
    destination.touch();
    if let Some(association) = associations.get_mut(&src) {
        association.destinations.insert(attribution_dst, destination);
    }
    record_udp_activity(associations, eviction_heap, src);
    lease_udp_attribution(associations, src, registration_id);
    lease_udp_mapping(associations, dns_cache, src, synthetic_ip);
    Some(registration_id)
}
