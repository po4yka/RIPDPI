use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::mpsc::error::TrySendError;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::TargetAddr;
use crate::session::udp::UdpMemoryBudget;
use crate::uid_policy::PROTO_UDP;

use super::super::association_removal::remove_association;
use super::super::association_state::{OutboundDatagram, UdpAssociation};
use super::super::event_handling::UdpEvent;
use super::super::eviction::{UdpEvictionEntry, record_udp_activity};
use super::ensure::ensure_udp_association;
use super::leases::{lease_udp_attribution, lease_udp_mapping};

#[allow(clippy::too_many_arguments)]
pub(super) fn deliver_udp_datagram(
    proxy_addr: SocketAddr,
    auth: &Auth,
    src: SocketAddr,
    attribution_dst: SocketAddr,
    resolved_dst: SocketAddr,
    target_host: Option<&str>,
    synthetic_ip: Option<u32>,
    payload: &[u8],
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
    let outbound = associations.get(&src).map(|association| association.outbound.clone())?;
    record_udp_activity(associations, eviction_heap, src);
    let target = target_host
        .map_or(TargetAddr::Ip(resolved_dst), |host| TargetAddr::ResolvedDomain(host.to_owned(), resolved_dst));
    let Some(datagram) = OutboundDatagram::try_new(target, resolved_dst, attribution_dst, payload, memory_budget)
    else {
        debug!("UDP aggregate queue byte budget exhausted for {src}; dropping datagram");
        stats.record_tun_queue_drop();
        return None;
    };
    match outbound.try_send(datagram) {
        Ok(()) => Some(admitted_id),
        Err(TrySendError::Full(_)) => {
            debug!("UDP association queue full for {src}; dropping datagram");
            stats.record_tun_queue_drop();
            None
        }
        Err(TrySendError::Closed(datagram)) => {
            // The app's UDP source remains live while its transport association is replaced.
            remove_association(associations, dns_cache, &mut Vec::new(), src);
            let replacement = ripdpi_flow_app_attribution::note_flow(PROTO_UDP, src, attribution_dst);
            ensure_udp_association(
                associations,
                eviction_heap,
                memory_budget,
                next_id,
                proxy_addr,
                auth,
                src,
                datagram.resolved_dest,
                &datagram.payload,
                dns_cache,
                closed_sources,
                idle_timeout,
                connect_timeout,
                protect_path,
                cancel,
                udp_tx,
                stats,
            );
            lease_udp_attribution(associations, src, replacement.registration_id);
            lease_udp_mapping(associations, dns_cache, src, synthetic_ip);
            if associations.get(&src).is_some_and(|association| association.outbound.try_send(datagram).is_ok()) {
                Some(replacement.registration_id)
            } else {
                stats.record_tun_queue_drop();
                None
            }
        }
    }
}
