use crate::types::ProxyMorphPolicy;
use ripdpi_config::{
    AutoTtlConfig, DesyncMode, FakePacketSource, OffsetBase, OffsetExpr, OffsetProto, QuicFakeProfile,
    TcpChainStepKind, UdpChainStepKind, WsTunnelMode, DETECT_CONNECT, FM_DUPSID, FM_ORIG,
    HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
};
use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};
use ripdpi_packets::{IS_HTTP, IS_HTTPS, IS_UDP};
use ripdpi_packets::{MH_DMIX, MH_HMIX, MH_HOSTEXTRASPACE, MH_HOSTTAB, MH_METHODEOL, MH_SPACE, MH_UNIXEOL};

use super::*;

fn minimal_ui() -> ProxyUiConfig {
    let mut config = ProxyUiConfig::default();
    config.protocols.desync_udp = true;
    config.chains.tcp_steps = vec![tcp_step("disorder", "host+1")];
    config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
    config.host_autolearn.max_hosts = HOST_AUTOLEARN_DEFAULT_MAX_HOSTS;
    config
}

fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: kind.to_string(),
        marker: marker.to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }
}

fn seqovl_step(marker: &str, overlap_size: i32, fake_mode: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: "seqovl".to_string(),
        marker: marker.to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        overlap_size,
        fake_mode: fake_mode.to_string(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
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

fn ui_payload(config: ProxyUiConfig) -> ProxyConfigPayload {
    ProxyConfigPayload::Ui { strategy_preset: None, config, runtime_context: None, log_context: None }
}

#[test]
fn ui_payload_parses_hostfake_and_quic_profile() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![ProxyUiTcpChainStep {
        kind: "hostfake".to_string(),
        marker: "endhost+8".to_string(),
        midhost_marker: "midsld".to_string(),
        fake_host_template: "googlevideo.com".to_string(),
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    ui.chains.udp_steps.push(udp_step("fake_burst", 3));
    ui.quic.fake_profile = "realistic_initial".to_string();
    ui.quic.fake_host = "Example.COM.".to_string();
    ui.fake_packets.tls_fingerprint_profile = "chrome_stable".to_string();

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert_eq!(config.groups[0].actions.http_fake_profile, HttpFakeProfile::CompatDefault);
    assert_eq!(config.groups[0].actions.tls_fake_profile, TlsFakeProfile::CompatDefault);
    assert_eq!(config.groups[0].actions.udp_fake_profile, UdpFakeProfile::CompatDefault);
    assert_eq!(config.groups[0].actions.quic_fake_profile, QuicFakeProfile::RealisticInitial);
    assert_eq!(config.groups[0].actions.quic_fake_host.as_deref(), Some("example.com"));
    assert_eq!(config.groups[0].actions.tcp_chain.len(), 2);
    assert_eq!(config.groups[0].actions.tcp_chain[0].kind, TcpChainStepKind::TlsRec);
    assert_eq!(config.groups[0].actions.tcp_chain[0].offset.base, OffsetBase::ExtLen);
    assert_eq!(config.groups[0].actions.tcp_chain[0].offset.proto, OffsetProto::TlsOnly);
    assert_eq!(config.groups[0].actions.tcp_chain[1].kind, TcpChainStepKind::HostFake);
    assert_eq!(config.groups[0].actions.udp_chain[0].count, 3);
}

#[test]
fn ui_payload_without_preset_still_gets_fallback_groups() {
    let ui = minimal_ui();
    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
    // Primary group + 3 fallback groups + CONNECT passthrough = at least 5
    assert!(
        config.groups.len() >= 5,
        "strategy_preset=None must still inject ripdpi_default fallback groups, got {} groups",
        config.groups.len(),
    );
    let labels: Vec<&str> = config.groups.iter().map(|g| g.policy.label.as_str()).collect();
    assert!(labels.contains(&"tlsrec_disorder"), "missing tlsrec_disorder fallback: {labels:?}");
    assert!(labels.contains(&"disorder_host"), "missing disorder_host fallback: {labels:?}");
    assert!(labels.contains(&"split_host"), "missing split_host fallback: {labels:?}");
}

#[test]
fn ui_payload_preserves_explicit_tlsrec_before_hostfake() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![
        tcp_step("tlsrec", "extlen"),
        ProxyUiTcpChainStep {
            kind: "hostfake".to_string(),
            marker: "endhost+8".to_string(),
            midhost_marker: "midsld".to_string(),
            fake_host_template: "googlevideo.com".to_string(),
            overlap_size: 0,
            fake_mode: String::new(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        },
    ];

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert_eq!(config.groups[0].actions.tcp_chain.len(), 2);
    assert_eq!(config.groups[0].actions.tcp_chain[0].kind, TcpChainStepKind::TlsRec);
    assert_eq!(config.groups[0].actions.tcp_chain[1].kind, TcpChainStepKind::HostFake);
}

#[test]
fn ui_payload_parses_seqovl_step_and_fields() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), seqovl_step("auto(midsld)", 14, "rand")];

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
    let tcp_chain = &config.groups[0].actions.tcp_chain;

    assert_eq!(tcp_chain.len(), 2);
    assert_eq!(tcp_chain[0].kind, TcpChainStepKind::TlsRec);
    assert_eq!(tcp_chain[1].kind, TcpChainStepKind::SeqOverlap);
    assert_eq!(tcp_chain[1].offset, OffsetExpr::adaptive(OffsetBase::AutoMidSld));
    assert_eq!(tcp_chain[1].overlap_size, 14);
    assert_eq!(tcp_chain[1].seqovl_fake_mode, ripdpi_config::SeqOverlapFakeMode::Rand);
}

