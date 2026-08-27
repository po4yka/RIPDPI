use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;

use super::super::association_state::UdpAssociation;
use super::super::event_handling::UdpEvent;
use super::super::eviction::UdpEvictionEntry;
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
    dns_cache: &mut Option<DnsCache>,
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    memory_budget: &UdpMemoryBudget,
    next_id: &mut u64,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
    stats: &Arc<Stats>,
    admitted_token: ripdpi_flow_app_attribution::FlowAttributionToken,
) {
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
        idle_timeout,
        protect_path,
        cancel,
        udp_tx,
        stats,
    );

    lease_udp_attribution(associations, src, admitted_token);
    lease_udp_mapping(associations, dns_cache, src, synthetic_ip);

    deliver_udp_datagram(
        proxy_addr,
        auth,
        src,
        attribution_dst,
        resolved_dst,
        target_host,
        synthetic_ip,
        payload,
        dns_cache,
        associations,
        eviction_heap,
        memory_budget,
        next_id,
        idle_timeout,
        protect_path,
        cancel,
        udp_tx,
        stats,
    );
}
