use crate::sync::{Arc, AtomicBool, Ordering};
use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use crate::platform;
use crate::runtime_policy::{extract_host_info, ConnectionRoute, HostSource, RouteAdvance, TransportProtocol};
use ripdpi_config::{QuicInitialMode, RuntimeConfig, DETECT_CONNECT};
use ripdpi_desync::{plan_udp, ActivationTransport, DesyncAction};
use ripdpi_session::{SessionState, SocketType, S_ATP_I4, S_ATP_I6};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use super::adaptive::{note_adaptive_udp_failure, note_adaptive_udp_success, resolve_adaptive_udp_hints};
use super::retry::{
    build_retry_selection_penalties, maybe_emit_candidate_diversification, note_retry_failure, note_retry_success,
};
use super::routing::{note_route_success_for_transport, select_route_for_transport};
use super::state::{flush_autolearn_updates, RuntimeState, UDP_FLOW_IDLE_TIMEOUT};

pub(super) struct UdpRelaySockets {
    pub(super) client: UdpSocket,
}

struct UdpFlowActivationState {
    session: SessionState,
    last_used: Instant,
    route: ConnectionRoute,
    host: Option<String>,
    payload: Vec<u8>,
    awaiting_response: bool,
    upstream: UdpSocket,
    quic_migrated: bool,
}

pub(super) fn build_udp_relay_sockets(ip: IpAddr, _protect_path: Option<&str>) -> io::Result<UdpRelaySockets> {
    let client = bind_udp_socket(SocketAddr::new(ip, 0), None)?;
    client.set_nonblocking(true)?;
    Ok(UdpRelaySockets { client })
}

fn bind_udp_socket(bind_addr: SocketAddr, protect_path: Option<&str>) -> io::Result<UdpSocket> {
    let domain = match bind_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, path)?;
    }
    socket.bind(&SockAddr::from(bind_addr))?;
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(250)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    Ok(socket)
}

fn build_udp_upstream_socket(target: SocketAddr, protect_path: Option<&str>, bind_low_port: bool) -> io::Result<UdpSocket> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, path)?;
    }
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(250)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    if bind_low_port {
        let local_ip = match target {
            SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        };
        if let Err(err) = platform::bind_udp_low_port(&socket, local_ip, 4_096) {
            tracing::warn!(%target, %err, "failed to bind UDP flow to a low source port");
        }
    }
    socket.connect(target)?;
    socket.set_nonblocking(true)?;
    Ok(socket)
}

