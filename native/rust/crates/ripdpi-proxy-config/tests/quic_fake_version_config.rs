use ripdpi_packets::IS_UDP;
use ripdpi_proxy_config::{ProxyUiConfig, ProxyUiUdpChainStep, parse_proxy_config_json, runtime_config_from_payload};

#[test]
fn ui_json_preserves_fake_version_in_the_udp_group() {
    for (version, expected) in [
        (None, 0x1a2a_3a4a),
        (Some(0xdead_beef), 0xdead_beef),
        (Some(0), 0),
        (Some(1), 1),
        (Some(0x6b33_43cf), 0x6b33_43cf),
        (Some(u32::MAX), u32::MAX),
    ] {
        let mut ui = ProxyUiConfig::default();
        ui.protocols.desync_udp = true;
        ui.fake_packets.quic_fake_version = version;
        ui.chains.udp_steps = vec![ProxyUiUdpChainStep {
            kind: "quic_fake_version".to_string(),
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        }];
        let mut json = serde_json::to_value(ui).expect("serialize UI config");
        json["kind"] = "ui".into();
        json["schemaVersion"] = 2.into();
        let payload = parse_proxy_config_json(&json.to_string()).expect("parse native config JSON");
        let config = runtime_config_from_payload(payload).expect("convert native config");
        let udp = config.groups.iter().find(|group| group.matches.proto == IS_UDP).expect("UDP group");

        assert_eq!(udp.actions.quic_fake_version, expected, "UI override {version:?}");
        assert_eq!(udp.actions.udp_chain.len(), 1);
    }
}
