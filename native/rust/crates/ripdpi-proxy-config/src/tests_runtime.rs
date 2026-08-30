use super::*;

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
        session_overrides: None,
        schema_version: 2,
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
fn ws_tunnel_allow_insecure_sni_defaults_to_false() {
    let config = runtime_config_from_ui(minimal_ui()).expect("runtime config");
    assert!(!config.adaptive.ws_tunnel_allow_insecure_sni);
}

#[test]
fn ws_tunnel_allow_insecure_sni_maps_from_ui() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.fake_sni = Some("yandex.ru".to_string());
    ui.ws_tunnel.allow_insecure_sni = true;
    let config = runtime_config_from_ui(ui).expect("runtime config");
    assert_eq!(config.adaptive.ws_tunnel_fake_sni.as_deref(), Some("yandex.ru"));
    assert!(config.adaptive.ws_tunnel_allow_insecure_sni);
}

#[test]
fn ws_tunnel_cloudflare_worker_route_maps_from_ui_without_debug_secret() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.cloudflare_worker_url = Some("https://edge.example.workers.dev/relay".to_string());
    ui.ws_tunnel.cloudflare_worker_bearer = Some("secret-token".to_string());

    let debug = format!("{:?}", ui.ws_tunnel);
    let config = runtime_config_from_ui(ui).expect("runtime config");
    let route = config.adaptive.ws_tunnel_worker_route.expect("worker route");

    assert_eq!(route.url(), "https://edge.example.workers.dev/relay");
    assert_eq!(route.bearer().expose_secret(), "secret-token");
    assert!(!debug.contains("secret-token"));
    assert!(debug.contains("<redacted>"));
    assert!(!format!("{route:?}").contains("secret-token"));
}

#[test]
fn ws_tunnel_cloudflare_worker_route_rejects_partial_config() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.cloudflare_worker_url = Some("https://edge.example.workers.dev/relay".to_string());

    let err = runtime_config_from_ui(ui).expect_err("partial worker route should fail");

    assert!(err.to_string().contains("cloudflareWorkerUrl"));
    assert!(err.to_string().contains("cloudflareWorkerBearer"));
}

