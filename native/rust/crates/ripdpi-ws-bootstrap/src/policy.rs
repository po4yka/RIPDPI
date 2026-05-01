use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyEncryptedDnsContext, ProxyRuntimeContext};

use crate::catalog::{
    default_encrypted_dns_context, primary_encrypted_dns_context, secondary_encrypted_dns_context, WS_TUNNEL_PORT,
};

pub fn runtime_encrypted_dns_context_for_host(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> ProxyEncryptedDnsContext {
    runtime_encrypted_dns_context_for_host_with_default(host, runtime_context, default_encrypted_dns_context)
}

pub(crate) fn runtime_encrypted_dns_context_for_host_with_default(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
) -> ProxyEncryptedDnsContext {
    let capability = direct_path_capability_for_host(runtime_context, host);
    let base_context = capability
        .and_then(capability_encrypted_dns_context)
        .or_else(|| runtime_context.and_then(|context| context.encrypted_dns.clone()))
        .unwrap_or_else(default_context);
    gate_doq_for_capability(base_context, capability)
}

fn capability_encrypted_dns_context(capability: &ProxyDirectPathCapability) -> Option<ProxyEncryptedDnsContext> {
    match capability.dns_mode.trim().to_ascii_uppercase().as_str() {
        "DOH_PRIMARY" => Some(primary_encrypted_dns_context()),
        "DOH_SECONDARY" => Some(secondary_encrypted_dns_context()),
        _ => None,
    }
}

fn gate_doq_for_capability(
    mut context: ProxyEncryptedDnsContext,
    capability: Option<&ProxyDirectPathCapability>,
) -> ProxyEncryptedDnsContext {
    if !context.protocol.eq_ignore_ascii_case("doq") {
        return context;
    }
    let udp_clean = capability.is_none_or(capability_udp_clean_for_resolver);
    if udp_clean {
        return context;
    }
    context.protocol = "doh".to_string();
    context.port = WS_TUNNEL_PORT;
    context.tls_server_name = context.tls_server_name.or_else(|| Some(context.host.clone()));
    if context.doh_url.as_deref().is_none_or(|value| value.trim().is_empty()) {
        context.doh_url = Some(format!("https://{}/dns-query", context.host));
    }
    context
}

fn direct_path_capability_for_host<'a>(
    runtime_context: Option<&'a ProxyRuntimeContext>,
    host: &str,
) -> Option<&'a ProxyDirectPathCapability> {
    let normalized_host = normalize_authority(host)?;
    let candidates = [normalized_host.clone(), format!("{normalized_host}:{WS_TUNNEL_PORT}")];
    runtime_context?.direct_path_capabilities.iter().find(|capability| candidates.contains(&capability.authority))
}

fn capability_udp_clean_for_resolver(capability: &ProxyDirectPathCapability) -> bool {
    if capability.reason_code.as_deref() == Some("NO_TCP_FALLBACK") {
        return true;
    }
    capability.udp_usable != Some(false)
        && capability.quic_usable != Some(false)
        && !matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
}

fn normalize_authority(value: &str) -> Option<String> {
    let normalized = value.trim().trim_end_matches('.').to_ascii_lowercase();
    (!normalized.is_empty()).then_some(normalized)
}
