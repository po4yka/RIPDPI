use ripdpi_config::UdpChainStepKind;
use ripdpi_packets::IS_UDP;

use crate::{ProxyUiConfig, ProxyUiUdpChainStep, runtime_config_from_ui};

#[test]
fn udp_group_preserves_configured_fixed_fake_ttl() {
    for ttl in [1u8, 23, 255] {
        let mut ui = ProxyUiConfig::default();
        ui.protocols.desync_udp = true;
        ui.fake_packets.fake_ttl = i32::from(ttl);
        ui.fake_packets.adaptive_fake_ttl_enabled = false;
        ui.chains.udp_steps = vec![ProxyUiUdpChainStep {
            kind: "quic_crypto_split".to_string(),
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        }];

        let config = runtime_config_from_ui(ui).expect("valid UDP split config");
        let group = config
            .groups
            .iter()
            .find(|group| {
                group.matches.proto == IS_UDP
                    && group.actions.udp_chain.iter().any(|step| step.kind == UdpChainStepKind::QuicCryptoSplit)
            })
            .expect("UDP split group");

        assert_eq!(group.actions.ttl, Some(ttl));
        assert_eq!(group.actions.auto_ttl, None);
    }
}
