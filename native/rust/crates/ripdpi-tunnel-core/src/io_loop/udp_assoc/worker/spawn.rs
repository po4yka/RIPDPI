use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::session::{Auth, UdpSession};

use super::super::super::packet::build_udp_response;
use super::super::association_state::{OutboundDatagram, touch_udp_activity, udp_association_is_idle};
use super::super::event_handling::UdpEvent;

pub(super) struct UdpWorkerConfig {
    pub(super) proxy_addr: SocketAddr,
    pub(super) auth: Auth,
    pub(super) protect_path: Option<String>,
    pub(super) src: SocketAddr,
    pub(super) association_id: u64,
    pub(super) idle_timeout: Duration,
}

pub(super) fn spawn_udp_worker(
    config: UdpWorkerConfig,
    mut outbound_rx: tokio::sync::mpsc::Receiver<OutboundDatagram>,
    last_activity: Arc<std::sync::atomic::AtomicU64>,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let UdpWorkerConfig { proxy_addr, auth, protect_path, src, association_id, idle_timeout } = config;
        // UdpSession::connect is intentionally allowed to finish: its SOCKS5
        // handshake is not cancel-safe. Keeping it in this worker prevents the
        // single TUN/smoltcp owner task from stalling during setup.
        let mut session = match UdpSession::connect(proxy_addr, auth.clone(), protect_path.as_deref()).await {
            Ok(session) => session.with_recv_timeout(idle_timeout),
            Err(err) => {
                debug!("UDP association {} for {} failed during setup: {}", association_id, src, err);
                let _ = udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                return;
            }
        };

        loop {
            // Cancel safety: recv_from, mpsc::Receiver::recv, and
            // CancellationToken::cancelled are all cancel-safe, so select may
            // drop either losing arm without losing a datagram or queue item.
            tokio::select! {
                _ = cancel.cancelled() => break,
                outbound = outbound_rx.recv() => {
                    let Some(outbound) = outbound else { break };
                    touch_udp_activity(&last_activity);
                    // send_to is message-atomic and cancel-safe. The explicit
                    // cancellation arm keeps shutdown bounded if the socket is
                    // not currently writable.
                    let sent = tokio::select! {
                        biased;
                        _ = cancel.cancelled() => break,
                        result = session.send_to(outbound.dest, &outbound.payload) => result,
                    };
                    if let Err(err) = sent {
                        debug!("UDP association {} for {} send failed: {}", association_id, src, err);
                        // Preserve the previous one-reconnect retry semantics,
                        // but keep the non-cancel-safe SOCKS5 setup isolated in
                        // this worker instead of blocking the TUN owner task.
                        session = match UdpSession::connect(proxy_addr, auth.clone(), protect_path.as_deref()).await {
                            Ok(retry) => retry.with_recv_timeout(idle_timeout),
                            Err(retry_err) => {
                                debug!(
                                    "UDP association {} for {} reconnect failed: {}",
                                    association_id, src, retry_err
                                );
                                break;
                            }
                        };
                        let retried = tokio::select! {
                            biased;
                            _ = cancel.cancelled() => break,
                            result = session.send_to(outbound.dest, &outbound.payload) => result,
                        };
                        if let Err(retry_err) = retried {
                            debug!("UDP association {} for {} retry failed: {}", association_id, src, retry_err);
                            break;
                        }
                    }
                }
                received = session.recv_from(cancel.clone()) => match received {
                    Ok(Some((resp_payload, from))) => {
                        touch_udp_activity(&last_activity);
                        let raw = build_udp_response(from, src, &resp_payload);
                        if !raw.is_empty()
                            && udp_tx.send(UdpEvent::Packet { src, association_id, raw }).await.is_err()
                        {
                            break;
                        }
                    }
                    Ok(None) => {
                        if cancel.is_cancelled() || udp_association_is_idle(&last_activity, idle_timeout) {
                            break;
                        }
                    }
                    Err(err) => {
                        debug!("UDP association {} for {} failed: {}", association_id, src, err);
                        break;
                    }
                },
            }
        }
        let _ = udp_tx.send(UdpEvent::Closed { src, association_id }).await;
    })
}
