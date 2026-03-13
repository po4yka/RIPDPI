//! Named ISP presets for Russian DPI bypass.
//!
//! Each preset applies a known-good set of defaults to a `ProxyUiConfig`.

use crate::{ProxyConfigError, ProxyUiConfig, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT};

/// Apply the named preset to `config`.
///
/// Returns `Err` if `preset_id` is not recognised.
pub fn apply_preset(preset_id: &str, config: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    match preset_id {
        "russia_rostelecom" => apply_russia_rostelecom(config),
        "russia_mgts" => apply_russia_mgts(config),
        "russia_mts_mobile" => apply_russia_mts_mobile(config),
        "byedpi_default" => apply_byedpi_default(config),
        other => Err(ProxyConfigError::InvalidConfig(format!("Unknown strategyPreset: {other}"))),
    }
}

/// Rostelecom / TTK -- inline active DPI (ecDPI), injects TCP RST into server
/// response. Needs packet desync with fake TTL so the fake reaches the DPI but
/// not the real server.
fn apply_russia_rostelecom(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.desync_https = true;
    c.desync_http = true;
    c.desync_method = "fake".to_string();
    c.split_at_host = true;
    c.split_position = 0;
    c.adaptive_fake_ttl_enabled = true;
    c.fake_ttl = 8;
    c.tls_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.desync_udp = true;
    c.quic_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic_initial_mode = Some("route_and_cache".to_string());
    Ok(())
}

/// MGTS (Moscow city network) -- passive DPI, injects TCP RST packets with
/// IP ID 0x0000/0x0001. A simple split at the SNI breaks the pattern match
/// without needing fake packets.
fn apply_russia_mgts(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.desync_https = true;
    c.desync_http = false;
    c.desync_method = "split".to_string();
    c.split_at_host = true;
    c.split_position = 0;
    c.adaptive_fake_ttl_enabled = false;
    Ok(())
}

/// MTS/Tele2/Beeline mobile -- whitelist mode default-deny; Cloudflare 1.1.1.1
/// is blocked. Focus on QUIC compat to avoid >1001-byte QUIC fake drop.
/// DNS is handled by the monitor layer (already switched to dns.google after the P0 fix).
fn apply_russia_mts_mobile(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.desync_https = true;
    c.desync_http = true;
    c.desync_method = "split".to_string();
    c.split_at_host = true;
    c.split_position = 0;
    c.adaptive_fake_ttl_enabled = false;
    c.desync_udp = true;
    c.quic_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic_initial_mode = Some("route_and_cache".to_string());
    Ok(())
}

/// ByeDPI default -- broad Russian ISP compatibility using disorder (more
/// reliable than split on modern TSPU) with adaptive fake TTL enabled.
fn apply_byedpi_default(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.desync_https = true;
    c.desync_http = true;
    c.desync_method = "disorder".to_string();
    c.split_at_host = true;
    c.split_position = 0;
    c.adaptive_fake_ttl_enabled = true;
    c.fake_ttl = 8;
    c.tls_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.desync_udp = true;
    c.quic_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic_initial_mode = Some("route_and_cache".to_string());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base() -> ProxyUiConfig {
        ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: false,
            desync_https: false,
            desync_udp: false,
            desync_method: "split".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            group_activation_filter: crate::ProxyUiActivationFilter::default(),
            split_position: 0,
            split_at_host: false,
            fake_ttl: 0,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: crate::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: crate::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: crate::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: crate::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: String::new(),
            http_fake_profile: FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: crate::FAKE_TLS_SNI_MODE_FIXED.to_string(),
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
            hosts_mode: "disable".to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: crate::QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: ciadpi_config::HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: ciadpi_config::HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
            strategy_preset: None,
        }
    }

    #[test]
    fn rostelecom_enables_adaptive_ttl() {
        let mut c = base();
        apply_russia_rostelecom(&mut c).unwrap();
        assert!(c.adaptive_fake_ttl_enabled);
        assert_eq!(c.desync_method, "fake");
    }

    #[test]
    fn mgts_does_not_enable_fake() {
        let mut c = base();
        apply_russia_mgts(&mut c).unwrap();
        assert_eq!(c.desync_method, "split");
        assert!(!c.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn mts_mobile_enables_quic_compat() {
        let mut c = base();
        apply_russia_mts_mobile(&mut c).unwrap();
        assert_eq!(c.quic_fake_profile, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT);
        assert!(c.desync_udp);
    }

    #[test]
    fn all_presets_produce_valid_runtime_config() {
        use crate::runtime_config_from_ui;
        for preset in &["russia_rostelecom", "russia_mgts", "russia_mts_mobile", "byedpi_default"] {
            let mut c = base();
            apply_preset(preset, &mut c).unwrap();
            runtime_config_from_ui(c).unwrap_or_else(|e| panic!("preset {preset} failed validation: {e}"));
        }
    }
}
