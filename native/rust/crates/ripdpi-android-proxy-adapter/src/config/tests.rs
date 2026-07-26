use super::*;

use std::net::IpAddr;
use std::str::FromStr;

use proptest::collection::vec;
use proptest::prelude::*;
use ripdpi_config::{
    FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI, QuicFakeProfile, QuicInitialMode, TcpChainStepKind,
};
use ripdpi_proxy_config::{
    FAKE_TLS_SNI_MODE_RANDOMIZED, ProxyUiActivationFilter, ProxyUiChainConfig, ProxyUiDestinationRoutingConfig,
    ProxyUiFakePacketConfig, ProxyUiHostAutolearnConfig, ProxyUiHostsConfig, ProxyUiListenConfig,
    ProxyUiParserEvasionConfig, ProxyUiProtocolConfig, ProxyUiQuicConfig, ProxyUiTcpChainStep, ProxyUiUdpChainStep,
    QUIC_FAKE_PROFILE_DISABLED,
};

const HOSTS_DISABLE: &str = "disable";
const HOSTS_BLACKLIST: &str = "blacklist";
const HOSTS_WHITELIST: &str = "whitelist";

fn lossy_string(max_len: usize) -> impl Strategy<Value = String> {
    vec(any::<u8>(), 0..max_len).prop_map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
}

fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: kind.to_string(),
        marker: marker.to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        fake_order: String::new(),
        fake_seq_mode: String::new(),
        tcp_flags_set: String::new(),
        tcp_flags_unset: String::new(),
        tcp_flags_orig_set: String::new(),
        tcp_flags_orig_unset: String::new(),
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
        random_fake_host: false,
    }
}

fn udp_step(kind: &str, count: i32) -> ProxyUiUdpChainStep {
    ProxyUiUdpChainStep {
        kind: kind.to_string(),
        count,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }
}

fn minimal_ui_config() -> ProxyUiConfig {
    let mut config = ProxyUiConfig::default();
    config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
    config
}

fn ui_payload(config: ProxyUiConfig) -> ProxyConfigPayload {
    ProxyConfigPayload::Ui {
        strategy_preset: None,
        config,
        runtime_context: None,
        log_context: None,
        session_overrides: None,
        // Current native-config wire schema version.
        schema_version: 2,
    }
}

fn command_line_payload(args: Vec<String>) -> ProxyConfigPayload {
    ProxyConfigPayload::CommandLine {
        args,
        host_autolearn_store_path: None,
        destination_routing: ProxyUiDestinationRoutingConfig::default(),
        geoip_db_path: None,
        geosite_db_path: None,
        runtime_context: None,
        log_context: None,
        session_overrides: None,
        // Current native-config wire schema version.
        schema_version: 2,
    }
}

#[derive(Debug)]
struct ListenParts {
    ip: String,
    port: i32,
    max_connections: i32,
    buffer_size: i32,
    tcp_fast_open: bool,
    default_ttl: i32,
    custom_ttl: bool,
}

#[derive(Debug)]
struct ProtocolParts {
    resolve_domains: bool,
    desync_http: bool,
    desync_https: bool,
    desync_udp: bool,
}

#[derive(Debug)]
struct TcpParts {
    desync_method: String,
    split_marker: Option<String>,
    use_activation_filter: bool,
    fragment_count: i32,
}

#[derive(Debug)]
struct FakePacketParts {
    fake_ttl: i32,
    fake_sni: String,
    oob_char: u8,
    fake_tls_use_original: bool,
    fake_tls_randomize: bool,
    fake_tls_dup_session_id: bool,
    fake_tls_pad_encap: bool,
    fake_tls_size: i32,
    fake_offset_marker: Option<String>,
    drop_sack: bool,
}

#[derive(Debug)]
struct ParserEvasionParts {
    host_mixed_case: bool,
    domain_mixed_case: bool,
    host_remove_spaces: bool,
    http_method_eol: bool,
    http_unix_eol: bool,
    http_method_space: bool,
    http_host_pad: bool,
    http_host_extra_space: bool,
    http_host_tab: bool,
}

