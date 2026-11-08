use crate::io_loop::udp_assoc::UdpAssociations;
use std::cmp::Reverse;
use std::collections::BinaryHeap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::TargetAddr;
use crate::session::udp::UdpMemoryBudget;

use super::super::association_state::{UdpAssociationKey, now_millis};
use super::super::event_handling::UdpEvent;
use super::super::eviction::{UdpEvictionEntry, record_udp_activity};
use super::admission::prepare_udp_destination;
use super::delivery::deliver_udp_datagram;
use super::ensure::ensure_udp_association;
use super::leases::{lease_udp_attribution, lease_udp_mapping, retire_previous_udp_registration};

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
    associations: &mut UdpAssociations,
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
    let key = UdpAssociationKey::for_registration(src, &admitted_id);
    let held_pin = retire_previous_udp_registration(associations, dns_cache, &key, admitted_id, true);
    // The routing boundary admitted this exact packet before invoking raw hooks.
    // Do not resolve again: cache churn must not replay an already-admitted packet.
    ensure_udp_association(
        associations,
        eviction_heap,
        memory_budget,
        next_id,
        proxy_addr,
        auth,
        &key,
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
        &key,
        target,
        resolved_dst,
        attribution_dst,
        synthetic_ip,
        idle_timeout,
        now_millis(),
    ) else {
        release_replaced_pin(dns_cache, held_pin);
        super::super::release_unowned_udp_attribution(associations, src, admitted_id);
        stats.record_tun_queue_drop();
        return None;
    };

    let Some((registration_id, destination)) = deliver_udp_datagram(
        proxy_addr,
        auth,
        &key,
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
        release_replaced_pin(dns_cache, held_pin);
        super::super::release_unowned_udp_attribution(associations, src, admitted_id);
        return None;
    };
    destination.touch();
    if let Some(association) = associations.get_mut(&key) {
        association.destinations.insert(attribution_dst, destination);
    }
    record_udp_activity(associations, eviction_heap, &key);
    lease_udp_attribution(associations, &key, registration_id);
    lease_udp_mapping(associations, dns_cache, &key, synthetic_ip);
    associations.record_destination(&key, registration_id);
    release_replaced_pin(dns_cache, held_pin);
    Some(registration_id)
}

fn release_replaced_pin(cache: &mut Option<DnsCache>, ip: Option<u32>) {
    if let (Some(cache), Some(ip)) = (cache.as_mut(), ip) {
        cache.unpin(ip);
    }
}
