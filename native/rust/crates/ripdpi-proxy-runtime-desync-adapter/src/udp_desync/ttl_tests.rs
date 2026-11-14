use super::*;
use ripdpi_proxy_config::{ProxyUiConfig, ProxyUiUdpChainStep, runtime_config_from_ui};
use ripdpi_runtime_services::{ServicesState, ServicesStateHandle};
use socket2::SockRef;

#[test]
fn fixed_udp_ttl_reaches_ipv4_and_ipv6_sockets() {
    check_udp_ttl(false, 1, None);
}

#[test]
fn tcp_adaptive_ttl_preserves_fixed_udp_ttl_on_ipv4_and_ipv6_sockets() {
    check_udp_ttl(true, 9, None);
}

#[test]
fn resolved_udp_ttl_overrides_fallback_on_ipv4_and_ipv6_sockets() {
    check_udp_ttl(true, 3, Some(ripdpi_config::AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 3 }));
}

fn check_udp_ttl(adaptive: bool, expected_ttl: u32, explicit_auto_ttl: Option<ripdpi_config::AutoTtlConfig>) {
    let mut ui = ProxyUiConfig::default();
    ui.protocols.desync_udp = true;
    ui.fake_packets.fake_ttl = if adaptive { 9 } else { 1 };
    ui.fake_packets.adaptive_fake_ttl_enabled = adaptive;
    ui.fake_packets.adaptive_fake_ttl_min = 3;
    ui.fake_packets.adaptive_fake_ttl_max = 3;
    ui.fake_packets.adaptive_fake_ttl_fallback = 9;
    ui.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "quic_multi_initial_realistic".to_string(),
        count: 2,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    let mut config = runtime_config_from_ui(ui).expect("UDP TTL config");
    let group_index =
        config.groups.iter().position(|group| group.matches.proto == ripdpi_packets::IS_UDP).expect("UDP group");
    if let Some(auto_ttl) = explicit_auto_ttl {
        config.groups[group_index].actions.auto_ttl = Some(auto_ttl);
    }
    let planner = udp_desync_planner(&config);
    let services = ServicesStateHandle::new(ServicesState::new(config, None, None));
    let payload = ripdpi_packets::build_realistic_quic_initial(1, Some("private.example.test")).expect("input Initial");

    for address in ["127.0.0.1:0", "[::1]:0"] {
        let peer = UdpSocket::bind(address).expect("bind peer");
        peer.set_read_timeout(Some(Duration::from_millis(200))).expect("peer timeout");
        let sender = UdpSocket::bind(address).expect("bind sender");
        let target = peer.local_addr().expect("peer address");
        sender.connect(target).expect("connect sender");
        let actions = plan_udp_actions_for_runtime(
            UdpDesyncPlanContext {
                planner: &planner,
                runtime_context: None,
                telemetry: None,
                adaptive_hints: &services,
            },
            UdpDesyncPlanRequest {
                group_index,
                payload: &payload,
                progress: ripdpi_session::OutboundProgress {
                    round: 1,
                    payload_size: payload.len(),
                    stream_start: 0,
                    stream_end: payload.len() - 1,
                },
                host: Some("private.example.test"),
                target,
                default_ttl: 64,
            },
        )
        .expect("plan UDP TTL");
        assert_eq!(actions.first(), Some(&DesyncAction::SetTtl(expected_ttl as u8)));
        let context = UdpActionExecContext {
            upstream: &sender,
            target,
            default_ttl: 64,
            protect_path: None,
            ip_id_mode: None,
            socks_udp_frame: false,
        };

        execute_udp_actions(context, &actions[..1], &payload).expect("set fake TTL");
        assert_eq!(socket_ttl(&sender, target), expected_ttl);
        let outcome = execute_udp_actions(context, &actions[1..], &payload).expect("send UDP prelude and original");
        assert_eq!(outcome.real_writes_committed, 1);
        assert_eq!(outcome.technique_actions_completed, 2);
        assert_eq!(socket_ttl(&sender, target), 64);

        for index in 0..3 {
            let mut bytes = [0; 1500];
            let received = peer.recv(&mut bytes).expect("receive UDP packet");
            if index == 2 {
                assert_eq!(&bytes[..received], payload);
            }
        }
        assert!(peer.recv(&mut [0; 1500]).is_err(), "only two decoys and the original are sent");
    }
}

fn socket_ttl(socket: &UdpSocket, target: SocketAddr) -> u32 {
    if target.is_ipv4() {
        SockRef::from(socket).ttl_v4().expect("read IPv4 TTL")
    } else {
        SockRef::from(socket).unicast_hops_v6().expect("read IPv6 hop limit")
    }
}
