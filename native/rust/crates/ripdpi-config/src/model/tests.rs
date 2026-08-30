use super::*;
use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;

use crate::{
    AUTO_RECONN, AUTO_SORT, DETECT_CONNECT, DETECT_HTTP_LOCAT, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
    HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
};
use ripdpi_packets::{HttpFakeProfile, MH_HMIX, MH_SPACE, TlsFakeProfile, UdpFakeProfile};

#[test]
fn prefix_match_bytes_honors_partial_bits() {
    assert!(prefix_match_bytes(&[0b1011_0000], &[0b1011_1111], 4));
    assert!(!prefix_match_bytes(&[0b1011_0000], &[0b1001_1111], 4));
}

#[test]
fn prefix_match_bytes_full_byte_boundary() {
    // 24-bit prefix (rem == 0 early return)
    assert!(prefix_match_bytes(&[192, 168, 1, 100], &[192, 168, 1, 200], 24));
    assert!(!prefix_match_bytes(&[192, 168, 2, 100], &[192, 168, 1, 200], 24));
}

#[test]
fn common_suffix_match_dot_boundary() {
    // "notexample.com" should NOT match rule "example.com"
    assert!(!common_suffix_match("notexample.com", "example.com"));
    // "sub.example.com" SHOULD match
    assert!(common_suffix_match("sub.example.com", "example.com"));
    // exact match
    assert!(common_suffix_match("example.com", "example.com"));
}

#[test]
fn fake_offset_support_rejects_adaptive_and_ech_markers() {
    assert!(OffsetExpr::marker(OffsetBase::Host, 1).supports_fake_offset());
    assert!(OffsetExpr::marker(OffsetBase::ExtLen, 0).supports_fake_offset());
    assert!(!OffsetExpr::marker(OffsetBase::EchExt, 0).supports_fake_offset());
    assert!(!OffsetExpr::adaptive(OffsetBase::AutoHost).supports_fake_offset());
}

// --- ActivationFilter ---

#[test]
fn activation_filter_is_unbounded_when_all_none() {
    let filter = ActivationFilter::default();
    assert!(filter.is_unbounded());
}

#[test]
fn activation_filter_is_bounded_with_any_field_set() {
    let with_round = ActivationFilter { round: Some(NumericRange::new(1, 3)), ..Default::default() };
    assert!(!with_round.is_unbounded());

    let with_payload = ActivationFilter { payload_size: Some(NumericRange::new(0, 100)), ..Default::default() };
    assert!(!with_payload.is_unbounded());

    let with_stream = ActivationFilter { stream_bytes: Some(NumericRange::new(0, 512)), ..Default::default() };
    assert!(!with_stream.is_unbounded());

    let with_timestamp = ActivationFilter { tcp_has_timestamp: Some(true), ..Default::default() };
    assert!(!with_timestamp.is_unbounded());
}

// --- DesyncGroup ---

#[test]
fn desync_group_new_has_correct_id_and_bit() {
    let g0 = DesyncGroup::new(0);
    assert_eq!(g0.id, 0);
    assert_eq!(g0.bit, 1);

    let g3 = DesyncGroup::new(3);
    assert_eq!(g3.id, 3);
    assert_eq!(g3.bit, 8);
}

