//! Named ISP presets for Russian path optimization.
//!
//! Each preset applies a known-good set of defaults to a `ProxyUiConfig`.

use crate::{ProxyConfigError, ProxyUiConfig, ProxyUiTcpChainStep, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT};

/// Apply the named preset to `config`.
///
/// Returns `Err` if `preset_id` is not recognised.
pub fn apply_preset(preset_id: &str, config: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    match preset_id {
        "russia_rostelecom" => apply_russia_rostelecom(config),
        "russia_mgts" => apply_russia_mgts(config),
        "russia_mts_mobile" => apply_russia_mts_mobile(config),
        "ripdpi_default" => apply_ripdpi_default(config),
        other => Err(ProxyConfigError::InvalidConfig(format!("Unknown strategyPreset: {other}"))),
    }
}

/// Rostelecom / TTK -- inline active DPI (ecDPI), injects TCP RST into server
/// response. Needs packet desync with fake TTL so the fake reaches the DPI but
/// not the real server.
fn apply_russia_rostelecom(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.protocols.desync_https = true;
    c.protocols.desync_http = true;
    c.protocols.desync_udp = true;
    c.chains.tcp_steps = vec![tcp_step("fake", "host")];
    c.fake_packets.adaptive_fake_ttl_enabled = true;
    c.fake_packets.fake_ttl = 8;
    c.fake_packets.tls_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic.fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic.initial_mode = "route_and_cache".to_string();
    Ok(())
}

/// MGTS (Moscow city network) -- passive DPI, injects TCP RST packets with
/// IP ID 0x0000/0x0001. A simple split at the SNI breaks the pattern match
/// without needing fake packets.
fn apply_russia_mgts(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.protocols.desync_https = true;
    c.protocols.desync_http = false;
    c.chains.tcp_steps = vec![tcp_step("split", "host")];
    c.fake_packets.adaptive_fake_ttl_enabled = false;
    Ok(())
}

/// MTS/Tele2/Beeline mobile -- whitelist mode default-deny; Cloudflare 1.1.1.1
/// is blocked. Focus on QUIC compat to avoid >1001-byte QUIC fake drop.
/// DNS is handled by the monitor layer and now defaults to Cloudflare when no override is supplied.
fn apply_russia_mts_mobile(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.protocols.desync_https = true;
    c.protocols.desync_http = true;
    c.protocols.desync_udp = true;
    c.chains.tcp_steps = vec![tcp_step("split", "host")];
    c.fake_packets.adaptive_fake_ttl_enabled = false;
    c.quic.fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic.initial_mode = "route_and_cache".to_string();
    Ok(())
}

/// RIPDPI default -- broad Russian ISP compatibility using disorder (more
/// reliable than split on modern middlebox) with adaptive fake TTL enabled.
fn apply_ripdpi_default(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.protocols.desync_https = true;
    c.protocols.desync_http = true;
    c.protocols.desync_udp = true;
    c.chains.tcp_steps = vec![tcp_step("disorder", "host")];
    c.fake_packets.adaptive_fake_ttl_enabled = true;
    c.fake_packets.fake_ttl = 8;
    c.fake_packets.tls_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic.fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic.initial_mode = "route_and_cache".to_string();
    Ok(())
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
        activation_filter: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base() -> ProxyUiConfig {
        let mut config = ProxyUiConfig::default();
        config.protocols.desync_http = false;
        config.protocols.desync_https = false;
        config.protocols.desync_udp = false;
        config.chains.tcp_steps.clear();
        config.fake_packets.fake_ttl = 0;
        config.fake_packets.fake_sni = String::new();
        config.hosts.mode = "disable".to_string();
        config
    }

    #[test]
    fn rostelecom_enables_adaptive_ttl() {
        let mut c = base();
        apply_russia_rostelecom(&mut c).unwrap();
        assert!(c.fake_packets.adaptive_fake_ttl_enabled);
        assert_eq!(c.chains.tcp_steps[0].kind, "fake");
    }

    #[test]
    fn mgts_does_not_enable_fake() {
        let mut c = base();
        apply_russia_mgts(&mut c).unwrap();
        assert_eq!(c.chains.tcp_steps[0].kind, "split");
        assert!(!c.fake_packets.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn mts_mobile_enables_quic_compat() {
        let mut c = base();
        apply_russia_mts_mobile(&mut c).unwrap();
        assert_eq!(c.quic.fake_profile, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT);
        assert!(c.protocols.desync_udp);
    }

    #[test]
    fn all_presets_produce_valid_runtime_config() {
        use crate::runtime_config_from_ui;
        for preset in &["russia_rostelecom", "russia_mgts", "russia_mts_mobile", "ripdpi_default"] {
            let mut c = base();
            apply_preset(preset, &mut c).unwrap();
            runtime_config_from_ui(c).unwrap_or_else(|e| panic!("preset {preset} failed validation: {e}"));
        }
    }
}