#[derive(Debug)]
struct HostsParts {
    mode: String,
    entries: Option<String>,
}

#[derive(Debug)]
struct QuicParts {
    initial_mode: String,
    support_v1: bool,
    support_v2: bool,
    fake_profile: String,
    fake_host: String,
}

#[derive(Debug)]
struct AutolearnParts {
    enabled: bool,
    penalty_ttl_hours: i64,
    max_hosts: usize,
    store_path: Option<String>,
}

fn listen_strategy() -> impl Strategy<Value = ListenParts> {
    (
        lossy_string(48),
        -32i32..65_536i32,
        -16i32..4_096i32,
        -16i32..65_536i32,
        any::<bool>(),
        -16i32..512i32,
        any::<bool>(),
    )
        .prop_map(|(ip, port, max_connections, buffer_size, tcp_fast_open, default_ttl, custom_ttl)| ListenParts {
            ip,
            port,
            max_connections,
            buffer_size,
            tcp_fast_open,
            default_ttl,
            custom_ttl,
        })
}

fn protocol_strategy() -> impl Strategy<Value = ProtocolParts> {
    (any::<bool>(), any::<bool>(), any::<bool>(), any::<bool>()).prop_map(
        |(resolve_domains, desync_http, desync_https, desync_udp)| ProtocolParts {
            resolve_domains,
            desync_http,
            desync_https,
            desync_udp,
        },
    )
}

fn tcp_strategy() -> impl Strategy<Value = TcpParts> {
    (
        prop_oneof![
            Just("none".to_string()),
            Just("split".to_string()),
            Just("disorder".to_string()),
            Just("fake".to_string()),
            Just("oob".to_string()),
            Just("disoob".to_string()),
            lossy_string(16),
        ],
        proptest::option::of(lossy_string(24)),
        any::<bool>(),
        0i32..8i32,
    )
        .prop_map(|(desync_method, split_marker, use_activation_filter, fragment_count)| TcpParts {
            desync_method,
            split_marker,
            use_activation_filter,
            fragment_count,
        })
}

fn fake_packet_strategy() -> impl Strategy<Value = FakePacketParts> {
    (
        -16i32..512i32,
        lossy_string(64),
        any::<u8>(),
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
        -16i32..1_024i32,
        proptest::option::of(lossy_string(24)),
        any::<bool>(),
    )
        .prop_map(
            |(
                fake_ttl,
                fake_sni,
                oob_char,
                fake_tls_use_original,
                fake_tls_randomize,
                fake_tls_dup_session_id,
                fake_tls_pad_encap,
                fake_tls_size,
                fake_offset_marker,
                drop_sack,
            )| FakePacketParts {
                fake_ttl,
                fake_sni,
                oob_char,
                fake_tls_use_original,
                fake_tls_randomize,
                fake_tls_dup_session_id,
                fake_tls_pad_encap,
                fake_tls_size,
                fake_offset_marker,
                drop_sack,
            },
        )
}

fn parser_evasion_strategy() -> impl Strategy<Value = ParserEvasionParts> {
    (
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
        any::<bool>(),
    )
        .prop_map(
            |(
                host_mixed_case,
                domain_mixed_case,
                host_remove_spaces,
                http_method_eol,
                http_unix_eol,
                http_method_space,
                http_host_pad,
                http_host_extra_space,
                http_host_tab,
            )| ParserEvasionParts {
                host_mixed_case,
                domain_mixed_case,
                host_remove_spaces,
                http_method_eol,
                http_unix_eol,
                http_method_space,
                http_host_pad,
                http_host_extra_space,
                http_host_tab,
            },
        )
}

fn hosts_strategy() -> impl Strategy<Value = HostsParts> {
    (
        prop_oneof![
            Just(HOSTS_DISABLE.to_string()),
            Just(HOSTS_BLACKLIST.to_string()),
            Just(HOSTS_WHITELIST.to_string()),
            lossy_string(16),
        ],
        proptest::option::of(lossy_string(64)),
    )
        .prop_map(|(mode, entries)| HostsParts { mode, entries })
}