pub(super) fn udp_associate_loop(
    client_relay: UdpSocket,
    protect_path: Option<String>,
    state: RuntimeState,
    running: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut udp_client_addr = None;
    let mut client_buffer = [0u8; 65_535];
    let mut upstream_buffer = [0u8; 65_535];
    let mut flow_state = HashMap::<(SocketAddr, SocketAddr), UdpFlowActivationState>::new();

    while running.load(Ordering::Relaxed) {
        expire_udp_flows(&state, &mut flow_state, Instant::now())?;
        let mut made_progress = false;
        match client_relay.recv_from(&mut client_buffer) {
            Ok((n, sender)) => {
                made_progress = true;
                let now = Instant::now();
                let known_client = udp_client_addr;
                if known_client.is_none() || known_client == Some(sender) {
                    udp_client_addr = Some(sender);
                    let Some((target, payload)) = parse_socks5_udp_packet(&client_buffer[..n], &state.config) else {
                        continue;
                    };
                    let host_info = extract_host_info(&state.config, payload);
                    let host = host_info.as_ref().map(|value| value.host.clone());
                    let Ok(route) = select_route_for_transport(
                        &state,
                        target,
                        Some(payload),
                        host.as_deref(),
                        false,
                        TransportProtocol::Udp,
                    ) else {
                        continue;
                    };
                    if let Some(telemetry) = &state.telemetry {
                        telemetry.on_route_selected(target, route.group_index, host.as_deref(), "initial");
                    }
                    if let Some(host) =
                        host.clone().filter(|_| should_cache_udp_host(&state.config, host_info.as_ref()))
                    {
                        if let Ok(mut cache) = state.cache.lock() {
                            let _ =
                                cache.store(&state.config, target, route.group_index, route.attempted_mask, Some(host));
                        }
                    }
                    let Some(group) = state.config.groups.get(route.group_index) else {
                        continue;
                    };
                    let bind_low_port = group.actions.quic_bind_low_port;
                    let activation = {
                        let adaptive_hints = resolve_adaptive_udp_hints(
                            &state,
                            target,
                            route.group_index,
                            group,
                            host.as_deref(),
                            payload,
                        )?;
                        if let std::collections::hash_map::Entry::Vacant(e) = flow_state.entry((sender, target)) {
                            e.insert(UdpFlowActivationState {
                                session: SessionState::default(),
                                last_used: now,
                                route: route.clone(),
                                host: host.clone(),
                                payload: payload.to_vec(),
                                awaiting_response: true,
                                upstream: build_udp_upstream_socket(target, protect_path.as_deref(), bind_low_port)?,
                                quic_migrated: false,
                            });
                        }
                        let entry = flow_state
                            .get_mut(&(sender, target))
                            .ok_or_else(|| io::Error::other("udp flow entry missing after insert"))?;
                        entry.last_used = now;
                        entry.route = route.clone();
                        entry.host = host.clone();
                        entry.payload.clear();
                        entry.payload.extend_from_slice(payload);
                        entry.awaiting_response = true;
                        let progress = entry.session.observe_datagram_outbound(payload);
                        super::desync::activation_context_from_progress(
                            progress,
                            ActivationTransport::Udp,
                            None,
                            None,
                            adaptive_hints,
                        )
                    };
                    let actions = plan_udp(group, payload, state.config.network.default_ttl, activation);
                    let entry = flow_state
                        .get(&(sender, target))
                        .ok_or_else(|| io::Error::other("udp flow entry missing after insert"))?;
                    execute_udp_actions(&entry.upstream, target, &actions)?;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(err) => return Err(err),
        }

        for (&(client_addr, sender), entry) in &mut flow_state {
            match entry.upstream.recv(&mut upstream_buffer) {
                Ok(n) => {
                    made_progress = true;
                    let now = Instant::now();
                    entry.last_used = now;
                    entry.session.observe_inbound(&upstream_buffer[..n]);
                    if entry.awaiting_response {
                        note_adaptive_udp_success(
                            &state,
                            sender,
                            entry.route.group_index,
                            entry.host.as_deref(),
                            &entry.payload,
                        )?;
                        note_retry_success(
                            &state,
                            sender,
                            entry.route.group_index,
                            entry.host.as_deref(),
                            Some(&entry.payload),
                            TransportProtocol::Udp,
                        )?;
                        note_route_success_for_transport(
                            &state,
                            sender,
                            &entry.route,
                            entry.host.as_deref(),
                            TransportProtocol::Udp,
                        )?;
                        entry.awaiting_response = false;
                    }
                    // QUIC connection migration: on first short-header (post-handshake)
                    // response, rebind to a new source port so DPI loses flow tracking.
                    if !entry.quic_migrated
                        && n > 0
                        && (upstream_buffer[0] & 0x80) == 0
                        && entry.session.round_count >= 2
                        && should_migrate_quic_flow(&state.config, &entry.route)
                    {
                        let bind_low_port = state
                            .config
                            .groups
                            .get(entry.route.group_index)
                            .is_some_and(|group| group.actions.quic_bind_low_port);
                        if let Ok(new_socket) = build_udp_upstream_socket(sender, protect_path.as_deref(), bind_low_port) {
                            entry.upstream = new_socket;
                            entry.quic_migrated = true;
                            tracing::info!(
                                target = %sender,
                                round = entry.session.round_count,
                                "QUIC connection migrated to new source port"
                            );
                        }
                    }
                    let packet = encode_socks5_udp_packet(sender, &upstream_buffer[..n]);
                    client_relay.send_to(&packet, client_addr)?;
                }
                Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                Err(err) if err.raw_os_error() == Some(libc::ECONNREFUSED) => {}
                Err(err) => return Err(err),
            }
        }

        if !made_progress {
            thread::sleep(Duration::from_millis(10));
        }
    }

    expire_udp_flows(&state, &mut flow_state, Instant::now())?;
    Ok(())
}

fn expire_udp_flows(
    state: &RuntimeState,
    flow_state: &mut HashMap<(SocketAddr, SocketAddr), UdpFlowActivationState>,
    now: Instant,
) -> io::Result<()> {
    let expired = flow_state
        .iter()
        .filter(|(_, value)| now.duration_since(value.last_used) >= UDP_FLOW_IDLE_TIMEOUT)
        .map(|(key, _)| *key)
        .collect::<Vec<_>>();

    for (client_addr, target) in expired {
        let Some(entry) = flow_state.remove(&(client_addr, target)) else {
            continue;
        };
        if !entry.awaiting_response {
            continue;
        }
        let _ = note_retry_failure(
            state,
            target,
            entry.route.group_index,
            entry.host.as_deref(),
            Some(entry.payload.as_slice()),
            TransportProtocol::Udp,
        )?;
        note_adaptive_udp_failure(state, target, entry.route.group_index, entry.host.as_deref(), &entry.payload)?;
        let retry_penalties = build_retry_selection_penalties(
            state,
            target,
            entry.host.as_deref(),
            Some(entry.payload.as_slice()),
            TransportProtocol::Udp,
        )?;
        let next = {
            let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
            let next = cache.advance_route(
                &state.config,
                &entry.route,
                RouteAdvance {
                    dest: target,
                    payload: Some(entry.payload.as_slice()),
                    transport: TransportProtocol::Udp,
                    trigger: DETECT_CONNECT,
                    can_reconnect: true,
                    host: entry.host.clone(),
                    penalize_strategy_failure: false,
                    retry_penalties: Some(&retry_penalties),
                },
            )?;
            flush_autolearn_updates(state, &mut cache);
            next
        };
        if let Some(next_route) = next.as_ref() {
            maybe_emit_candidate_diversification(state, target, next_route, &retry_penalties);
        }
        if let (Some(telemetry), Some(next)) = (&state.telemetry, next) {
            telemetry.on_route_advanced(
                target,
                entry.route.group_index,
                next.group_index,
                DETECT_CONNECT,
                entry.host.as_deref(),
            );
        }
    }
    Ok(())
}

pub(super) fn parse_socks5_udp_packet<'a>(packet: &'a [u8], config: &RuntimeConfig) -> Option<(SocketAddr, &'a [u8])> {
    if packet.len() < 4 || packet[2] != 0 {
        return None;
    }
    let atyp = packet[3];
    match atyp {
        S_ATP_I4 => {
            if packet.len() < 10 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[4], packet[5], packet[6], packet[7]);
            let port = u16::from_be_bytes([packet[8], packet[9]]);
            Some((SocketAddr::new(IpAddr::V4(ip), port), &packet[10..]))
        }
        S_ATP_I6 => {
            if packet.len() < 22 || !config.network.ipv6 {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[4..20]);
            let port = u16::from_be_bytes([packet[20], packet[21]]);
            Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), &packet[22..]))
        }
        0x03 => {
            let len = *packet.get(4)? as usize;
            let offset = 5 + len;
            if packet.len() < offset + 2 || !config.network.resolve {
                return None;
            }
            let host = std::str::from_utf8(&packet[5..offset]).ok()?;
            let port = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
            let resolved = super::handshake::resolve_name(host, SocketType::Datagram, config)?;
            Some((SocketAddr::new(resolved.ip(), port), &packet[offset + 2..]))
        }
        _ => None,
    }
}

