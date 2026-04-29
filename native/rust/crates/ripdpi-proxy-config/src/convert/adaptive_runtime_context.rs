use std::net::IpAddr;

use ripdpi_config::{
    DesyncGroup, RuntimeConfig, AUTO_RECONN, AUTO_SORT, DETECT_CONNECT, DETECT_HTTP_LOCAT, DETECT_TLS_ERR, DETECT_TORST,
};
use ripdpi_packets::{IS_HTTP, IS_HTTPS};

use crate::types::{
    ProxyConfigError, ProxyLogContext, ProxyRuntimeContext, ProxySessionOverrides, ProxyUiAdaptiveFallbackConfig,
    ProxyUiHostAutolearnConfig, ProxyUiListenConfig, ProxyUiWsTunnelConfig,
};

use super::shared::trim_non_empty;

pub(crate) fn sanitize_runtime_context(runtime_context: Option<ProxyRuntimeContext>) -> Option<ProxyRuntimeContext> {
    let mut runtime_context = runtime_context?;
    runtime_context.encrypted_dns = runtime_context.encrypted_dns.and_then(|mut value| {
        value.protocol = value.protocol.trim().to_ascii_lowercase();
        value.host = value.host.trim().to_string();
        if value.host.is_empty() {
            return None;
        }
        value.port = if value.port == 0 { 443 } else { value.port };
        value.tls_server_name = trim_non_empty(value.tls_server_name);
        value.bootstrap_ips = value
            .bootstrap_ips
            .into_iter()
            .map(|entry| entry.trim().to_string())
            .filter(|entry| !entry.is_empty())
            .collect();
        value.doh_url = trim_non_empty(value.doh_url);
        value.dnscrypt_provider_name = trim_non_empty(value.dnscrypt_provider_name);
        value.dnscrypt_public_key = trim_non_empty(value.dnscrypt_public_key);
        value.resolver_id = trim_non_empty(value.resolver_id);
        Some(value)
    });
    runtime_context.protect_path = trim_non_empty(runtime_context.protect_path);
    runtime_context.direct_path_capabilities = runtime_context
        .direct_path_capabilities
        .into_iter()
        .filter_map(|mut capability| {
            capability.authority = capability.authority.trim().trim_end_matches('.').to_ascii_lowercase();
            if capability.authority.is_empty() {
                return None;
            }
            capability.transport_policy_version = capability.transport_policy_version.max(0);
            capability.ip_set_digest = capability.ip_set_digest.trim().to_string();
            capability.dns_classification =
                trim_non_empty(capability.dns_classification).map(|value| value.to_ascii_uppercase());
            capability.quic_mode = capability.quic_mode.trim().to_ascii_uppercase();
            if capability.quic_mode.is_empty() {
                capability.quic_mode = "ALLOW".to_string();
            }
            capability.preferred_stack = capability.preferred_stack.trim().to_ascii_uppercase();
            if capability.preferred_stack.is_empty() {
                capability.preferred_stack = "H3".to_string();
            }
            capability.dns_mode = capability.dns_mode.trim().to_ascii_uppercase();
            if capability.dns_mode.is_empty() {
                capability.dns_mode = "SYSTEM".to_string();
            }
            capability.tcp_family = capability.tcp_family.trim().to_ascii_uppercase();
            if capability.tcp_family.is_empty() {
                capability.tcp_family = "NONE".to_string();
            }
            capability.outcome = capability.outcome.trim().to_ascii_uppercase();
            if capability.outcome.is_empty() {
                capability.outcome = "TRANSPARENT_OK".to_string();
            }
            capability.transport_class =
                trim_non_empty(capability.transport_class).map(|value| value.to_ascii_uppercase());
            capability.reason_code = trim_non_empty(capability.reason_code).map(|value| value.to_ascii_uppercase());
            capability.cooldown_until = capability.cooldown_until.filter(|value| *value > 0);
            capability.repeated_handshake_failure_class = trim_non_empty(capability.repeated_handshake_failure_class);
            capability.updated_at = capability.updated_at.max(0);
            Some(capability)
        })
        .collect();
    runtime_context.morph_policy = runtime_context.morph_policy.and_then(|mut policy| {
        policy.id = policy.id.trim().to_string();
        if policy.id.is_empty() {
            return None;
        }
        policy.first_flight_size_min = policy.first_flight_size_min.max(0);
        policy.first_flight_size_max = policy.first_flight_size_max.max(policy.first_flight_size_min);
        policy.padding_envelope_min = policy.padding_envelope_min.max(0);
        policy.padding_envelope_max = policy.padding_envelope_max.max(policy.padding_envelope_min);
        policy.entropy_target_permil = policy.entropy_target_permil.max(0);
        policy.tcp_burst_cadence_ms = policy.tcp_burst_cadence_ms.into_iter().map(|value| value.max(0)).collect();
        policy.tls_burst_cadence_ms = policy.tls_burst_cadence_ms.into_iter().map(|value| value.max(0)).collect();
        policy.quic_burst_profile = policy.quic_burst_profile.trim().to_ascii_lowercase();
        policy.fake_packet_shape_profile = policy.fake_packet_shape_profile.trim().to_ascii_lowercase();
        Some(policy)
    });
    if runtime_context.encrypted_dns.is_none()
        && runtime_context.protect_path.is_none()
        && runtime_context.preferred_edges.is_empty()
        && runtime_context.direct_path_capabilities.is_empty()
        && runtime_context.morph_policy.is_none()
    {
        return None;
    }
    Some(runtime_context)
}

