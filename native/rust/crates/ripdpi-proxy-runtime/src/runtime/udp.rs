mod actions;
mod codec;
mod flow;
mod migration;
mod sockets;

use crate::sync::{Arc, AtomicBool, Ordering};
use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_runtime_policy::runtime_policy::{extract_host_info, route_matches_payload, TransportProtocol};
use ripdpi_session::SessionState;

pub(crate) use self::codec::{encode_socks5_udp_packet, parse_socks5_udp_packet};
use self::flow::{
    expire_udp_flows, reselect_udp_flow_target, select_udp_flow_target, send_udp_flow_payload, should_cache_udp_host,
    store_udp_route_hint, udp_flow_at_capacity, udp_flow_limit, UdpFlowActivationState,
};
use self::migration::maybe_rebind_udp_source_port;
pub(crate) use self::sockets::build_udp_relay_sockets;
use super::adaptive::{
    emit_due_direct_path_learning_timeouts, note_adaptive_udp_success, note_direct_path_quic_success,
    note_evolver_success,
};
use super::retry::note_retry_success;
use super::routing::{note_route_success_for_transport, preferred_targets_for_transport};
use super::state::RuntimeState;

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
    let flow_limit = udp_flow_limit(&state.config);

    while running.load(Ordering::Relaxed) {
        emit_due_direct_path_learning_timeouts(&state)?;
        expire_udp_flows(&state, &mut flow_state, protect_path.as_deref(), Instant::now())?;
        let mut made_progress = false;
        match client_relay.recv_from(&mut client_buffer) {
            Ok((n, sender)) => {
                made_progress = true;
                let now = Instant::now();
                let known_client = udp_client_addr;
                if known_client.is_none() || known_client == Some(sender) {
                    udp_client_addr = Some(sender);
                    let Some((original_target, payload)) = parse_socks5_udp_packet(&client_buffer[..n], &state) else {
                        continue;
                    };
                    let host_info = extract_host_info(&state.config, payload);
                    let host = host_info.as_ref().map(|value| value.host.clone());
                    let cache_host = should_cache_udp_host(&state.config, host_info.as_ref());
                    let flow_key = (sender, original_target);
                    if udp_flow_at_capacity(&flow_state, flow_key, flow_limit) {
                        tracing::warn!(
                            client = %sender,
                            target = %original_target,
                            flows = flow_state.len(),
                            limit = flow_limit,
                            "UDP flow rejected: at capacity"
                        );
                        if let Some(telemetry) = &state.telemetry {
                            telemetry.on_client_slot_exhausted();
                        }
                        continue;
                    }
                    if let std::collections::hash_map::Entry::Vacant(e) = flow_state.entry(flow_key) {
                        let target_candidates = preferred_targets_for_transport(
                            &state,
                            original_target,
                            host.as_deref(),
                            TransportProtocol::Udp,
                        );
                        let Some(selection) = select_udp_flow_target(
                            &state,
                            protect_path.as_deref(),
                            host.as_deref(),
                            payload,
                            &target_candidates,
                            0,
                            "initial",
                        )?
                        else {
                            continue;
                        };
                        let entry = UdpFlowActivationState {
                            session: SessionState::default(),
                            last_used: now,
                            route: selection.route,
                            host: host.clone(),
                            payload: Vec::new(),
                            awaiting_response: true,
                            upstream: selection.upstream,
                            quic_migrated: false,
                            current_target: selection.target,
                            target_candidates,
                            target_index: selection.target_index,
                            cache_host,
                        };
                        store_udp_route_hint(&state, &entry)?;
                        e.insert(entry);
                    }
                    let entry = flow_state
                        .get_mut(&flow_key)
                        .ok_or_else(|| io::Error::other("udp flow entry missing after insert"))?;
                    let host_changed = entry.host.as_deref() != host.as_deref();
                    entry.host = host.clone();
                    entry.cache_host = cache_host;
                    if host_changed
                        || !route_matches_payload(
                            &state.config,
                            entry.route.group_index,
                            entry.current_target,
                            payload,
                            TransportProtocol::Udp,
                        )
                    {
                        let Some(selection) = reselect_udp_flow_target(
                            &state,
                            protect_path.as_deref(),
                            original_target,
                            payload,
                            host.as_deref(),
                        )?
                        else {
                            continue;
                        };
                        entry.route = selection.route;
                        entry.upstream = selection.upstream;
                        entry.current_target = selection.target;
                        entry.target_candidates = selection.target_candidates;
                        entry.target_index = selection.target_index;
                        entry.quic_migrated = false;
                        store_udp_route_hint(&state, entry)?;
                    }
                    send_udp_flow_payload(&state, entry, payload, now, protect_path.as_deref())?;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(err) => return Err(err),
        }

        for (&(client_addr, _logical_target), entry) in &mut flow_state {
            match entry.upstream.recv(&mut upstream_buffer) {
                Ok(n) => {
                    made_progress = true;
                    let now = Instant::now();
                    entry.last_used = now;
                    entry.session.observe_inbound(&upstream_buffer[..n]);
                    if entry.awaiting_response {
                        let _ = note_direct_path_quic_success(&state, entry.host.as_deref(), &entry.target_candidates);
                        note_adaptive_udp_success(
                            &state,
                            entry.current_target,
                            entry.route.group_index,
                            entry.host.as_deref(),
                            &entry.payload,
                        )?;
                        note_retry_success(
                            &state,
                            entry.current_target,
                            entry.route.group_index,
                            entry.host.as_deref(),
                            Some(&entry.payload),
                            TransportProtocol::Udp,
                        )?;
                        note_route_success_for_transport(
                            &state,
                            entry.current_target,
                            &entry.route,
                            entry.host.as_deref(),
                            TransportProtocol::Udp,
                        )?;
                        note_evolver_success(&state, 0);
                        entry.awaiting_response = false;
                    }
                    maybe_rebind_udp_source_port(&state, entry, &upstream_buffer[..n], protect_path.as_deref())?;
                    let packet = encode_socks5_udp_packet(entry.current_target, &upstream_buffer[..n]);
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

    expire_udp_flows(&state, &mut flow_state, protect_path.as_deref(), Instant::now())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::state::RuntimeState;
    use local_network_fixture::{FixtureConfig, FixtureStack};
    use ripdpi_config::{QuicInitialMode, RuntimeConfig};
    use ripdpi_proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
    use ripdpi_runtime_policy::runtime_policy::HostSource;
    use ripdpi_session::S_ATP_I4;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    fn test_runtime_state(config: RuntimeConfig) -> RuntimeState {
        test_runtime_state_with_context(config, None)
    }

    fn test_runtime_state_with_context(
        config: RuntimeConfig,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> RuntimeState {
        RuntimeState::test_with_context(config, runtime_context)
    }

    fn fixture_runtime_context(dns_http_port: u16) -> ProxyRuntimeContext {
        ProxyRuntimeContext {
            encrypted_dns: Some(ProxyEncryptedDnsContext {
                resolver_id: Some("fixture-doh".to_string()),
                protocol: "doh".to_string(),
                host: "127.0.0.1".to_string(),
                port: dns_http_port,
                tls_server_name: None,
                bootstrap_ips: vec!["127.0.0.1".to_string()],
                doh_url: Some(format!("http://127.0.0.1:{dns_http_port}/dns-query")),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            }),
            protect_path: None,
            preferred_edges: std::collections::BTreeMap::default(),
            direct_path_capabilities: Vec::new(),
            morph_policy: None,
        }
    }

    fn dynamic_fixture_config() -> FixtureConfig {
        FixtureConfig {
            tcp_echo_port: 0,
            udp_echo_port: 0,
            tls_echo_port: 0,
            dns_udp_port: 0,
            dns_http_port: 0,
            dns_dot_port: 0,
            dns_dnscrypt_port: 0,
            dns_doq_port: 0,
            socks5_port: 0,
            control_port: 0,
            ..FixtureConfig::default()
        }
    }

    #[test]
    fn udp_packet_round_trip_preserves_sender_and_payload() {
        let config = RuntimeConfig::default();
        let state = test_runtime_state(config);
        let sender = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)), 5353);
        let payload = b"dns-payload";
        let packet = encode_socks5_udp_packet(sender, payload);

        let (decoded_sender, decoded_payload) =
            parse_socks5_udp_packet(&packet, &state).expect("parse udp relay packet");
        assert_eq!(decoded_sender, sender);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn should_cache_udp_host_only_caches_quic_in_cache_mode() {
        let mut config = RuntimeConfig::default();
        let quic = ripdpi_runtime_policy::runtime_policy::ExtractedHost {
            host: "docs.example.test".to_string(),
            source: HostSource::Quic,
        };
        let tls = ripdpi_runtime_policy::runtime_policy::ExtractedHost {
            host: "docs.example.test".to_string(),
            source: HostSource::Tls,
        };

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
        let state = test_runtime_state(config.clone());
        let sender = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)), 8443);
        let payload = b"quic-initial-stub";
        let packet = encode_socks5_udp_packet(sender, payload);

        let (decoded_sender, decoded_payload) =
            parse_socks5_udp_packet(&packet, &state).expect("parse ipv6 udp packet");
        assert_eq!(decoded_sender, sender);
        assert_eq!(decoded_payload, payload);

        // IPv6 rejected when ipv6 disabled
        config.network.ipv6 = false;
        assert!(parse_socks5_udp_packet(&packet, &test_runtime_state(config)).is_none());
    }

    #[test]
    fn udp_packet_round_trip_empty_payload() {
        let config = RuntimeConfig::default();
        let state = test_runtime_state(config);
        let sender = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 443);
        let packet = encode_socks5_udp_packet(sender, b"");

        let (decoded_sender, decoded_payload) = parse_socks5_udp_packet(&packet, &state).expect("parse empty payload");
        assert_eq!(decoded_sender, sender);
        assert!(decoded_payload.is_empty());
    }

    #[test]
    fn udp_packet_parse_rejects_malformed_packets() {
        let config = RuntimeConfig::default();
        let state = test_runtime_state(config);

        // Too short
        assert!(parse_socks5_udp_packet(&[0, 0, 0], &state).is_none());

        // Non-zero fragment byte (index 2)
        assert!(parse_socks5_udp_packet(&[0, 0, 1, S_ATP_I4, 127, 0, 0, 1, 0, 80], &state).is_none());

        // IPv4 truncated (missing port)
        assert!(parse_socks5_udp_packet(&[0, 0, 0, S_ATP_I4, 127, 0, 0, 1], &state).is_none());

        // Unknown address type
        assert!(parse_socks5_udp_packet(&[0, 0, 0, 0x05, 0, 0, 0, 0, 0, 0], &state).is_none());
    }

    #[test]
    fn udp_associate_domain_targets_resolve_through_encrypted_dns_runtime_context() {
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");
        let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);
        let state = test_runtime_state_with_context(RuntimeConfig::default(), Some(runtime_context));
        let packet = [
            0, 0, 0, 0x03, 12, b'f', b'i', b'x', b't', b'u', b'r', b'e', b'.', b't', b'e', b's', b't', 0x01, 0xbb,
            b'd', b'n', b's',
        ];

        let (target, payload) = parse_socks5_udp_packet(&packet, &state).expect("parse udp associate domain target");

        assert_eq!(target.ip(), stack.manifest().dns_answer_ipv4.parse::<IpAddr>().expect("fixture ip"));
        assert_eq!(target.port(), 443);
        assert_eq!(payload, b"dns");
    }

    #[test]
    fn build_udp_relay_sockets_keep_client_loopback() {
        let sockets = build_udp_relay_sockets(IpAddr::V4(Ipv4Addr::LOCALHOST), None).expect("udp relay sockets");
        assert_eq!(sockets.client.local_addr().expect("client relay addr").ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
    }

    #[test]
    fn build_udp_upstream_socket_connects_ipv4_targets() {
        let upstream = sockets::build_udp_upstream_socket(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 10)), 443),
            None,
            false,
        )
        .expect("udp upstream socket");
        assert!(upstream.local_addr().expect("upstream relay addr").is_ipv4());
    }

    #[test]
    fn preferred_targets_for_transport_return_two_quic_edges_then_original_target() {
        let mut runtime_context = fixture_runtime_context(443);
        runtime_context.preferred_edges.insert(
            "example.org".to_string(),
            vec![
                ripdpi_proxy_config::ProxyPreferredEdge {
                    ip: "203.0.113.10".to_string(),
                    transport_kind: "quic".to_string(),
                },
                ripdpi_proxy_config::ProxyPreferredEdge {
                    ip: "203.0.113.20".to_string(),
                    transport_kind: "quic".to_string(),
                },
                ripdpi_proxy_config::ProxyPreferredEdge {
                    ip: "203.0.113.30".to_string(),
                    transport_kind: "quic".to_string(),
                },
            ],
        );
        let state = test_runtime_state_with_context(RuntimeConfig::default(), Some(runtime_context));
        let original = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 51, 100, 40)), 443);

        let targets = preferred_targets_for_transport(&state, original, Some("example.org"), TransportProtocol::Udp);

        assert_eq!(
            targets,
            vec![
                SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443),
                SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443),
                original,
            ]
        );
    }

    #[test]
    fn udp_flow_limit_floors_non_positive_limits_to_one() {
        let mut config = RuntimeConfig::default();
        config.network.max_open = 0;
        assert_eq!(udp_flow_limit(&config), 1);

        config.network.max_open = -8;
        assert_eq!(udp_flow_limit(&config), 1);
    }

    #[test]
    fn udp_flow_capacity_rejects_only_new_flows_once_limit_is_reached() {
        let client = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 10_800);
        let first_target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 10)), 443);
        let second_target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 11)), 443);
        let third_target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 12)), 443);
        let mut flow_state = HashMap::<(SocketAddr, SocketAddr), ()>::new();

        flow_state.insert((client, first_target), ());
        flow_state.insert((client, second_target), ());

        assert!(!udp_flow_at_capacity(&flow_state, (client, first_target), 2));
        assert!(udp_flow_at_capacity(&flow_state, (client, third_target), 2));
    }
}