#[test]
fn desync_group_is_not_actionable_when_empty() {
    let group = DesyncGroup::new(0);
    assert!(!group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_tcp_chain() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(5)));
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_udp_chain() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain.push(UdpChainStep {
        kind: UdpChainStepKind::FakeBurst,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    });
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_mod_http() {
    let mut group = DesyncGroup::new(0);
    group.actions.mod_http = MH_HMIX;
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_detect() {
    let mut group = DesyncGroup::new(0);
    group.matches.detect = DETECT_CONNECT;
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_hosts() {
    let mut group = DesyncGroup::new(0);
    group.matches.filters.hosts.push("example.com".to_string());
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_port_filter() {
    let mut group = DesyncGroup::new(0);
    group.matches.port_filter = Some((443, 443));
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_ext_socks() {
    let mut group = DesyncGroup::new(0);
    group.policy.ext_socks =
        Some(UpstreamSocksConfig { addr: SocketAddr::new(IpAddr::from_str("127.0.0.1").unwrap(), 1081) });
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_tlsminor() {
    let mut group = DesyncGroup::new(0);
    group.actions.tlsminor = Some(3);
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_fake_data() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_data = Some(vec![0x00]);
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_fake_sni_list() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_sni_list.push("cdn.test".to_string());
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_fake_offset() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_offset = Some(OffsetExpr::absolute(3));
    assert!(group.is_actionable());
}

#[test]
fn desync_group_is_actionable_with_ipset() {
    let mut group = DesyncGroup::new(0);
    group.matches.filters.ipset.push(Cidr { addr: IpAddr::from_str("10.0.0.0").unwrap(), bits: 8 });
    assert!(group.is_actionable());
}

#[test]
fn desync_group_effective_chains_clone_actions() {
    let mut group = DesyncGroup::new(0);
    let step = TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::host(1));
    group.actions.tcp_chain.push(step.clone());
    assert_eq!(group.effective_tcp_chain(), vec![step]);

    let udp_step = UdpChainStep {
        kind: UdpChainStepKind::QuicSniSplit,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    };
    group.actions.udp_chain.push(udp_step);
    assert_eq!(group.effective_udp_chain(), group.actions.udp_chain);
}

#[test]
fn desync_group_set_activation_filter_drops_unbounded() {
    let mut group = DesyncGroup::new(0);
    group.set_activation_filter(ActivationFilter::default());
    assert_eq!(group.matches.activation_filter, None);
    assert_eq!(group.activation_filter(), None);
}

#[test]
fn desync_group_set_activation_filter_keeps_bounded() {
    let mut group = DesyncGroup::new(0);
    let filter = ActivationFilter { round: Some(NumericRange::new(1, 5)), ..Default::default() };
    group.set_activation_filter(filter);
    assert!(group.matches.activation_filter.is_some());
    assert_eq!(group.activation_filter(), Some(filter));
}

#[test]
fn desync_group_set_round_activation() {
    let mut group = DesyncGroup::new(0);
    group.set_round_activation(Some(NumericRange::new(2, 4)));
    let act = group.activation_filter().expect("activation filter");
    assert_eq!(act.round, Some(NumericRange::new(2, 4)));
    assert_eq!(act.payload_size, None);

    // Setting None round on otherwise-empty filter drops entire filter
    group.set_round_activation(None);
    assert_eq!(group.activation_filter(), None);
}

#[test]
fn desync_group_nested_buckets_round_trip() {
    let mut group = DesyncGroup::new(2);
    let match_settings = DesyncGroupMatchSettings {
        detect: DETECT_CONNECT | DETECT_HTTP_LOCAT,
        proto: 0x22,
        any_protocol: false,
        payload_disable: 0,
        filters: FilterSet {
            hosts: vec!["video.example.test".to_string()],
            ipset: vec![Cidr { addr: IpAddr::from_str("203.0.113.10").expect("ip"), bits: 24 }],
            ..Default::default()
        },
        port_filter: Some((443, 8443)),
        activation_filter: Some(ActivationFilter {
            round: Some(NumericRange::new(2, 4)),
            payload_size: Some(NumericRange::new(64, 512)),
            stream_bytes: Some(NumericRange::new(0, 2048)),
            tcp_has_timestamp: None,
            tcp_has_ech: None,
            tcp_window_below: None,
            tcp_mss_below: None,
        }),
    };
    let split_offset = OffsetExpr::marker(OffsetBase::Host, 1);
    let tls_record = OffsetExpr::absolute(5);
    let action_settings = DesyncGroupActionSettings {
        ttl: Some(7),
        auto_ttl: Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 }),
        md5sig: true,
        fake_data: Some(vec![0x16, 0x03, 0x01]),
        fake_tls_source: FakePacketSource::CapturedClientHello,
        fake_tls_secondary_profile: Some(TlsFakeProfile::VkChrome),
        fake_tcp_timestamp_enabled: true,
        fake_tcp_timestamp_delta_ticks: 17,
        fake_offset: Some(OffsetExpr::absolute(3)),
        fake_sni_list: vec!["cdn.example.test".to_string()],
        fake_mod: 3,
        fake_tls_size: 128,
        http_fake_profile: HttpFakeProfile::CloudflareGet,
        tls_fake_profile: TlsFakeProfile::GoogleChrome,
        udp_fake_profile: UdpFakeProfile::DnsQuery,
        quic_fake_profile: QuicFakeProfile::RealisticInitial,
        quic_fake_host: Some("quic.example.test".to_string()),
        drop_sack: true,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        quic_fake_version: 0x1a2a_3a4a,
        ip_id_mode: None,
        oob_data: Some(0x42),
        tcp_chain: vec![
            TcpChainStep::new(TcpChainStepKind::TlsRec, tls_record),
            TcpChainStep::new(TcpChainStepKind::Split, split_offset),
        ],
        rotation_policy: None,
        udp_chain: vec![UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 2,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        }],
        mod_http: MH_HMIX | MH_SPACE,
        tlsminor: Some(1),
        window_clamp: Some(2),
        wsize: None,
        strip_timestamps: false,
        entropy_padding_target_permil: None,
        entropy_padding_max: 256,
        entropy_mode: EntropyMode::Disabled,
        shannon_entropy_target_permil: None,
    };
    let policy_settings = DesyncGroupPolicySettings {
        ext_socks: Some(UpstreamSocksConfig {
            addr: SocketAddr::new(IpAddr::from_str("127.0.0.1").expect("proxy ip"), 1081),
        }),
        label: "primary".to_string(),
        pri: 7,
        fail_count: 2,
        cache_ttl: 60,
        cache_file: Some("cache.txt".to_string()),
    };

    group.matches = match_settings.clone();
    group.actions = action_settings.clone();
    group.policy = policy_settings.clone();

    assert_eq!(group.matches, match_settings);
    assert_eq!(group.actions, action_settings);
    assert_eq!(group.policy, policy_settings);
    assert_eq!(
        group.activation_filter(),
        Some(ActivationFilter {
            round: Some(NumericRange::new(2, 4)),
            payload_size: Some(NumericRange::new(64, 512)),
            stream_bytes: Some(NumericRange::new(0, 2048)),
            tcp_has_timestamp: None,
            tcp_has_ech: None,
            tcp_window_below: None,
            tcp_mss_below: None,
        })
    );
}

#[test]
fn runtime_config_actionable_group_returns_first_actionable() {
    let mut config = RuntimeConfig::default();
    // Default group 0 is not actionable
    assert_eq!(config.actionable_group(), 0);

    // Add a second group that is actionable
    let mut g1 = DesyncGroup::new(1);
    g1.actions.mod_http = MH_SPACE;
    config.groups.push(g1);
    assert_eq!(config.actionable_group(), 1);
}

#[test]
fn runtime_config_adapter_views_round_trip() {
    let mut config = RuntimeConfig::default();
    let network = RuntimeNetworkSettings {
        listen: ListenConfig {
            listen_ip: IpAddr::from_str("127.0.0.1").expect("listen ip"),
            listen_port: 2442,
            bind_ip: IpAddr::from_str("::1").expect("bind ip"),
            auth_token: None,
        },
        resolve: false,
        ipv6: true,
        udp: false,
        transparent: true,
        http_connect: true,
        mixed: true,
        shadowsocks: true,
        delay_conn: true,
        tfo: true,
        max_open: 128,
        buffer_size: 32_768,
        default_ttl: 9,
        custom_ttl: true,
    };
    let timeouts = RuntimeTimeoutSettings {
        timeout_ms: 800,
        partial_timeout_ms: 120,
        timeout_count_limit: 4,
        timeout_bytes_limit: 2048,
        wait_send: true,
        await_interval: 15,
        connect_timeout_ms: 10_000,
        freeze_window_ms: 5_000,
        freeze_min_bytes: 512,
        freeze_max_stalls: 0,
    };
    let process = RuntimeProcessSettings {
        debug: 3,
        protect_path: Some("protect.sock".to_string()),
        geoip_db_path: Some("geoip.db".to_string()),
        geosite_db_path: Some("geosite.db".to_string()),
        daemonize: true,
        pid_file: Some("ripdpi.pid".to_string()),
        root_mode: false,
        root_helper_socket_path: None,
        environment_kind: EnvironmentKind::Unknown,
    };
    let quic = RuntimeQuicSettings { initial_mode: QuicInitialMode::Route, support_v1: false, support_v2: true };
    let adaptive = RuntimeAdaptiveSettings {
        auto_level: AUTO_RECONN | AUTO_SORT,
        cache_ttl: 90,
        cache_prefix: 24,
        network_scope_key: Some("wifi:test".to_string()),
        ws_tunnel_mode: WsTunnelMode::Fallback,
        ws_tunnel_fake_sni: None,
        ws_tunnel_allow_insecure_sni: false,
        ws_tunnel_worker_route: None,
        strategy_evolution: false,
        evolution_epsilon_permil: 100,
        evolution_experiment_ttl_ms: 30_000,
        evolution_decay_half_life_ms: 3_600_000,
        evolution_cooldown_after_failures: 3,
        evolution_cooldown_ms: 300_000,
    };
    let autolearn = HostAutolearnSettings {
        enabled: true,
        penalty_ttl_secs: 7200,
        max_hosts: 1024,
        store_path: Some("hosts.json".to_string()),
        warmup_probe_enabled: true,
        network_reprobe_enabled: true,
    };

    config.network = network.clone();
    config.timeouts = timeouts;
    config.process = process.clone();
    config.quic = quic;
    config.adaptive = adaptive.clone();
    config.host_autolearn = autolearn.clone();

    assert_eq!(config.network, network);
    assert_eq!(config.timeouts, timeouts);
    assert_eq!(config.process, process);
    assert_eq!(config.quic, quic);
    assert_eq!(config.adaptive, adaptive);
    assert_eq!(config.host_autolearn, autolearn);
}

// --- TcpChainStepKind ---

#[test]
fn tcp_chain_step_kind_from_mode_round_trip() {
    assert_eq!(TcpChainStepKind::from_mode(DesyncMode::None), Some(TcpChainStepKind::Split));
    assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Split), Some(TcpChainStepKind::Split));
    assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Disorder), Some(TcpChainStepKind::Disorder));
    assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Oob), Some(TcpChainStepKind::Oob));
    assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Disoob), Some(TcpChainStepKind::Disoob));
    assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Fake), Some(TcpChainStepKind::Fake));
}

