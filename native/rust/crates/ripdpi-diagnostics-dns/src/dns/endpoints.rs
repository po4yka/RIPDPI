use std::net::{IpAddr, Ipv4Addr};

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

use crate::types::DnsTarget;
use crate::util::{DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, DEFAULT_DOH_PORT, DEFAULT_DOH_URL};

/// Returns hardcoded bootstrap IPs for well-known DoH resolver identifiers.
///
/// These IPs allow the DoH bootstrap connection to bypass tampered DNS entirely,
/// eliminating the 4+ second delay caused by resolving the DoH host through
/// censored DNS infrastructure.
pub fn bootstrap_ips_for_resolver(resolver_id: &str) -> Vec<IpAddr> {
    match resolver_id {
        "cloudflare" => vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
        "adguard" => vec![IpAddr::V4(Ipv4Addr::new(94, 140, 14, 14)), IpAddr::V4(Ipv4Addr::new(94, 140, 15, 15))],
        "google" | "google_ip" => vec![IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), IpAddr::V4(Ipv4Addr::new(8, 8, 4, 4))],
        "quad9" => vec![IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9)), IpAddr::V4(Ipv4Addr::new(149, 112, 112, 112))],
        "dnssb" => vec![IpAddr::V4(Ipv4Addr::new(185, 222, 222, 222)), IpAddr::V4(Ipv4Addr::new(45, 11, 45, 11))],
        "mullvad" => vec![IpAddr::V4(Ipv4Addr::new(194, 242, 2, 2))],
        _ => vec![],
    }
}

pub fn encrypted_dns_protocol(value: Option<&str>) -> EncryptedDnsProtocol {
    match value.unwrap_or_default().trim().to_ascii_lowercase().as_str() {
        "dot" => EncryptedDnsProtocol::Dot,
        "dnscrypt" => EncryptedDnsProtocol::DnsCrypt,
        "doq" => EncryptedDnsProtocol::Doq,
        _ => EncryptedDnsProtocol::Doh,
    }
}

pub fn parse_url_host(value: &str) -> Option<String> {
    let trimmed = value.trim();
    let (_, remainder) = trimmed.split_once("://")?;
    let authority = remainder.split('/').next()?;
    if authority.is_empty() {
        return None;
    }
    let host_port = authority.rsplit_once('@').map_or(authority, |(_, suffix)| suffix);
    if host_port.starts_with('[') {
        let end = host_port.find(']')?;
        return Some(host_port[1..end].to_string());
    }
    host_port.split(':').next().map(ToOwned::to_owned)
}

pub fn encrypted_dns_endpoint_for_target(target: &DnsTarget) -> Result<(EncryptedDnsEndpoint, Vec<String>), String> {
    let protocol = encrypted_dns_protocol(target.encrypted_protocol.as_deref());
    let bootstrap_strings = if !target.encrypted_bootstrap_ips.is_empty() {
        target.encrypted_bootstrap_ips.clone()
    } else if let Some(ref rid) = target.encrypted_resolver_id {
        let pinned = bootstrap_ips_for_resolver(rid);
        if !pinned.is_empty() {
            pinned.iter().map(ToString::to_string).collect::<Vec<_>>()
        } else {
            DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect::<Vec<_>>()
        }
    } else {
        DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect::<Vec<_>>()
    };
    let doh_url = target
        .encrypted_doh_url
        .clone()
        .or_else(|| (protocol == EncryptedDnsProtocol::Doh).then(|| DEFAULT_DOH_URL.to_string()));
    let host =
        target.encrypted_host.clone().or_else(|| doh_url.as_deref().and_then(parse_url_host)).unwrap_or_else(|| {
            if protocol == EncryptedDnsProtocol::Doh {
                DEFAULT_DOH_HOST.to_string()
            } else {
                String::new()
            }
        });
    let port = target.encrypted_port.unwrap_or(match protocol {
        EncryptedDnsProtocol::Doh => DEFAULT_DOH_PORT,
        EncryptedDnsProtocol::Dot => 853,
        EncryptedDnsProtocol::DnsCrypt => 443,
        EncryptedDnsProtocol::Doq => 853,
    });

    Ok((
        EncryptedDnsEndpoint {
            protocol,
            resolver_id: target.encrypted_resolver_id.clone().or_else(|| Some(protocol.as_str().to_string())),
            host,
            port,
            tls_server_name: target.encrypted_tls_server_name.clone(),
            bootstrap_ips: parse_bootstrap_ips(&bootstrap_strings)?,
            doh_url,
            dnscrypt_provider_name: target.encrypted_dnscrypt_provider_name.clone(),
            dnscrypt_public_key: target.encrypted_dnscrypt_public_key.clone(),
        },
        bootstrap_strings,
    ))
}

pub fn parse_bootstrap_ips(values: &[String]) -> Result<Vec<IpAddr>, String> {
    values.iter().map(|value| value.parse::<IpAddr>().map_err(|err| err.to_string())).collect()
}

pub fn encrypted_dns_endpoint_for_resolver_id(resolver_id: &str) -> EncryptedDnsEndpoint {
    let (resolver_id, host, port, doh_url, tls_server_name) = match resolver_id {
        "cloudflare" => (
            "cloudflare",
            "cloudflare-dns.com",
            443,
            "https://cloudflare-dns.com/dns-query",
            Some("cloudflare-dns.com"),
        ),
        "google" => ("google", "dns.google", 443, "https://dns.google/dns-query", Some("dns.google")),
        "google_ip" => ("google_ip", "8.8.8.8", 443, "https://8.8.8.8/dns-query", None),
        "quad9" => ("quad9", "dns.quad9.net", 443, "https://dns.quad9.net/dns-query", Some("dns.quad9.net")),
        "dnssb" => ("dnssb", "dns.sb", 443, "https://doh.dns.sb/dns-query", Some("dns.sb")),
        "mullvad" => ("mullvad", "dns.mullvad.net", 443, "https://dns.mullvad.net/dns-query", Some("dns.mullvad.net")),
        _ => ("adguard", DEFAULT_DOH_HOST, DEFAULT_DOH_PORT, DEFAULT_DOH_URL, Some(DEFAULT_DOH_HOST)),
    };

    let bootstrap_ips = {
        let pinned = bootstrap_ips_for_resolver(resolver_id);
        if pinned.is_empty() {
            DEFAULT_DOH_BOOTSTRAP_IPS.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect()
        } else {
            pinned
        }
    };

    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some(resolver_id.to_string()),
        host: host.to_string(),
        port,
        tls_server_name: tls_server_name.map(ToString::to_string),
        bootstrap_ips,
        doh_url: Some(doh_url.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
