//! Named ISP presets for Russian DPI bypass.
//!
//! Each preset applies a known-good set of defaults to a `ProxyUiConfig`.

use ripdpi_config::{
    DesyncGroup, OffsetBase, OffsetExpr, RuntimeConfig, TcpChainStep, TcpChainStepKind, DETECT_CONNECT, FM_ORIG,
    FM_RNDSNI,
};
use ripdpi_packets::{IS_HTTP, IS_HTTPS};

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

/// RIPDPI default -- TLS record split at extension-length boundary + fake
/// packet with low TTL. Modern TSPU reassembles TCP segments before
/// inspecting TLS ClientHello, so plain TCP split/disorder is no longer
/// sufficient; splitting at the TLS record layer forces the DPI to see an
/// incomplete record while the real server reassembles correctly.
fn apply_ripdpi_default(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.protocols.desync_https = true;
    c.protocols.desync_http = true;
    c.protocols.desync_udp = true;
    c.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("fake", "host+1")];
    c.fake_packets.adaptive_fake_ttl_enabled = true;
    c.fake_packets.fake_ttl = 8;
    c.fake_packets.fake_tls_use_original = true;
    c.fake_packets.fake_tls_randomize = true;
    c.fake_packets.fake_tls_dup_session_id = true;
    c.fake_packets.fake_tls_pad_encap = true;
    c.fake_packets.fake_tls_sni_mode = "randomized".to_string();
    c.fake_packets.fake_offset_marker = "endhost-1".to_string();
    c.fake_packets.tls_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic.fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic.initial_mode = "route_and_cache".to_string();
    Ok(())
}

/// Apply a runtime-level preset that injects fallback desync groups into the
/// already-built `RuntimeConfig`. This runs *after* `runtime_config_from_ui`
/// so we can add multiple groups without changing the single-chain UI model.
pub fn apply_runtime_preset(preset_id: &str, config: &mut RuntimeConfig) -> Result<(), ProxyConfigError> {
    match preset_id {
        "ripdpi_default" => apply_ripdpi_default_fallback_groups(config),
        // Other presets use single-group strategies for now.
        _ => Ok(()),
    }
}

/// Inject 3 fallback groups for the `ripdpi_default` preset so the runtime
/// can automatically cascade through strategies when DPI blocks the primary.
///
/// Cascade order (field-tested against Russian TSPU):
/// 1. Primary: tlsrec(extlen) + fake(host+1) -- TLS record split + fake, best TSPU bypass
/// 2. tlsrec(extlen) + disorder(host+1) -- TLS record + out-of-order delivery
/// 3. disorder(host) -- plain TCP disorder, legacy fallback for passive DPI
/// 4. split(host+2) -- minimal split for passive DPI (MGTS-style)
fn apply_ripdpi_default_fallback_groups(config: &mut RuntimeConfig) -> Result<(), ProxyConfigError> {
    let Some(primary) = config.groups.iter().find(|g| !g.actions.tcp_chain.is_empty()).cloned() else {
        // No actionable primary group (e.g., empty chain config) -- skip fallback injection.
        return Ok(());
    };

    let fallback_groups = vec![
        build_fallback_group(
            0, // placeholder, reindex fixes it
            "tlsrec_disorder",
            vec![
                TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
                TcpChainStep::new(TcpChainStepKind::Disorder, OffsetExpr::marker(OffsetBase::Host, 1)),
            ],
            &primary,
            false,
        ),
        build_fallback_group(
            0,
            "disorder_host",
            vec![TcpChainStep::new(TcpChainStepKind::Disorder, OffsetExpr::marker(OffsetBase::Host, 0))],
            &primary,
            false,
        ),
        build_fallback_group(
            0,
            "split_host",
            vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::Host, 2))],
            &primary,
            false,
        ),
    ];

    // Insert before CONNECT passthrough group (must remain last).
    let insert_pos =
        config.groups.iter().position(|g| g.matches.detect == DETECT_CONNECT).unwrap_or(config.groups.len());

    for (i, group) in fallback_groups.into_iter().enumerate() {
        config.groups.insert(insert_pos + i, group);
    }

    reindex_groups(config);
    config.network.delay_conn = config
        .groups
        .iter()
        .any(|g| !g.matches.filters.hosts.is_empty() || (g.matches.proto & (IS_HTTP | IS_HTTPS)) != 0);

    Ok(())
}

