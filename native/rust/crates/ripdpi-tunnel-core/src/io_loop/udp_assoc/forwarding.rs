use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;
use crate::uid_policy::{CachedFlowUidSource, PROTO_UDP, UidFlowPolicy, Verdict};

mod allocation;
mod ensure;
mod quic_sni;

use ensure::ensure_udp_association;
#[cfg(test)]
pub(in crate::io_loop) use quic_sni::record_quic_sni_if_present;
use tokio::sync::mpsc::error::TrySendError;

use super::association_removal::remove_association;
use super::association_state::{OutboundDatagram, UdpAssociation, touch_udp_activity};
use super::event_handling::UdpEvent;
use super::eviction::UdpEvictionEntry;

#[allow(clippy::too_many_arguments)]
pub(in crate::io_loop) fn forward_udp_payload(
    proxy_addr: SocketAddr,
    auth: &Auth,
    src: SocketAddr,
    resolved_dst: SocketAddr,
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
) {
    let observation = ripdpi_flow_app_attribution::note_flow(PROTO_UDP, src, resolved_dst);
    match uid_policy.admit(&CachedFlowUidSource, PROTO_UDP, src, resolved_dst) {
        Verdict::Allow => {}
        Verdict::Pending | Verdict::DropUdp | Verdict::ResetTcp => return,
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

    let Some(association) = associations.get(&src) else {
        return;
    };
    let outbound = association.outbound.clone();

    touch_udp_activity(&association.last_activity);
    let Some(datagram) = OutboundDatagram::try_new(resolved_dst, payload, memory_budget) else {
        debug!("UDP aggregate queue byte budget exhausted for {src}; dropping datagram");
        return;
    };
    match outbound.try_send(datagram) {
        Ok(()) => {}
        Err(TrySendError::Full(_)) => debug!("UDP association queue full for {src}; dropping datagram"),
        Err(TrySendError::Closed(datagram)) => {
            remove_association(associations, dns_cache, src);
            let replacement = ripdpi_flow_app_attribution::note_flow(PROTO_UDP, src, datagram.dest);
            ensure_udp_association(
                associations,
                eviction_heap,
                memory_budget,
                next_id,
                proxy_addr,
                auth,
                src,
                datagram.dest,
                &datagram.payload,
                dns_cache,
                idle_timeout,
                protect_path,
                cancel,
                udp_tx,
                stats,
            );
            lease_udp_attribution(associations, src, replacement.token);
            lease_udp_mapping(associations, dns_cache, src, synthetic_ip);
            if let Some(association) = associations.get(&src) {
                let _ = association.outbound.try_send(datagram);
            }
        }
    }
}

fn lease_udp_attribution(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    src: SocketAddr,
    token: ripdpi_flow_app_attribution::FlowAttributionToken,
) {
    if let Some(association) = associations.get_mut(&src) {
        association.attribution_tokens.insert(token);
    }
}

pub(super) fn lease_udp_mapping(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    dns_cache: &mut Option<DnsCache>,
    src: SocketAddr,
    synthetic_ip: Option<u32>,
) {
    let (Some(association), Some(cache), Some(ip)) = (associations.get_mut(&src), dns_cache.as_mut(), synthetic_ip)
    else {
        return;
    };
    if association.leased_synthetic_ips.insert(ip) && !cache.pin(ip) {
        association.leased_synthetic_ips.remove(&ip);
    }
}
