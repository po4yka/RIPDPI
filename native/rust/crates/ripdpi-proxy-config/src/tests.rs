use ciadpi_config::{
    AutoTtlConfig, DesyncMode, QuicFakeProfile, TcpChainStepKind, FM_DUPSID, FM_ORIG, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
    HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
};
use ciadpi_packets::{
    HttpFakeProfile, TlsFakeProfile, UdpFakeProfile, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL,
};

use super::*;

fn minimal_ui() -> ProxyUiConfig {
    ProxyUiConfig {
        ip: "127.0.0.1".to_string(),
        port: 1080,
        max_connections: 512,
        buffer_size: 16384,
        default_ttl: 0,
        custom_ttl: false,
        no_domain: false,
        desync_http: true,
        desync_https: true,
        desync_udp: true,
        desync_method: "disorder".to_string(),
        split_marker: Some("host+1".to_string()),
        tcp_chain_steps: Vec::new(),
        group_activation_filter: ProxyUiActivationFilter::default(),
        split_position: 0,
        split_at_host: false,
        fake_ttl: 8,
        adaptive_fake_ttl_enabled: false,
        adaptive_fake_ttl_delta: ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
        adaptive_fake_ttl_min: ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
        adaptive_fake_ttl_max: ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
        adaptive_fake_ttl_fallback: ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
        fake_sni: "www.wikipedia.org".to_string(),
        http_fake_profile: FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string(),
        fake_tls_use_original: false,
        fake_tls_randomize: false,
        fake_tls_dup_session_id: false,
        fake_tls_pad_encap: false,
        fake_tls_size: 0,
        fake_tls_sni_mode: FAKE_TLS_SNI_MODE_FIXED.to_string(),
        tls_fake_profile: FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string(),
        oob_char: b'a',
        host_mixed_case: false,
        domain_mixed_case: false,
        host_remove_spaces: false,
        http_method_eol: false,
        http_unix_eol: false,
        tls_record_split: false,
        tls_record_split_marker: None,
        tls_record_split_position: 0,
        tls_record_split_at_sni: false,
        hosts_mode: HOSTS_DISABLE.to_string(),
        hosts: None,
        tcp_fast_open: false,
        udp_fake_count: 0,
        udp_chain_steps: Vec::new(),
        udp_fake_profile: FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string(),
        drop_sack: false,
        fake_offset_marker: Some("0".to_string()),
        fake_offset: 0,
        quic_initial_mode: Some("route_and_cache".to_string()),
        quic_support_v1: true,
        quic_support_v2: true,
        quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
        quic_fake_host: String::new(),
        host_autolearn_enabled: false,
        host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
        host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
        host_autolearn_store_path: None,
        network_scope_key: None,
        strategy_preset: None,
    }
}

#[test]
fn ui_payload_parses_hostfake_and_quic_profile() {
    let mut ui = minimal_ui();
    ui.tcp_chain_steps.push(ProxyUiTcpChainStep {
        kind: "hostfake".to_string(),
        marker: "endhost+8".to_string(),
        midhost_marker: Some("midsld".to_string()),
        fake_host_template: Some("googlevideo.com".to_string()),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: ProxyUiActivationFilter::default(),
    });
    ui.udp_chain_steps.push(ProxyUiUdpChainStep {
        kind: "fake_burst".to_string(),
        count: 3,
        activation_filter: ProxyUiActivationFilter::default(),
    });
    ui.quic_fake_profile = "realistic_initial".to_string();
    ui.quic_fake_host = "Example.COM.".to_string();

    let config = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect("runtime config");

    assert_eq!(config.groups[0].http_fake_profile, HttpFakeProfile::CompatDefault);
    assert_eq!(config.groups[0].tls_fake_profile, TlsFakeProfile::CompatDefault);
    assert_eq!(config.groups[0].udp_fake_profile, UdpFakeProfile::CompatDefault);
    assert_eq!(config.groups[0].quic_fake_profile, QuicFakeProfile::RealisticInitial);
    assert_eq!(config.groups[0].quic_fake_host.as_deref(), Some("example.com"));
    assert_eq!(config.groups[0].tcp_chain[0].kind, TcpChainStepKind::HostFake);
    assert_eq!(config.groups[0].udp_chain[0].count, 3);
}

