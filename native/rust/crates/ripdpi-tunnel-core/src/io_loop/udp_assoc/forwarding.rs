use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::Stats;
use crate::session::Auth;

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
    payload: &[u8],
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    eviction_heap: &mut BoundedHeap<UdpEvictionEntry>,
    next_id: &mut u64,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
    stats: &Arc<Stats>,
) {
    ensure_udp_association(
        associations,
        eviction_heap,
        next_id,
        proxy_addr,
        auth,
        src,
        resolved_dst,
        payload,
        idle_timeout,
        protect_path,
        cancel,
        udp_tx,
        stats,
    );

    let Some((outbound, last_activity)) = associations
        .get(&src)
        .map(|association| (association.outbound.clone(), Arc::clone(&association.last_activity)))
    else {
        return;
    };

    touch_udp_activity(&last_activity);
    match outbound.try_send(OutboundDatagram { dest: resolved_dst, payload: payload.to_vec() }) {
        Ok(()) => {}
        Err(TrySendError::Full(_)) => debug!("UDP association queue full for {src}; dropping datagram"),
        Err(TrySendError::Closed(datagram)) => {
            remove_association(associations, src);
            ensure_udp_association(
                associations,
                eviction_heap,
                next_id,
                proxy_addr,
                auth,
                src,
                datagram.dest,
                &datagram.payload,
                idle_timeout,
                protect_path,
                cancel,
                udp_tx,
                stats,
            );
            if let Some(association) = associations.get(&src) {
                let _ = association.outbound.try_send(datagram);
            }
        }
    }
}
