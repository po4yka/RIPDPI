use super::*;

pub(super) fn minimal_ui() -> ProxyUiConfig {
    let mut config = ProxyUiConfig::default();
    config.protocols.desync_udp = true;
    config.chains.tcp_steps = vec![tcp_step("disorder", "host+1")];
    config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
    config.host_autolearn.max_hosts = HOST_AUTOLEARN_DEFAULT_MAX_HOSTS;
    config
}

pub(super) fn with_destination_routing_digest(mut json: serde_json::Value) -> serde_json::Value {
    let section = json.get("destinationRouting").cloned().expect("destinationRouting section");
    let config: ProxyUiDestinationRoutingConfig = serde_json::from_value(section).expect("destination routing config");
    let digest = crate::convert::compute_canonical_digest(&config.rules);
    json["destinationRouting"]["canonicalDigest"] = serde_json::Value::String(digest);
    json
}

pub(super) fn destination_policy_with_digest(
    rules: Vec<ProxyUiDestinationRoutingRule>,
) -> ProxyUiDestinationRoutingConfig {
    let canonical_digest = crate::convert::compute_canonical_digest(&rules);
    ProxyUiDestinationRoutingConfig {
        rules,
        default_action: ProxyUiDestinationRoutingAction::Tunneled,
        canonical_digest,
    }
}

pub(super) fn exact_aggregate_boundary_policy() -> ProxyUiDestinationRoutingConfig {
    let rules = (0..256)
        .map(|rule_index| ProxyUiDestinationRoutingRule {
            action: ProxyUiDestinationRoutingAction::Direct,
            network: ProxyUiDestinationRoutingNetwork::Both,
            domains: (0..4)
                .map(|matcher_index| {
                    let prefix = format!("r{rule_index}m{matcher_index}");
                    ProxyUiDestinationDomainMatcher {
                        kind: ProxyUiDestinationDomainMatcherKind::Geosite,
                        value: format!("{prefix:a<63}"),
                    }
                })
                .collect(),
            ip_ranges: Vec::new(),
            destination_ports: Vec::new(),
        })
        .collect();
    destination_policy_with_digest(rules)
}

pub(super) fn runtime_config_from_destination_policy(
    destination_routing: ProxyUiDestinationRoutingConfig,
) -> Result<RuntimeConfig, ProxyConfigError> {
    runtime_config_from_ui(ProxyUiConfig { destination_routing, ..ProxyUiConfig::default() })
}

#[test]
pub(super) fn shared_destination_routing_fixture_has_cross_language_digest() {
    let path =
        golden_test_support::repo_root().join("core/engine/src/test/resources/fixtures/destination-routing-mixed.json");
    let json: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(path).expect("read shared fixture"))
        .expect("parse shared fixture");
    let config: ProxyUiDestinationRoutingConfig =
        serde_json::from_value(json["destinationRouting"].clone()).expect("destination routing config");

    assert_eq!(
        crate::convert::compute_canonical_digest(&config.rules),
        "e7ed9f9ec8688b89eea6f22a7ae6e93e7f441a903b9f9de96d387700c122bee7"
    );
}

#[test]
pub(super) fn ui_config_maps_geo_database_paths() {
    let mut ui = minimal_ui();
    ui.geoip_db_path = Some(" /data/user/0/app/files/geo/geoip.db ".to_string());
    ui.geosite_db_path = Some("/data/user/0/app/files/geo/geosite.db".to_string());

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert_eq!(config.process.geoip_db_path.as_deref(), Some("/data/user/0/app/files/geo/geoip.db"));
    assert_eq!(config.process.geosite_db_path.as_deref(), Some("/data/user/0/app/files/geo/geosite.db"));
}

#[test]
pub(super) fn ui_listen_mixed_flag_enables_mixed_network_mode() {
    let mut ui = minimal_ui();
    ui.listen.mixed = true;
    let config = runtime_config_from_ui(ui).expect("runtime config");
    assert!(config.network.mixed, "listen.mixed must propagate to network.mixed");

    let off = runtime_config_from_ui(minimal_ui()).expect("runtime config");
    assert!(!off.network.mixed, "mixed must default off when listen.mixed is unset");
}

pub(super) fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
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

pub(super) fn seqovl_step(marker: &str, overlap_size: i32, fake_mode: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: "seqovl".to_string(),
        marker: marker.to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        fake_order: String::new(),
        fake_seq_mode: String::new(),
        tcp_flags_set: String::new(),
        tcp_flags_unset: String::new(),
        tcp_flags_orig_set: String::new(),
        tcp_flags_orig_unset: String::new(),
        overlap_size,
        fake_mode: fake_mode.to_string(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        inter_segment_delay_ms: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
        random_fake_host: false,
    }
}

pub(super) fn udp_step(kind: &str, count: i32) -> ProxyUiUdpChainStep {
    ProxyUiUdpChainStep {
        kind: kind.to_string(),
        count,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }
}

pub(super) fn activation_filter_with_tcp_state() -> ProxyUiActivationFilter {
    ProxyUiActivationFilter {
        round: None,
        payload_size: None,
        stream_bytes: None,
        tcp_has_timestamp: Some(true),
        tcp_has_ech: Some(false),
        tcp_window_below: Some(4096),
        tcp_mss_below: Some(1400),
    }
}

pub(super) fn ui_payload(config: ProxyUiConfig) -> ProxyConfigPayload {
    ProxyConfigPayload::Ui {
        strategy_preset: None,
        config,
        runtime_context: None,
        log_context: None,
        session_overrides: None,
        schema_version: 2,
    }
}