#[test]
fn ui_payload_treats_fake_approx_steps_as_fake_payload_consumers() {
    for kind in ["fakedsplit", "fakeddisorder"] {
        let mut ui = minimal_ui();
        ui.tcp_chain_steps.push(ProxyUiTcpChainStep {
            kind: kind.to_string(),
            marker: "host+1".to_string(),
            midhost_marker: None,
            fake_host_template: None,
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: ProxyUiActivationFilter::default(),
        });
        ui.fake_tls_use_original = true;
        ui.fake_tls_dup_session_id = true;
        ui.fake_offset_marker = Some("host+1".to_string());

        let config = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
            .expect("runtime config");
        let group = &config.groups[0];

        let expected_mode = if kind == "fakedsplit" { DesyncMode::Fake } else { DesyncMode::Disorder };

        assert_eq!(group.tcp_chain[0].kind.as_mode(), Some(expected_mode));
        assert_eq!(group.fake_offset.map(|offset| offset.delta), Some(1));
        assert_ne!(group.fake_mod & FM_ORIG, 0);
        assert_ne!(group.fake_mod & FM_DUPSID, 0);
    }
}

#[test]
fn ui_payload_rejects_non_terminal_fake_approx_step() {
    let mut ui = minimal_ui();
    ui.tcp_chain_steps.push(ProxyUiTcpChainStep {
        kind: "fakedsplit".to_string(),
        marker: "host+1".to_string(),
        midhost_marker: None,
        fake_host_template: None,
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: ProxyUiActivationFilter::default(),
    });
    ui.tcp_chain_steps.push(ProxyUiTcpChainStep {
        kind: "split".to_string(),
        marker: "endhost".to_string(),
        midhost_marker: None,
        fake_host_template: None,
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: ProxyUiActivationFilter::default(),
    });

    let err = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect_err("non-terminal fakedsplit");

    assert!(err.to_string().contains("fakedsplit"));
}

#[test]
fn ui_payload_parses_fake_payload_profiles() {
    let mut ui = minimal_ui();
    ui.http_fake_profile = "cloudflare_get".to_string();
    ui.tls_fake_profile = "google_chrome".to_string();
    ui.udp_fake_profile = "dns_query".to_string();

    let config = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect("runtime config");

    assert_eq!(config.groups[0].http_fake_profile, HttpFakeProfile::CloudflareGet);
    assert_eq!(config.groups[0].tls_fake_profile, TlsFakeProfile::GoogleChrome);
    assert_eq!(config.groups[0].udp_fake_profile, UdpFakeProfile::DnsQuery);
}

#[test]
fn ui_payload_maps_extended_http_parser_evasions_into_mod_http() {
    let mut ui = minimal_ui();
    ui.host_mixed_case = true;
    ui.domain_mixed_case = true;
    ui.host_remove_spaces = true;
    ui.http_method_eol = true;
    ui.http_unix_eol = true;

    let config = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect("runtime config");

    assert_eq!(config.groups[0].mod_http, MH_HMIX | MH_DMIX | MH_SPACE | MH_METHODEOL | MH_UNIXEOL);
}

#[test]
fn command_line_payload_requires_runnable_config() {
    let err = runtime_config_from_payload(ProxyConfigPayload::CommandLine {
        args: vec!["ciadpi".to_string(), "--help".to_string()],
        runtime_context: None,
    })
    .expect_err("help should not produce runnable config");

    assert!(err.to_string().contains("runnable"));
}

#[test]
fn invalid_quic_fake_profile_is_rejected() {
    let mut ui = minimal_ui();
    ui.quic_fake_profile = "bogus".to_string();

    let err = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect_err("invalid quic profile");

    assert!(err.to_string().contains("quicFakeProfile"));
}

#[test]
fn invalid_http_fake_profile_is_rejected() {
    let mut ui = minimal_ui();
    ui.http_fake_profile = "bogus".to_string();

    let err = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect_err("invalid http profile");

    assert!(err.to_string().contains("httpFakeProfile"));
}

#[test]
fn adaptive_hostfake_marker_is_rejected() {
    let mut ui = minimal_ui();
    ui.tcp_chain_steps.push(ProxyUiTcpChainStep {
        kind: "hostfake".to_string(),
        marker: "auto(host)".to_string(),
        midhost_marker: None,
        fake_host_template: None,
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: ProxyUiActivationFilter::default(),
    });

    let err = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect_err("adaptive hostfake marker");

    assert!(err.to_string().contains("hostfake"));
}