#[test]
fn ui_payload_rejects_duplicate_seqovl_step() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![seqovl_step("host+1", 12, "profile"), seqovl_step("midsld", 16, "rand")];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("duplicate seqovl");

    assert!(err.to_string().contains("seqovl must appear at most once per tcp chain"));
}

#[test]
fn ui_payload_rejects_non_leading_seqovl_step() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("split", "host+1"), seqovl_step("midsld", 12, "profile")];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("non-leading seqovl");

    assert!(err.to_string().contains("seqovl must be the first tcp send step"));
}

#[test]
fn ui_payload_parses_multidisorder_terminal_run() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps =
        vec![tcp_step("tlsrec", "extlen"), tcp_step("multidisorder", "sniext"), tcp_step("multidisorder", "host")];

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
    let tcp_chain = &config.groups[0].actions.tcp_chain;

    assert_eq!(tcp_chain.len(), 3);
    assert_eq!(tcp_chain[0].kind, TcpChainStepKind::TlsRec);
    assert_eq!(tcp_chain[1].kind, TcpChainStepKind::MultiDisorder);
    assert_eq!(tcp_chain[2].kind, TcpChainStepKind::MultiDisorder);
}

#[test]
fn ui_payload_rejects_singleton_multidisorder_step() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("multidisorder", "host+1")];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("singleton multidisorder");

    assert!(err.to_string().contains("multidisorder must declare at least two markers"));
}

#[test]
fn ui_payload_rejects_mixed_multidisorder_chain() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("multidisorder", "host+1"), tcp_step("split", "midsld")];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("mixed multidisorder");

    assert!(err.to_string().contains("multidisorder must be the only tcp send step family"));
}

#[test]
fn ui_payload_parses_ipfrag_steps_and_udp_split_bytes() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("ipfrag2", "host+2")];
    ui.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "ipfrag2_udp".to_string(),
        count: 0,
        split_bytes: 8,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
    let group = &config.groups[0];

    assert_eq!(group.actions.tcp_chain.len(), 2);
    assert_eq!(group.actions.tcp_chain[0].kind, TcpChainStepKind::TlsRec);
    assert_eq!(group.actions.tcp_chain[1].kind, TcpChainStepKind::IpFrag2);
    assert_eq!(group.actions.tcp_chain[1].offset.base, OffsetBase::Host);
    assert_eq!(group.actions.tcp_chain[1].offset.delta, 2);
    assert_eq!(group.actions.udp_chain.len(), 1);
    assert_eq!(group.actions.udp_chain[0].kind, UdpChainStepKind::IpFrag2Udp);
    assert_eq!(group.actions.udp_chain[0].count, 0);
    assert_eq!(group.actions.udp_chain[0].split_bytes, 8);
}

#[test]
fn ui_payload_parses_tcp_rotation_policy_defaults() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("split", "host+2")];
    ui.chains.tcp_rotation = Some(ProxyUiTcpRotationConfig {
        candidates: vec![
            ProxyUiTcpRotationCandidate {
                tcp_steps: vec![
                    tcp_step("tlsrec", "extlen"),
                    ProxyUiTcpChainStep {
                        kind: "hostfake".to_string(),
                        marker: "endhost+8".to_string(),
                        midhost_marker: "midsld".to_string(),
                        fake_host_template: "googlevideo.com".to_string(),
                        overlap_size: 0,
                        fake_mode: String::new(),
                        fragment_count: 0,
                        min_fragment_size: 0,
                        max_fragment_size: 0,
                        inter_segment_delay_ms: 0,
                        activation_filter: None,
                        ipv6_extension_profile: "none".to_string(),
                    },
                    tcp_step("split", "midsld"),
                ],
            },
            ProxyUiTcpRotationCandidate { tcp_steps: vec![tcp_step("split", "host+2")] },
        ],
        ..ProxyUiTcpRotationConfig::default()
    });

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
    let rotation = config.groups[0].actions.rotation_policy.as_ref().expect("rotation policy");

    assert_eq!(rotation.fails, 3);
    assert_eq!(rotation.retrans, 3);
    assert_eq!(rotation.seq, 65_536);
    assert_eq!(rotation.rst, 1);
    assert_eq!(rotation.time_secs, 60);
    assert_eq!(rotation.candidates.len(), 2);
    assert_eq!(rotation.candidates[0].tcp_chain[0].kind, TcpChainStepKind::TlsRec);
    assert_eq!(rotation.candidates[0].tcp_chain[1].kind, TcpChainStepKind::HostFake);
    assert_eq!(rotation.candidates[0].tcp_chain[2].kind, TcpChainStepKind::Split);
    assert_eq!(rotation.candidates[1].tcp_chain[0].kind, TcpChainStepKind::Split);
}

#[test]
fn ui_payload_rejects_empty_tcp_rotation_candidates() {
    let mut ui = minimal_ui();
    ui.chains.tcp_rotation =
        Some(ProxyUiTcpRotationConfig { candidates: Vec::new(), ..ProxyUiTcpRotationConfig::default() });

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("empty rotation");

    assert!(err.to_string().contains("chains.tcpRotation must declare at least one candidate"));
}