fn quic_strategy() -> impl Strategy<Value = QuicParts> {
    (
        prop_oneof![
            Just("disabled".to_string()),
            Just("route".to_string()),
            Just("route_and_cache".to_string()),
            lossy_string(24),
        ],
        any::<bool>(),
        any::<bool>(),
        prop_oneof![
            Just(QUIC_FAKE_PROFILE_DISABLED.to_string()),
            Just("compat_default".to_string()),
            Just("realistic_initial".to_string()),
            lossy_string(24),
        ],
        lossy_string(64),
    )
        .prop_map(|(initial_mode, support_v1, support_v2, fake_profile, fake_host)| QuicParts {
            initial_mode,
            support_v1,
            support_v2,
            fake_profile,
            fake_host,
        })
}

fn autolearn_strategy() -> impl Strategy<Value = AutolearnParts> {
    (any::<bool>(), -32i64..48i64, 0usize..2_048usize, proptest::option::of(lossy_string(96))).prop_map(
        |(enabled, penalty_ttl_hours, max_hosts, store_path)| AutolearnParts {
            enabled,
            penalty_ttl_hours,
            max_hosts,
            store_path,
        },
    )
}

fn listen_config(parts: ListenParts) -> ProxyUiListenConfig {
    ProxyUiListenConfig {
        ip: parts.ip,
        port: parts.port,
        max_connections: parts.max_connections,
        buffer_size: parts.buffer_size,
        tcp_fast_open: parts.tcp_fast_open,
        default_ttl: parts.default_ttl,
        custom_ttl: parts.custom_ttl,
        freeze_detection_enabled: false,
        mixed: false,
        auth_token: None,
    }
}

fn protocol_config(parts: ProtocolParts) -> ProxyUiProtocolConfig {
    ProxyUiProtocolConfig {
        resolve_domains: parts.resolve_domains,
        desync_http: parts.desync_http,
        desync_https: parts.desync_https,
        desync_udp: parts.desync_udp,
        udp_associate_enabled: None,
    }
}

fn chain_config(tcp: TcpParts, udp_fake_count: i32) -> ProxyUiChainConfig {
    ProxyUiChainConfig {
        any_protocol: false,
        payload_disable: Vec::new(),
        tcp_steps: tcp_steps(tcp),
        tcp_rotation: None,
        udp_steps: if udp_fake_count > 0 { vec![udp_step("fake_burst", udp_fake_count)] } else { Vec::new() },
        group_activation_filter: None,
    }
}

fn tcp_steps(parts: TcpParts) -> Vec<ProxyUiTcpChainStep> {
    if parts.desync_method == "none" {
        return Vec::new();
    }
    vec![ProxyUiTcpChainStep {
        kind: parts.desync_method,
        marker: parts.split_marker.filter(|value| !value.trim().is_empty()).unwrap_or_else(|| "1".to_string()),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        fake_order: String::new(),
        fake_seq_mode: String::new(),
        tcp_flags_set: String::new(),
        tcp_flags_unset: String::new(),
        tcp_flags_orig_set: String::new(),
        tcp_flags_orig_unset: String::new(),
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: parts.fragment_count,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: parts.use_activation_filter.then_some(ProxyUiActivationFilter::default()),
        ipv6_extension_profile: "none".to_string(),
        random_fake_host: false,
    }]
}

fn fake_packet_config(parts: FakePacketParts, default_config: ProxyUiFakePacketConfig) -> ProxyUiFakePacketConfig {
    ProxyUiFakePacketConfig {
        fake_ttl: parts.fake_ttl,
        fake_sni: parts.fake_sni,
        oob_char: parts.oob_char,
        fake_tls_use_original: parts.fake_tls_use_original,
        fake_tls_randomize: parts.fake_tls_randomize,
        fake_tls_dup_session_id: parts.fake_tls_dup_session_id,
        fake_tls_pad_encap: parts.fake_tls_pad_encap,
        fake_tls_size: parts.fake_tls_size,
        fake_offset_marker: parts.fake_offset_marker.unwrap_or_else(|| "0".to_string()),
        drop_sack: parts.drop_sack,
        ..default_config
    }
}

