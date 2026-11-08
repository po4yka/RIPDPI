use crate::io_loop::udp_assoc::UdpAssociations;
use std::cmp::Reverse;
use std::collections::BinaryHeap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::mpsc::error::TrySendError;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::Stats;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;

use super::super::association_removal::remove_association;
use super::super::association_state::{OutboundDatagram, UdpAssociationKey, UdpDestination};
use super::super::event_handling::UdpEvent;
use super::super::eviction::UdpEvictionEntry;
use super::ensure::ensure_udp_association;

#[allow(clippy::too_many_arguments)]
pub(super) fn deliver_udp_datagram(
    proxy_addr: SocketAddr,
    auth: &Auth,
    key: &UdpAssociationKey,
    destination: Arc<UdpDestination>,
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
) -> Option<(ripdpi_flow_app_attribution::FlowRegistrationId, Arc<UdpDestination>)> {
    let src = key.src;
    let outbound = associations.get(key)?.outbound.clone();
    let Some(mut datagram) =
        OutboundDatagram::try_new_for_destination(Arc::clone(&destination), payload, memory_budget)
    else {
        debug!("UDP aggregate queue byte budget exhausted for {src}; dropping datagram");
        stats.record_tun_queue_drop();
        return None;
    };
    datagram.error_context = original.and_then(|packet| {
        super::super::worker::RemoteErrorContext::capture(packet, admitted_id, Arc::clone(&destination), memory_budget)
    });
    match outbound.try_send(datagram) {
        Ok(()) => Some((admitted_id, destination)),
        Err(TrySendError::Full(_)) => {
            debug!("UDP association queue full for {src}; dropping datagram");
            stats.record_tun_queue_drop();
            None
        }
        Err(TrySendError::Closed(mut datagram)) => {
            // The app's UDP source remains live while its transport association is replaced.
            // The current packet was already admitted; replacing a failed transport
            // must preserve that generation instead of borrowing a new lookup.
            if let Some(association) = associations.get_mut(key) {
                association.attribution_ids.pop(&admitted_id.request());
            }
            remove_association(associations, dns_cache, &mut Vec::new(), key);
            ensure_udp_association(
                associations,
                eviction_heap,
                memory_budget,
                next_id,
                proxy_addr,
                auth,
                key,
                destination.resolved_dest,
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
            let destination = Arc::new(UdpDestination::new(
                destination.target.clone(),
                destination.resolved_dest,
                destination.response_source,
                destination.synthetic_ip,
            ));
            datagram.destination = Arc::clone(&destination);
            if let Some(context) = &mut datagram.error_context {
                context.registration_id = admitted_id;
                context.destination = Arc::clone(&destination);
            }
            if let Some(association) = associations.get(key)
                && association.outbound.try_send(datagram).is_ok()
            {
                return Some((admitted_id, destination));
            }
            super::super::release_unowned_udp_attribution(associations, src, admitted_id);
            stats.record_tun_queue_drop();
            None
        }
    }
}
