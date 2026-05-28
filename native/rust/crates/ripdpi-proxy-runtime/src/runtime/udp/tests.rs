use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
#[cfg(unix)]
use std::os::fd::AsRawFd;
use std::time::{Duration, Instant};

use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_proxy_runtime_adapter::model::config::{
    should_cache_udp_host, udp_flow_limit, QuicInitialMode, RuntimeConfig,
};
use ripdpi_proxy_runtime_adapter::model::decision::{ExtractedHost, HostSource, TransportProtocol};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::session::S_ATP_I4;

use super::flow::{udp_flow_at_capacity, UdpFlowActivationState};
use super::session::UdpFlowSession;
use super::upstream_pump::pump_udp_upstream_responses;
#[cfg(unix)]
use super::upstream_pump::ready_udp_poll_keys;
use super::{
    build_udp_relay_sockets, encode_socks5_udp_packet, parse_socks5_udp_packet, sockets, RuntimeUdpPacketSettings,
    RuntimeUdpSocketSettings, RuntimeUdpSourceRebindPolicy,
};
use crate::runtime::routing::preferred_targets_for_transport;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeConnectionRoute;

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
        dns_odoh_proxy_port: 0,
        dns_odoh_target_port: 0,
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

    let (decoded_sender, decoded_payload) = parse_socks5_udp_packet(&packet, &state).expect("parse udp relay packet");
    assert_eq!(decoded_sender, sender);
    assert_eq!(decoded_payload, payload);
}

#[test]
fn should_cache_udp_host_only_caches_quic_in_cache_mode() {
    let mut config = RuntimeConfig::default();
    let quic = ExtractedHost { host: "docs.example.test".to_string(), source: HostSource::Quic };
    let tls = ExtractedHost { host: "docs.example.test".to_string(), source: HostSource::Tls };

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

    let (decoded_sender, decoded_payload) = parse_socks5_udp_packet(&packet, &state).expect("parse ipv6 udp packet");
    assert_eq!(decoded_sender, sender);
    assert_eq!(decoded_payload, payload);

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

    assert!(parse_socks5_udp_packet(&[0, 0, 0], &state).is_none());
    assert!(parse_socks5_udp_packet(&[0, 0, 1, S_ATP_I4, 127, 0, 0, 1, 0, 80], &state).is_none());
    assert!(parse_socks5_udp_packet(&[0, 0, 0, S_ATP_I4, 127, 0, 0, 1], &state).is_none());
    assert!(parse_socks5_udp_packet(&[0, 0, 0, 0x05, 0, 0, 0, 0, 0, 0], &state).is_none());
}

#[test]
fn udp_associate_domain_targets_resolve_through_encrypted_dns_runtime_context() {
    let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");
    let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);
    let state = test_runtime_state_with_context(RuntimeConfig::default(), Some(runtime_context));
    let packet = [
        0, 0, 0, 0x03, 12, b'f', b'i', b'x', b't', b'u', b'r', b'e', b'.', b't', b'e', b's', b't', 0x01, 0xbb, b'd',
        b'n', b's',
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
    let upstream =
        sockets::build_udp_upstream_socket(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 10)), 443), None, false)
            .expect("udp upstream socket");
    assert!(upstream.local_addr().expect("upstream relay addr").is_ipv4());
}