fn build_fallback_group(
    id: usize,
    label: &str,
    tcp_chain: Vec<TcpChainStep>,
    primary: &DesyncGroup,
    needs_fake_mod: bool,
) -> DesyncGroup {
    let mut g = DesyncGroup::new(id);
    g.matches.proto = primary.matches.proto;
    g.actions.tcp_chain = tcp_chain;
    g.actions.auto_ttl = primary.actions.auto_ttl;
    g.actions.ttl = primary.actions.ttl;
    g.actions.tls_fake_profile = primary.actions.tls_fake_profile;
    g.actions.http_fake_profile = primary.actions.http_fake_profile;
    g.actions.drop_sack = primary.actions.drop_sack;
    if needs_fake_mod {
        g.actions.fake_mod = FM_ORIG | FM_RNDSNI;
    }
    g.policy.label = label.to_string();
    g
}

fn reindex_groups(config: &mut RuntimeConfig) {
    for (i, group) in config.groups.iter_mut().enumerate() {
        group.id = i;
        group.bit = 1u64 << i;
    }
}

fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: kind.to_string(),
        marker: marker.to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
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
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_packets::IS_UDP;

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

    #[test]
    fn ripdpi_default_runtime_preset_adds_fallback_groups() {
        use crate::runtime_config_from_ui;
        let mut c = base();
        apply_preset("ripdpi_default", &mut c).unwrap();
        let mut config = runtime_config_from_ui(c).unwrap();
        let groups_before = config.groups.len();
        apply_runtime_preset("ripdpi_default", &mut config).unwrap();
        assert!(
            config.groups.len() >= groups_before + 3,
            "expected at least 3 fallback groups added, got {} total (was {})",
            config.groups.len(),
            groups_before,
        );
    }

    #[test]
    fn ripdpi_default_fallback_group_indices_are_sequential() {
        use crate::runtime_config_from_ui;
        let mut c = base();
        apply_preset("ripdpi_default", &mut c).unwrap();
        let mut config = runtime_config_from_ui(c).unwrap();
        apply_runtime_preset("ripdpi_default", &mut config).unwrap();
        for (i, group) in config.groups.iter().enumerate() {
            assert_eq!(group.id, i, "group at index {i} has id {}", group.id);
            assert_eq!(group.bit, 1u64 << i, "group at index {i} has wrong bit");
        }
    }

    #[test]
    fn ripdpi_default_connect_passthrough_remains_last() {
        use crate::runtime_config_from_ui;
        let mut c = base();
        apply_preset("ripdpi_default", &mut c).unwrap();
        let mut config = runtime_config_from_ui(c).unwrap();
        apply_runtime_preset("ripdpi_default", &mut config).unwrap();
        let last = config.groups.last().expect("at least one group");
        assert_eq!(last.matches.detect, DETECT_CONNECT, "CONNECT passthrough must be the last group",);
    }

    #[test]
    fn ripdpi_default_fallback_groups_inherit_proto_mask() {
        use crate::runtime_config_from_ui;
        let mut c = base();
        apply_preset("ripdpi_default", &mut c).unwrap();
        let mut config = runtime_config_from_ui(c).unwrap();
        apply_runtime_preset("ripdpi_default", &mut config).unwrap();
        let primary = &config.groups[0];
        let proto = primary.matches.proto;
        assert_ne!(proto, 0, "primary group should have protocol flags");
        for group in &config.groups[1..] {
            if group.matches.detect == DETECT_CONNECT {
                continue;
            }
            if group.matches.proto == IS_UDP {
                continue; // dedicated UDP transport group
            }
            assert_eq!(
                group.matches.proto, proto,
                "fallback group '{}' should inherit proto mask from primary",
                group.policy.label,
            );
        }
    }

    #[test]
    fn non_ripdpi_preset_runtime_is_noop() {
        use crate::runtime_config_from_ui;
        let mut c = base();
        apply_preset("russia_mgts", &mut c).unwrap();
        let mut config = runtime_config_from_ui(c).unwrap();
        let groups_before = config.groups.len();
        apply_runtime_preset("russia_mgts", &mut config).unwrap();
        assert_eq!(config.groups.len(), groups_before, "non-ripdpi presets should not add groups");
    }
}
