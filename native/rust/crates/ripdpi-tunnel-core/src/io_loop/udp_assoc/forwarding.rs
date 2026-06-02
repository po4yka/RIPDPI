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
mod retry;

use ensure::ensure_udp_association;
#[cfg(test)]
pub(in crate::io_loop) use quic_sni::record_quic_sni_if_present;
use retry::retry_udp_send_with_new_association;

use super::association_state::{UdpAssociation, touch_udp_activity};
use super::event_handling::UdpEvent;
use super::eviction::UdpEvictionEntry;

#[allow(clippy::too_many_arguments)]
pub(in crate::io_loop) async fn forward_udp_payload(
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
    if let Err(err) = ensure_udp_association(
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
    )
    .await
    {
        debug!("Failed to create UDP association for {src}: {err}");
        return;
    }

    let Some((session, last_activity)) =
        associations.get(&src).map(|association| (association.session.clone(), Arc::clone(&association.last_activity)))
    else {
        return;
    };

    touch_udp_activity(&last_activity);
    if session.send_to(resolved_dst, payload).await.is_ok() {
        return;
    }

    retry_udp_send_with_new_association(
        associations,
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
    )
    .await;
}
