use ciadpi_config::{
    AutoTtlConfig, DesyncMode, QuicFakeProfile, TcpChainStepKind, FM_DUPSID, FM_ORIG, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
};
use ciadpi_packets::{
    HttpFakeProfile, TlsFakeProfile, UdpFakeProfile, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL,
};

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
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: None,
    }
}

fn udp_step(kind: &str, count: i32) -> ProxyUiUdpChainStep {
    ProxyUiUdpChainStep { kind: kind.to_string(), count, activation_filter: None }
}

fn ui_payload(config: ProxyUiConfig) -> ProxyConfigPayload {
    ProxyConfigPayload::Ui { strategy_preset: None, config, runtime_context: None }
}

#[test]
fn ui_payload_parses_hostfake_and_quic_profile() {
    let mut ui = minimal_ui();
    ui.chains.tcp_steps = vec![ProxyUiTcpChainStep {
        kind: "hostfake".to_string(),
        marker: "endhost+8".to_string(),
        midhost_marker: "midsld".to_string(),
        fake_host_template: "googlevideo.com".to_string(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: None,
    }];
    ui.chains.udp_steps.push(udp_step("fake_burst", 3));
    ui.quic.fake_profile = "realistic_initial".to_string();
    ui.quic.fake_host = "Example.COM.".to_string();

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

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
        ui.chains.tcp_steps = vec![tcp_step(kind, "host+1")];
        ui.fake_packets.fake_tls_use_original = true;
        ui.fake_packets.fake_tls_dup_session_id = true;
        ui.fake_packets.fake_offset_marker = "host+1".to_string();

        let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
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

    assert_eq!(config.groups[0].http_fake_profile, HttpFakeProfile::CloudflareGet);
    assert_eq!(config.groups[0].tls_fake_profile, TlsFakeProfile::GoogleChrome);
    assert_eq!(config.groups[0].udp_fake_profile, UdpFakeProfile::DnsQuery);
}

#[test]
fn ui_payload_maps_extended_http_parser_evasions_into_mod_http() {
    let mut ui = minimal_ui();
    ui.parser_evasions.host_mixed_case = true;
    ui.parser_evasions.domain_mixed_case = true;
    ui.parser_evasions.host_remove_spaces = true;
    ui.parser_evasions.http_method_eol = true;
    ui.parser_evasions.http_unix_eol = true;

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");

    assert_eq!(config.groups[0].mod_http, MH_HMIX | MH_DMIX | MH_SPACE | MH_METHODEOL | MH_UNIXEOL);
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
        args: vec!["ciadpi".to_string(), "--help".to_string()],
        runtime_context: None,
    })
    .expect_err("help should not produce runnable config");

    assert!(err.to_string().contains("runnable"));
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
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: None,
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

    assert_eq!(group.auto_ttl, Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 }),);
    assert_eq!(group.ttl, Some(9));
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

    assert_eq!(group.auto_ttl, None);
    assert_eq!(group.ttl, Some(13));
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
        strategy_preset: Some("byedpi_default".to_string()),
        config: cfg,
        runtime_context: None,
    };
    let json = serde_json::to_string(&payload).unwrap();
    let decoded: ProxyConfigPayload = serde_json::from_str(&json).unwrap();
    match decoded {
        ProxyConfigPayload::Ui { strategy_preset, .. } => {
            assert_eq!(strategy_preset.as_deref(), Some("byedpi_default"));
        }
        other => panic!("expected ui payload, got {other:?}"),
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
