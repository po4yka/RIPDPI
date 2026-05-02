use crate::types::ProxyRuntimeContext;

use super::super::shared::trim_non_empty;

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