#[test]
fn ws_tunnel_cloudflare_worker_route_rejects_unsafe_url_and_bearer() {
    let mut ui = minimal_ui();
    ui.ws_tunnel.cloudflare_worker_url = Some("http://edge.example.workers.dev/relay".to_string());
    ui.ws_tunnel.cloudflare_worker_bearer = Some("secret-token".to_string());
    assert!(runtime_config_from_ui(ui).expect_err("http route should fail").to_string().contains("https or wss"));

    let mut ui = minimal_ui();
    ui.ws_tunnel.cloudflare_worker_url = Some("https://edge.example.workers.dev/relay".to_string());
    ui.ws_tunnel.cloudflare_worker_bearer = Some("bad\r\nsecret".to_string());
    assert!(runtime_config_from_ui(ui).expect_err("non-RFC 6750 bearer should fail").to_string().contains("RFC 6750"),);
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
fn warp_rules_without_route_hosts_do_not_create_upstream_group() {
    let mut ui = minimal_ui();
    ui.warp.enabled = true;
    ui.warp.route_mode = "rules".to_string();
    ui.warp.route_hosts = String::new();

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert!(
        config.groups.iter().all(|group| group.policy.label != "warp_routed"),
        "ordinary empty WARP rules must not route all traffic",
    );
}

#[test]
fn amneziawg_route_marker_creates_all_traffic_upstream_group() {
    let mut ui = minimal_ui();
    ui.warp.enabled = true;
    ui.warp.route_mode = "rules".to_string();
    ui.warp.route_hosts = "__ripdpi_awg_all_traffic__".to_string();
    ui.warp.local_socks_port = 10809;

    let config = runtime_config_from_ui(ui).expect("runtime config");
    let warp_group = config
        .groups
        .iter()
        .find(|group| group.policy.label == "warp_routed")
        .expect("AWG egress marker must create a WARP-routed upstream group");

    assert!(warp_group.matches.filters.host_filters_empty());
    assert_eq!(
        warp_group.policy.ext_socks.map(|upstream| upstream.addr),
        Some(std::net::SocketAddr::from(([127, 0, 0, 1], 10809))),
    );
}

#[test]
fn amneziawg_route_marker_covers_all_generated_groups() {
    let mut ui = minimal_ui();
    ui.warp.enabled = true;
    ui.warp.route_mode = "rules".to_string();
    ui.warp.route_hosts = "__ripdpi_awg_all_traffic__".to_string();
    ui.warp.local_socks_port = 10_809;

    let config = runtime_config_from_ui(ui).expect("runtime config");
    let awg = std::net::SocketAddr::from(([127, 0, 0, 1], 10_809));

    assert!(config.groups.len() >= 5, "fixture must include TCP, UDP, and adaptive groups");
    for group in &config.groups {
        assert_eq!(
            group.policy.ext_socks.map(|upstream| upstream.addr),
            Some(awg),
            "group {} must not bypass the configured AmneziaWG egress",
            group.id,
        );
    }
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
fn udp_associate_unset_keeps_network_udp_enabled() {
    let ui = minimal_ui();
    assert_eq!(ui.protocols.udp_associate_enabled, None, "fixture must leave the switch unset");

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert!(config.network.udp, "unset udpAssociateEnabled must preserve the always-on default");
}

#[test]
fn udp_associate_disabled_clears_network_udp() {
    let mut ui = minimal_ui();
    ui.protocols.udp_associate_enabled = Some(false);

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert!(!config.network.udp, "udpAssociateEnabled=false must disable SOCKS5 UDP ASSOCIATE");
}

#[test]
fn udp_associate_enabled_sets_network_udp() {
    let mut ui = minimal_ui();
    ui.protocols.udp_associate_enabled = Some(true);

    let config = runtime_config_from_ui(ui).expect("runtime config");

    assert!(config.network.udp, "udpAssociateEnabled=true must enable SOCKS5 UDP ASSOCIATE");
}

#[test]
fn udp_enabled_with_udp_disabled_relay_is_rejected() {
    let mut ui = minimal_ui();
    ui.protocols.udp_associate_enabled = Some(true);
    ui.upstream_relay.enabled = true;
    ui.upstream_relay.kind = "vless_reality".to_string();
    ui.upstream_relay.udp_enabled = false;

    let error = runtime_config_from_ui(ui).expect_err("relay must provide required UDP egress");

    assert_eq!(
        error,
        ProxyConfigError::InvalidConfig(
            "relay transport vless_reality is missing required capability UDP ASSOCIATE".to_string()
        )
    );
}

#[test]
fn udp_enabled_with_udp_enabled_relay_routes_every_udp_group_through_relay() {
    let mut ui = minimal_ui();
    ui.protocols.udp_associate_enabled = Some(true);
    ui.upstream_relay.enabled = true;
    ui.upstream_relay.kind = "hysteria2".to_string();
    ui.upstream_relay.udp_enabled = true;

    let config = runtime_config_from_ui(ui).expect("UDP-capable relay config");

    let udp_groups: Vec<_> = config.groups.iter().filter(|group| group.matches.proto & IS_UDP != 0).collect();
    assert!(!udp_groups.is_empty(), "fixture must synthesize at least one UDP group");
    assert!(
        udp_groups.iter().all(|group| group.policy.ext_socks.is_some()),
        "configured relay must own every UDP group"
    );
}

#[test]
fn tcp_only_mode_accepts_tcp_only_relay() {
    let mut ui = minimal_ui();
    ui.protocols.udp_associate_enabled = Some(false);
    ui.upstream_relay.enabled = true;
    ui.upstream_relay.kind = "vless_reality".to_string();
    ui.upstream_relay.udp_enabled = false;

    let config = runtime_config_from_ui(ui).expect("TCP-only relay config");

    assert!(!config.network.udp);
    assert!(config.groups.iter().all(|group| group.policy.ext_socks.is_some()));
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
fn relay_upstream_covers_all_generated_fallback_groups() {
    let mut ui = minimal_ui();
    ui.upstream_relay.enabled = true;
    ui.upstream_relay.kind = "hysteria2".to_string();
    ui.upstream_relay.udp_enabled = true;
    ui.upstream_relay.local_socks_port = 11_980;

    let config = runtime_config_from_payload(ui_payload(ui)).expect("runtime config");
    let relay = std::net::SocketAddr::from(([127, 0, 0, 1], 11_980));

    assert!(config.groups.len() >= 7, "fixture must include UDP, adaptive, and runtime-preset fallback groups");
    for group in &config.groups {
        assert_eq!(
            group.policy.ext_socks.map(|upstream| upstream.addr),
            Some(relay),
            "group {} must not bypass the configured relay",
            group.id,
        );
    }
}

#[test]
fn relay_upstream_preserves_warp_routed_group() {
    let mut ui = minimal_ui();
    ui.upstream_relay.enabled = true;
    ui.upstream_relay.kind = "hysteria2".to_string();
    ui.upstream_relay.udp_enabled = true;
    ui.upstream_relay.local_socks_port = 11_980;
    ui.warp.enabled = true;
    ui.warp.route_mode = "rules".to_string();
    ui.warp.route_hosts = "__ripdpi_awg_all_traffic__".to_string();
    ui.warp.local_socks_port = 10_809;

    let config = runtime_config_from_ui(ui).expect("runtime config");
    let warp_group = config.groups.iter().find(|group| group.policy.label == "warp_routed").expect("WARP-routed group");

    assert_eq!(
        warp_group.policy.ext_socks.map(|upstream| upstream.addr),
        Some(std::net::SocketAddr::from(([127, 0, 0, 1], 10_809))),
    );
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
fn loopback_listen_without_auth_is_accepted() {
    let mut ui = minimal_ui();
    ui.listen.ip = "::1".to_string();
    ui.listen.auth_token = None;

    let config = runtime_config_from_ui(ui).expect("loopback listener should not require auth");

    assert_eq!(config.network.listen.listen_ip.to_string(), "::1");
    assert!(config.network.listen.auth_token.is_none());
}

#[test]
fn non_loopback_listen_without_auth_is_rejected() {
    let mut ui = minimal_ui();
    ui.listen.ip = "192.0.2.10".to_string();
    ui.listen.auth_token = None;

    let err = runtime_config_from_ui(ui).expect_err("non-loopback listener without auth");

    assert!(err.to_string().contains("listen.authToken"), "expected listen.authToken error, got: {err}");
}

#[test]
fn unspecified_listen_without_auth_is_rejected() {
    let mut ui = minimal_ui();
    ui.listen.ip = "0.0.0.0".to_string();
    ui.listen.auth_token = Some(String::new());

    let err = runtime_config_from_ui(ui).expect_err("unspecified listener without auth");

    assert!(err.to_string().contains("listen.authToken"), "expected listen.authToken error, got: {err}");
}

#[test]
fn non_loopback_listen_with_auth_is_accepted() {
    let mut ui = minimal_ui();
    ui.listen.ip = "192.0.2.10".to_string();
    ui.listen.auth_token = Some("local-lan-proxy-token".to_string());

    let config = runtime_config_from_ui(ui).expect("non-loopback listener with auth");

    assert_eq!(config.network.listen.listen_ip.to_string(), "192.0.2.10");
    assert_eq!(config.network.listen.auth_token.as_deref(), Some("local-lan-proxy-token"));
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

#[test]
fn ui_payload_maps_strategy_evolution_settings() {
    let payload = serde_json::json!({
        "kind": "ui",
        "schemaVersion": 2,
        "adaptiveFallback": {
            "strategyEvolution": true,
            "evolutionEpsilon": 0.2,
            "evolutionExperimentTtlMs": 45_000,
            "evolutionDecayHalfLifeMs": 1_800_000,
            "evolutionCooldownAfterFailures": 5,
            "evolutionCooldownMs": 120_000,
            "futureAdaptiveField": { "version": 2 }
        },
        "futureTopLevel": true
    });

    let parsed = parse_proxy_config_json(&payload.to_string()).expect("additive config");
    let config = runtime_config_envelope_from_payload(parsed).expect("runtime config").config;

    assert!(config.adaptive.strategy_evolution);
    assert_eq!(config.adaptive.evolution_epsilon_permil, 200);
    assert_eq!(config.adaptive.evolution_experiment_ttl_ms, 45_000);
    assert_eq!(config.adaptive.evolution_decay_half_life_ms, 1_800_000);
    assert_eq!(config.adaptive.evolution_cooldown_after_failures, 5);
    assert_eq!(config.adaptive.evolution_cooldown_ms, 120_000);
}

#[test]
fn missing_strategy_evolution_settings_keep_current_defaults() {
    let parsed = parse_proxy_config_json(r#"{"kind":"ui","schemaVersion":2,"adaptiveFallback":{}}"#)
        .expect("current sparse config");
    let config = runtime_config_envelope_from_payload(parsed).expect("runtime config").config;

    assert!(!config.adaptive.strategy_evolution);
    assert_eq!(config.adaptive.evolution_epsilon_permil, 100);
    assert_eq!(config.adaptive.evolution_experiment_ttl_ms, 30_000);
    assert_eq!(config.adaptive.evolution_decay_half_life_ms, 3_600_000);
    assert_eq!(config.adaptive.evolution_cooldown_after_failures, 3);
    assert_eq!(config.adaptive.evolution_cooldown_ms, 300_000);
}

#[test]
fn schema_v2_without_destination_routing_keeps_inert_runtime_policy() {
    let parsed = parse_proxy_config_json(r#"{"kind":"ui","schemaVersion":2,"adaptiveFallback":{}}"#)
        .expect("old schema-v2 payload");
    let config = runtime_config_from_payload(parsed).expect("runtime config");

    assert!(config.destination_routing.rules.is_empty());
    assert_eq!(config.destination_routing.default_action, ripdpi_config::DestinationRoutingAction::Tunneled);
    assert!(config.destination_routing.canonical_digest.is_empty());
}

#[test]
fn command_line_payload_overlays_destination_routing_after_argument_parsing() {
    let json = with_destination_routing_digest(serde_json::json!({
        "kind": "command_line",
        "schemaVersion": 2,
        "args": ["ripdpi", "--port", "1081"],
        "geoipDbPath": " /tmp/geoip.db ",
        "geositeDbPath": "/tmp/geosite.db",
        "destinationRouting": {
            "rules": [{
                "action": "direct",
                "network": "both",
                "domains": [{"kind": "exact", "value": "direct.example"}],
                "ipRanges": [],
                "destinationPorts": []
            }],
            "defaultAction": "tunneled",
            "canonicalDigest": ""
        }
    }));
    let parsed = parse_proxy_config_json(&json.to_string()).expect("command-line payload");
    let config = runtime_config_from_payload(parsed).expect("runtime config");

    assert_eq!(config.network.listen.listen_port, 1081);
    assert_eq!(config.destination_routing.rules.len(), 1);
    assert_eq!(config.destination_routing.rules[0].action, ripdpi_config::DestinationRoutingAction::Direct);
    assert_eq!(config.process.geoip_db_path.as_deref(), Some("/tmp/geoip.db"));
    assert_eq!(config.process.geosite_db_path.as_deref(), Some("/tmp/geosite.db"));
}

#[test]
fn destination_routing_round_trips_and_maps_every_wire_variant() {
    let json = with_destination_routing_digest(serde_json::json!({
        "kind": "ui",
        "schemaVersion": 2,
        "destinationRouting": {
            "rules": [
                {
                    "action": "direct",
                    "network": "both",
                    "domains": [
                        {"kind": "exact", "value": "example.com"},
                        {"kind": "suffix", "value": "example.net"},
                        {"kind": "geosite", "value": "ru"}
                    ],
                    "ipRanges": [
                        {"kind": "cidr", "value": "192.0.2.0/24"},
                        {"kind": "geo_ip", "value": "ru"}
                    ],
                    "destinationPorts": [{"start": 443, "endInclusive": 8443}]
                },
                {
                    "action": "block",
                    "network": "tcp",
                    "domains": [{"kind": "exact", "value": "blocked.example"}]
                },
                {
                    "action": "tunneled",
                    "network": "udp",
                    "destinationPorts": [{"start": 53, "endInclusive": 53}]
                }
            ],
            "defaultAction": "tunneled"
        }
    }));
    assert_eq!(
        json["destinationRouting"]["canonicalDigest"],
        "5c6ebcf0aff1d5a4b0c1885acc4c7b8533718d1791066105336a03c9e09e7d51"
    );

    let parsed = parse_proxy_config_json(&json.to_string()).expect("destination routing payload");
    let reparsed =
        parse_proxy_config_json(&serde_json::to_string(&parsed).expect("serialize destination routing payload"))
            .expect("reparse destination routing payload");
    assert_eq!(parsed, reparsed);

    let config = runtime_config_from_payload(parsed).expect("runtime config");
    assert_eq!(config.destination_routing.rules.len(), 3);
    assert_eq!(config.destination_routing.rules[0].action, ripdpi_config::DestinationRoutingAction::Direct);
    assert_eq!(config.destination_routing.rules[0].network, ripdpi_config::DestinationRoutingNetwork::Both);
    assert_eq!(config.destination_routing.rules[0].ip_ranges[1].kind, ripdpi_config::DestinationIpMatcherKind::GeoIp);
    assert_eq!(config.destination_routing.rules[1].action, ripdpi_config::DestinationRoutingAction::Block);
    assert_eq!(config.destination_routing.rules[2].network, ripdpi_config::DestinationRoutingNetwork::Udp);
}

#[test]
fn destination_routing_rejects_unknown_enum_and_rule_fields() {
    let unknown_action =
        r#"{"kind":"ui","schemaVersion":2,"destinationRouting":{"rules":[],"defaultAction":"future"}}"#;
    let unknown_rule_field = r#"{"kind":"ui","schemaVersion":2,"destinationRouting":{"rules":[{"action":"block","network":"tcp","domains":[{"kind":"exact","value":"example.com"}],"unexpected":true}],"canonicalDigest":"digest"}}"#;

    assert!(parse_proxy_config_json(unknown_action).is_err());
    assert!(parse_proxy_config_json(unknown_rule_field).is_err());
}

#[test]
fn destination_routing_rejects_invalid_rule_atomically_during_conversion() {
    let invalid_rule = r#"{"kind":"ui","schemaVersion":2,"destinationRouting":{"rules":[{"action":"direct","network":"both","destinationPorts":[{"start":8443,"endInclusive":443}]}],"defaultAction":"tunneled","canonicalDigest":"0000000000000000000000000000000000000000000000000000000000000000"}}"#;
    let parsed = parse_proxy_config_json(invalid_rule).expect("structurally valid payload");

    let error = runtime_config_from_payload(parsed).expect_err("invalid rule must reject the whole policy");
    assert!(error.to_string().contains("invalid destination port range"));
}

#[test]
fn destination_routing_rejects_direct_and_block_defaults_even_without_rules() {
    for action in ["direct", "block"] {
        let json = format!(
            r#"{{"kind":"ui","schemaVersion":2,"destinationRouting":{{"rules":[],"defaultAction":"{action}"}}}}"#
        );
        let parsed = parse_proxy_config_json(&json).expect("structurally valid payload");

        let error = runtime_config_from_payload(parsed).expect_err("unmatched destinations must always tunnel");
        assert!(error.to_string().contains("defaultAction must be tunneled"));
    }
}

#[test]
fn destination_routing_empty_policy_rejects_nonempty_digest() {
    let json = r#"{"kind":"ui","schemaVersion":2,"destinationRouting":{"rules":[],"defaultAction":"tunneled","canonicalDigest":"0000000000000000000000000000000000000000000000000000000000000000"}}"#;
    let parsed = parse_proxy_config_json(json).expect("structurally valid payload");

    let error = runtime_config_from_payload(parsed).expect_err("empty policy identity must be canonical");
    assert!(error.to_string().contains("must be empty when rules are absent"));
}

#[test]
fn destination_routing_rejects_noncanonical_matchers_and_stale_digest() {
    let malformed_matchers = [
        serde_json::json!({"domains": [{"kind": "exact", "value": "Example.com"}]}),
        serde_json::json!({"domains": [{"kind": "suffix", "value": ".example.com"}]}),
        serde_json::json!({"domains": [{"kind": "geosite", "value": "RU"}]}),
        serde_json::json!({"ipRanges": [{"kind": "cidr", "value": "192.0.2.1/24"}]}),
        serde_json::json!({"ipRanges": [{"kind": "geo_ip", "value": "RU"}]}),
    ];
    for matchers in malformed_matchers {
        let mut rule = serde_json::json!({"action": "direct", "network": "both"});
        rule.as_object_mut().expect("rule object").extend(matchers.as_object().expect("matcher object").clone());
        let json = serde_json::json!({
            "kind": "ui",
            "schemaVersion": 2,
            "destinationRouting": {
                "rules": [rule],
                "defaultAction": "tunneled",
                "canonicalDigest": "0000000000000000000000000000000000000000000000000000000000000000"
            }
        });
        let parsed = parse_proxy_config_json(&json.to_string()).expect("structurally valid payload");
        assert!(runtime_config_from_payload(parsed).is_err());
    }

    let json = serde_json::json!({
        "kind": "ui",
        "schemaVersion": 2,
        "destinationRouting": {
            "rules": [{
                "action": "direct",
                "network": "tcp",
                "domains": [{"kind": "exact", "value": "example.com"}]
            }],
            "defaultAction": "tunneled",
            "canonicalDigest": "0000000000000000000000000000000000000000000000000000000000000000"
        }
    });
    let parsed = parse_proxy_config_json(&json.to_string()).expect("structurally valid payload");
    let error = runtime_config_from_payload(parsed).expect_err("forged digest must reject the whole policy");
    assert!(error.to_string().contains("does not match"));

    let valid = with_destination_routing_digest(json);
    let uppercase_digest =
        valid["destinationRouting"]["canonicalDigest"].as_str().expect("digest").to_ascii_uppercase();
    let mut uppercase = valid;
    uppercase["destinationRouting"]["canonicalDigest"] = serde_json::Value::String(uppercase_digest);
    let parsed = parse_proxy_config_json(&uppercase.to_string()).expect("structurally valid payload");
    let error = runtime_config_from_payload(parsed).expect_err("uppercase digest must reject the whole policy");
    assert!(error.to_string().contains("lowercase hexadecimal"));
}

#[test]
fn destination_routing_rejects_structural_bounds_atomically() {
    let rules = (0..=256)
        .map(|index| {
            serde_json::json!({
                "action": "direct",
                "network": "tcp",
                "domains": [{"kind": "exact", "value": format!("r{index}.example")}]
            })
        })
        .collect::<Vec<_>>();
    let json = serde_json::json!({
        "kind": "ui",
        "schemaVersion": 2,
        "destinationRouting": {
            "rules": rules,
            "defaultAction": "tunneled",
            "canonicalDigest": "0000000000000000000000000000000000000000000000000000000000000000"
        }
    });
    let parsed = parse_proxy_config_json(&json.to_string()).expect("structurally valid payload");
    let error = runtime_config_from_payload(parsed).expect_err("oversized policy must reject atomically");
    assert!(error.to_string().contains("rules exceeds 256"));

    let domains = (0..=256)
        .map(|index| serde_json::json!({"kind": "exact", "value": format!("d{index}.example")}))
        .collect::<Vec<_>>();
    let json = serde_json::json!({
        "kind": "ui",
        "schemaVersion": 2,
        "destinationRouting": {
            "rules": [{"action": "direct", "network": "tcp", "domains": domains}],
            "defaultAction": "tunneled",
            "canonicalDigest": "0000000000000000000000000000000000000000000000000000000000000000"
        }
    });
    let parsed = parse_proxy_config_json(&json.to_string()).expect("structurally valid payload");
    let error = runtime_config_from_payload(parsed).expect_err("oversized field must reject atomically");
    assert!(error.to_string().contains("domains exceeds 256"));

    let json = serde_json::json!({
        "kind": "ui",
        "schemaVersion": 2,
        "destinationRouting": {
            "rules": [{
                "action": "direct",
                "network": "tcp",
                "domains": [{"kind": "geosite", "value": "a".repeat(254)}]
            }],
            "defaultAction": "tunneled",
            "canonicalDigest": "0000000000000000000000000000000000000000000000000000000000000000"
        }
    });
    let parsed = parse_proxy_config_json(&json.to_string()).expect("structurally valid payload");
    let error = runtime_config_from_payload(parsed).expect_err("oversized token must reject atomically");
    assert!(error.to_string().contains("non-canonical domain matcher"));
}

#[test]
fn destination_routing_accepts_exact_aggregate_and_canonical_bounds() {
    let policy = exact_aggregate_boundary_policy();

    runtime_config_from_destination_policy(policy)
        .expect("exact rule, matcher, and canonical-size bounds must be accepted");
}

#[test]
fn destination_routing_rejects_aggregate_and_canonical_overflow() {
    let mut aggregate = exact_aggregate_boundary_policy();
    aggregate.rules[0].domains.push(ProxyUiDestinationDomainMatcher {
        kind: ProxyUiDestinationDomainMatcherKind::Geosite,
        value: format!("{:a<63}", "aggregate-overflow"),
    });
    aggregate.canonical_digest = "0".repeat(64);
    let error = runtime_config_from_destination_policy(aggregate).expect_err("1,025 matchers must reject atomically");
    assert!(error.to_string().contains("matcher count exceeds 1024"));

    let mut canonical = exact_aggregate_boundary_policy();
    canonical.rules[0].domains[0].value.push('a');
    canonical.canonical_digest = "0".repeat(64);
    let error = runtime_config_from_destination_policy(canonical)
        .expect_err("65,537 canonical characters must reject atomically");
    assert!(error.to_string().contains("exceeds 65536"));
}

#[test]
fn destination_routing_field_limits_cover_ip_ranges_and_ports() {
    let exact_rule = ProxyUiDestinationRoutingRule {
        action: ProxyUiDestinationRoutingAction::Direct,
        network: ProxyUiDestinationRoutingNetwork::Both,
        domains: (0..256)
            .map(|index| ProxyUiDestinationDomainMatcher {
                kind: ProxyUiDestinationDomainMatcherKind::Geosite,
                value: format!("d{index}"),
            })
            .collect(),
        ip_ranges: (0..256)
            .map(|index| ProxyUiDestinationIpMatcher {
                kind: ProxyUiDestinationIpMatcherKind::Cidr,
                value: format!("10.0.{index}.0/24"),
            })
            .collect(),
        destination_ports: (1..=256)
            .map(|port| ProxyUiDestinationPortRange { start: port, end_inclusive: port })
            .collect(),
    };
    runtime_config_from_destination_policy(destination_policy_with_digest(vec![exact_rule.clone()]))
        .expect("256 entries in every matcher field must be accepted");

    let mut ip_overflow = exact_rule.clone();
    ip_overflow.domains.clear();
    ip_overflow.destination_ports.clear();
    ip_overflow.ip_ranges.push(ProxyUiDestinationIpMatcher {
        kind: ProxyUiDestinationIpMatcherKind::Cidr,
        value: "10.1.0.0/24".to_string(),
    });
    let error = runtime_config_from_destination_policy(destination_policy_with_digest(vec![ip_overflow]))
        .expect_err("257 IP ranges must reject atomically");
    assert!(error.to_string().contains("ipRanges exceeds 256"));

    let mut port_overflow = exact_rule;
    port_overflow.domains.clear();
    port_overflow.ip_ranges.clear();
    port_overflow.destination_ports.push(ProxyUiDestinationPortRange { start: 257, end_inclusive: 257 });
    let error = runtime_config_from_destination_policy(destination_policy_with_digest(vec![port_overflow]))
        .expect_err("257 port ranges must reject atomically");
    assert!(error.to_string().contains("destinationPorts exceeds 256"));
}
