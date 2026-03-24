use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant as StdInstant};

use tokio::io::unix::AsyncFd;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::session::{Auth, UdpSession};
use crate::Stats;

use super::bridge::try_write_tun_packet;
use super::packet::build_udp_response;

pub(super) struct UdpAssociation {
    pub(super) id: u64,
    pub(super) session: UdpSession,
    pub(super) cancel: CancellationToken,
    pub(super) last_activity: Arc<Mutex<StdInstant>>,
    pub(super) worker: tokio::task::JoinHandle<()>,
}

#[derive(Debug)]
pub(super) enum UdpEvent {
    Packet { src: SocketAddr, association_id: u64, raw: Vec<u8> },
    Closed { src: SocketAddr, association_id: u64 },
}

pub(super) fn touch_udp_activity(last_activity: &Arc<Mutex<StdInstant>>) {
    if let Ok(mut guard) = last_activity.lock() {
        *guard = StdInstant::now();
    }
}

fn udp_association_is_idle(last_activity: &Arc<Mutex<StdInstant>>, idle_timeout: Duration) -> bool {
    last_activity.lock().map(|guard| guard.elapsed() >= idle_timeout).unwrap_or(true)
}

#[allow(clippy::too_many_arguments)]
pub(super) async fn create_udp_association(
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    association_id: u64,
    idle_timeout: Duration,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
) -> io::Result<UdpAssociation> {
    let session = UdpSession::connect(proxy_addr, auth).await?.with_recv_timeout(idle_timeout);
    let last_activity = Arc::new(Mutex::new(StdInstant::now()));
    let worker_session = session.clone();
    let worker_last_activity = Arc::clone(&last_activity);
    let worker_cancel = cancel.clone();
    let worker_udp_tx = udp_tx.clone();
    let worker = tokio::spawn(async move {
        loop {
            match worker_session.recv_from(worker_cancel.clone()).await {
                Ok(Some((resp_payload, from))) => {
                    touch_udp_activity(&worker_last_activity);
                    let raw = build_udp_response(from, src, &resp_payload);
                    if raw.is_empty() {
                        continue;
                    }
                    if worker_udp_tx.send(UdpEvent::Packet { src, association_id, raw }).await.is_err() {
                        break;
                    }
                }
                Ok(None) => {
                    if worker_cancel.is_cancelled() {
                        break;
                    }
                    if udp_association_is_idle(&worker_last_activity, idle_timeout) {
                        let _ = worker_udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                        break;
                    }
                }
                Err(err) => {
                    debug!("UDP association {} for {} failed: {}", association_id, src, err);
                    let _ = worker_udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                    break;
                }
            }
        }
    });

    Ok(UdpAssociation { id: association_id, session, cancel, last_activity, worker })
}

pub(super) fn handle_udp_event(
    tun: &AsyncFd<std::fs::File>,
    stats: &Arc<Stats>,
    udp_associations: &mut HashMap<SocketAddr, UdpAssociation>,
    event: UdpEvent,
) {
    match event {
        UdpEvent::Packet { src, association_id, raw } => {
            let current_id = udp_associations.get(&src).map(|association| association.id);
            if current_id == Some(association_id) {
                try_write_tun_packet(tun, stats, &raw, "udp");
            }
        }
        UdpEvent::Closed { src, association_id } => {
            if udp_associations.get(&src).is_some_and(|association| association.id == association_id) {
                udp_associations.remove(&src);
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub(super) async fn forward_udp_payload(
    proxy_addr: SocketAddr,
    auth: &Auth,
    src: SocketAddr,
    resolved_dst: SocketAddr,
    payload: &[u8],
    udp_associations: &mut HashMap<SocketAddr, UdpAssociation>,
    next_udp_association_id: &mut u64,
    udp_idle_timeout: Duration,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
) {
    #[allow(clippy::map_entry)]
    if !udp_associations.contains_key(&src) {
        let association_id = *next_udp_association_id;
        *next_udp_association_id = next_udp_association_id.wrapping_add(1);
        match create_udp_association(
            proxy_addr,
            auth.clone(),
            src,
            association_id,
            udp_idle_timeout,
            cancel.child_token(),
            udp_tx.clone(),
        )
        .await
        {
            Ok(association) => {
                udp_associations.insert(src, association);
            }
            Err(err) => {
                debug!("Failed to create UDP association for {}: {}", src, err);
                return;
            }
        }
    }

    let Some((session, last_activity)) = udp_associations
        .get(&src)
        .map(|association| (association.session.clone(), Arc::clone(&association.last_activity)))
    else {
        return;
    };

    touch_udp_activity(&last_activity);
    if let Err(err) = session.send_to(resolved_dst, payload).await {
        debug!("UDP association send to {} from {} failed: {}", resolved_dst, src, err);
        if let Some(association) = udp_associations.remove(&src) {
            association.cancel.cancel();
        }

        let association_id = *next_udp_association_id;
        *next_udp_association_id = next_udp_association_id.wrapping_add(1);
        match create_udp_association(
            proxy_addr,
            auth.clone(),
            src,
            association_id,
            udp_idle_timeout,
            cancel.child_token(),
            udp_tx.clone(),
        )
        .await
        {
            Ok(association) => {
                let retry_session = association.session.clone();
                touch_udp_activity(&association.last_activity);
                udp_associations.insert(src, association);
                if let Err(retry_err) = retry_session.send_to(resolved_dst, payload).await {
                    debug!("UDP association retry to {} from {} failed: {}", resolved_dst, src, retry_err);
                    if let Some(association) = udp_associations.remove(&src) {
                        association.cancel.cancel();
                    }
                }
            }
            Err(recreate_err) => {
                debug!("Failed to recreate UDP association for {} after send error: {}", src, recreate_err);
            }
        }
    }
}

pub(super) async fn shutdown_udp_associations(udp_associations: &mut HashMap<SocketAddr, UdpAssociation>) {
    for (_src, association) in udp_associations.drain() {
        association.cancel.cancel();
        let _ = tokio::time::timeout(Duration::from_secs(5), association.worker).await;
    }
}