#[test]
fn adaptive_hostfake_midhost_marker_is_rejected() {
    let mut ui = minimal_ui();
    ui.tcp_chain_steps.push(ProxyUiTcpChainStep {
        kind: "hostfake".to_string(),
        marker: "endhost+8".to_string(),
        midhost_marker: Some("auto(midsld)".to_string()),
        fake_host_template: None,
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: ProxyUiActivationFilter::default(),
    });

    let err = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect_err("adaptive hostfake midhost");

    assert!(err.to_string().contains("midhostMarker"));
}

#[test]
fn adaptive_fake_offset_marker_is_rejected() {
    let mut ui = minimal_ui();
    ui.desync_method = "fake".to_string();
    ui.fake_offset_marker = Some("auto(host)".to_string());

    let err = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect_err("adaptive fake offset");

    assert!(err.to_string().contains("fakeOffsetMarker"));
}

#[test]
fn ui_payload_maps_adaptive_fake_ttl_and_fallback() {
    let mut ui = minimal_ui();
    ui.fake_ttl = 11;
    ui.adaptive_fake_ttl_enabled = true;
    ui.adaptive_fake_ttl_delta = -1;
    ui.adaptive_fake_ttl_min = 3;
    ui.adaptive_fake_ttl_max = 12;
    ui.adaptive_fake_ttl_fallback = 9;

    let config = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect("adaptive fake ttl config");
    let group = &config.groups[0];

    assert_eq!(group.auto_ttl, Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 }),);
    assert_eq!(group.ttl, Some(9));
}

#[test]
fn ui_payload_rejects_invalid_adaptive_fake_ttl_window() {
    let mut ui = minimal_ui();
    ui.adaptive_fake_ttl_enabled = true;
    ui.adaptive_fake_ttl_min = 12;
    ui.adaptive_fake_ttl_max = 3;

    let err = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect_err("invalid adaptive ttl window");

    assert!(err.to_string().contains("adaptive fake TTL window"));
}

#[test]
fn ui_payload_uses_fixed_fake_ttl_when_adaptive_is_disabled() {
    let mut ui = minimal_ui();
    ui.fake_ttl = 13;
    ui.adaptive_fake_ttl_enabled = false;
    ui.adaptive_fake_ttl_fallback = 5;

    let config = runtime_config_from_payload(ProxyConfigPayload::Ui { config: ui, runtime_context: None })
        .expect("fixed fake ttl config");
    let group = &config.groups[0];

    assert_eq!(group.auto_ttl, None);
    assert_eq!(group.ttl, Some(13));
}

#[test]
fn known_preset_applies_without_error() {
    let base = minimal_ui();
    let mut cfg = base.clone();
    crate::presets::apply_preset("russia_rostelecom", &mut cfg).unwrap();
    assert!(cfg.desync_https, "rostelecom preset should enable HTTPS desync");
    assert!(cfg.adaptive_fake_ttl_enabled, "rostelecom preset should enable adaptive TTL");
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
    let mut cfg = minimal_ui();
    cfg.strategy_preset = Some("byedpi_default".to_string());
    let json = serde_json::to_string(&cfg).unwrap();
    let decoded: ProxyUiConfig = serde_json::from_str(&json).unwrap();
    assert_eq!(decoded.strategy_preset.as_deref(), Some("byedpi_default"));
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
        wifi: Some(WifiSnapshot { frequency_band: "5ghz".to_string(), ssid_hash: "abc123def456".to_string() }),
        mtu: Some(1500),
        traffic_tx_bytes: 1_234_567,
        traffic_rx_bytes: 9_876_543,
        captured_at_ms: 1_700_000_000_000,
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
        }),
        wifi: None,
        mtu: Some(1420),
        traffic_tx_bytes: 0,
        traffic_rx_bytes: 0,
        captured_at_ms: 1_700_000_000_000,
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
        mtu: Some(1280),
        ..NetworkSnapshot::default()
    };
    let json = serde_json::to_string(&snapshot).expect("serialize");
    assert!(json.contains("\"captivePortal\""), "expected camelCase key in: {json}");
    assert!(json.contains("\"mtu\""), "expected mtu key in: {json}");
    assert!(json.contains("\"privateDnsMode\""), "expected camelCase key in: {json}");
}
