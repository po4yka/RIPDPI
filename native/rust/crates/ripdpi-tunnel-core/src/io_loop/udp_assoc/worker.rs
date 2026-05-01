use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::session::{Auth, UdpSession};

use super::super::packet::build_udp_response;
use super::association_state::{now_millis, touch_udp_activity, udp_association_is_idle, UdpAssociation};
use super::event_handling::UdpEvent;

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
    let last_activity = Arc::new(std::sync::atomic::AtomicU64::new(now_millis()));
    let worker = spawn_udp_worker(
        session.clone(),
        Arc::clone(&last_activity),
        cancel.clone(),
        udp_tx.clone(),
        src,
        association_id,
        idle_timeout,
    );

    Ok(UdpAssociation { id: association_id, session, cancel, last_activity, worker })
}

fn spawn_udp_worker(
    session: UdpSession,
    last_activity: Arc<std::sync::atomic::AtomicU64>,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
    src: SocketAddr,
    association_id: u64,
    idle_timeout: Duration,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        loop {
            match session.recv_from(cancel.clone()).await {
                Ok(Some((resp_payload, from))) => {
                    touch_udp_activity(&last_activity);
                    let raw = build_udp_response(from, src, &resp_payload);
                    if !raw.is_empty() && udp_tx.send(UdpEvent::Packet { src, association_id, raw }).await.is_err() {
                        break;
                    }
                }
                Ok(None) => {
                    if cancel.is_cancelled() || udp_association_is_idle(&last_activity, idle_timeout) {
                        let _ = udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                        break;
                    }
                }
                Err(err) => {
                    debug!("UDP association {} for {} failed: {}", association_id, src, err);
                    let _ = udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                    break;
                }
            }
        }
    })
}
