use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::session::TargetAddr;
use crate::session::udp::UdpMemoryBudget;
use crate::session::{Auth, UdpSession};

use super::super::super::packet::build_udp_response;
use super::super::association_state::{OutboundDatagram, touch_udp_activity, udp_association_is_idle};
use super::super::event_handling::UdpEvent;

const MAX_RESPONSE_HOSTS: usize = 256;

type ResponseAuthority = (String, IpAddr, u16);

fn response_authority(host: &str, pinned: SocketAddr) -> ResponseAuthority {
    (host.strip_suffix('.').unwrap_or(host).to_ascii_lowercase(), pinned.ip(), pinned.port())
}

fn remember_response_source(
    response_sources: &mut HashMap<ResponseAuthority, SocketAddr>,
    host: &str,
    pinned: SocketAddr,
    source: SocketAddr,
) {
    let authority = response_authority(host, pinned);
    if response_sources.len() >= MAX_RESPONSE_HOSTS
        && !response_sources.contains_key(&authority)
        && let Some(evicted) = response_sources.keys().next().cloned()
    {
        response_sources.remove(&evicted);
    }
    response_sources.insert(authority, source);
}

fn response_source_for_target(
    response_sources: &HashMap<ResponseAuthority, SocketAddr>,
    target: TargetAddr,
) -> Option<SocketAddr> {
    match target {
        TargetAddr::Ip(source) => Some(source),
        TargetAddr::Domain(host, port) => response_sources
            .iter()
            .find(|((known_host, _, known_port), _)| known_host == &host.to_ascii_lowercase() && *known_port == port)
            .map(|(_, source)| SocketAddr::new(source.ip(), port)),
        TargetAddr::ResolvedDomain(host, pinned) => response_sources.get(&response_authority(&host, pinned)).copied(),
    }
}

pub(super) struct UdpWorkerConfig {
    pub(super) proxy_addr: SocketAddr,
    pub(super) auth: Auth,
    pub(super) protect_path: Option<String>,
    pub(super) src: SocketAddr,
    pub(super) association_id: u64,
    pub(super) idle_timeout: Duration,
    pub(super) memory_budget: UdpMemoryBudget,
}

pub(super) fn spawn_udp_worker(
    config: UdpWorkerConfig,
    mut outbound_rx: tokio::sync::mpsc::Receiver<OutboundDatagram>,
    last_activity: Arc<std::sync::atomic::AtomicU64>,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let UdpWorkerConfig { proxy_addr, auth, protect_path, src, association_id, idle_timeout, memory_budget } =
            config;
        // UdpSession::connect is intentionally allowed to finish: its SOCKS5
        // handshake is not cancel-safe. Keeping it in this worker prevents the
        // single TUN/smoltcp owner task from stalling during setup.
        let mut session = match UdpSession::connect_with_memory_budget(
            proxy_addr,
            auth.clone(),
            protect_path.as_deref(),
            memory_budget.clone(),
        )
        .await
        {
            Ok(session) => session.with_recv_timeout(idle_timeout),
            Err(err) => {
                debug!("UDP association {} for {} failed during setup: {}", association_id, src, err);
                let _ = udp_tx.send(UdpEvent::Closed { src, association_id }).await;
                return;
            }
        };
        let mut response_sources = HashMap::<ResponseAuthority, SocketAddr>::new();

        loop {
            // Cancel safety: recv_from, mpsc::Receiver::recv, and
            // CancellationToken::cancelled are all cancel-safe, so select may
            // drop either losing arm without losing a datagram or queue item.
            tokio::select! {
                biased;
                _ = cancel.cancelled() => break,
                outbound = outbound_rx.recv() => {
                    let Some(outbound) = outbound else { break };
                    touch_udp_activity(&last_activity);
                    if let TargetAddr::ResolvedDomain(host, pinned) = &outbound.dest {
                        remember_response_source(&mut response_sources, host, *pinned, outbound.response_source);
                    }
                    // send_to is message-atomic and cancel-safe. The explicit
                    // cancellation arm keeps shutdown bounded if the socket is
                    // not currently writable.
                    let sent = tokio::select! {
                        biased;
                        _ = cancel.cancelled() => break,
                        result = session.send_to_target(&outbound.dest, &outbound.payload) => result,
                    };
                    if let Err(err) = sent {
                        debug!("UDP association {} for {} send failed: {}", association_id, src, err);
                        // Preserve the previous one-reconnect retry semantics,
                        // but keep the non-cancel-safe SOCKS5 setup isolated in
                        // this worker instead of blocking the TUN owner task.
                        session = match UdpSession::connect_with_memory_budget(
                            proxy_addr,
                            auth.clone(),
                            protect_path.as_deref(),
                            memory_budget.clone(),
                        )
                        .await
                        {
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
                            result = session.send_to_target(&outbound.dest, &outbound.payload) => result,
                        };
                        if let Err(retry_err) = retried {
                            debug!("UDP association {} for {} retry failed: {}", association_id, src, retry_err);
                            break;
                        }
                    }
                }
                received = session.recv_from_target(cancel.clone()) => match received {
                    Ok(Some((resp_payload, from))) => {
                        touch_udp_activity(&last_activity);
                        let Some(response_source) = response_source_for_target(&response_sources, from) else {
                            continue;
                        };
                        let raw = build_udp_response(response_source, src, &resp_payload);
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn domain_responses_are_attributed_by_host_not_arrival_order() {
        let first: SocketAddr = "198.18.0.1:1001".parse().unwrap();
        let second: SocketAddr = "198.18.0.2:1002".parse().unwrap();
        let first_pinned: SocketAddr = "203.0.113.7:443".parse().unwrap();
        let second_pinned: SocketAddr = "203.0.113.7:53".parse().unwrap();
        let mut sources = HashMap::new();
        remember_response_source(&mut sources, "one.example", first_pinned, first);
        remember_response_source(&mut sources, "two.example", second_pinned, second);

        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("two.example".into(), second_pinned)),
            Some(second)
        );
        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("one.example".into(), first_pinned)),
            Some(first)
        );
        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("unknown.example".into(), first_pinned)),
            None
        );
    }

    #[test]
    fn resolved_responses_distinguish_multi_a_records_and_canonicalize_host_case() {
        let first_pinned: SocketAddr = "203.0.113.10:443".parse().unwrap();
        let second_pinned: SocketAddr = "203.0.113.11:443".parse().unwrap();
        let first_source: SocketAddr = "198.18.0.10:443".parse().unwrap();
        let second_source: SocketAddr = "198.18.0.11:443".parse().unwrap();
        let mut sources = HashMap::new();
        remember_response_source(&mut sources, "Multi.Example.", first_pinned, first_source);
        remember_response_source(&mut sources, "MULTI.EXAMPLE", second_pinned, second_source);

        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("multi.example".into(), second_pinned)),
            Some(second_source)
        );
        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("MULTI.EXAMPLE.".into(), first_pinned)),
            Some(first_source)
        );
    }

    #[test]
    fn response_source_table_remains_bounded() {
        let mut sources = HashMap::new();
        for index in 0..(MAX_RESPONSE_HOSTS + 32) {
            let source: SocketAddr = format!("198.18.0.1:{}", index + 1).parse().unwrap();
            let pinned: SocketAddr = format!("203.0.113.1:{}", index + 1).parse().unwrap();
            remember_response_source(&mut sources, &format!("host-{index}.example"), pinned, source);
        }

        assert_eq!(sources.len(), MAX_RESPONSE_HOSTS);
    }
}