#[test]
fn tcp_chain_step_kind_as_mode() {
    assert_eq!(TcpChainStepKind::Split.as_mode(), Some(DesyncMode::Split));
    assert_eq!(TcpChainStepKind::Disorder.as_mode(), Some(DesyncMode::Disorder));
    assert_eq!(TcpChainStepKind::Fake.as_mode(), Some(DesyncMode::Fake));
    assert_eq!(TcpChainStepKind::FakeSplit.as_mode(), Some(DesyncMode::Fake));
    assert_eq!(TcpChainStepKind::FakeDisorder.as_mode(), Some(DesyncMode::Disorder));
    assert_eq!(TcpChainStepKind::HostFake.as_mode(), None);
    assert_eq!(TcpChainStepKind::Oob.as_mode(), Some(DesyncMode::Oob));
    assert_eq!(TcpChainStepKind::Disoob.as_mode(), Some(DesyncMode::Disoob));
    assert_eq!(TcpChainStepKind::TlsRec.as_mode(), None);
    assert_eq!(TcpChainStepKind::TlsRandRec.as_mode(), None);
}

#[test]
fn tcp_chain_step_kind_is_tls_prelude() {
    assert!(TcpChainStepKind::TlsRec.is_tls_prelude());
    assert!(TcpChainStepKind::TlsRandRec.is_tls_prelude());
    assert!(!TcpChainStepKind::Split.is_tls_prelude());
    assert!(!TcpChainStepKind::Fake.is_tls_prelude());
}