#[test]
fn ui_payload_rejects_malformed_tcp_rotation_candidate_chain() {
    let mut ui = minimal_ui();
    ui.chains.tcp_rotation = Some(ProxyUiTcpRotationConfig {
        candidates: vec![ProxyUiTcpRotationCandidate {
            tcp_steps: vec![seqovl_step("host+1", 12, "profile"), seqovl_step("midsld", 16, "rand")],
        }],
        ..ProxyUiTcpRotationConfig::default()
    });

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("invalid rotation candidate");

    assert!(err.to_string().contains("seqovl must appear at most once per tcp chain"));
}

#[test]
fn ui_payload_treats_fake_approx_steps_as_fake_payload_consumers() {
    for kind in ["fakedsplit", "fakeddisorder"] {
        let mut ui = minimal_ui();
        ui.chains.tcp_steps = vec![tcp_step(kind, "host+1")];
        ui.fake_packets.fake_tls_use_original = true;
        ui.fake_packets.fake_tls_dup_session_id = true;
        ui.fake_packets.fake_offset_marker = "host+1".to_string();

        let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
        let group = &config.groups[0];

        let expected_mode = if kind == "fakedsplit" { DesyncMode::Fake } else { DesyncMode::Disorder };

        assert_eq!(group.actions.tcp_chain[0].kind.as_mode(), Some(expected_mode));
        assert_eq!(group.actions.fake_offset.map(|offset| offset.delta), Some(1));
        assert_ne!(group.actions.fake_mod & FM_ORIG, 0);
        assert_ne!(group.actions.fake_mod & FM_DUPSID, 0);
    }
}

#[test]
fn ui_payload_rejects_non_terminal_fake_approx_step() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps.push(tcp_step("fakedsplit", "host+1"));
    ui.chains.tcp_steps.push(tcp_step("split", "endhost"));

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("non-terminal fakedsplit");

    assert!(err.to_string().contains("fakedsplit"));
}

#[test]
fn ui_payload_parses_fake_payload_profiles() {
    let mut ui = minimal_ui();
    ui.fake_packets.http_fake_profile = "cloudflare_get".to_string();
    ui.fake_packets.tls_fake_profile = "google_chrome".to_string();
    ui.fake_packets.udp_fake_profile = "dns_query".to_string();

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert_eq!(config.groups[0].actions.http_fake_profile, HttpFakeProfile::CloudflareGet);
    assert_eq!(config.groups[0].actions.tls_fake_profile, TlsFakeProfile::GoogleChrome);
    assert_eq!(config.groups[0].actions.udp_fake_profile, UdpFakeProfile::DnsQuery);
}

#[test]
fn ui_payload_maps_fake_tls_source_and_secondary_profile() {
    let mut ui = minimal_ui();
    ui.fake_packets.fake_tls_source = "captured_client_hello".to_string();
    ui.fake_packets.fake_tls_secondary_profile = "google_chrome".to_string();

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert_eq!(config.groups[0].actions.fake_tls_source, FakePacketSource::CapturedClientHello);
    assert_eq!(config.groups[0].actions.fake_tls_secondary_profile, Some(TlsFakeProfile::GoogleChrome));
}

#[test]
fn ui_payload_maps_fake_tcp_timestamp_settings() {
    let mut ui = minimal_ui();
    ui.fake_packets.fake_tcp_timestamp_enabled = true;
    ui.fake_packets.fake_tcp_timestamp_delta_ticks = -77;

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert!(config.groups[0].actions.fake_tcp_timestamp_enabled);
    assert_eq!(config.groups[0].actions.fake_tcp_timestamp_delta_ticks, -77);
}

#[test]
fn ui_payload_maps_extended_http_parser_evasions_into_mod_http() {
    let mut ui = minimal_ui();
    ui.parser_evasions.host_mixed_case = true;
    ui.parser_evasions.domain_mixed_case = true;
    ui.parser_evasions.host_remove_spaces = true;
    ui.parser_evasions.http_method_eol = true;
    ui.parser_evasions.http_unix_eol = true;
    ui.parser_evasions.http_host_extra_space = true;
    ui.parser_evasions.http_host_tab = true;

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert_eq!(
        config.groups[0].actions.mod_http,
        MH_HMIX | MH_DMIX | MH_SPACE | MH_METHODEOL | MH_UNIXEOL | MH_HOSTEXTRASPACE | MH_HOSTTAB
    );
}

#[test]
fn ui_payload_rejects_unknown_ipv6_extension_profile() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![ProxyUiTcpChainStep {
        kind: "ipfrag2".to_string(),
        marker: "host+2".to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: None,
        ipv6_extension_profile: "bogus".to_string(),
    }];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("invalid ipv6 extension profile");

    assert!(err.to_string().contains("Unsupported ipv6ExtensionProfile"));
}

#[test]
fn legacy_flat_ui_json_is_rejected() {
    let err = parse_proxy_config_json(
        &serde_json::json!({
            "kind": "ui",
            "ip": "127.0.0.1",
            "port": 1080,
            "desyncMethod": "disorder",
        })
        .to_string(),
    )
    .expect_err("legacy flat ui config should be rejected");

    assert!(err.to_string().contains("Legacy flat UI config JSON is not supported"));
}