pub(crate) fn sanitize_log_context(log_context: Option<ProxyLogContext>) -> Option<ProxyLogContext> {
    let mut log_context = log_context?;
    log_context.runtime_id = trim_non_empty(log_context.runtime_id);
    log_context.mode = trim_non_empty(log_context.mode).map(|value| value.to_ascii_lowercase());
    log_context.policy_signature = trim_non_empty(log_context.policy_signature);
    log_context.fingerprint_hash = trim_non_empty(log_context.fingerprint_hash);
    log_context.diagnostics_session_id = trim_non_empty(log_context.diagnostics_session_id);
    if log_context.runtime_id.is_none()
        && log_context.mode.is_none()
        && log_context.policy_signature.is_none()
        && log_context.fingerprint_hash.is_none()
        && log_context.diagnostics_session_id.is_none()
    {
        None
    } else {
        Some(log_context)
    }
}

pub(crate) fn apply_session_overrides(
    config: &mut RuntimeConfig,
    session_overrides: Option<ProxySessionOverrides>,
) -> Result<(), ProxyConfigError> {
    let Some(session_overrides) = sanitize_session_overrides(session_overrides) else {
        return Ok(());
    };

    if let Some(port_override) = session_overrides.listen_port_override {
        config.network.listen.listen_port = u16::try_from(port_override)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid sessionOverrides.listenPortOverride".to_string()))?;
    }
    if let Some(auth_token) = session_overrides.auth_token {
        config.network.listen.auth_token = Some(auth_token);
    }
    Ok(())
}

pub(crate) fn apply_runtime_section(
    config: &mut RuntimeConfig,
    adaptive_fallback: &ProxyUiAdaptiveFallbackConfig,
    host_autolearn: &ProxyUiHostAutolearnConfig,
    ws_tunnel: &ProxyUiWsTunnelConfig,
) {
    config.host_autolearn.enabled = host_autolearn.enabled;
    config.host_autolearn.penalty_ttl_secs = host_autolearn.penalty_ttl_hours.max(1).saturating_mul(3600);
    config.host_autolearn.max_hosts = host_autolearn.max_hosts.max(1);
    config.host_autolearn.store_path =
        host_autolearn.store_path.as_deref().map(str::trim).filter(|value| !value.is_empty()).map(ToOwned::to_owned);
    config.host_autolearn.warmup_probe_enabled = host_autolearn.warmup_probe_enabled;
    config.host_autolearn.network_reprobe_enabled = host_autolearn.network_reprobe_enabled;
    config.adaptive.network_scope_key = host_autolearn
        .network_scope_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned);
    config.adaptive.ws_tunnel_mode = match ws_tunnel.mode.as_deref() {
        Some("fallback") => ripdpi_config::WsTunnelMode::Fallback,
        Some("always") => ripdpi_config::WsTunnelMode::Always,
        Some("off" | _) => ripdpi_config::WsTunnelMode::Off,
        None => {
            if ws_tunnel.enabled {
                ripdpi_config::WsTunnelMode::Always
            } else {
                ripdpi_config::WsTunnelMode::Off
            }
        }
    };
    config.adaptive.ws_tunnel_fake_sni = ws_tunnel.fake_sni.clone().filter(|value| !value.is_empty());
    config.adaptive.auto_level = if adaptive_fallback.enabled { AUTO_RECONN } else { 0 };
    if adaptive_fallback.enabled && adaptive_fallback.auto_sort {
        config.adaptive.auto_level |= AUTO_SORT;
    }
    config.adaptive.cache_ttl = adaptive_fallback.cache_ttl_seconds.max(0);
    config.adaptive.cache_prefix = (32 - adaptive_fallback.cache_prefix_v4.clamp(1, 32)).max(1);
}