#[test]
fn tcp_chain_step_new_defaults() {
    let step = TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(5));
    assert_eq!(step.kind(), TcpChainStepKind::Fake);
    assert_eq!(step.offset(), OffsetExpr::absolute(5));
    assert_eq!(step.activation_filter(), None);
    assert_eq!(step.midhost_offset(), None);
    assert_eq!(step.fake_host_template(), None);
    assert_eq!(step.tls_randrec_payload(), None);
    assert_eq!(step.ip_frag_payload(), None);
}

// --- OffsetExpr ---

#[test]
fn offset_expr_absolute_positive() {
    assert_eq!(OffsetExpr::absolute(10).absolute_positive(), Some(10));
    assert_eq!(OffsetExpr::absolute(0).absolute_positive(), Some(0));
    assert_eq!(OffsetExpr::absolute(-1).absolute_positive(), None);
    assert_eq!(OffsetExpr::host(5).absolute_positive(), None);
}

#[test]
fn offset_expr_needs_tls_record_adjustment() {
    // Non-Abs base always needs adjustment
    assert!(OffsetExpr::host(0).needs_tls_record_adjustment());
    // Abs with negative delta needs adjustment
    assert!(OffsetExpr::absolute(-1).needs_tls_record_adjustment());
    // Abs with non-negative delta does not need adjustment
    assert!(!OffsetExpr::absolute(0).needs_tls_record_adjustment());
    assert!(!OffsetExpr::absolute(5).needs_tls_record_adjustment());
}