#[test]
fn command_line_payload_requires_runnable_config() {
    let err = runtime_config_from_payload(ProxyConfigPayload::CommandLine {
        args: vec!["ripdpi".to_string(), "--help".to_string()],
        host_autolearn_store_path: None,
        runtime_context: None,
        log_context: None,
    })
    .expect_err("help should not produce runnable config");

    assert!(err.to_string().contains("runnable"));
}

#[test]
fn runtime_context_sanitizes_direct_path_capabilities() {
    let envelope = runtime_config_envelope_from_payload(ProxyConfigPayload::CommandLine {
        args: vec!["ripdpi".to_string(), "--split".to_string(), "host+1".to_string()],
        host_autolearn_store_path: None,
        runtime_context: Some(ProxyRuntimeContext {
            encrypted_dns: None,
            protect_path: None,
            preferred_edges: std::collections::BTreeMap::default(),
            direct_path_capabilities: vec![
                ProxyDirectPathCapability {
                    authority: " Example.org:443. ".to_string(),
                    quic_usable: Some(false),
                    udp_usable: Some(false),
                    fallback_required: Some(true),
                    repeated_handshake_failure_class: Some(" tcp_reset ".to_string()),
                    updated_at: -10,
                },
                ProxyDirectPathCapability {
                    authority: "   ".to_string(),
                    quic_usable: None,
                    udp_usable: None,
                    fallback_required: None,
                    repeated_handshake_failure_class: None,
                    updated_at: 0,
                },
            ],
            morph_policy: Some(ProxyMorphPolicy {
                id: " balanced ".to_string(),
                first_flight_size_min: -10,
                first_flight_size_max: 700,
                padding_envelope_min: -1,
                padding_envelope_max: 80,
                entropy_target_permil: -50,
                tcp_burst_cadence_ms: vec![0, -5, 12],
                tls_burst_cadence_ms: vec![8, -2],
                quic_burst_profile: " Compat_Burst ".to_string(),
                fake_packet_shape_profile: " Compat_Default ".to_string(),
            }),
        }),
        log_context: None,
    })
    .expect("runtime config envelope");

    let mut runtime_context = envelope.runtime_context.expect("runtime context");
    let capability = runtime_context.direct_path_capabilities.pop().expect("capability");
    let morph_policy = runtime_context.morph_policy.expect("morph policy");

    assert_eq!(capability.authority, "example.org:443");
    assert_eq!(capability.quic_usable, Some(false));
    assert_eq!(capability.udp_usable, Some(false));
    assert_eq!(capability.fallback_required, Some(true));
    assert_eq!(capability.repeated_handshake_failure_class.as_deref(), Some("tcp_reset"));
    assert_eq!(capability.updated_at, 0);
    assert_eq!(morph_policy.id, "balanced");
    assert_eq!(morph_policy.first_flight_size_min, 0);
    assert_eq!(morph_policy.first_flight_size_max, 700);
    assert_eq!(morph_policy.padding_envelope_min, 0);
    assert_eq!(morph_policy.padding_envelope_max, 80);
    assert_eq!(morph_policy.entropy_target_permil, 0);
    assert_eq!(morph_policy.tcp_burst_cadence_ms, vec![0, 0, 12]);
    assert_eq!(morph_policy.tls_burst_cadence_ms, vec![8, 0]);
    assert_eq!(morph_policy.quic_burst_profile, "compat_burst");
    assert_eq!(morph_policy.fake_packet_shape_profile, "compat_default");
}

#[test]
fn invalid_quic_fake_profile_is_rejected() {
    let mut ui = minimal_ui();
    ui.quic.fake_profile = "bogus".to_string();

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("invalid quic profile");

    assert!(err.to_string().contains("quicFakeProfile"));
}

#[test]
fn invalid_http_fake_profile_is_rejected() {
    let mut ui = minimal_ui();
    ui.fake_packets.http_fake_profile = "bogus".to_string();

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("invalid http profile");

    assert!(err.to_string().contains("httpFakeProfile"));
}

#[test]
fn adaptive_hostfake_marker_is_rejected() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps.push(tcp_step("hostfake", "auto(host)"));

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("adaptive hostfake marker");

    assert!(err.to_string().contains("hostfake"));
}

#[test]
fn adaptive_hostfake_midhost_marker_is_rejected() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps.push(ProxyUiTcpChainStep {
        kind: "hostfake".to_string(),
        marker: "endhost+8".to_string(),
        midhost_marker: "auto(midsld)".to_string(),
        fake_host_template: String::new(),
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    });

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("adaptive hostfake midhost");

    assert!(err.to_string().contains("midhostMarker"));
}

#[test]
fn adaptive_fake_offset_marker_is_rejected() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("fake", "host+1")];
    ui.fake_packets.fake_offset_marker = "auto(host)".to_string();

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("adaptive fake offset");

    assert!(err.to_string().contains("fakeOffsetMarker"));
}

#[test]
fn ui_payload_rejects_non_terminal_ipfrag2_step() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps =
        vec![tcp_step("tlsrec", "extlen"), tcp_step("ipfrag2", "host+2"), tcp_step("split", "endhost")];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("non-terminal ipfrag2");

    assert!(err.to_string().contains("ipfrag2 must be the only tcp send step"));
}