fn parser_evasion_config(parts: ParserEvasionParts) -> ProxyUiParserEvasionConfig {
    ProxyUiParserEvasionConfig {
        host_mixed_case: parts.host_mixed_case,
        domain_mixed_case: parts.domain_mixed_case,
        host_remove_spaces: parts.host_remove_spaces,
        http_method_eol: parts.http_method_eol,
        http_unix_eol: parts.http_unix_eol,
        http_method_space: parts.http_method_space,
        http_host_pad: parts.http_host_pad,
        http_host_extra_space: parts.http_host_extra_space,
        http_host_tab: parts.http_host_tab,
    }
}

fn quic_config(parts: QuicParts) -> ProxyUiQuicConfig {
    ProxyUiQuicConfig {
        initial_mode: parts.initial_mode,
        support_v1: parts.support_v1,
        support_v2: parts.support_v2,
        fake_profile: parts.fake_profile,
        fake_host: parts.fake_host,
    }
}

fn autolearn_config(parts: AutolearnParts) -> ProxyUiHostAutolearnConfig {
    ProxyUiHostAutolearnConfig {
        enabled: parts.enabled,
        penalty_ttl_hours: parts.penalty_ttl_hours,
        max_hosts: parts.max_hosts,
        store_path: parts.store_path,
        network_scope_key: None,
        warmup_probe_enabled: true,
        network_reprobe_enabled: true,
    }
}

fn proxy_ui_config_strategy() -> impl Strategy<Value = ProxyUiConfig> {
    (
        listen_strategy(),
        protocol_strategy(),
        tcp_strategy(),
        fake_packet_strategy(),
        parser_evasion_strategy(),
        hosts_strategy(),
        quic_strategy(),
        autolearn_strategy(),
        0i32..8i32,
    )
        .prop_map(
            |(listen, protocols, tcp, fake_packets, parser_evasions, hosts, quic, autolearn, udp_fake_count)| {
                let mut config = minimal_ui_config();
                config.listen = listen_config(listen);
                config.protocols = protocol_config(protocols);
                config.chains = chain_config(tcp, udp_fake_count);
                config.fake_packets = fake_packet_config(fake_packets, config.fake_packets);
                config.parser_evasions = parser_evasion_config(parser_evasions);
                config.hosts = ProxyUiHostsConfig { mode: hosts.mode, entries: hosts.entries };
                config.quic = quic_config(quic);
                config.host_autolearn = autolearn_config(autolearn);
                config
            },
        )
}