#[test]
fn offset_expr_tls_marker_sets_proto() {
    let expr = OffsetExpr::tls_marker(OffsetBase::Host, 3);
    assert_eq!(expr.proto, OffsetProto::TlsOnly);
    assert_eq!(expr.base, OffsetBase::Host);
    assert_eq!(expr.delta, 3);
}

#[test]
fn offset_expr_tls_host_convenience() {
    let expr = OffsetExpr::tls_host(2);
    assert_eq!(expr.base, OffsetBase::Host);
    assert_eq!(expr.proto, OffsetProto::TlsOnly);
    assert_eq!(expr.delta, 2);
}

#[test]
fn offset_expr_with_repeat_skip_preserves_base() {
    let base = OffsetExpr::host(5);
    let modified = base.with_repeat_skip(3, 1);
    assert_eq!(modified.base, OffsetBase::Host);
    assert_eq!(modified.delta, 5);
    assert_eq!(modified.repeats, 3);
    assert_eq!(modified.skip, 1);
}

// --- OffsetBase::is_adaptive ---

#[test]
fn offset_base_is_adaptive() {
    assert!(OffsetBase::AutoBalanced.is_adaptive());
    assert!(OffsetBase::AutoHost.is_adaptive());
    assert!(OffsetBase::AutoMidSld.is_adaptive());
    assert!(OffsetBase::AutoEndHost.is_adaptive());
    assert!(OffsetBase::AutoMethod.is_adaptive());
    assert!(OffsetBase::AutoSniExt.is_adaptive());
    assert!(OffsetBase::AutoExtLen.is_adaptive());

    assert!(!OffsetBase::Abs.is_adaptive());
    assert!(!OffsetBase::Host.is_adaptive());
    assert!(!OffsetBase::Sld.is_adaptive());
    assert!(!OffsetBase::Method.is_adaptive());
}