#[test]
fn ui_payload_rejects_ipfrag2_udp_with_count() {
    let mut ui = minimal_ui();
    ui.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "ipfrag2_udp".to_string(),
        count: 1,
        split_bytes: 8,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("ipfrag2_udp count");

    assert!(err.to_string().contains("must not declare count"));
}

#[test]
fn ui_payload_rejects_ipfrag2_udp_without_positive_split_bytes() {
    let mut ui = minimal_ui();
    ui.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "ipfrag2_udp".to_string(),
        count: 0,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("ipfrag2_udp splitBytes");

    assert!(err.to_string().contains("must declare positive splitBytes"));
}

#[test]
fn ui_payload_rejects_split_bytes_for_non_ipfrag_udp_steps() {
    let mut ui = minimal_ui();
    ui.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "fake_burst".to_string(),
        count: 2,
        split_bytes: 8,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("fake_burst splitBytes");

    assert!(err.to_string().contains("splitBytes is only supported"));
}

#[test]
fn ui_payload_rejects_mixed_ipfrag2_udp_chain() {
    let mut ui = minimal_ui();
    ui.chains.udp_steps = vec![
        ProxyUiUdpChainStep {
            kind: "ipfrag2_udp".to_string(),
            count: 0,
            split_bytes: 8,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        },
        udp_step("fake_burst", 1),
    ];

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("mixed ipfrag2_udp chain");

    assert!(err.to_string().contains("ipfrag2_udp must be the only udp chain step"));
}

#[test]
fn ech_fake_offset_marker_is_rejected() {
    for marker in ["echext", "echext+4"] {
        let mut ui = minimal_ui();
        ui.chains.tcp_steps = vec![tcp_step("fake", "host+1")];
        ui.fake_packets.fake_offset_marker = marker.to_string();

        let err = runtime_config_from_payload(ui_payload(ui)).expect_err("ech fake offset");

        assert!(err.to_string().contains("fakeOffsetMarker"), "{marker} should reference fakeOffsetMarker");
    }
}

#[test]
fn ui_payload_maps_adaptive_fake_ttl_and_fallback() {
    let mut ui = minimal_ui();
    ui.fake_packets.fake_ttl = 11;
    ui.fake_packets.adaptive_fake_ttl_enabled = true;
    ui.fake_packets.adaptive_fake_ttl_delta = -1;
    ui.fake_packets.adaptive_fake_ttl_min = 3;
    ui.fake_packets.adaptive_fake_ttl_max = 12;
    ui.fake_packets.adaptive_fake_ttl_fallback = 9;

    let config = runtime_config_from_payload(ui_payload(ui)).expect("adaptive fake ttl config");
    let group = &config.groups[0];

    assert_eq!(group.actions.auto_ttl, Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 }),);
    assert_eq!(group.actions.ttl, Some(9));
}

#[test]
fn ui_payload_rejects_invalid_adaptive_fake_ttl_window() {
    let mut ui = minimal_ui();
    ui.fake_packets.adaptive_fake_ttl_enabled = true;
    ui.fake_packets.adaptive_fake_ttl_min = 12;
    ui.fake_packets.adaptive_fake_ttl_max = 3;

    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("invalid adaptive ttl window");

    assert!(err.to_string().contains("adaptive fake TTL window"));
}

#[test]
fn ui_payload_uses_fixed_fake_ttl_when_adaptive_is_disabled() {
    let mut ui = minimal_ui();
    ui.fake_packets.fake_ttl = 13;
    ui.fake_packets.adaptive_fake_ttl_enabled = false;
    ui.fake_packets.adaptive_fake_ttl_fallback = 5;

    let config = runtime_config_from_payload(ui_payload(ui)).expect("fixed fake ttl config");
    let group = &config.groups[0];

    assert_eq!(group.actions.auto_ttl, None);
    assert_eq!(group.actions.ttl, Some(13));
}

#[test]
fn known_preset_applies_without_error() {
    let base = minimal_ui();
    let mut cfg = base.clone();
    crate::presets::apply_preset("russia_rostelecom", &mut cfg).unwrap();
    assert!(cfg.protocols.desync_https, "rostelecom preset should enable HTTPS desync");
    assert!(cfg.fake_packets.adaptive_fake_ttl_enabled, "rostelecom preset should enable adaptive TTL");
}

#[test]
fn unknown_preset_returns_error() {
    let mut cfg = minimal_ui();
    let result = crate::presets::apply_preset("not_a_real_preset", &mut cfg);
    assert!(result.is_err());
}

#[test]
fn russia_mgts_preset_produces_valid_runtime_config() {
    let mut cfg = minimal_ui();
    crate::presets::apply_preset("russia_mgts", &mut cfg).unwrap();
    runtime_config_from_ui(cfg).unwrap();
}

#[test]
fn preset_field_in_ui_config_round_trips_json() {
    let cfg = minimal_ui();
    let payload = ProxyConfigPayload::Ui {
        strategy_preset: Some("ripdpi_default".to_string()),
        config: cfg,
        runtime_context: None,
        log_context: None,
    };
    let json = serde_json::to_string(&payload).unwrap();
    let decoded: ProxyConfigPayload = serde_json::from_str(&json).unwrap();
    match decoded {
        ProxyConfigPayload::Ui { strategy_preset, .. } => {
            assert_eq!(strategy_preset.as_deref(), Some("ripdpi_default"));
        }
        other @ ProxyConfigPayload::CommandLine { .. } => panic!("expected ui payload, got {other:?}"),
    }
}

