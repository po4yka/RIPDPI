use ripdpi_dns_resolver::EncryptedDnsEndpoint;
use ripdpi_proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};

use crate::connectivity::adapters::dns::{build_fallback_encrypted_dns_endpoints, encrypted_dns_endpoint_for_target};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::strategy::adapters::candidates::{
    strategy_probe_encrypted_dns_context, strategy_probe_encrypted_dns_endpoint,
};
use crate::types::DnsTarget;

#[derive(Debug, Clone)]
pub struct PolicyApprovedEncryptedDnsEndpoint {
    endpoint: EncryptedDnsEndpoint,
}

impl PolicyApprovedEncryptedDnsEndpoint {
    pub fn endpoint(&self) -> &EncryptedDnsEndpoint {
        &self.endpoint
    }

    pub fn into_endpoint(self) -> EncryptedDnsEndpoint {
        self.endpoint
    }
}

#[derive(Debug, Clone)]
pub struct ProbeExecutionContext {
    transport: TransportConfig,
    resolver_policy: ResolverPolicy,
    pub network_scope_key: Option<String>,
    pub relay_hint: Option<String>,
    pub strategy_signature: Option<String>,
}

#[derive(Debug, Clone)]
enum ResolverPolicy {
    TargetOrCatalogFallback,
    Approved {
        primary: PolicyApprovedEncryptedDnsEndpoint,
        fallback: Vec<PolicyApprovedEncryptedDnsEndpoint>,
        allow_fallback: bool,
    },
}

pub struct ContextDnsResolvers {
    pub primary: EncryptedDnsEndpoint,
    pub bootstrap_ips: Vec<String>,
    pub fallback: Vec<EncryptedDnsEndpoint>,
}

impl ProbeExecutionContext {
    pub fn new(transport: TransportConfig) -> Self {
        Self {
            transport,
            resolver_policy: ResolverPolicy::TargetOrCatalogFallback,
            network_scope_key: None,
            relay_hint: None,
            strategy_signature: None,
        }
    }

    pub fn from_runtime_context(
        transport: TransportConfig,
        runtime_context: Option<&ProxyRuntimeContext>,
    ) -> Result<Self, String> {
        let mut context = Self::new(transport);
        if let Some(encrypted_dns) = runtime_context.and_then(|value| value.encrypted_dns.as_ref()) {
            context = context.with_approved_runtime_resolver(encrypted_dns, true)?;
        }
        Ok(context)
    }

    pub fn transport(&self) -> &TransportConfig {
        &self.transport
    }

    pub fn approved_primary_resolver(&self) -> Option<&EncryptedDnsEndpoint> {
        match &self.resolver_policy {
            ResolverPolicy::TargetOrCatalogFallback => None,
            ResolverPolicy::Approved { primary, .. } => Some(primary.endpoint()),
        }
    }

    pub fn approved_fallback_resolvers(&self, primary_resolver_id: Option<&str>) -> Vec<EncryptedDnsEndpoint> {
        match &self.resolver_policy {
            ResolverPolicy::TargetOrCatalogFallback => build_fallback_encrypted_dns_endpoints(primary_resolver_id),
            ResolverPolicy::Approved { fallback, allow_fallback, .. } if *allow_fallback => {
                fallback.iter().map(|value| value.endpoint().clone()).collect()
            }
            ResolverPolicy::Approved { .. } => Vec::new(),
        }
    }

    pub fn resolvers_for_dns_target(&self, target: &DnsTarget) -> Result<ContextDnsResolvers, String> {
        let target_has_explicit_resolver = target.encrypted_host.is_some()
            || target.encrypted_doh_url.is_some()
            || target.encrypted_protocol.is_some()
            || target.encrypted_resolver_id.is_some()
            || !target.encrypted_bootstrap_ips.is_empty();

        if target_has_explicit_resolver {
            let (primary, bootstrap_ips) = encrypted_dns_endpoint_for_target(target)?;
            return Ok(ContextDnsResolvers { primary, bootstrap_ips, fallback: Vec::new() });
        }

        match &self.resolver_policy {
            ResolverPolicy::TargetOrCatalogFallback => {
                let (primary, bootstrap_ips) = encrypted_dns_endpoint_for_target(target)?;
                let fallback = build_fallback_encrypted_dns_endpoints(primary.resolver_id.as_deref());
                Ok(ContextDnsResolvers { primary, bootstrap_ips, fallback })
            }
            ResolverPolicy::Approved { primary, .. } => {
                let primary_endpoint = primary.endpoint().clone();
                let bootstrap_ips = primary_endpoint.bootstrap_ips.iter().map(ToString::to_string).collect();
                let fallback = self.approved_fallback_resolvers(primary_endpoint.resolver_id.as_deref());
                Ok(ContextDnsResolvers { primary: primary_endpoint, bootstrap_ips, fallback })
            }
        }
    }