pub(crate) fn append_fallback_groups(
    groups: &mut Vec<DesyncGroup>,
    config: &RuntimeConfig,
    tcp_proto: u32,
    udp_enabled: bool,
    adaptive_fallback: &ProxyUiAdaptiveFallbackConfig,
) {
    let has_tcp_proto = tcp_proto != 0;
    if !(has_tcp_proto || udp_enabled) {
        return;
    }

    let adaptive_detect = adaptive_detect_mask(adaptive_fallback);
    if adaptive_fallback.enabled && adaptive_detect != 0 && has_tcp_proto {
        let mut adaptive_direct = DesyncGroup::new(groups.len());
        adaptive_direct.matches.detect = adaptive_detect;
        adaptive_direct.matches.proto = tcp_proto;
        adaptive_direct.policy.label = "adaptive_direct".to_string();
        adaptive_direct.policy.cache_ttl = config.adaptive.cache_ttl;
        groups.push(adaptive_direct);
    }

    let mut fallback = DesyncGroup::new(groups.len());
    fallback.matches.detect = DETECT_CONNECT;
    groups.push(fallback);
}

pub(crate) fn finalize_ui_config(
    mut config: RuntimeConfig,
    groups: Vec<DesyncGroup>,
    listen: &ProxyUiListenConfig,
    host_autolearn: &ProxyUiHostAutolearnConfig,
    root_mode: bool,
    root_helper_socket_path: Option<String>,
    environment_kind: Option<&str>,
) -> Result<RuntimeConfig, ProxyConfigError> {
    config.groups = groups;
    config.timeouts.connect_timeout_ms = 10_000;
    if listen.freeze_detection_enabled {
        config.timeouts.freeze_max_stalls = 3;
    }
    config.network.delay_conn = config.groups.iter().any(group_needs_delayed_connect);
    if !matches!(config.network.listen.bind_ip, IpAddr::V6(_)) {
        config.network.ipv6 = false;
    }
    if config.host_autolearn.enabled && config.host_autolearn.store_path.is_none() {
        return Err(ProxyConfigError::InvalidConfig(
            "hostAutolearn.storePath is required when hostAutolearn.enabled is true".to_string(),
        ));
    }

    config.process.root_mode = root_mode;
    config.process.root_helper_socket_path = root_helper_socket_path;
    config.process.environment_kind = parse_environment_kind(environment_kind);

    let _ = host_autolearn;
    Ok(config)
}

/// Map the JSON wire-form string to [`EnvironmentKind`]. An absent or
/// unrecognised value falls back to [`EnvironmentKind::Unknown`] so a
/// stale Kotlin client speaking the pre-Phase-F.4 wire form does not
/// inject a wild value into the bandit's HashMap key (P4.4.5, offline-learner architecture note).
fn parse_environment_kind(value: Option<&str>) -> ripdpi_config::EnvironmentKind {
    match value {
        Some("Field") => ripdpi_config::EnvironmentKind::Field,
        Some("Emulator") => ripdpi_config::EnvironmentKind::Emulator,
        _ => ripdpi_config::EnvironmentKind::Unknown,
    }
}

fn sanitize_session_overrides(session_overrides: Option<ProxySessionOverrides>) -> Option<ProxySessionOverrides> {
    let mut session_overrides = session_overrides?;
    session_overrides.auth_token = trim_non_empty(session_overrides.auth_token);
    if session_overrides.listen_port_override.is_none() && session_overrides.auth_token.is_none() {
        None
    } else {
        Some(session_overrides)
    }
}

fn adaptive_detect_mask(config: &ProxyUiAdaptiveFallbackConfig) -> u32 {
    let mut detect = 0;
    if config.torst {
        detect |= DETECT_TORST;
    }
    if config.tls_err {
        detect |= DETECT_TLS_ERR;
    }
    if config.http_redirect {
        detect |= DETECT_HTTP_LOCAT;
    }
    if config.connect_failure {
        detect |= DETECT_CONNECT;
    }
    detect
}

fn group_needs_delayed_connect(group: &DesyncGroup) -> bool {
    !group.matches.filters.hosts.is_empty() || (group.matches.proto & (IS_HTTP | IS_HTTPS)) != 0
}