// --- NetworkSnapshot serde tests ---

#[test]
fn network_snapshot_deserializes_from_empty_json() {
    let snapshot: NetworkSnapshot = serde_json::from_str("{}").expect("deserialize");
    assert_eq!(snapshot.transport, "");
    assert!(!snapshot.validated);
    assert!(!snapshot.captive_portal);
    assert!(!snapshot.metered);
    assert_eq!(snapshot.private_dns_mode, "");
    assert!(snapshot.dns_servers.is_empty());
    assert!(snapshot.cellular.is_none());
    assert!(snapshot.wifi.is_none());
    assert!(snapshot.mtu.is_none());
    assert_eq!(snapshot.traffic_tx_bytes, 0);
    assert_eq!(snapshot.traffic_rx_bytes, 0);
    assert_eq!(snapshot.captured_at_ms, 0);
}

#[test]
fn network_snapshot_round_trips_wifi_snapshot() {
    let snapshot = NetworkSnapshot {
        transport: "wifi".to_string(),
        validated: true,
        captive_portal: false,
        metered: false,
        private_dns_mode: "system".to_string(),
        dns_servers: vec!["8.8.8.8".to_string(), "8.8.4.4".to_string()],
        cellular: None,
        wifi: Some(WifiSnapshot {
            frequency_band: "5ghz".to_string(),
            ssid_hash: "abc123def456".to_string(),
            frequency_mhz: Some(5180),
            rssi_dbm: Some(-58),
            link_speed_mbps: Some(866),
            rx_link_speed_mbps: Some(780),
            tx_link_speed_mbps: Some(720),
            channel_width: "80 MHz".to_string(),
            wifi_standard: "802.11ax".to_string(),
        }),
        mtu: Some(1500),
        traffic_tx_bytes: 1_234_567,
        traffic_rx_bytes: 9_876_543,
        captured_at_ms: 1_700_000_000_000,
        vpn_service_was_active: false,
    };
    let json = serde_json::to_string(&snapshot).expect("serialize");
    let decoded: NetworkSnapshot = serde_json::from_str(&json).expect("deserialize");
    assert_eq!(decoded, snapshot);
}

#[test]
fn network_snapshot_round_trips_cellular_snapshot() {
    let snapshot = NetworkSnapshot {
        transport: "cellular".to_string(),
        validated: true,
        captive_portal: false,
        metered: true,
        private_dns_mode: "system".to_string(),
        dns_servers: vec!["10.0.0.1".to_string()],
        cellular: Some(CellularSnapshot {
            generation: "4g".to_string(),
            roaming: false,
            operator_code: "25001".to_string(),
            data_network_type: "LTE".to_string(),
            service_state: "in_service".to_string(),
            carrier_id: Some(42),
            signal_level: Some(4),
            signal_dbm: Some(-95),
        }),
        wifi: None,
        mtu: Some(1420),
        traffic_tx_bytes: 0,
        traffic_rx_bytes: 0,
        captured_at_ms: 1_700_000_000_000,
        vpn_service_was_active: false,
    };
    let json = serde_json::to_string(&snapshot).expect("serialize");
    let decoded: NetworkSnapshot = serde_json::from_str(&json).expect("deserialize");
    assert_eq!(decoded, snapshot);
}

#[test]
fn network_snapshot_ignores_unknown_fields() {
    let json = r#"{
        "transport": "wifi",
        "validated": true,
        "mtu": 1500,
        "futureField": "some_value",
        "anotherNewField": 42
    }"#;
    let snapshot: NetworkSnapshot = serde_json::from_str(json).expect("deserialize with unknown fields");
    assert_eq!(snapshot.transport, "wifi");
    assert!(snapshot.validated);
    assert_eq!(snapshot.mtu, Some(1500));
}

#[test]
fn network_snapshot_uses_camel_case_keys() {
    let snapshot = NetworkSnapshot {
        transport: "cellular".to_string(),
        metered: true,
        captive_portal: true,
        private_dns_mode: "dns.example.com".to_string(),
        cellular: Some(CellularSnapshot {
            data_network_type: "NR".to_string(),
            service_state: "in_service".to_string(),
            carrier_id: Some(7),
            signal_level: Some(4),
            signal_dbm: Some(-93),
            ..CellularSnapshot::default()
        }),
        wifi: Some(WifiSnapshot {
            frequency_mhz: Some(5955),
            channel_width: "80 MHz".to_string(),
            wifi_standard: "802.11be".to_string(),
            ..WifiSnapshot::default()
        }),
        mtu: Some(1280),
        ..NetworkSnapshot::default()
    };
    let json = serde_json::to_string(&snapshot).expect("serialize");
    assert!(json.contains("\"captivePortal\""), "expected camelCase key in: {json}");
    assert!(json.contains("\"mtu\""), "expected mtu key in: {json}");
    assert!(json.contains("\"privateDnsMode\""), "expected camelCase key in: {json}");
    assert!(json.contains("\"dataNetworkType\""), "expected dataNetworkType key in: {json}");
    assert!(json.contains("\"signalLevel\""), "expected signalLevel key in: {json}");
    assert!(json.contains("\"frequencyMhz\""), "expected frequencyMhz key in: {json}");
    assert!(json.contains("\"wifiStandard\""), "expected wifiStandard key in: {json}");
}

