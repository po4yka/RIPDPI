use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

#[derive(Debug, Clone, PartialEq, Eq)]
struct ResolverEndpointConfig {
    servers: Vec<ResolverNameServer>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ResolverNameServer {
    port: u16,
    tls_name: String,
    http_endpoint: Option<String>,
}

impl ResolverEndpointConfig {
    #[allow(dead_code)]
    fn name_servers(&self) -> &[ResolverNameServer] {
        &self.servers
    }
}

fn doh_path(endpoint: &EncryptedDnsEndpoint) -> String {
    endpoint
        .doh_url
        .as_deref()
        .and_then(|url| url.find("/dns-query").map(|idx| url[idx..].to_string()))
        .unwrap_or_else(|| "/dns-query".to_string())
}

fn resolver_config_for_endpoint(endpoint: &EncryptedDnsEndpoint) -> Result<ResolverEndpointConfig, String> {
    if matches!(endpoint.protocol, EncryptedDnsProtocol::DnsCrypt) {
        return Err("hickory-resolver does not support DNSCrypt".to_string());
    }
    if endpoint.bootstrap_ips.is_empty() {
        return Err("at least one bootstrap IP is required".to_string());
    }
    let tls_name = endpoint.tls_server_name.clone().unwrap_or_else(|| endpoint.host.clone());
    let http_endpoint = matches!(endpoint.protocol, EncryptedDnsProtocol::Doh).then(|| doh_path(endpoint));
    Ok(ResolverEndpointConfig {
        servers: endpoint
            .bootstrap_ips
            .iter()
            .map(|_| ResolverNameServer {
                port: endpoint.port,
                tls_name: tls_name.clone(),
                http_endpoint: http_endpoint.clone(),
            })
            .collect(),
    })
}

pub fn resolve_via_hickory_dns(domain: &str, endpoint: EncryptedDnsEndpoint) -> Result<Vec<String>, String> {
    resolver_config_for_endpoint(&endpoint)?;
    let transport = crate::transport::direct_transport();
    super::resolve_via_encrypted_dns(domain, endpoint, &transport)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::IpAddr;

    fn make_doh_endpoint(bootstrap_ips: Vec<IpAddr>) -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("doh".to_string()),
            host: "dns.google".to_string(),
            port: 443,
            tls_server_name: Some("dns.google".to_string()),
            bootstrap_ips,
            doh_url: Some("https://dns.google/dns-query".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        }
    }

    fn make_dot_endpoint(bootstrap_ips: Vec<IpAddr>) -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("dot".to_string()),
            host: "dns.google".to_string(),
            port: 853,
            tls_server_name: Some("dns.google".to_string()),
            bootstrap_ips,
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        }
    }

    #[test]
    fn resolver_config_builds_for_doh() {
        let endpoint = make_doh_endpoint(vec!["8.8.8.8".parse().unwrap()]);
        let config = resolver_config_for_endpoint(&endpoint).unwrap();
        let ns = &config.name_servers()[0];
        assert_eq!(ns.port, 443);
        assert_eq!(ns.tls_name, "dns.google");
        assert_eq!(ns.http_endpoint.as_deref(), Some("/dns-query"));
    }

    #[test]
    fn resolver_config_builds_for_dot() {
        let endpoint = make_dot_endpoint(vec!["8.8.8.8".parse().unwrap()]);
        let config = resolver_config_for_endpoint(&endpoint).unwrap();
        let ns = &config.name_servers()[0];
        assert_eq!(ns.port, 853);
        assert_eq!(ns.tls_name, "dns.google");
        assert!(ns.http_endpoint.is_none());
    }

    #[test]
    fn resolver_config_rejects_dnscrypt() {
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::DnsCrypt,
            resolver_id: None,
            host: String::new(),
            port: 443,
            tls_server_name: None,
            bootstrap_ips: vec!["1.1.1.1".parse().unwrap()],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        };
        let err = resolver_config_for_endpoint(&endpoint).unwrap_err();
        assert!(err.contains("DNSCrypt"), "expected DNSCrypt error, got: {err}");
    }

    #[test]
    fn resolver_config_rejects_empty_bootstrap_ips() {
        let endpoint = make_doh_endpoint(vec![]);
        let err = resolver_config_for_endpoint(&endpoint).unwrap_err();
        assert!(err.contains("bootstrap IP"), "expected bootstrap IP error, got: {err}");
    }

    #[test]
    fn resolver_config_doh_url_without_dns_query_defaults_path() {
        let mut endpoint = make_doh_endpoint(vec!["8.8.8.8".parse().unwrap()]);
        endpoint.doh_url = Some("https://dns.example/custom".to_string());
        let config = resolver_config_for_endpoint(&endpoint).unwrap();
        let ns = &config.name_servers()[0];
        assert_eq!(ns.http_endpoint.as_deref(), Some("/dns-query"));
    }

    #[test]
    fn resolver_config_doh_url_none_defaults_path() {
        let mut endpoint = make_doh_endpoint(vec!["8.8.8.8".parse().unwrap()]);
        endpoint.doh_url = None;
        let config = resolver_config_for_endpoint(&endpoint).unwrap();
        let ns = &config.name_servers()[0];
        assert_eq!(ns.http_endpoint.as_deref(), Some("/dns-query"));
    }

    #[test]
    fn resolver_config_uses_host_as_tls_name_fallback() {
        let mut endpoint = make_dot_endpoint(vec!["8.8.8.8".parse().unwrap()]);
        endpoint.tls_server_name = None;
        endpoint.host = "my-resolver.example".to_string();
        let config = resolver_config_for_endpoint(&endpoint).unwrap();
        let ns = &config.name_servers()[0];
        assert_eq!(ns.tls_name, "my-resolver.example");
    }

    #[test]
    fn resolver_config_multiple_bootstrap_ips_creates_multiple_servers() {
        let endpoint = make_dot_endpoint(vec![
            "8.8.8.8".parse().unwrap(),
            "8.8.4.4".parse().unwrap(),
            "2001:4860:4860::8888".parse().unwrap(),
        ]);
        let config = resolver_config_for_endpoint(&endpoint).unwrap();
        assert_eq!(config.name_servers().len(), 3);
    }
}
