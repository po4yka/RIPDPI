use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::session::Auth;

use super::association_state::{remove_association, touch_udp_activity, UdpAssociation};
use super::event_handling::UdpEvent;
use super::eviction::{evict_if_over_capacity, UdpEvictionEntry};
use super::worker::create_udp_association;

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
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
) {
    #[allow(clippy::map_entry)]
    if !associations.contains_key(&src) {
        if eviction_heap.is_full() {
            evict_if_over_capacity(associations, eviction_heap);
        }
        match alloc_association(next_id, proxy_addr, auth.clone(), src, idle_timeout, cancel, udp_tx).await {
            Ok(association) => {
                eviction_heap.push(UdpEvictionEntry {
                    addr: src,
                    last_activity_epoch: association.last_activity.load(Ordering::Relaxed),
                });
                associations.insert(src, association);
            }
            Err(err) => {
                debug!("Failed to create UDP association for {src}: {err}");
                return;
            }
        }
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

    remove_association(associations, src);
    let Ok(association) = alloc_association(next_id, proxy_addr, auth.clone(), src, idle_timeout, cancel, udp_tx).await
    else {
        return;
    };

    let retry = association.session.clone();
    touch_udp_activity(&association.last_activity);
    associations.insert(src, association);
    if retry.send_to(resolved_dst, payload).await.is_err() {
        remove_association(associations, src);
    }
}

async fn alloc_association(
    next_id: &mut u64,
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    idle_timeout: Duration,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
) -> io::Result<UdpAssociation> {
    let id = *next_id;
    *next_id = next_id.wrapping_add(1);

    create_udp_association(proxy_addr, auth, src, id, idle_timeout, cancel.child_token(), udp_tx.clone()).await
}