// --- WsTunnelMode conversion tests ---

#[test]
fn ws_tunnel_mode_fallback() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.mode = Some("fallback".to_string());
    let config = runtime_config_from_ui(ui).expect("runtime config");
    assert_eq!(config.adaptive.ws_tunnel_mode, WsTunnelMode::Fallback);
}

#[test]
fn ws_tunnel_mode_always() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.mode = Some("always".to_string());
    let config = runtime_config_from_ui(ui).expect("runtime config");
    assert_eq!(config.adaptive.ws_tunnel_mode, WsTunnelMode::Always);
}

#[test]
fn ws_tunnel_mode_off() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.mode = Some("off".to_string());
    let config = runtime_config_from_ui(ui).expect("runtime config");
    assert_eq!(config.adaptive.ws_tunnel_mode, WsTunnelMode::Off);
}

#[test]
fn ws_tunnel_mode_unknown_string_maps_to_off() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.mode = Some("unknown_string".to_string());
    let config = runtime_config_from_ui(ui).expect("runtime config");
    assert_eq!(config.adaptive.ws_tunnel_mode, WsTunnelMode::Off);
}

#[test]
fn ws_tunnel_mode_none_enabled_true_maps_to_always() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.mode = None;
    ui.ws_tunnel.enabled = true;
    let config = runtime_config_from_ui(ui).expect("runtime config");
    assert_eq!(config.adaptive.ws_tunnel_mode, WsTunnelMode::Always);
}

#[test]
fn ws_tunnel_mode_none_enabled_false_maps_to_off() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.mode = None;
    ui.ws_tunnel.enabled = false;
    let config = runtime_config_from_ui(ui).expect("runtime config");
    assert_eq!(config.adaptive.ws_tunnel_mode, WsTunnelMode::Off);
}

#[test]
fn ui_http_strategy_enables_delay_connect() {
    let mut ui = minimal_ui();
    ui.protocols.desync_http = true;
    ui.protocols.desync_udp = false;

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert!(config.network.delay_conn);
}

#[test]
fn ui_host_filters_enable_delay_connect() {
    let mut ui = minimal_ui();
    ui.hosts.mode = "whitelist".to_string();
    ui.hosts.entries = Some("example.com".to_string());

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert!(config.network.delay_conn);
}

#[test]
fn ui_udp_only_strategy_keeps_delay_connect_disabled() {
    let mut ui = minimal_ui();
    ui.protocols.desync_http = false;
    ui.protocols.desync_https = false;

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert!(!config.network.delay_conn);
}

#[test]
fn actionable_ui_strategy_synthesizes_detect_connect_plain_fallback_group() {
    let config = runtime_config_from_ui(minimal_ui()).expect("runtime config");

    // minimal_ui() has desync_http=true, desync_https=true, desync_udp=true
    // -> TCP group + UDP group + adaptive_direct + CONNECT-detect fallback
    assert_eq!(config.groups.len(), 4);
    assert_eq!(config.groups[0].matches.detect, 0);
    assert_eq!(config.groups[0].matches.proto, IS_HTTP | IS_HTTPS);
    assert_eq!(config.groups[1].matches.proto, IS_UDP);
    let adaptive_direct = &config.groups[2];
    assert_ne!(adaptive_direct.matches.detect, 0);
    assert_eq!(adaptive_direct.matches.proto, IS_HTTP | IS_HTTPS);
    let fallback = &config.groups[3];
    assert_eq!(fallback.matches.detect, DETECT_CONNECT);
    assert!(fallback.actions.tcp_chain.is_empty());
    assert!(fallback.actions.udp_chain.is_empty());
    assert_eq!(fallback.matches.proto, 0);
}

#[test]
fn tcp_and_udp_desync_produces_separate_groups() {
    let mut ui = minimal_ui();
    ui.protocols.desync_http = true;
    ui.protocols.desync_https = true;
    ui.protocols.desync_udp = true;

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert_eq!(config.groups.len(), 4);
    assert_eq!(config.groups[0].matches.proto & IS_UDP, 0, "TCP group must not have IS_UDP");
    assert_eq!(config.groups[0].matches.proto, IS_HTTP | IS_HTTPS);
    assert_eq!(config.groups[1].matches.proto, IS_UDP);
    assert!(config.groups[1].actions.tcp_chain.is_empty(), "UDP group should not carry tcp_chain");
    assert_ne!(config.groups[2].matches.detect, 0);
    assert_eq!(config.groups[2].matches.proto, IS_HTTP | IS_HTTPS);
    assert_eq!(config.groups[3].matches.detect, DETECT_CONNECT);
}

#[test]
fn udp_only_strategy_produces_single_action_group() {
    let mut ui = minimal_ui();
    ui.protocols.desync_http = false;
    ui.protocols.desync_https = false;
    ui.protocols.desync_udp = true;

    let config = runtime_config_from_ui(ui).expect("runtime config");

    // TCP group (proto=0, no actions) + UDP group + CONNECT fallback
    assert_eq!(config.groups.len(), 3);
    assert_eq!(config.groups[0].matches.proto, 0);
    assert_eq!(config.groups[1].matches.proto, IS_UDP);
    assert_eq!(config.groups[2].matches.detect, DETECT_CONNECT);
}