pub(super) fn encode_socks5_udp_packet(sender: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut packet = vec![0, 0, 0];
    match sender {
        SocketAddr::V4(addr) => {
            packet.push(S_ATP_I4);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            packet.push(S_ATP_I6);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    packet.extend_from_slice(payload);
    packet
}

fn execute_udp_actions(upstream: &UdpSocket, target: SocketAddr, actions: &[DesyncAction]) -> io::Result<()> {
    for action in actions {
        match action {
            DesyncAction::Write(bytes) => {
                upstream.send(bytes)?;
            }
            DesyncAction::SetTtl(ttl) => {
                set_udp_ttl(upstream, target, *ttl)?;
            }
            DesyncAction::RestoreDefaultTtl => {}
            DesyncAction::WriteUrgent { .. }
            | DesyncAction::SetMd5Sig { .. }
            | DesyncAction::AttachDropSack
            | DesyncAction::DetachDropSack
            | DesyncAction::AwaitWritable
            | DesyncAction::SetWindowClamp(_)
            | DesyncAction::RestoreWindowClamp => {}
        }
    }
    Ok(())
}

fn set_udp_ttl(relay: &UdpSocket, target: SocketAddr, ttl: u8) -> io::Result<()> {
    match target {
        SocketAddr::V4(_) => relay.set_ttl(ttl as u32),
        SocketAddr::V6(_) => Ok(()),
    }
}

fn should_migrate_quic_flow(config: &RuntimeConfig, route: &ConnectionRoute) -> bool {
    config.groups.get(route.group_index).map_or(false, |group| group.actions.quic_migrate_after_handshake)
}

fn should_cache_udp_host(config: &RuntimeConfig, host: Option<&crate::runtime_policy::ExtractedHost>) -> bool {
    match host.map(|value| value.source) {
        Some(HostSource::Quic) => matches!(config.quic.initial_mode, QuicInitialMode::RouteAndCache),
        Some(HostSource::Http | HostSource::Tls) => true,
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::IpAddr;

    #[test]
    fn udp_packet_round_trip_preserves_sender_and_payload() {
        let config = RuntimeConfig::default();
        let sender = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)), 5353);
        let payload = b"dns-payload";
        let packet = encode_socks5_udp_packet(sender, payload);

        let (decoded_sender, decoded_payload) =
            parse_socks5_udp_packet(&packet, &config).expect("parse udp relay packet");
        assert_eq!(decoded_sender, sender);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn should_cache_udp_host_only_caches_quic_in_cache_mode() {
        let mut config = RuntimeConfig::default();
        let quic =
            crate::runtime_policy::ExtractedHost { host: "docs.example.test".to_string(), source: HostSource::Quic };
        let tls =
            crate::runtime_policy::ExtractedHost { host: "docs.example.test".to_string(), source: HostSource::Tls };

        config.quic.initial_mode = QuicInitialMode::Route;
        assert!(!should_cache_udp_host(&config, Some(&quic)));
        assert!(should_cache_udp_host(&config, Some(&tls)));

        config.quic.initial_mode = QuicInitialMode::RouteAndCache;
        assert!(should_cache_udp_host(&config, Some(&quic)));
    }

    #[test]
    fn udp_packet_round_trip_preserves_ipv6_sender_and_payload() {
        let mut config = RuntimeConfig::default();
        config.network.ipv6 = true;
        let sender = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)), 8443);
        let payload = b"quic-initial-stub";
        let packet = encode_socks5_udp_packet(sender, payload);

        let (decoded_sender, decoded_payload) =
            parse_socks5_udp_packet(&packet, &config).expect("parse ipv6 udp packet");
        assert_eq!(decoded_sender, sender);
        assert_eq!(decoded_payload, payload);

        // IPv6 rejected when ipv6 disabled
        config.network.ipv6 = false;
        assert!(parse_socks5_udp_packet(&packet, &config).is_none());
    }

    #[test]
    fn udp_packet_round_trip_empty_payload() {
        let config = RuntimeConfig::default();
        let sender = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 443);
        let packet = encode_socks5_udp_packet(sender, b"");

        let (decoded_sender, decoded_payload) = parse_socks5_udp_packet(&packet, &config).expect("parse empty payload");
        assert_eq!(decoded_sender, sender);
        assert!(decoded_payload.is_empty());
    }

    #[test]
    fn udp_packet_parse_rejects_malformed_packets() {
        let config = RuntimeConfig::default();

        // Too short
        assert!(parse_socks5_udp_packet(&[0, 0, 0], &config).is_none());

        // Non-zero fragment byte (index 2)
        assert!(parse_socks5_udp_packet(&[0, 0, 1, S_ATP_I4, 127, 0, 0, 1, 0, 80], &config).is_none());

        // IPv4 truncated (missing port)
        assert!(parse_socks5_udp_packet(&[0, 0, 0, S_ATP_I4, 127, 0, 0, 1], &config).is_none());

        // Unknown address type
        assert!(parse_socks5_udp_packet(&[0, 0, 0, 0x05, 0, 0, 0, 0, 0, 0], &config).is_none());
    }

    #[test]
    fn build_udp_relay_sockets_keep_client_loopback() {
        let sockets = build_udp_relay_sockets(IpAddr::V4(Ipv4Addr::LOCALHOST), None).expect("udp relay sockets");
        assert_eq!(sockets.client.local_addr().expect("client relay addr").ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
    }

    #[test]
    fn build_udp_upstream_socket_connects_ipv4_targets() {
        let upstream = build_udp_upstream_socket(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 10)), 443), None, false)
            .expect("udp upstream socket");
        assert!(upstream.local_addr().expect("upstream relay addr").is_ipv4());
    }
}