// --- WsTunnelMode ---

#[test]
fn ws_tunnel_mode_is_enabled() {
    assert!(!WsTunnelMode::Off.is_enabled());
    assert!(WsTunnelMode::Always.is_enabled());
    assert!(WsTunnelMode::Fallback.is_enabled());
}

// --- Cidr::matches ---

#[test]
fn cidr_matches_ipv4() {
    let cidr = Cidr { addr: IpAddr::from_str("192.168.1.0").unwrap(), bits: 24 };
    assert!(cidr.matches(IpAddr::from_str("192.168.1.100").unwrap()));
    assert!(cidr.matches(IpAddr::from_str("192.168.1.0").unwrap()));
    assert!(!cidr.matches(IpAddr::from_str("192.168.2.1").unwrap()));
}

#[test]
fn cidr_matches_ipv6() {
    let cidr = Cidr { addr: IpAddr::from_str("2001:db8::").unwrap(), bits: 32 };
    assert!(cidr.matches(IpAddr::from_str("2001:db8::1").unwrap()));
    assert!(cidr.matches(IpAddr::from_str("2001:db8:ffff::1").unwrap()));
    assert!(!cidr.matches(IpAddr::from_str("2001:db9::1").unwrap()));
}

#[test]
fn cidr_rejects_cross_family() {
    let v4_cidr = Cidr { addr: IpAddr::from_str("10.0.0.0").unwrap(), bits: 8 };
    assert!(!v4_cidr.matches(IpAddr::from_str("::ffff:10.0.0.1").unwrap()));

    let v6_cidr = Cidr { addr: IpAddr::from_str("::1").unwrap(), bits: 128 };
    assert!(!v6_cidr.matches(IpAddr::from_str("0.0.0.1").unwrap()));
}

// --- FilterSet ---

#[test]
fn filter_set_hosts_match_suffix_and_exact() {
    let fs = FilterSet { hosts: vec!["example.com".to_string()], ..Default::default() };
    assert!(fs.hosts_match("example.com"));
    assert!(fs.hosts_match("sub.example.com"));
    assert!(!fs.hosts_match("notexample.com"));
    assert!(!fs.hosts_match("other.net"));
}

#[test]
fn filter_set_ipset_match() {
    let fs =
        FilterSet { ipset: vec![Cidr { addr: IpAddr::from_str("10.0.0.0").unwrap(), bits: 8 }], ..Default::default() };
    assert!(fs.ipset_match(IpAddr::from_str("10.255.255.255").unwrap()));
    assert!(!fs.ipset_match(IpAddr::from_str("11.0.0.1").unwrap()));
}

// --- NumericRange ---

#[test]
fn numeric_range_new_stores_start_and_end() {
    let range = NumericRange::new(10, 20);
    assert_eq!(range.start, 10);
    assert_eq!(range.end, 20);
}

// --- Enum defaults ---

#[test]
fn entropy_mode_default_is_disabled() {
    assert_eq!(EntropyMode::default(), EntropyMode::Disabled);
}

#[test]
fn quic_initial_mode_default() {
    assert_eq!(QuicInitialMode::default(), QuicInitialMode::RouteAndCache);
}

#[test]
fn quic_fake_profile_default() {
    assert_eq!(QuicFakeProfile::default(), QuicFakeProfile::Disabled);
}

// --- Default values verification ---

#[test]
fn default_runtime_config_has_one_group() {
    let config = RuntimeConfig::default();
    assert_eq!(config.groups.len(), 1);
    assert_eq!(config.groups[0].id, 0);
    assert_eq!(config.max_route_retries, 8);
}

#[test]
fn default_timeout_settings() {
    let ts = RuntimeTimeoutSettings::default();
    assert_eq!(ts.connect_timeout_ms, 10_000);
    assert_eq!(ts.freeze_window_ms, 5_000);
    assert_eq!(ts.freeze_min_bytes, 512);
    assert_eq!(ts.await_interval, 10);
}

