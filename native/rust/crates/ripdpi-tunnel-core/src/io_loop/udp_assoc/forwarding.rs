use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::Stats;
use crate::session::Auth;

use super::association_state::{UdpAssociation, remove_association, touch_udp_activity};
use super::event_handling::UdpEvent;
use super::eviction::{UdpEvictionEntry, evict_if_over_capacity};
use super::worker::create_udp_association;

/// IANA IP protocol number for UDP, for flow-attribution `note_flow`.
const PROTO_UDP: u8 = 17;

/// Extracts the QUIC Initial SNI from `payload` (if it carries a QUIC long-header
/// packet) and records it into `stats`. Called once per new UDP association.
/// Non-QUIC datagrams return immediately without any allocation.
pub(in crate::io_loop) fn record_quic_sni_if_present(stats: &Stats, payload: &[u8]) {
    // QUIC long-header: first byte has bit 7 set (0x80).
    if payload.first().is_none_or(|&b| b & 0x80 == 0) {
        return;
    }
    if let Some(info) = ripdpi_packets::parse_quic_initial(payload) {
        let host = info.host();
        if !host.is_empty()
            && let Ok(sni) = std::str::from_utf8(host)
        {
            stats.record_last_host(Some(sni));
        }
    }
}

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
    #[allow(clippy::map_entry)]
    if !associations.contains_key(&src) {
        // New UDP association: record the originating app's flow for per-app
        // attribution (once per association, not per datagram). `note_flow` only
        // locks a mutex and pushes to a queue (deduped by destination) -- no JNI
        // on this path; a background worker resolves off-path.
        ripdpi_flow_app_attribution::note_flow(PROTO_UDP, src, resolved_dst);
        record_quic_sni_if_present(stats, payload);
        if eviction_heap.is_full() {
            evict_if_over_capacity(associations, eviction_heap);
        }
        match alloc_association(
            next_id,
            proxy_addr,
            auth.clone(),
            src,
            resolved_dst,
            idle_timeout,
            protect_path,
            cancel,
            udp_tx,
        )
        .await
        {
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
    let Ok(association) = alloc_association(
        next_id,
        proxy_addr,
        auth.clone(),
        src,
        resolved_dst,
        idle_timeout,
        protect_path,
        cancel,
        udp_tx,
    )
    .await
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

#[allow(clippy::too_many_arguments)]
async fn alloc_association(
    next_id: &mut u64,
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    dest: SocketAddr,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
) -> io::Result<UdpAssociation> {
    let id = *next_id;
    *next_id = next_id.wrapping_add(1);

    create_udp_association(
        proxy_addr,
        auth,
        src,
        dest,
        id,
        idle_timeout,
        protect_path,
        cancel.child_token(),
        udp_tx.clone(),
    )
    .await
}
