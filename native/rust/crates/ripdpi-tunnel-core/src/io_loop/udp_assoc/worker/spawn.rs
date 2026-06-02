use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::session::UdpSession;

use super::super::super::packet::build_udp_response;
use super::super::association_state::{touch_udp_activity, udp_association_is_idle};
use super::super::event_handling::UdpEvent;

pub(super) fn spawn_udp_worker(
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
