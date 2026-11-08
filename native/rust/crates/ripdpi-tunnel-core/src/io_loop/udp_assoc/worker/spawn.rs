use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::session::udp::UdpMemoryBudget;
use crate::session::{Auth, UdpSession};

use super::super::super::packet::build_udp_response;
use super::super::association_state::{OutboundDatagram, touch_udp_activity, udp_association_is_idle};
use super::super::event_handling::UdpEvent;
use super::replies::{destination_for_target, remember_destination};

pub(super) struct UdpWorkerConfig {
    pub(super) proxy_addr: SocketAddr,
    pub(super) auth: Auth,
    pub(super) protect_path: Option<String>,
    pub(super) src: SocketAddr,
    pub(super) association_id: u64,
    pub(super) idle_timeout: Duration,
    pub(super) connect_timeout: Duration,
    pub(super) memory_budget: UdpMemoryBudget,
}

/// Runs the SOCKS5 UDP ASSOCIATE setup, bounded by `connect_timeout` and `cancel`. Returns
/// `Ok(None)` when cancelled.
///
/// # Cancel safety:
///
/// cancel-safe: setup owns its pending sockets. Cancellation or timeout drops
/// the future and closes any partial control connection and relay socket.
async fn connect_session(
    proxy_addr: SocketAddr,
    auth: &Auth,
    protect_path: Option<&str>,
    memory_budget: &UdpMemoryBudget,
    connect_timeout: Duration,
    cancel: &CancellationToken,
) -> std::io::Result<Option<UdpSession>> {
    let setup = UdpSession::connect_with_memory_budget(proxy_addr, auth.clone(), protect_path, memory_budget.clone());
    tokio::select! {
        biased;
        _ = cancel.cancelled() => Ok(None),
        result = tokio::time::timeout(connect_timeout, setup) => match result {
            Ok(session) => session.map(Some),
            Err(_) => Err(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!("SOCKS5 UDP ASSOCIATE setup timed out after {connect_timeout:?}"),
            )),
        },
    }
}

/// Sends `event` to the io loop unless `cancel` fires first, so a worker never waits on a full
/// channel that the stopping loop no longer drains. Returns whether the event was sent.
///
/// # Cancel safety
///
/// cancel-safe: `mpsc::Sender::send` is cancel-safe; an unsent event is dropped, and after
/// cancellation the loop removes the association itself.
async fn send_event(udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>, cancel: &CancellationToken, event: UdpEvent) -> bool {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => false,
        sent = udp_tx.send(event) => sent.is_ok(),
    }
}

pub(super) fn spawn_udp_worker(
    config: UdpWorkerConfig,
    mut outbound_rx: tokio::sync::mpsc::Receiver<OutboundDatagram>,
    last_activity: Arc<std::sync::atomic::AtomicU64>,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let UdpWorkerConfig {
            proxy_addr,
            auth,
            protect_path,
            src,
            association_id,
            idle_timeout,
            connect_timeout,
            memory_budget,
        } = config;
        // Setup runs in this worker, so it never stalls the single TUN/smoltcp owner task. A proxy
        // that accepts but never answers is bounded by the connect timeout, and eviction or shutdown
        // stops it through `cancel`.
        let mut session =
            match connect_session(proxy_addr, &auth, protect_path.as_deref(), &memory_budget, connect_timeout, &cancel)
                .await
            {
                Ok(Some(session)) => session.with_recv_timeout(idle_timeout),
                Ok(None) => {
                    send_event(&udp_tx, &cancel, UdpEvent::Closed { src, association_id }).await;
                    return;
                }
                Err(err) => {
                    debug!("UDP association {} for {} failed during setup: {}", association_id, src, err);
                    let event = if matches!(
                        err.kind(),
                        std::io::ErrorKind::ConnectionRefused
                            | std::io::ErrorKind::NotConnected
                            | std::io::ErrorKind::NetworkUnreachable
                            | std::io::ErrorKind::HostUnreachable
                            | std::io::ErrorKind::ConnectionReset
                            | std::io::ErrorKind::ConnectionAborted
                            | std::io::ErrorKind::BrokenPipe
                    ) {
                        UdpEvent::SetupFailed { src, association_id }
                    } else {
                        UdpEvent::Closed { src, association_id }
                    };
                    send_event(&udp_tx, &cancel, event).await;
                    return;
                }
            };
        let mut response_sources = HashMap::new();

        loop {
            // Cancel safety: recv_from, mpsc::Receiver::recv, and
            // CancellationToken::cancelled are all cancel-safe, so select may
            // drop either losing arm without losing a datagram or queue item.
            tokio::select! {
                biased;
                _ = cancel.cancelled() => break,
                outbound = outbound_rx.recv() => {
                    let Some(outbound) = outbound else { break };
                    if !remember_destination(&mut response_sources, &outbound.destination) {
                        continue;
                    }
                    outbound.destination.touch();
                    touch_udp_activity(&last_activity);
                    // send_to is message-atomic and cancel-safe. The explicit
                    // cancellation arm keeps shutdown bounded if the socket is
                    // not currently writable.
                    let sent = tokio::select! {
                        biased;
                        _ = cancel.cancelled() => break,
                        result = session.send_to_target(&outbound.destination.target, &outbound.payload) => result,
                    };
                    if let Err(err) = sent {
                        debug!("UDP association {} for {} send failed: {}", association_id, src, err);
                        // One reconnect, with the same bounds as the first setup.
                        session = match connect_session(
                            proxy_addr,
                            &auth,
                            protect_path.as_deref(),
                            &memory_budget,
                            connect_timeout,
                            &cancel,
                        )
                        .await
                        {
                            Ok(Some(retry)) => retry.with_recv_timeout(idle_timeout),
                            Ok(None) => break,
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
                            result = session.send_to_target(&outbound.destination.target, &outbound.payload) => result,
                        };
                        if let Err(retry_err) = retried {
                            debug!("UDP association {} for {} retry failed: {}", association_id, src, retry_err);
                            break;
                        }
                    }
                }
                received = session.recv_from_target(cancel.clone()) => match received {
                    Ok(Some((resp_payload, from))) => {
                        let Some(destination) = destination_for_target(&response_sources, from) else {
                            continue;
                        };
                        destination.touch();
                        touch_udp_activity(&last_activity);
                        let raw = build_udp_response(destination.response_source, src, &resp_payload);
                        if !raw.is_empty()
                            && !send_event(&udp_tx, &cancel, UdpEvent::Packet { src, association_id, destination, raw }).await
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
        send_event(&udp_tx, &cancel, UdpEvent::Closed { src, association_id }).await;
    })
}