#[test]
fn preferred_targets_for_transport_return_two_quic_edges_then_original_target() {
    let mut runtime_context = fixture_runtime_context(443);
    runtime_context.preferred_edges.insert(
        "example.org".to_string(),
        vec![
            ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyPreferredEdge {
                ip: "203.0.113.10".to_string(),
                transport_kind: "quic".to_string(),
            },
            ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyPreferredEdge {
                ip: "203.0.113.20".to_string(),
                transport_kind: "quic".to_string(),
            },
            ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyPreferredEdge {
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
fn udp_preferred_edge_response_keeps_original_socks5_source_identity() {
    let state = test_runtime_state(RuntimeConfig::default());
    let client_receiver =
        UdpSocket::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)).expect("client receiver");
    client_receiver.set_read_timeout(Some(std::time::Duration::from_secs(1))).expect("client receiver timeout");
    let client_relay = UdpSocket::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)).expect("client relay");
    let upstream = UdpSocket::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)).expect("upstream socket");
    upstream.set_nonblocking(true).expect("upstream nonblocking");
    let upstream_peer = UdpSocket::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)).expect("upstream peer");
    upstream.connect(upstream_peer.local_addr().expect("upstream peer addr")).expect("connect upstream");
    upstream_peer.connect(upstream.local_addr().expect("upstream addr")).expect("connect upstream peer");

    let original_target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 51, 100, 40)), 443);
    let preferred_edge = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443);
    let client_addr = client_receiver.local_addr().expect("client addr");
    let mut flow_state = HashMap::new();
    flow_state.insert(
        (client_addr, original_target),
        UdpFlowActivationState {
            session: UdpFlowSession::new(),
            last_used: Instant::now(),
            route: RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 },
            socket_settings: RuntimeUdpSocketSettings { bind_low_port: false },
            packet_settings: RuntimeUdpPacketSettings { default_ttl: 64, ip_id_mode: None },
            source_rebind_policy: RuntimeUdpSourceRebindPolicy::after_handshake(false),
            host: Some("example.org".to_string()),
            payload: b"quic-initial".to_vec(),
            awaiting_response: true,
            upstream,
            quic_migrated: false,
            logical_target: original_target,
            current_target: preferred_edge,
            target_candidates: vec![preferred_edge, original_target],
            target_index: 0,
            cache_host: true,
        },
    );

    upstream_peer.send(b"edge-response").expect("send upstream response");

    let mut upstream_buffer = [0u8; 1500];
    let deadline = Instant::now() + Duration::from_secs(1);
    let made_progress = loop {
        let made_progress =
            pump_udp_upstream_responses(&state, &client_relay, &mut upstream_buffer, &mut flow_state, None)
                .expect("pump upstream response");
        if made_progress || Instant::now() >= deadline {
            break made_progress;
        }
        std::thread::sleep(Duration::from_millis(10));
    };
    assert!(made_progress);
    let mut client_buffer = [0u8; 1500];
    let (n, _) = client_receiver.recv_from(&mut client_buffer).expect("client response");
    let (decoded_target, payload) =
        parse_socks5_udp_packet(&client_buffer[..n], &state).expect("parse client response");

    assert_eq!(decoded_target, original_target);
    assert_ne!(decoded_target, preferred_edge);
    assert_eq!(payload, b"edge-response");
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

#[cfg(unix)]
#[test]
fn udp_upstream_poll_returns_only_ready_flow_keys() {
    let ready_receiver =
        std::net::UdpSocket::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)).expect("ready receiver");
    ready_receiver.set_nonblocking(true).expect("ready receiver nonblocking");
    let idle_receiver =
        std::net::UdpSocket::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)).expect("idle receiver");
    idle_receiver.set_nonblocking(true).expect("idle receiver nonblocking");
    let sender = std::net::UdpSocket::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)).expect("sender");

    sender.send_to(b"ready", ready_receiver.local_addr().expect("ready receiver addr")).expect("send ready datagram");

    let client = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 10_800);
    let ready_target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 30)), 443);
    let idle_target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 31)), 443);
    let poll_entries =
        [((client, ready_target), ready_receiver.as_raw_fd()), ((client, idle_target), idle_receiver.as_raw_fd())];
    let deadline = Instant::now() + Duration::from_secs(1);
    let ready = loop {
        let ready = ready_udp_poll_keys(&poll_entries).expect("poll ready udp sockets");
        if !ready.is_empty() || Instant::now() >= deadline {
            break ready;
        }
        std::thread::sleep(Duration::from_millis(10));
    };

    assert_eq!(ready, vec![(client, ready_target)]);
}