#[test]
fn parses_ui_config_payload() {
    let mut ui = minimal_ui_config();
    ui.protocols.desync_udp = false;
    ui.chains.tcp_steps = vec![tcp_step("fake", "host+1")];
    ui.fake_packets.fake_ttl = 8;
    ui.fake_packets.fake_sni = "www.iana.org".to_string();
    ui.fake_packets.fake_tls_use_original = true;
    ui.fake_packets.fake_tls_randomize = true;
    ui.fake_packets.fake_tls_dup_session_id = true;
    ui.fake_packets.fake_tls_pad_encap = true;
    ui.fake_packets.fake_tls_size = 192;
    ui.fake_packets.fake_tls_sni_mode = FAKE_TLS_SNI_MODE_RANDOMIZED.to_string();
    ui.quic.initial_mode = "route".to_string();
    ui.quic.support_v1 = false;
    ui.quic.support_v2 = true;
    ui.quic.fake_profile = "realistic_initial".to_string();
    ui.quic.fake_host = "video.example.test".to_string();
    ui.host_autolearn.enabled = true;
    ui.host_autolearn.penalty_ttl_hours = 1;
    ui.host_autolearn.max_hosts = 128;
    ui.host_autolearn.store_path = Some("/tmp/host-autolearn-v2.json".to_string());
    ui.host_autolearn.network_scope_key = Some("scope-a".to_string());

    let config = runtime_config_from_payload(ui_payload(ui)).expect("ui config");

    assert_eq!(config.network.listen.listen_port, 1080);
    // TCP primary + adaptive_direct + 3 ripdpi_default fallback groups + CONNECT passthrough
    assert_eq!(config.groups.len(), 6);
    assert_eq!(config.quic.initial_mode, QuicInitialMode::Route);
    assert!(!config.quic.support_v1);
    assert!(config.quic.support_v2);
    assert_eq!(config.groups[0].actions.quic_fake_profile, QuicFakeProfile::RealisticInitial);
    assert_eq!(config.groups[0].actions.quic_fake_host.as_deref(), Some("video.example.test"));
    assert_eq!(config.groups[0].actions.fake_mod, FM_ORIG | FM_RAND | FM_DUPSID | FM_PADENCAP | FM_RNDSNI);
    assert_eq!(config.groups[0].actions.fake_tls_size, 192);
    assert!(config.groups[0].actions.fake_sni_list.is_empty());
    assert!(config.host_autolearn.enabled);
    assert_eq!(config.host_autolearn.penalty_ttl_secs, 3_600);
    assert_eq!(config.host_autolearn.max_hosts, 128);
    assert_eq!(config.host_autolearn.store_path.as_deref(), Some("/tmp/host-autolearn-v2.json"));
}

#[test]
fn parses_hostfake_tcp_chain_step_payload() {
    let mut ui = minimal_ui_config();
    ui.chains.tcp_steps = vec![ProxyUiTcpChainStep {
        kind: "hostfake".to_string(),
        marker: "endhost+8".to_string(),
        midhost_marker: "midsld".to_string(),
        fake_host_template: "googlevideo.com".to_string(),
        fake_order: String::new(),
        fake_seq_mode: String::new(),
        tcp_flags_set: String::new(),
        tcp_flags_unset: String::new(),
        tcp_flags_orig_set: String::new(),
        tcp_flags_orig_unset: String::new(),
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: Some(ProxyUiActivationFilter::default()),
        ipv6_extension_profile: "none".to_string(),
        random_fake_host: false,
    }];

    let config = runtime_config_from_payload(ui_payload(ui)).expect("hostfake ui config");
    let group = &config.groups[0];

    assert_eq!(group.actions.tcp_chain.len(), 2);
    assert!(matches!(group.actions.tcp_chain[0].kind(), TcpChainStepKind::TlsRec));
    assert_eq!(
        group.actions.tcp_chain[0].offset(),
        ripdpi_config::OffsetExpr::tls_marker(ripdpi_config::OffsetBase::ExtLen, 0)
    );
    assert!(matches!(group.actions.tcp_chain[1].kind(), TcpChainStepKind::HostFake));
    assert_eq!(
        group.actions.tcp_chain[1].midhost_offset(),
        Some(ripdpi_config::OffsetExpr::marker(ripdpi_config::OffsetBase::MidSld, 0))
    );
    assert_eq!(group.actions.tcp_chain[1].fake_host_template(), Some("googlevideo.com"));
}

#[test]
fn parses_tlsrandrec_tcp_chain_step_payload() {
    let mut value = serde_json::to_value(ui_payload(minimal_ui_config())).expect("serialize payload");
    value["chains"]["tcpSteps"] = serde_json::json!([
        {
            "kind": "tlsrandrec",
            "marker": "sniext+4",
            "midhostMarker": "",
            "fakeHostTemplate": "",
            "fragmentCount": 5,
            "minFragmentSize": 24,
            "maxFragmentSize": 48
        }
    ]);

    let payload = parse_proxy_config_json(&value.to_string()).expect("parse tlsrandrec payload");
    let config = runtime_config_from_payload(payload).expect("tlsrandrec ui config");
    let step = &config.groups[0].actions.tcp_chain[0];

    assert!(matches!(step.kind(), TcpChainStepKind::TlsRandRec));
    let payload = step.tls_randrec_payload().expect("tlsrandrec payload");
    assert_eq!(payload.fragment_count, 5);
    assert_eq!(payload.min_fragment_size, 24);
    assert_eq!(payload.max_fragment_size, 48);
}

