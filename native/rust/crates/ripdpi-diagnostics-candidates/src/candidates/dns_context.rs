use super::prelude::*;

pub fn strategy_probe_config_json(config: &ProxyUiConfig) -> String {
    serde_json::to_string(&ProxyConfigPayload::Ui {
        strategy_preset: None,
        config: config.clone(),
        runtime_context: None,
        log_context: None,
        session_overrides: None,
        // Current native-config wire schema version.
        schema_version: 2,
    })
    .expect("serialize ui proxy config")
}

/// Returns the hardcoded AdGuard DoH configuration used as fallback when no
/// user-supplied runtime encrypted DNS context is available.
///
/// Strategy probes should ideally resolve DNS through the same resolver that
/// runtime traffic uses. When a user has configured a different resolver (e.g.
/// Google DoH, Quad9), falling back to AdGuard here means probes may observe
/// different DNS behavior than actual connections. See
/// [`strategy_probe_encrypted_dns_context`] for the precedence logic.
pub fn default_runtime_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some("adguard".to_string()),
        protocol: "doh".to_string(),
        host: DEFAULT_DOH_HOST.to_string(),
        port: DEFAULT_DOH_PORT,
        tls_server_name: Some(DEFAULT_DOH_HOST.to_string()),
        bootstrap_ips: DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect(),
        doh_url: Some(DEFAULT_DOH_URL.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

/// Resolves the encrypted DNS context for strategy probes.
///
/// Precedence: user-supplied runtime context > AdGuard DoH default.
/// When the runtime context is absent or has no `encrypted_dns` field, the
/// AdGuard fallback from [`default_runtime_encrypted_dns_context`] is used
/// and a debug log is emitted so operators can correlate any DNS-path mismatch.
pub fn strategy_probe_encrypted_dns_context(runtime_context: Option<&ProxyRuntimeContext>) -> ProxyEncryptedDnsContext {
    match runtime_context.and_then(|value| value.encrypted_dns.clone()) {
        Some(ctx) => ctx,
        None => {
            let fallback = default_runtime_encrypted_dns_context();
            tracing::debug!(
                "no runtime encrypted DNS context provided; falling back to default {} for strategy probes",
                strategy_probe_encrypted_dns_label(&fallback)
            );
            fallback
        }
    }
}

pub fn strategy_probe_encrypted_dns_endpoint(
    context: &ProxyEncryptedDnsContext,
) -> Result<EncryptedDnsEndpoint, String> {
    Ok(EncryptedDnsEndpoint {
        protocol: encrypted_dns_protocol(Some(context.protocol.as_str())),
        resolver_id: context.resolver_id.clone(),
        host: context.host.clone(),
        port: context.port,
        tls_server_name: context.tls_server_name.clone(),
        bootstrap_ips: parse_bootstrap_ips(&context.bootstrap_ips)?,
        doh_url: context.doh_url.clone(),
        dnscrypt_provider_name: context.dnscrypt_provider_name.clone(),
        dnscrypt_public_key: context.dnscrypt_public_key.clone(),
        odoh: None,
    })
}

pub fn strategy_probe_encrypted_dns_label(context: &ProxyEncryptedDnsContext) -> String {
    context
        .doh_url
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{}:{}", context.host, context.port))
}