#[test]
fn default_quic_settings() {
    let qs = RuntimeQuicSettings::default();
    assert_eq!(qs.initial_mode, QuicInitialMode::RouteAndCache);
    assert!(qs.support_v1);
    assert!(qs.support_v2);
}

#[test]
fn default_adaptive_settings() {
    let adaptive = RuntimeAdaptiveSettings::default();
    assert_eq!(adaptive.auto_level, 0);
    assert_eq!(adaptive.ws_tunnel_mode, WsTunnelMode::Off);
    assert!(!adaptive.strategy_evolution);
    assert_eq!(adaptive.evolution_epsilon_permil, 100);
}

#[test]
fn default_host_autolearn_settings() {
    let hl = HostAutolearnSettings::default();
    assert!(!hl.enabled);
    assert_eq!(hl.penalty_ttl_secs, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS);
    assert_eq!(hl.max_hosts, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS);
    assert_eq!(hl.store_path, None);
}

#[test]
fn runtime_worker_route_rejects_invalid_authorities() {
    for url in [
        "https://edge.example:0/relay",
        "https://edge.example:not-a-port/relay",
        "https://2001:db8::1/relay",
        "https://[2001:db8::1/relay",
    ] {
        assert!(
            RuntimeWsTunnelWorkerRoute::parse(url.to_string(), "secret-token".to_string()).is_err(),
            "route should reject {url}",
        );
    }
}

#[test]
fn default_desync_group_action_settings() {
    let actions = DesyncGroupActionSettings::default();
    assert_eq!(actions.http_fake_profile, HttpFakeProfile::CompatDefault);
    assert_eq!(actions.tls_fake_profile, TlsFakeProfile::CompatDefault);
    assert_eq!(actions.udp_fake_profile, UdpFakeProfile::CompatDefault);
    assert_eq!(actions.quic_fake_profile, QuicFakeProfile::Disabled);
    assert_eq!(actions.quic_fake_version, 0x1a2a_3a4a);
    assert_eq!(actions.entropy_padding_max, 256);
    assert_eq!(actions.entropy_mode, EntropyMode::Disabled);
    assert!(!actions.md5sig);
    assert!(!actions.drop_sack);
    assert!(!actions.strip_timestamps);
}

#[test]
fn default_network_settings_listen_port() {
    let net = RuntimeNetworkSettings::default();
    assert_eq!(net.listen.listen_port, 1080);
    assert_eq!(net.listen.listen_ip, IpAddr::V4(std::net::Ipv4Addr::LOCALHOST));
    assert!(net.resolve);
    assert!(net.udp);
    assert!(!net.transparent);
    assert!(!net.mixed);
    assert_eq!(net.max_open, 512);
    assert_eq!(net.buffer_size, 16_384);
}

// --- New coverage gap tests ---

#[test]
fn desync_group_not_actionable_with_only_window_clamp() {
    let mut group = DesyncGroup::new(0);
    group.actions.window_clamp = Some(2);
    assert!(!group.is_actionable());
}

#[test]
fn desync_group_not_actionable_with_only_strip_timestamps() {
    let mut group = DesyncGroup::new(0);
    group.actions.strip_timestamps = true;
    assert!(!group.is_actionable());
}

#[test]
fn desync_mode_discriminant_values() {
    assert_eq!(DesyncMode::None as u8, 0);
    assert_eq!(DesyncMode::Split as u8, 1);
    assert_eq!(DesyncMode::Disorder as u8, 2);
    assert_eq!(DesyncMode::Oob as u8, 3);
    assert_eq!(DesyncMode::Disoob as u8, 4);
    assert_eq!(DesyncMode::Fake as u8, 5);
}

