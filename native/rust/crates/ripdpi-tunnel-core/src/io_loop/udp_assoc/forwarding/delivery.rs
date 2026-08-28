use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
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
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    memory_budget: &UdpMemoryBudget,
    next_id: &mut u64,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
    stats: &Arc<Stats>,
) {
    let Some(outbound) = associations.get(&src).map(|association| association.outbound.clone()) else {
        return;
    };
    record_udp_activity(associations, eviction_heap, src);
    let target = target_host
        .map_or(TargetAddr::Ip(resolved_dst), |host| TargetAddr::ResolvedDomain(host.to_owned(), resolved_dst));
    let Some(datagram) = OutboundDatagram::try_new(target, resolved_dst, attribution_dst, payload, memory_budget)
    else {
        debug!("UDP aggregate queue byte budget exhausted for {src}; dropping datagram");
        stats.record_tun_queue_drop();
        return;
    };
    match outbound.try_send(datagram) {
        Ok(()) => {}
        Err(TrySendError::Full(_)) => {
            debug!("UDP association queue full for {src}; dropping datagram");
            stats.record_tun_queue_drop();
        }
        Err(TrySendError::Closed(datagram)) => {
            remove_association(associations, dns_cache, src);
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
                idle_timeout,
                protect_path,
                cancel,
                udp_tx,
                stats,
            );
            lease_udp_attribution(associations, src, replacement.registration_id);
            lease_udp_mapping(associations, dns_cache, src, synthetic_ip);
            if let Some(association) = associations.get(&src) {
                if association.outbound.try_send(datagram).is_err() {
                    stats.record_tun_queue_drop();
                }
            } else {
                stats.record_tun_queue_drop();
            }
        }
    }
}
