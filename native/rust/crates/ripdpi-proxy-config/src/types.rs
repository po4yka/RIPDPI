mod adaptive_fallback;
mod autolearn;
mod chains;
mod common;
mod constants;
mod fake_packets;
mod hosts;
mod listen;
mod network;
mod parser;
mod payload;
mod protocol;
mod quic;
mod relay;
mod runtime_context;
mod ui;
mod warp;

pub use adaptive_fallback::ProxyUiAdaptiveFallbackConfig;
pub use autolearn::ProxyUiHostAutolearnConfig;
pub use chains::{
    ProxyUiChainConfig, ProxyUiTcpChainStep, ProxyUiTcpRotationCandidate, ProxyUiTcpRotationConfig, ProxyUiUdpChainStep,
};
pub use common::{ProxyUiActivationFilter, ProxyUiNumericRange};
pub use constants::{
    ADAPTIVE_FAKE_TTL_DEFAULT_DELTA, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
    ADAPTIVE_FAKE_TTL_DEFAULT_MIN, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT, FAKE_TLS_SNI_MODE_FIXED,
    FAKE_TLS_SNI_MODE_RANDOMIZED, FAKE_TLS_SOURCE_CAPTURED_CLIENT_HELLO, FAKE_TLS_SOURCE_PROFILE,
    HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_HOURS, IP_ID_MODE_RND, IP_ID_MODE_SEQ, IP_ID_MODE_SEQGROUP, IP_ID_MODE_ZERO,
    QUIC_FAKE_PROFILE_DISABLED, SEQOVL_DEFAULT_OVERLAP_SIZE, SEQOVL_FAKE_MODE_PROFILE, SEQOVL_FAKE_MODE_RAND,
};
pub(crate) use constants::{
    HOSTS_BLACKLIST, HOSTS_DISABLE, HOSTS_WHITELIST, RELAY_KIND_OFF, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT,
    TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE, WARP_ROUTE_MODE_RULES,
};
pub use fake_packets::ProxyUiFakePacketConfig;
pub use hosts::ProxyUiHostsConfig;
pub use listen::ProxyUiListenConfig;
pub use network::{CellularSnapshot, NetworkSnapshot, WifiSnapshot};
pub use parser::ProxyUiParserEvasionConfig;
pub use payload::{ProxyConfigError, ProxyConfigPayload, ProxySessionOverrides, RuntimeConfigEnvelope};
pub use protocol::ProxyUiProtocolConfig;
pub use quic::ProxyUiQuicConfig;
pub use relay::ProxyUiRelayConfig;
pub use runtime_context::{
    ProxyDirectPathCapability, ProxyEncryptedDnsContext, ProxyLogContext, ProxyMorphPolicy, ProxyPreferredEdge,
    ProxyRuntimeContext,
};
pub use ui::{ProxyUiConfig, ProxyUiWsTunnelConfig};
pub use warp::ProxyUiWarpConfig;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn network_snapshot_field_manifest_matches_contract_fixture() {
        use golden_test_support::{assert_contract_fixture, extract_field_paths};

        let snapshot = NetworkSnapshot {
            transport: "cellular".to_string(),
            validated: true,
            captive_portal: false,
            metered: true,
            private_dns_mode: "system".to_string(),
            dns_servers: vec!["8.8.8.8".to_string(), "8.8.4.4".to_string()],
            cellular: Some(CellularSnapshot {
                generation: "4g".to_string(),
                roaming: false,
                operator_code: "310260".to_string(),
                data_network_type: "LTE".to_string(),
                service_state: "in_service".to_string(),
                carrier_id: Some(1),
                signal_level: Some(3),
                signal_dbm: Some(-85),
            }),
            wifi: Some(WifiSnapshot {
                frequency_band: "5ghz".to_string(),
                ssid_hash: "abc123def456".to_string(),
                frequency_mhz: Some(5180),
                rssi_dbm: Some(-55),
                link_speed_mbps: Some(866),
                rx_link_speed_mbps: Some(780),
                tx_link_speed_mbps: Some(866),
                channel_width: "80 MHz".to_string(),
                wifi_standard: "802.11ax".to_string(),
            }),
            mtu: Some(1500),
            traffic_tx_bytes: 123456,
            traffic_rx_bytes: 654321,
            captured_at_ms: 1700000000000,
            vpn_service_was_active: false,
        };

        let json = serde_json::to_value(&snapshot).expect("serialize network snapshot");
        let paths = extract_field_paths(&json);
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
        assert_contract_fixture("network_snapshot_fields.json", &manifest);
    }
}