#[test]
fn rejects_tlsrandrec_fragment_fields_on_non_tlsrandrec_steps() {
    let mut value = serde_json::to_value(ui_payload(minimal_ui_config())).expect("serialize payload");
    value["chains"]["tcpSteps"] = serde_json::json!([
        {
            "kind": "split",
            "marker": "host+1",
            "midhostMarker": "",
            "fakeHostTemplate": "",
            "fragmentCount": 5,
            "minFragmentSize": 0,
            "maxFragmentSize": 0
        }
    ]);

    let payload = parse_proxy_config_json(&value.to_string()).expect("parse invalid payload");
    let err = runtime_config_from_payload(payload).expect_err("non-tlsrandrec fragment fields should fail");

    assert!(err.to_string().contains("tlsrandrec fragment fields are only supported"));
}

#[test]
fn parses_command_line_payloads_for_runtime_config() {
    let config = runtime_config_from_payload(command_line_payload(vec![
        "ripdpi".to_string(),
        "--ip".to_string(),
        "127.0.0.1".to_string(),
        "--port".to_string(),
        "2080".to_string(),
        "--split".to_string(),
        "host+1".to_string(),
    ]))
    .expect("command-line config");

    assert_eq!(config.network.listen.listen_ip, IpAddr::from_str("127.0.0.1").unwrap());
    assert_eq!(config.network.listen.listen_port, 2080);
}

#[test]
fn rejects_non_runnable_command_line_payloads() {
    let err = runtime_config_from_command_line(vec!["ripdpi".to_string(), "--help".to_string()])
        .expect_err("help payload should not run");

    assert!(err.to_string().contains("runnable config"));
}

#[test]
fn rejects_invalid_ui_proxy_port() {
    let mut ui = minimal_ui_config();
    ui.listen.port = 0;

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("port zero should be rejected");

    assert!(err.to_string().contains("Invalid proxy port"));
}

#[test]
fn ui_payload_defaults_quic_settings_when_omitted() {
    let mut value = serde_json::to_value(ui_payload(minimal_ui_config())).expect("serialize payload");
    value.as_object_mut().expect("payload object").remove("quic");

    let payload = parse_proxy_config_json(&value.to_string()).expect("parse ui payload");
    let config = runtime_config_from_payload(payload).expect("ui config");

    assert_eq!(config.quic.initial_mode, QuicInitialMode::RouteAndCache);
    assert!(config.quic.support_v1);
    assert!(config.quic.support_v2);
    assert_eq!(config.groups[0].actions.quic_fake_profile, QuicFakeProfile::Disabled);
    assert_eq!(config.groups[0].actions.quic_fake_host, None);
}

#[test]
fn rejects_unknown_quic_initial_mode_in_ui_payload() {
    let mut ui = minimal_ui_config();
    ui.quic.initial_mode = "bogus".to_string();

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("unknown quic mode should be rejected");

    assert!(err.to_string().contains("Unknown quicInitialMode"));
}

#[test]
fn rejects_unknown_quic_fake_profile_in_ui_payload() {
    let mut ui = minimal_ui_config();
    ui.quic.fake_profile = "bogus".to_string();

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("unknown quic fake profile should be rejected");

    assert!(err.to_string().contains("Unknown quicFakeProfile"));
}

#[test]
fn invalid_quic_fake_host_normalizes_to_absent() {
    let mut ui = minimal_ui_config();
    ui.quic.fake_profile = "realistic_initial".to_string();
    ui.quic.fake_host = "127.0.0.1".to_string();

    let config = runtime_config_from_payload(ui_payload(ui)).expect("ui config");

    assert_eq!(config.groups[0].actions.quic_fake_profile, QuicFakeProfile::RealisticInitial);
    assert_eq!(config.groups[0].actions.quic_fake_host, None);
}