#[test]
fn tcp_only_strategy_produces_two_groups() {
    let mut ui = minimal_ui();
    ui.protocols.desync_http = true;
    ui.protocols.desync_https = true;
    ui.protocols.desync_udp = false;
    ui.chains.udp_steps.clear();

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert_eq!(config.groups.len(), 3);
    assert_eq!(config.groups[0].matches.proto, IS_HTTP | IS_HTTPS);
    assert_ne!(config.groups[1].matches.detect, 0);
    assert_eq!(config.groups[1].matches.proto, IS_HTTP | IS_HTTPS);
    assert_eq!(config.groups[2].matches.detect, DETECT_CONNECT);
}

// --- Validation edge-case tests ---

#[test]
fn port_overflow_is_rejected() {
    let mut ui = minimal_ui();
    ui.listen.port = 100_000;
    let err = runtime_config_from_ui(ui).expect_err("port overflow");
    assert!(err.to_string().contains("port"), "expected port error, got: {err}");
}

#[test]
fn zero_port_is_rejected() {
    let mut ui = minimal_ui();
    ui.listen.port = 0;
    let err = runtime_config_from_ui(ui).expect_err("zero port");
    assert!(err.to_string().contains("port"), "expected port error, got: {err}");
}

#[test]
fn zero_max_connections_is_rejected() {
    let mut ui = minimal_ui();
    ui.listen.max_connections = 0;
    let err = runtime_config_from_ui(ui).expect_err("zero max connections");
    assert!(err.to_string().contains("maxConnections"), "expected maxConnections error, got: {err}");
}

#[test]
fn negative_buffer_size_is_rejected() {
    let mut ui = minimal_ui();
    ui.listen.buffer_size = -1;
    let err = runtime_config_from_ui(ui).expect_err("negative buffer size");
    assert!(err.to_string().contains("bufferSize"), "expected bufferSize error, got: {err}");
}

#[test]
fn default_ttl_overflow_when_custom_ttl_enabled() {
    let mut ui = minimal_ui();
    ui.listen.custom_ttl = true;
    ui.listen.default_ttl = 300;
    let err = runtime_config_from_ui(ui).expect_err("ttl overflow");
    assert!(err.to_string().contains("defaultTtl"), "expected defaultTtl error, got: {err}");
}

#[test]
fn zero_default_ttl_when_custom_ttl_enabled() {
    let mut ui = minimal_ui();
    ui.listen.custom_ttl = true;
    ui.listen.default_ttl = 0;
    let err = runtime_config_from_ui(ui).expect_err("zero ttl");
    assert!(err.to_string().contains("defaultTtl"), "expected defaultTtl error, got: {err}");
}

#[test]
fn host_autolearn_enabled_without_store_path_is_rejected() {
    let mut ui = minimal_ui();
    ui.host_autolearn.enabled = true;
    ui.host_autolearn.store_path = None;
    let err = runtime_config_from_ui(ui).expect_err("missing store path");
    assert!(err.to_string().contains("storePath"), "expected storePath error, got: {err}");
}

// --- TCP chain validation tests ---

#[test]
fn tlsrec_after_send_step_is_rejected() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("split", "host+1"), tcp_step("tlsrec", "host+1")];
    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("tlsrec after send");
    assert!(err.to_string().contains("tlsrec"), "expected tlsrec error, got: {err}");
}

#[test]
fn empty_tcp_chain_is_accepted() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![];
    let config = runtime_config_from_payload(ui_payload(ui)).expect("empty chain should be valid");
    assert!(config.groups[0].actions.tcp_chain.is_empty());
}

#[test]
fn tlsrec_before_send_step_is_accepted() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("tlsrec", "host+1"), tcp_step("disorder", "host+1")];
    runtime_config_from_payload(ui_payload(ui)).expect("tlsrec before send should be valid");
}

#[test]
fn fakeddisorder_not_last_is_rejected() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![tcp_step("fakeddisorder", "host+1"), tcp_step("split", "endhost")];
    let err = runtime_config_from_payload(ui_payload(ui)).expect_err("non-terminal fakeddisorder");
    assert!(err.to_string().contains("fakeddisorder"), "expected fakeddisorder error, got: {err}");
}

#[test]
fn ui_config_maps_window_clamp() {
    let mut ui = minimal_ui();
    ui.fake_packets.window_clamp = Some(2);
    let config = runtime_config_from_payload(ui_payload(ui)).expect("valid config");
    assert_eq!(config.groups[0].actions.window_clamp, Some(2));
}

#[test]
fn ui_config_maps_strip_timestamps() {
    let mut ui = minimal_ui();
    ui.fake_packets.strip_timestamps = true;
    let config = runtime_config_from_payload(ui_payload(ui)).expect("valid config");
    assert!(config.groups[0].actions.strip_timestamps);
}

#[test]
fn ui_config_maps_quic_bind_low_port() {
    let mut ui = minimal_ui();
    ui.fake_packets.quic_bind_low_port = true;

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert!(config.groups[0].actions.quic_bind_low_port);
}

#[test]
fn ui_config_maps_quic_migrate() {
    let mut ui = minimal_ui();
    ui.fake_packets.quic_migrate_after_handshake = true;

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert!(config.groups[0].actions.quic_migrate_after_handshake);
}