#[test]
fn cidr_matches_slash_32_exact() {
    let cidr = Cidr { addr: IpAddr::from_str("10.0.0.1").unwrap(), bits: 32 };
    assert!(cidr.matches(IpAddr::from_str("10.0.0.1").unwrap()));
    assert!(!cidr.matches(IpAddr::from_str("10.0.0.2").unwrap()));
}

#[test]
fn cidr_matches_slash_128_exact() {
    let cidr = Cidr { addr: IpAddr::from_str("::1").unwrap(), bits: 128 };
    assert!(cidr.matches(IpAddr::from_str("::1").unwrap()));
    assert!(!cidr.matches(IpAddr::from_str("::2").unwrap()));
}

#[test]
fn common_suffix_match_empty_inputs() {
    assert!(!common_suffix_match("", "example.com"));
    assert!(!common_suffix_match("example.com", ""));
    assert!(common_suffix_match("", ""));
}

#[test]
fn prefix_match_bytes_zero_bits() {
    // 0 bits means "match everything"
    assert!(prefix_match_bytes(&[1, 2, 3, 4], &[5, 6, 7, 8], 0));
}

#[test]
fn filter_set_empty_matches_nothing() {
    let fs = FilterSet::default();
    assert!(!fs.hosts_match("example.com"));
    assert!(!fs.ipset_match(IpAddr::from_str("10.0.0.1").unwrap()));
}

#[test]
fn offset_expr_all_non_adaptive_support_fake_offset() {
    // Every non-adaptive, non-EchExt base should support fake_offset
    let supported_bases = [
        OffsetBase::Abs,
        OffsetBase::PayloadEnd,
        OffsetBase::PayloadMid,
        OffsetBase::PayloadRand,
        OffsetBase::Host,
        OffsetBase::EndHost,
        OffsetBase::HostMid,
        OffsetBase::HostRand,
        OffsetBase::Sld,
        OffsetBase::MidSld,
        OffsetBase::EndSld,
        OffsetBase::Method,
        OffsetBase::ExtLen,
        OffsetBase::SniExt,
    ];
    for base in supported_bases {
        assert!(OffsetExpr::marker(base, 0).supports_fake_offset(), "{base:?} should support fake_offset");
    }
}

#[test]
fn actionable_group_returns_zero_when_no_actionable() {
    let config = RuntimeConfig::default();
    assert_eq!(config.actionable_group(), 0);
}

#[test]
fn runtime_config_defaults_to_inert_tunneled_destination_policy() {
    let policy = RuntimeConfig::default().destination_routing;

    assert!(policy.rules.is_empty());
    assert_eq!(policy.default_action, DestinationRoutingAction::Tunneled);
    assert!(policy.canonical_digest.is_empty());
}

#[test]
fn seq_overlap_fake_mode_default_is_profile() {
    assert_eq!(SeqOverlapFakeMode::default(), SeqOverlapFakeMode::Profile);
}

#[test]
fn tcp_step_kinds_map_to_expected_emitter_tiers() {
    assert_eq!(TcpChainStepKind::Split.emitter_tier(), EmitterTier::NonRootProduction);
    assert_eq!(TcpChainStepKind::SeqOverlap.emitter_tier(), EmitterTier::RootedProduction);
    assert_eq!(TcpChainStepKind::MultiDisorder.emitter_tier(), EmitterTier::RootedProduction);
    assert_eq!(TcpChainStepKind::IpFrag2.emitter_tier(), EmitterTier::RootedProduction);
    assert_eq!(TcpChainStepKind::FakeRst.emitter_tier(), EmitterTier::LabDiagnosticsOnly);
}

#[test]
fn udp_step_kinds_map_to_expected_emitter_tiers() {
    assert_eq!(UdpChainStepKind::FakeBurst.emitter_tier(), EmitterTier::NonRootProduction);
    assert_eq!(UdpChainStepKind::QuicCryptoSplit.emitter_tier(), EmitterTier::NonRootProduction);
    assert_eq!(UdpChainStepKind::IpFrag2Udp.emitter_tier(), EmitterTier::RootedProduction);
}