#[test]
fn rejects_invalid_proxy_json_payload() {
    let err = parse_proxy_config_json("{").expect_err("invalid json");

    assert!(err.to_string().contains("Invalid proxy config JSON"));
}

#[test]
fn rejects_legacy_flat_ui_payload() {
    let err = parse_proxy_config_json(
        &serde_json::json!({
            "kind": "ui",
            "ip": "127.0.0.1",
            "port": 1080,
            "desyncMethod": "disorder",
        })
        .to_string(),
    )
    .expect_err("legacy flat ui payload should be rejected");

    assert!(err.to_string().contains("Legacy flat UI config JSON is not supported"));
}

#[test]
fn rejects_enabled_autolearn_without_store_path() {
    let mut ui = minimal_ui_config();
    ui.host_autolearn.enabled = true;
    ui.host_autolearn.store_path = None;

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("missing autolearn path should be rejected");

    assert!(err.to_string().contains("hostAutolearn.storePath is required when hostAutolearn.enabled is true"));
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    #[test]
    fn fuzz_proxy_json_parser_never_panics(input in lossy_string(512)) {
        let _ = parse_proxy_config_json(&input);
    }

    #[test]
    fn fuzz_command_line_parser_never_panics(args in vec(lossy_string(32), 0..12)) {
        let _ = runtime_config_from_command_line(args);
    }

    #[test]
    fn fuzz_ui_payload_mapping_never_panics(payload in proxy_ui_config_strategy()) {
        let _ = runtime_config_from_ui(payload);
    }

    #[test]
    fn valid_ui_payloads_preserve_core_fields(
        ip in prop_oneof![
            Just("127.0.0.1".to_string()),
            Just("0.0.0.0".to_string()),
            Just("::1".to_string()),
        ],
        port in 1i32..65_536i32,
        max_connections in 1i32..4_096i32,
        buffer_size in 1i32..65_536i32,
        tcp_fast_open in any::<bool>(),
        drop_sack in any::<bool>(),
        udp_fake_count in 0i32..8i32,
        hosts_mode in prop_oneof![
            Just(HOSTS_DISABLE.to_string()),
            Just(HOSTS_BLACKLIST.to_string()),
            Just(HOSTS_WHITELIST.to_string()),
        ],
    ) {
        let hosts = match hosts_mode.as_str() {
            HOSTS_DISABLE => None,
            _ => Some("example.org".to_string()),
        };

        let mut ui = minimal_ui_config();
        let parsed_ip = IpAddr::from_str(&ip).expect("valid generated ip");
        let auth_token = if parsed_ip.is_loopback() { None } else { Some("valid-generated-token".to_string()) };
        ui.listen = ProxyUiListenConfig {
            ip: ip.clone(),
            port,
            max_connections,
            buffer_size,
            tcp_fast_open,
            default_ttl: 64,
            custom_ttl: true,
            freeze_detection_enabled: false,
            mixed: false,
            auth_token,
        };
        ui.fake_packets.drop_sack = drop_sack;
        ui.hosts.mode = hosts_mode;
        ui.hosts.entries = hosts;
        ui.chains.udp_steps = if udp_fake_count > 0 { vec![udp_step("fake_burst", udp_fake_count)] } else { Vec::new() };

        let config = runtime_config_from_ui(ui).expect("valid payload");

        prop_assert_eq!(config.network.listen.listen_ip, parsed_ip);
        prop_assert_eq!(config.network.listen.listen_port, u16::try_from(port).expect("valid port"));
        prop_assert_eq!(config.network.max_open, max_connections);
        prop_assert_eq!(config.network.buffer_size, usize::try_from(buffer_size).expect("valid buffer size"));
        prop_assert_eq!(config.network.tfo, tcp_fast_open);
        prop_assert!(!config.groups.is_empty());
    }
}
