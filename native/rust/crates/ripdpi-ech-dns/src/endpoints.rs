mod builder;
mod catalog;
mod parse;

pub use builder::{encrypted_dns_endpoint_for_resolver_id, encrypted_dns_endpoint_for_target};
pub use catalog::bootstrap_ips_for_resolver;
pub use parse::{encrypted_dns_protocol, parse_bootstrap_ips, parse_url_host};

/// Returns hardcoded bootstrap IPs for well-known DoH resolver identifiers.
///
/// These IPs allow the DoH bootstrap connection to bypass tampered DNS entirely,
/// eliminating the 4+ second delay caused by resolving the DoH host through
/// censored DNS infrastructure.
#[cfg(test)]
mod tests {
    use super::*;
    use std::net::IpAddr;

    use ripdpi_dns_resolver::EncryptedDnsProtocol;

    use crate::types::DnsTarget;
    use crate::util::{DEFAULT_DOH_HOST, DEFAULT_DOH_URL};

    #[test]
    fn encrypted_dns_protocol_defaults_to_doh() {
        assert_eq!(encrypted_dns_protocol(None), EncryptedDnsProtocol::Doh);
        assert_eq!(encrypted_dns_protocol(None), EncryptedDnsProtocol::Doh);
        assert_eq!(encrypted_dns_protocol(Some("")), EncryptedDnsProtocol::Doh);
        assert_eq!(encrypted_dns_protocol(Some("unknown")), EncryptedDnsProtocol::Doh);
    }

    #[test]
    fn encrypted_dns_protocol_recognizes_dot_and_dnscrypt() {
        assert_eq!(encrypted_dns_protocol(Some("dot")), EncryptedDnsProtocol::Dot);
        assert_eq!(encrypted_dns_protocol(Some("DOT")), EncryptedDnsProtocol::Dot);
        assert_eq!(encrypted_dns_protocol(Some("dnscrypt")), EncryptedDnsProtocol::DnsCrypt);
    }

    #[test]
    fn parse_url_host_extracts_hostname() {
        assert_eq!(parse_url_host("https://dns.google/dns-query"), Some("dns.google".to_string()));
        assert_eq!(parse_url_host("https://user@host.example:443/path"), Some("host.example".to_string()));
        assert_eq!(parse_url_host("https://[::1]:443/path"), Some("::1".to_string()));
    }

    #[test]
    fn parse_url_host_returns_none_for_invalid() {
        assert_eq!(parse_url_host("no-scheme"), None);
        assert_eq!(parse_url_host("https:///path"), None);
    }

    #[test]
    fn bootstrap_ips_for_known_resolvers() {
        let cf = bootstrap_ips_for_resolver("cloudflare");
        assert_eq!(cf.len(), 2);
        assert_eq!(cf[0], "1.1.1.1".parse::<IpAddr>().unwrap());
        assert_eq!(cf[1], "1.0.0.1".parse::<IpAddr>().unwrap());

        let ag = bootstrap_ips_for_resolver("adguard");
        assert_eq!(ag.len(), 2);
        assert_eq!(ag[0], "94.140.14.14".parse::<IpAddr>().unwrap());

        let g = bootstrap_ips_for_resolver("google");
        assert_eq!(g.len(), 2);
        assert_eq!(g[0], "8.8.8.8".parse::<IpAddr>().unwrap());

        let g_ip = bootstrap_ips_for_resolver("google_ip");
        assert_eq!(g_ip, g);

        let q9 = bootstrap_ips_for_resolver("quad9");
        assert_eq!(q9.len(), 2);
        assert_eq!(q9[0], "9.9.9.9".parse::<IpAddr>().unwrap());

        let dsb = bootstrap_ips_for_resolver("dnssb");
        assert_eq!(dsb.len(), 2);

        let mv = bootstrap_ips_for_resolver("mullvad");
        assert_eq!(mv.len(), 1);
        assert_eq!(mv[0], "194.242.2.2".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn bootstrap_ips_for_unknown_resolver_is_empty() {
        assert!(bootstrap_ips_for_resolver("unknown-provider").is_empty());
    }

    #[test]
    fn endpoint_for_resolver_id_uses_known_doh_metadata() {
        let endpoint = encrypted_dns_endpoint_for_resolver_id("cloudflare");
        assert_eq!(endpoint.resolver_id.as_deref(), Some("cloudflare"));
        assert_eq!(endpoint.host, "cloudflare-dns.com");
        assert_eq!(endpoint.tls_server_name.as_deref(), Some("cloudflare-dns.com"));
        assert_eq!(endpoint.doh_url.as_deref(), Some("https://cloudflare-dns.com/dns-query"));
        assert_eq!(endpoint.bootstrap_ips.len(), 2);
    }

    #[test]
    fn endpoint_for_unknown_resolver_id_falls_back_to_adguard() {
        let endpoint = encrypted_dns_endpoint_for_resolver_id("unknown");
        assert_eq!(endpoint.resolver_id.as_deref(), Some("adguard"));
        assert_eq!(endpoint.host, DEFAULT_DOH_HOST);
        assert_eq!(endpoint.doh_url.as_deref(), Some(DEFAULT_DOH_URL));
    }

    #[test]
    fn endpoint_for_target_uses_pinned_ips_when_resolver_known() {
        let target = DnsTarget {
            domain: "example.com".to_string(),
            udp_server: None,
            encrypted_resolver_id: Some("cloudflare".to_string()),
            encrypted_protocol: Some("doh".to_string()),
            encrypted_host: Some("cloudflare-dns.com".to_string()),
            encrypted_port: Some(443),
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: vec![],
            encrypted_doh_url: Some("https://cloudflare-dns.com/dns-query".to_string()),
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            expected_ips: vec![],
        };
        let (endpoint, bootstrap_strings) = encrypted_dns_endpoint_for_target(&target).unwrap();
        assert_eq!(
            endpoint.bootstrap_ips,
            vec!["1.1.1.1".parse::<IpAddr>().unwrap(), "1.0.0.1".parse::<IpAddr>().unwrap(),]
        );
        assert_eq!(bootstrap_strings, vec!["1.1.1.1", "1.0.0.1"]);
    }

    #[test]
    fn endpoint_for_target_respects_explicit_bootstrap_ips() {
        let target = DnsTarget {
            domain: "example.com".to_string(),
            udp_server: None,
            encrypted_resolver_id: Some("cloudflare".to_string()),
            encrypted_protocol: Some("doh".to_string()),
            encrypted_host: Some("cloudflare-dns.com".to_string()),
            encrypted_port: Some(443),
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: vec!["10.0.0.1".to_string()],
            encrypted_doh_url: Some("https://cloudflare-dns.com/dns-query".to_string()),
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            expected_ips: vec![],
        };
        let (endpoint, _) = encrypted_dns_endpoint_for_target(&target).unwrap();
        assert_eq!(endpoint.bootstrap_ips, vec!["10.0.0.1".parse::<IpAddr>().unwrap()]);
    }

    #[test]
    fn parse_bootstrap_ips_valid() {
        let input = vec!["8.8.8.8".to_string(), "2001:4860:4860::8888".to_string()];
        let result = parse_bootstrap_ips(&input).unwrap();
        assert_eq!(result.len(), 2);
        assert_eq!(result[0], "8.8.8.8".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn parse_bootstrap_ips_invalid() {
        let input = vec!["not-an-ip".to_string()];
        assert!(parse_bootstrap_ips(&input).is_err());
    }
}