    pub fn strategy_resolver_context(
        &self,
        runtime_context: Option<&ProxyRuntimeContext>,
    ) -> Result<(ProxyEncryptedDnsContext, EncryptedDnsEndpoint), String> {
        let resolver_context = strategy_probe_encrypted_dns_context(runtime_context);
        let endpoint = self
            .approved_primary_resolver()
            .cloned()
            .map(Ok)
            .unwrap_or_else(|| strategy_probe_encrypted_dns_endpoint(&resolver_context))?;
        Ok((resolver_context, endpoint))
    }

    fn with_approved_runtime_resolver(
        mut self,
        encrypted_dns: &ProxyEncryptedDnsContext,
        allow_fallback: bool,
    ) -> Result<Self, String> {
        let primary =
            PolicyApprovedEncryptedDnsEndpoint { endpoint: strategy_probe_encrypted_dns_endpoint(encrypted_dns)? };
        let fallback = if allow_fallback {
            build_fallback_encrypted_dns_endpoints(encrypted_dns.resolver_id.as_deref())
                .into_iter()
                .map(|endpoint| PolicyApprovedEncryptedDnsEndpoint { endpoint })
                .collect()
        } else {
            Vec::new()
        };
        self.resolver_policy = ResolverPolicy::Approved { primary, fallback, allow_fallback };
        Ok(self)
    }
}

#[cfg(test)]
mod tests {
    use std::net::IpAddr;

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

    use super::*;
    use crate::connectivity::adapters::transport::direct_transport;

    fn runtime_dns() -> ProxyEncryptedDnsContext {
        ProxyEncryptedDnsContext {
            resolver_id: Some("private".to_string()),
            protocol: "doh".to_string(),
            host: "resolver.internal".to_string(),
            port: 443,
            tls_server_name: Some("resolver.internal".to_string()),
            bootstrap_ips: vec!["203.0.113.10".to_string()],
            doh_url: Some("https://resolver.internal/dns-query".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

    fn target() -> DnsTarget {
        DnsTarget {
            domain: "example.test".to_string(),
            udp_server: None,
            encrypted_resolver_id: None,
            encrypted_protocol: None,
            encrypted_host: None,
            encrypted_port: None,
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: Vec::new(),
            encrypted_doh_url: None,
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            expected_ips: Vec::new(),
        }
    }

    #[test]
    fn runtime_context_resolver_overrides_catalog_default() {
        let context = ProbeExecutionContext::from_runtime_context(
            direct_transport(),
            Some(&ProxyRuntimeContext { encrypted_dns: Some(runtime_dns()), ..ProxyRuntimeContext::default() }),
        )
        .expect("context");

        let resolvers = context.resolvers_for_dns_target(&target()).expect("resolvers");

        assert_eq!(resolvers.primary.host, "resolver.internal");
        assert_eq!(resolvers.bootstrap_ips, vec!["203.0.113.10".to_string()]);
        assert!(!resolvers.fallback.iter().any(|endpoint| endpoint.host == "cloudflare-dns.com"));
    }

    #[test]
    fn explicit_target_resolver_is_not_replaced_by_runtime_context() {
        let context = ProbeExecutionContext::from_runtime_context(
            direct_transport(),
            Some(&ProxyRuntimeContext { encrypted_dns: Some(runtime_dns()), ..ProxyRuntimeContext::default() }),
        )
        .expect("context");
        let explicit = DnsTarget {
            encrypted_host: Some("target.resolver".to_string()),
            encrypted_protocol: Some("doh".to_string()),
            encrypted_doh_url: Some("https://target.resolver/dns-query".to_string()),
            encrypted_bootstrap_ips: vec!["198.51.100.53".to_string()],
            ..target()
        };

        let resolvers = context.resolvers_for_dns_target(&explicit).expect("resolvers");

        assert_eq!(resolvers.primary.host, "target.resolver");
        assert!(resolvers.fallback.is_empty());
    }

    #[test]
    fn approved_endpoint_constructor_keeps_field_private() {
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("private".to_string()),
            host: "resolver.internal".to_string(),
            port: 443,
            tls_server_name: None,
            bootstrap_ips: vec!["203.0.113.10".parse::<IpAddr>().expect("ip")],
            doh_url: Some("https://resolver.internal/dns-query".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        };
        let approved = PolicyApprovedEncryptedDnsEndpoint { endpoint };
        assert_eq!(approved.endpoint().host, "resolver.internal");
    }
}
