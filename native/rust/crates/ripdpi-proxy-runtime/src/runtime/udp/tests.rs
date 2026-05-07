use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_config::{QuicInitialMode, RuntimeConfig};
use ripdpi_proxy_runtime_adapter::proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::session::S_ATP_I4;
use ripdpi_runtime_decision_ports::policy::{HostSource, TransportProtocol};

use super::client_receive::should_cache_udp_host;
use super::flow::{udp_flow_at_capacity, udp_flow_limit};
use super::{build_udp_relay_sockets, encode_socks5_udp_packet, parse_socks5_udp_packet, sockets};
use crate::runtime::routing::preferred_targets_for_transport;
use crate::runtime::state::RuntimeState;

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

    let (decoded_sender, decoded_payload) = parse_socks5_udp_packet(&packet, &state).expect("parse udp relay packet");
    assert_eq!(decoded_sender, sender);
    assert_eq!(decoded_payload, payload);
}

#[test]
fn should_cache_udp_host_only_caches_quic_in_cache_mode() {
    let mut config = RuntimeConfig::default();
    let quic = ripdpi_runtime_decision_ports::policy::ExtractedHost {
        host: "docs.example.test".to_string(),
        source: HostSource::Quic,
    };
    let tls = ripdpi_runtime_decision_ports::policy::ExtractedHost {
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
            ripdpi_proxy_runtime_adapter::proxy_config::ProxyPreferredEdge {
                ip: "203.0.113.10".to_string(),
                transport_kind: "quic".to_string(),
            },
            ripdpi_proxy_runtime_adapter::proxy_config::ProxyPreferredEdge {
                ip: "203.0.113.20".to_string(),
                transport_kind: "quic".to_string(),
            },
            ripdpi_proxy_runtime_adapter::proxy_config::ProxyPreferredEdge {
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
