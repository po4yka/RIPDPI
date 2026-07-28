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
use crate::uid_policy::{CachedFlowUidSource, PROTO_UDP, UidFlowPolicy, Verdict};

use super::super::association_state::UdpAssociation;
use super::super::event_handling::UdpEvent;
use super::super::eviction::UdpEvictionEntry;
use super::delivery::deliver_udp_datagram;
use super::ensure::ensure_udp_association;
use super::leases::{lease_udp_attribution, lease_udp_mapping};

pub(in crate::io_loop) enum UdpForwardOutcome {
    Forwarded,
    PendingUid,
    Dropped,
}

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
    uid_policy: &UidFlowPolicy,
) -> UdpForwardOutcome {
    // Android's getConnectionOwnerUid sees the kernel-visible TUN tuple. For
    // MapDNS flows that tuple still contains the synthetic destination even
    // though the SOCKS association must use the separately resolved target.
    let observation = ripdpi_flow_app_attribution::note_flow(PROTO_UDP, src, attribution_dst);
    match uid_policy.admit(&CachedFlowUidSource, PROTO_UDP, src, attribution_dst) {
        Verdict::Allow => {}
        Verdict::Pending => return UdpForwardOutcome::PendingUid,
        Verdict::DropUdp | Verdict::ResetTcp => {
            let _ = ripdpi_flow_app_attribution::evict_flow(observation.token);
            stats.record_tun_policy_drop();
            return UdpForwardOutcome::Dropped;
        }
    }

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

    lease_udp_attribution(associations, src, observation.token);
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
    UdpForwardOutcome::Forwarded
}
